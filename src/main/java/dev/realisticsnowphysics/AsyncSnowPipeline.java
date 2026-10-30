package dev.realisticsnowphysics;

import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.block.enums.DoubleBlockHalf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * True async pipeline for accumulation and melt, server-side only.
 *
 * No client code, no client mod dependency, no custom packets.
 * The mod stays environment "*", server-side rules only, so vanilla
 * Java clients and Bedrock clients via Geyser join without the mod,
 * exactly as before. Block updates sent to clients are the normal
 * vanilla setBlock updates (flag 3), nothing else.
 *
 * Thread rules, non-negotiable:
 * - Server thread ONLY: every level.getBlockState, level.setBlock,
 *   level.getChunk, level.getHeight, level.isLoaded, biome, light,
 *   PlantStore peek/take/store/save, and SeasonsHook reads.
 * - Worker thread ONLY: pure computation on plain records copied
 *   during snapshot. It never touches a Level, a BlockState, a
 *   chunk, PlantStore, or SeasonsHook. It never calls setBlock.
 * - Happens-before: snapshot is finished on the server thread,
 *   then submitted to the single worker executor. The worker
 *   returns an immutable change list via Future. The server thread
 *   reads it only after Future.isDone(), then applies it.
 *
 * Cycle, per 30 game seconds (600 ticks):
 * 1. SNAPSHOT, server thread, staggered 120 bands, radius 128
 *    (plus a 6-column border for valley ground heights, so valley
 *    checks for edge columns are identical to the old code).
 *    Each column scan mirrors SnowProcessor.processAreaBanded:
 *    topY+3 down to topY-40, snow recorded, leaves tracked,
 *    ground bare positions recorded, plant/crop/canHoldSnow/
 *    leaf/snowing/canAccumulate/heat/blockLight/stored-plant
 *    flags copied into plain records.
 * 2. WORKER, one daemon server-side thread, no world access.
 *    It replays melt, plant restoration, bare accumulation, and
 *    pile-up in exactly the old order, on a mutable copy of the
 *    snapshot, with the same 15% skip rate (worker Random, not
 *    level random, so the rate is identical, the sequence is not
 *    bit-identical), the same leaf cap 4, the same valley rule
 *    (maxDiff >= 1 && totalDiff >= 0, radius 6) and the same
 *    valley bottom-first rule (top > lowest neighbour top + 1
 *    is skipped, neighbours within 2 blocks, from the initial
 *    top-snow map), the same max 8 layers, the same 8 snow-block
 *    valley cap, and crops exempt.
 * 3. APPLY, server thread, staggered: 2 ms budget AND max 200 changes
 *    per tick, flags 2 (clients only, no neighbour updates), change
 *    list shuffled so each tick's batch is spread across the whole
 *    radius instead of one area rising all at once. Each change is validated against the live block at
 *    that position (id and layer count must match the snapshot
 *    expectation); if a player, falling snow, or another system
 *    changed it since the snapshot, the change is skipped, never
 *    overwriting player work. Plant burial and restoration are
 *    done here, on the server thread, via PlantStore.
 *
 * Falling is NOT async, by design. FallingSnow.processQueue stays
 * on the server thread, cap 4, attempts 20, 50 ms guard, because
 * support loss must move immediately. Async is only for the
 * 30-second accumulation/melt cycle, which is where the lag was.
 *
 * Old code is kept, not deleted: SnowProcessor.tickStaggered and
 * processAreaBanded remain in the tree as the legacy synchronous
 * path, but the branch's tick handler calls this pipeline instead.
 * If the worker throws, the cycle is skipped (logged once), never
 * double-applied, and the next 600-tick cycle retries.
 */
public final class AsyncSnowPipeline {
    private AsyncSnowPipeline() {}

    // ---- Plain snapshot records. No Minecraft types except BlockPos,
    // which is immutable and is never mutated after creation. ----
    private static final class BareRec {
        int x, y, z, groundY;
        String bareId; // "minecraft:air", plant id, crop id, or other
        boolean isPlant, isCrop, canHoldSnow, leafCovered, canAccum, snowingInBiome;
        String storedPlantId; // PlantStore.peek at this pos, or null
    }
    private static final class SnowRec {
        int x, y, z;
        boolean isBlock;
        int layers; // 8 for a snow block, 1..8 for a layer
        boolean leafCovered, snowingInBiome, heated;
        String storedPlantId;
    }
    private static final class Snapshot {
        BlockPos centre;
        int radius;
        long cycleTick;
        boolean leafPass, day;
        int maxLayers;
        boolean meltEnabled, accumulationEnabled;
        // groundY for valley, for every column in radius+6, keyed by column
        Map<Long, Integer> groundY = new HashMap<>();
        List<BareRec> bares = new ArrayList<>();
        List<SnowRec> snows = new ArrayList<>();
    }
    // Change kinds, applied on the server thread only.
    private static final int SET_LAYER = 0;
    private static final int SET_BLOCK = 1;
    private static final int SET_AIR = 2;
    private static final int RESTORE_PLANT = 3;
    private static final int BURY_PLANT = 4; // store plant, then snow layer 1
    private static final int SET_LAYER_AND_AIR_ABOVE = 5; // tall plant burial: snow layer + above AIR
    private static final class Change {
        int x, y, z, kind, layers;
        String expectedId; int expectedLayers; // -1 means do not check layers
        String plantId; // for RESTORE_PLANT and BURY_PLANT
    }

    // ---- Pipeline state, server thread only, except workerFuture ----
    private enum State { IDLE, SNAPSHOT, WORKING, APPLYING }
    private static State state = State.IDLE;
    private static ServerWorld snapshotLevel;
    private static Snapshot snapshot;
    private static int snapshotBand;
    private static int snapX, snapZ, snapBandEndX;
    private static int bandCount() { return Math.max(1, RealisticSnowPhysicsConfig.get().asyncBandCount); }
    private static int radius() { return Math.max(16, RealisticSnowPhysicsConfig.get().asyncRadius); }
    private static Future<List<Change>> workerFuture;
    private static List<Change> pendingChanges;
    private static int applyIndex;
    private static long lastCycleStart = -100000;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "snow-async-worker");
        t.setDaemon(true);
        return t;
    });
    private static final java.util.Set<String> TALL_PLANTS = new java.util.HashSet<>(java.util.Arrays.asList(
        "minecraft:tall_grass", "minecraft:large_fern", "minecraft:sunflower",
        "minecraft:lilac", "minecraft:rose_bush", "minecraft:peony"));
    private static final java.util.Set<String> CROPS = new java.util.HashSet<>(java.util.Arrays.asList(
        "minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots",
        "minecraft:melon_stem", "minecraft:pumpkin_stem", "minecraft:attached_melon_stem",
        "minecraft:attached_pumpkin_stem", "minecraft:nether_wart", "minecraft:cocoa",
        "minecraft:sugar_cane", "minecraft:bamboo", "minecraft:chorus_plant",
        "minecraft:chorus_flower", "minecraft:kelp", "minecraft:seagrass", "minecraft:tall_seagrass"));

    private static long colKey(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    private static String idOf(BlockState s) { return Registries.BLOCK.getId(s.getBlock()).toString(); }
    private static boolean isLeavesId(String id) { return id.endsWith("_leaves"); }
    private static boolean isPlantId(String id) {
        if (CROPS.contains(id)) return false;
        if (id.equals("minecraft:grass_block") || id.equals("minecraft:dirt")) return false;
        if (id.equals("minecraft:short_grass") || id.equals("minecraft:grass") || id.equals("minecraft:tall_grass")
            || id.equals("minecraft:fern") || id.equals("minecraft:large_fern") || id.equals("minecraft:dead_bush")
            || id.equals("minecraft:red_mushroom") || id.equals("minecraft:brown_mushroom")
            || id.equals("minecraft:crimson_fungus") || id.equals("minecraft:warped_fungus")
            || id.equals("minecraft:crimson_roots") || id.equals("minecraft:warped_roots")
            || id.equals("minecraft:nether_sprouts")) return true;
        return id.endsWith("_flower") || id.endsWith("_tulip") || id.equals("minecraft:poppy")
            || id.equals("minecraft:dandelion") || id.equals("minecraft:blue_orchid") || id.equals("minecraft:allium")
            || id.equals("minecraft:azure_bluet") || id.equals("minecraft:oxeye_daisy") || id.equals("minecraft:cornflower")
            || id.equals("minecraft:lily_of_the_valley") || id.equals("minecraft:wither_rose")
            || id.equals("minecraft:sunflower") || id.equals("minecraft:lilac") || id.equals("minecraft:rose_bush")
            || id.equals("minecraft:peony");
    }
    private static boolean canHoldSnowAt(ServerWorld level, BlockPos below) {
        BlockState s = level.getBlockState(below);
        if (s.isAir()) return false;
        String id = idOf(s);
        if (id.equals("minecraft:water") || id.equals("minecraft:ice") || id.equals("minecraft:packed_ice")
            || id.equals("minecraft:blue_ice") || id.equals("minecraft:powder_snow")) return false;
        return s.isSideSolidFullSquare(level, below, net.minecraft.util.math.Direction.UP);
    }
    private static boolean snowingInBiomeAt(ServerWorld level, BlockPos pos, float tempOffset, boolean forceSnow) {
        if (forceSnow) return true;
        return level.isRaining() && level.getBiome(pos).value().hasPrecipitation()
            && (level.getBiome(pos).value().getTemperature() + tempOffset) < 0.15f;
    }
    private static boolean canAccumAt(ServerWorld level, BlockPos pos, float tempOffset, boolean forceSnow) {
        boolean snowing = level.isRaining() && level.isSkyVisible(pos)
            && level.getBiome(pos).value().hasPrecipitation()
            && (level.getBiome(pos).value().getTemperature() + tempOffset) < 0.15f;
        if (snowing) return true;
        if (!forceSnow) return false;
        return level.isRaining() && level.isSkyVisible(pos) && level.getBiome(pos).value().hasPrecipitation();
    }

    public static String status() {
        int pending = pendingChanges == null ? 0 : pendingChanges.size() - applyIndex;
        int snapSize = snapshot == null ? 0 : snapshot.snows.size() + snapshot.bares.size();
        return "async state=" + state + " snapshotRecords=" + snapSize + " pendingChanges=" + pending + " applyIndex=" + applyIndex;
    }

    /** Server thread only. Called every tick for the first player. */
    public static void tick(ServerWorld level, BlockPos centre, long tickCount) {
        if (!RealisticSnowPhysicsConfig.get().enabled) { state = State.IDLE; return; }
        if (SnowProcessor.tickScale(level) <= 0.0) return;
        switch (state) {
            case IDLE -> {
                if (tickCount - lastCycleStart < Math.max(20, RealisticSnowPhysicsConfig.get().asyncCycleTicks)) return;
                snapshotLevel = level;
                snapshot = new Snapshot();
                snapshot.centre = centre.toImmutable();
                snapshot.radius = radius();
                lastCycleStart = tickCount;
                snapshot.cycleTick = tickCount;
                snapshot.leafPass = (tickCount / 600) % 2 == 0;
                snapshot.day = level.isDay();
                snapshot.maxLayers = RealisticSnowPhysicsConfig.get().maxLayers;
                snapshot.meltEnabled = RealisticSnowPhysicsConfig.get().meltEnabled;
                snapshot.accumulationEnabled = RealisticSnowPhysicsConfig.get().accumulationEnabled;
                snapshotBand = 0; snapX = snapshot.centre.getX() - snapshot.radius - 6; snapZ = snapshot.centre.getZ() - snapshot.radius - 6; snapBandEndX = snapX + ((snapshot.radius + 6) * 2 + 1 + bandCount() - 1) / bandCount() - 1;
                state = State.SNAPSHOT;
            }
            case SNAPSHOT -> snapshotBand(level);
            case WORKING -> {
                if (workerFuture == null) { state = State.IDLE; return; }
                if (!workerFuture.isDone()) return;
                try {
                    pendingChanges = workerFuture.get();
                    applyIndex = 0;
                    state = State.APPLYING;
                } catch (Exception e) {
                    RealisticSnowPhysicsMod.LOGGER.error("[Realistic Snow Physics] async worker failed, cycle skipped", e);
                    state = State.IDLE;
                }
            }
            case APPLYING -> applyBatch(level);
        }
    }

    /** Server thread only: snapshot, time-budgeted and resumable.
     * One band is 2 columns wide (320 bands for radius 256). A band is
     * processed column by column until 2 ms elapse, then resumed next
     * tick from snapX/snapZ, so no single tick ever does a whole band
     * at once. At tick 500 (2 ms per tick) this is the difference
     * between a smooth run and a Can't keep up warning. */
    private static void snapshotBand(ServerWorld level) {
        if (snapshot == null || snapshotLevel != level) { state = State.IDLE; return; }
        long startNanos = System.nanoTime();
        int radius = snapshot.radius;
        int border = 6;
        int totalWidth = (radius + border) * 2 + 1;
        int bandWidth = (totalWidth + bandCount() - 1) / bandCount();
        float tempOffset = SeasonsHook.temperatureOffset();
        boolean forceSnow = SeasonsHook.forceSnowPrecipitation();
        int heatRadius = RealisticSnowPhysicsConfig.get().heatRadius;
        Map<Long, net.minecraft.world.chunk.WorldChunk> chunkCache = new HashMap<>();
        while (snapshotBand < bandCount()) {
            if (snapX > snapBandEndX) {
                snapshotBand++;
                if (snapshotBand >= bandCount()) break;
                int bx = snapshot.centre.getX() - radius - border + snapshotBand * bandWidth;
                snapX = bx; snapBandEndX = Math.min(snapshot.centre.getX() + radius + border, bx + bandWidth - 1);
                snapZ = snapshot.centre.getZ() - radius - border;
            }
            int x = snapX;
            int z = snapZ;
            // advance to next column for next iteration, before processing
            snapZ++;
            if (snapZ > snapshot.centre.getZ() + radius + border) { snapZ = snapshot.centre.getZ() - radius - border; snapX++; }
            if (!level.isChunkLoaded(x >> 4, z >> 4)) {
                if (System.nanoTime() - startNanos > Math.max(1, RealisticSnowPhysicsConfig.get().asyncSnapshotBudgetMillis) * 1_000_000L) return;
                continue;
            }
            long chunkKey = ((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL);
            net.minecraft.world.chunk.WorldChunk chunk = chunkCache.get(chunkKey);
            if (chunk == null) { chunk = level.getChunk(x >> 4, z >> 4); chunkCache.put(chunkKey, chunk); }
            int topY = level.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, x, z);
            int valleyGround = topY;
            for (int y = topY; y >= topY - 10 && y >= level.getBottomY(); y--) {
                BlockState s = chunk.getBlockState(new BlockPos(x, y, z));
                String sid = idOf(s);
                if (SnowProcessor.isSnow(s) || isPlantId(sid) || s.isAir() || isLeavesId(sid)) continue;
                valleyGround = y; break;
            }
            snapshot.groundY.put(colKey(x, z), valleyGround);
            if (Math.abs(x - snapshot.centre.getX()) > radius || Math.abs(z - snapshot.centre.getZ()) > radius) {
                if (System.nanoTime() - startNanos > Math.max(1, RealisticSnowPhysicsConfig.get().asyncSnapshotBudgetMillis) * 1_000_000L) return;
                continue;
            }
            // Per-column biome/snowing, computed once, not per snow record.
            BlockPos colPos = new BlockPos(x, topY, z);
            boolean colSnowing = snowingInBiomeAt(level, colPos, tempOffset, forceSnow);
            boolean leafAbove = false;
            for (int y = topY + 3; y >= topY - 40 && y >= level.getBottomY(); y--) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState s = chunk.getBlockState(pos);
                if (SnowProcessor.isSnow(s)) {
                    SnowRec rec = new SnowRec();
                    rec.x = x; rec.y = y; rec.z = z;
                    rec.isBlock = SnowProcessor.isSnowBlock(s);
                    rec.layers = rec.isBlock ? 8 : s.get(SnowBlock.LAYERS);
                    rec.leafCovered = leafAbove;
                    rec.snowingInBiome = colSnowing;
                    rec.heated = false;
                    if (level.getLightLevel(net.minecraft.world.LightType.BLOCK, pos) > 0) {
                        rec.heated = SnowProcessor.hasHeatNearby(level, pos, heatRadius);
                    }
                    PlantStore.StoredPlant stored = PlantStore.peek(level, pos);
                    rec.storedPlantId = stored == null ? null : stored.block;
                    snapshot.snows.add(rec);
                    continue;
                }
                if (s.isAir()) continue;
                String sid = idOf(s);
                if (isLeavesId(sid)) {
                    leafAbove = true;
                    BlockPos above = pos.up();
                    if (chunk.getBlockState(above).isAir() && canHoldSnowAt(level, pos)) {
                        BareRec b = bareRec(level, above, pos.getY(), leafAbove, tempOffset, forceSnow);
                        snapshot.bares.add(b);
                    }
                    continue;
                }
                if (isPlantId(sid)) {
                    BlockPos lowest = pos;
                    while (isPlantId(idOf(chunk.getBlockState(lowest.down())))) lowest = lowest.down();
                    BlockPos below = lowest.down();
                    if (canHoldSnowAt(level, below)) {
                        BareRec b = bareRec(level, lowest, below.getY(), leafAbove, tempOffset, forceSnow);
                        snapshot.bares.add(b);
                    }
                    break;
                }
                BlockPos above = pos.up();
                BlockState aboveState = chunk.getBlockState(above);
                if ((aboveState.isAir() || isPlantId(idOf(aboveState))) && canHoldSnowAt(level, pos)) {
                    BareRec b = bareRec(level, above, pos.getY(), leafAbove, tempOffset, forceSnow);
                    snapshot.bares.add(b);
                }
                break;
            }
            if (System.nanoTime() - startNanos > Math.max(1, RealisticSnowPhysicsConfig.get().asyncSnapshotBudgetMillis) * 1_000_000L) return;
        }
        if (snapshotBand >= bandCount()) {
            Snapshot done = snapshot;
            state = State.WORKING;
            workerFuture = WORKER.submit(() -> computeChanges(done));
        }
    }

    private static BareRec bareRec(ServerWorld level, BlockPos pos, int groundY, boolean leafAbove, float tempOffset, boolean forceSnow) {
        BareRec b = new BareRec();
        b.x = pos.getX(); b.y = pos.getY(); b.z = pos.getZ(); b.groundY = groundY;
        BlockState s = level.getBlockState(pos);
        b.bareId = idOf(s);
        b.isPlant = isPlantId(b.bareId);
        b.isCrop = CROPS.contains(b.bareId);
        b.canHoldSnow = canHoldSnowAt(level, pos.down());
        b.leafCovered = leafAbove;
        b.canAccum = canAccumAt(level, pos, tempOffset, forceSnow);
        b.snowingInBiome = snowingInBiomeAt(level, pos, tempOffset, forceSnow);
        PlantStore.StoredPlant stored = PlantStore.peek(level, pos);
        b.storedPlantId = stored == null ? null : stored.block;
        return b;
    }

    // ---- Worker thread only. No Level, no BlockState, no PlantStore, no SeasonsHook. ----
    private static List<Change> computeChanges(Snapshot snap) {
        java.util.Random rng = new java.util.Random(snap.cycleTick * 31L + snap.centre.getX() * 7L + snap.centre.getZ());
        List<Change> out = new ArrayList<>();
        // Mutable copy of world state at each pos: kind 0 air, 1 plant, 2 layer, 3 block, 4 other
        Map<Long, Integer> kind = new HashMap<>();
        Map<Long, Integer> layers = new HashMap<>();
        Map<Long, String> plantIdAt = new HashMap<>();
        Map<Long, String> storedAt = new HashMap<>();
        for (SnowRec r : snap.snows) {
            long k = new BlockPos(r.x, r.y, r.z).asLong();
            kind.put(k, r.isBlock ? 3 : 2);
            layers.put(k, r.layers);
            if (r.storedPlantId != null) storedAt.put(k, r.storedPlantId);
        }
        for (BareRec b : snap.bares) {
            long k = new BlockPos(b.x, b.y, b.z).asLong();
            if (!kind.containsKey(k)) {
                kind.put(k, b.isPlant ? 1 : (b.bareId.equals("minecraft:air") ? 0 : 4));
                if (b.isPlant) plantIdAt.put(k, b.bareId);
            }
            if (b.storedPlantId != null && !storedAt.containsKey(k)) storedAt.put(k, b.storedPlantId);
        }
        // Melt: top of each continuous snow segment, per column, descending.
        if (snap.meltEnabled) {
            Map<Long, List<SnowRec>> byCol = new HashMap<>();
            for (SnowRec r : snap.snows) byCol.computeIfAbsent(colKey(r.x, r.z), kk -> new ArrayList<>()).add(r);
            for (List<SnowRec> col : byCol.values()) {
                col.sort((a, b) -> Integer.compare(b.y, a.y));
                int prevY = Integer.MAX_VALUE;
                for (SnowRec r : col) {
                    boolean isTop = (prevY == Integer.MAX_VALUE || r.y != prevY - 1);
                    prevY = r.y;
                    if (!isTop) continue;
                    if (r.snowingInBiome) continue;
                    if (rng.nextDouble() < 0.15) continue;
                    if (!r.heated && !snap.day) continue;
                    long k = new BlockPos(r.x, r.y, r.z).asLong();
                    // Original melt() refuses if snow is directly above.
                    Integer aboveKind = kind.get(new BlockPos(r.x, r.y + 1, r.z).asLong());
                    if (aboveKind != null && (aboveKind == 2 || aboveKind == 3)) continue;
                    if (r.isBlock) {
                        Change c = new Change(); c.x = r.x; c.y = r.y; c.z = r.z; c.kind = SET_LAYER;
                        c.expectedId = "minecraft:snow_block"; c.expectedLayers = -1; c.layers = Math.max(1, snap.maxLayers - 1);
                        out.add(c); kind.put(k, 2); layers.put(k, c.layers);
                    } else if (r.layers <= 1) {
                        String stored = storedAt.get(k);
                        if (stored != null) {
                            Change c = new Change(); c.x = r.x; c.y = r.y; c.z = r.z; c.kind = RESTORE_PLANT;
                            c.expectedId = "minecraft:snow"; c.expectedLayers = 1; c.plantId = stored;
                            out.add(c); kind.put(k, 1); plantIdAt.put(k, stored); storedAt.remove(k);
                        } else {
                            Change c = new Change(); c.x = r.x; c.y = r.y; c.z = r.z; c.kind = SET_AIR;
                            c.expectedId = "minecraft:snow"; c.expectedLayers = 1;
                            out.add(c); kind.put(k, 0);
                        }
                    } else {
                        Change c = new Change(); c.x = r.x; c.y = r.y; c.z = r.z; c.kind = SET_LAYER;
                        c.expectedId = "minecraft:snow"; c.expectedLayers = r.layers; c.layers = r.layers - 1;
                        out.add(c); kind.put(k, 2); layers.put(k, c.layers);
                    }
                }
            }
        }
        // Plant restoration for bare spots where snow is gone and it is not snowing.
        TreeMap<Integer, List<BareRec>> byGroundY = new TreeMap<>();
        for (BareRec b : snap.bares) byGroundY.computeIfAbsent(b.groundY, kk -> new ArrayList<>()).add(b);
        for (List<BareRec> group : byGroundY.values()) {
            for (BareRec b : group) {
                if (b.canAccum) continue;
                long k = new BlockPos(b.x, b.y, b.z).asLong();
                if (kind.getOrDefault(k, 4) != 0) continue;
                String stored = storedAt.get(k);
                if (stored == null) continue;
                Change c = new Change(); c.x = b.x; c.y = b.y; c.z = b.z; c.kind = RESTORE_PLANT;
                c.expectedId = "minecraft:air"; c.expectedLayers = -1; c.plantId = stored;
                out.add(c); kind.put(k, 1); plantIdAt.put(k, stored); storedAt.remove(k);
            }
        }
        if (!snap.accumulationEnabled) return out;
        // Bare accumulation, lowest ground first.
        List<BareRec> accumBare = new ArrayList<>();
        for (List<BareRec> group : byGroundY.values()) {
            for (BareRec b : group) {
                if (b.canAccum) accumBare.add(b);
                else if (snap.leafPass && b.snowingInBiome && b.leafCovered) accumBare.add(b);
            }
        }
        for (BareRec b : accumBare) {
            if (b.isCrop) continue;
            if (rng.nextDouble() < 0.15) continue;
            accumulateWorker(out, kind, layers, plantIdAt, storedAt, b.x, b.y, b.z, snap, rng);
        }
        // Pile-up: initial snow list only, lowest snow first, top snow per column only.
        List<SnowRec> sortedSnow = new ArrayList<>(snap.snows);
        sortedSnow.sort((a, b) -> Integer.compare(a.y, b.y));
        Map<Long, Integer> topSnowY = new HashMap<>();
        for (SnowRec r : snap.snows) topSnowY.merge(colKey(r.x, r.z), r.y, Math::max);
        for (SnowRec r : sortedSnow) {
            if (r.leafCovered) { if (!snap.leafPass || !r.snowingInBiome) continue; }
            else { if (!r.snowingInBiome) continue; }
            long k = new BlockPos(r.x, r.y, r.z).asLong();
            Integer aboveKind = kind.get(new BlockPos(r.x, r.y + 1, r.z).asLong());
            if (aboveKind != null && (aboveKind == 2 || aboveKind == 3)) continue;
            if (isValleyWorker(snap, r.x, r.z)) {
                int myTop = topSnowY.getOrDefault(colKey(r.x, r.z), r.y);
                int minNeighbourTop = Integer.MAX_VALUE;
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Integer nt = topSnowY.get(colKey(r.x + dx, r.z + dz));
                    if (nt != null && nt < minNeighbourTop) minNeighbourTop = nt;
                }
                if (minNeighbourTop != Integer.MAX_VALUE && myTop > minNeighbourTop + 1) continue;
            }
            if (r.leafCovered) {
                Integer kk = kind.get(k);
                if (kk != null && kk == 3) continue;
                Integer lay = layers.get(k);
                if (lay != null && lay >= 4 && kind.getOrDefault(k, 0) == 2) continue;
            }
            if (rng.nextDouble() < 0.15) continue;
            accumulateWorker(out, kind, layers, plantIdAt, storedAt, r.x, r.y, r.z, snap, rng);
        }
        java.util.Collections.shuffle(out, new java.util.Random(snap.cycleTick));
        return out;
    }

    private static boolean isValleyWorker(Snapshot snap, int x, int z) {
        Integer ground = snap.groundY.get(colKey(x, z));
        if (ground == null) return false;
        int maxDiff = -100, totalDiff = 0, count = 0;
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
            if (dx == 0 && dz == 0) continue;
            Integer ny = snap.groundY.get(colKey(x + dx, z + dz));
            if (ny == null) continue;
            int diff = ny - ground;
            totalDiff += diff; count++;
            if (diff > maxDiff) maxDiff = diff;
        }
        if (count == 0) return false;
        return maxDiff >= 1 && totalDiff >= 0;
    }

    // Worker-only accumulate, mirroring SnowProcessor.accumulate on the mutable copy.
    private static void accumulateWorker(List<Change> out, Map<Long, Integer> kind, Map<Long, Integer> layers,
            Map<Long, String> plantIdAt, Map<Long, String> storedAt, int x, int y, int z, Snapshot snap, java.util.Random rng) {
        long k = new BlockPos(x, y, z).asLong();
        int kk = kind.getOrDefault(k, 0);
        if (kk == 2) {
            int lay = layers.getOrDefault(k, 1);
            if (lay >= snap.maxLayers) {
                if (!isValleyWorker(snap, x, z)) return;
                int blocksBelow = 0;
                for (int i = 1; i <= 8; i++) {
                    Integer belowKind = kind.get(new BlockPos(x, y - i, z).asLong());
                    if (belowKind != null && belowKind == 3) blocksBelow++; else break;
                }
                if (blocksBelow >= 8) return;
                Change c = new Change(); c.x = x; c.y = y; c.z = z; c.kind = SET_BLOCK;
                c.expectedId = "minecraft:snow"; c.expectedLayers = lay;
                out.add(c); kind.put(k, 3); layers.put(k, 8);
                return;
            }
            Change c = new Change(); c.x = x; c.y = y; c.z = z; c.kind = SET_LAYER;
            c.expectedId = "minecraft:snow"; c.expectedLayers = lay; c.layers = lay + 1;
            out.add(c); layers.put(k, lay + 1);
            return;
        }
        if (kk == 3) {
            long aboveK = new BlockPos(x, y + 1, z).asLong();
            if (kind.getOrDefault(aboveK, 0) != 0) return;
            // Above a snow block: air, can hold snow (snow block is a full face).
            Change c = new Change(); c.x = x; c.y = y + 1; c.z = z; c.kind = SET_LAYER;
            c.expectedId = "minecraft:air"; c.expectedLayers = -1; c.layers = 1;
            out.add(c); kind.put(aboveK, 2); layers.put(aboveK, 1);
            return;
        }
        if (kk != 0 && kk != 1) return;
        // Air or plant: canHoldSnow was captured in the snapshot for bare positions.
        // For pile-up on a snow position that became air via melt, below is snow/ground,
        // which the original canHoldSnow would accept unless it is water/ice/powder.
        // Worker has no below id for non-bare positions, so accept unless the position
        // is a known bare position with canHoldSnow=false (handled by the caller list:
        // bare accumulation only iterates snapshot bares, whose canHoldSnow was true
        // at snapshot time, by construction).
        if (kk == 1) {
            String pid = plantIdAt.get(k);
            if (pid == null) return;
            Change c = new Change(); c.x = x; c.y = y; c.z = z;
            c.kind = TALL_PLANTS.contains(pid) ? SET_LAYER_AND_AIR_ABOVE : BURY_PLANT;
            c.expectedId = pid; c.expectedLayers = -1; c.layers = 1; c.plantId = pid;
            out.add(c); kind.put(k, 2); layers.put(k, 1);
            if (TALL_PLANTS.contains(pid)) {
                long aboveK = new BlockPos(x, y + 1, z).asLong();
                kind.put(aboveK, 0);
            }
            return;
        }
        Change c = new Change(); c.x = x; c.y = y; c.z = z; c.kind = SET_LAYER;
        c.expectedId = "minecraft:air"; c.expectedLayers = -1; c.layers = 1;
        out.add(c); kind.put(k, 2); layers.put(k, 1);
    }

    // ---- Server thread only: staggered apply, 2 ms AND 200 changes per tick ----
    private static void applyBatch(ServerWorld level) {
        if (pendingChanges == null || snapshotLevel != level) { state = State.IDLE; return; }
        long start = System.nanoTime();
        int appliedThisTick = 0;
        while (applyIndex < pendingChanges.size()) {
            if (appliedThisTick >= Math.max(1, RealisticSnowPhysicsConfig.get().asyncApplyMaxPerTick)) return;
            if (System.nanoTime() - start > Math.max(1, RealisticSnowPhysicsConfig.get().asyncApplyBudgetMillis) * 1_000_000L) return; // resume next tick
            appliedThisTick++;
            Change c = pendingChanges.get(applyIndex++);
            BlockPos pos = new BlockPos(c.x, c.y, c.z);
            try { applyOne(level, pos, c); } catch (Exception e) {
                RealisticSnowPhysicsMod.LOGGER.error("[Realistic Snow Physics] async apply failed at " + c.x + "," + c.y + "," + c.z, e);
            }
        }
        PlantStore.saveIfDirty();
        pendingChanges = null;
        state = State.IDLE;
    }

    private static void applyOne(ServerWorld level, BlockPos pos, Change c) {
        BlockState current = level.getBlockState(pos);
        String curId = idOf(current);
        if (c.expectedId != null && !curId.equals(c.expectedId)) {
            // Player, falling snow, or another system changed this block since the snapshot.
            // Skip, never overwrite player work. Exception: RESTORE_PLANT after melt may
            // find AIR if melt already applied by another path; then the plant was already
            // handled, skip as well.
            return;
        }
        if (c.expectedLayers >= 0 && current.contains(SnowBlock.LAYERS)
            && current.get(SnowBlock.LAYERS) != c.expectedLayers) return;
        switch (c.kind) {
            case SET_LAYER -> level.setBlockState(pos, Blocks.SNOW.getDefaultState().with(SnowBlock.LAYERS, c.layers), 2);
            case SET_BLOCK -> level.setBlockState(pos, Blocks.SNOW_BLOCK.getDefaultState(), 2);
            case SET_AIR -> level.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
            case RESTORE_PLANT -> {
                PlantStore.StoredPlant plant = PlantStore.take(level, pos);
                if (plant == null) { level.setBlockState(pos, Blocks.AIR.getDefaultState(), 2); return; }
                BlockState restored = PlantStore.restoreState(plant);
                if (restored == null) { level.setBlockState(pos, Blocks.AIR.getDefaultState(), 2); return; }
                level.setBlockState(pos, restored, 2);
                if (PlantStore.isTallPlant(restored) && level.getBlockState(pos.up()).isAir()) {
                    BlockState upper = restored.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
                    level.setBlockState(pos.up(), upper, 2);
                }
                PlantStore.saveIfDirty();
            }
            case BURY_PLANT, SET_LAYER_AND_AIR_ABOVE -> {
                Block plantBlock = Registries.BLOCK.get(new Identifier(c.plantId));
                if (plantBlock != null && plantBlock != Blocks.AIR) {
                    PlantStore.store(level, pos, plantBlock.getDefaultState());
                }
                if (c.kind == SET_LAYER_AND_AIR_ABOVE) level.setBlockState(pos.up(), Blocks.AIR.getDefaultState(), 2);
                level.setBlockState(pos, Blocks.SNOW.getDefaultState().with(SnowBlock.LAYERS, 1), 2);
            }
        }
    }
}
