package dev.realisticsnowphysics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class SnowProcessor {
    private SnowProcessor() {}
    public static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
    public static boolean isSnowLayer(BlockState state) {
        return "minecraft:snow".equals(blockId(state));
    }
    public static boolean isSnowBlock(BlockState state) {
        return "minecraft:snow_block".equals(blockId(state));
    }
    public static boolean isSnow(BlockState state) {
        return isSnowLayer(state) || isSnowBlock(state);
    }
    private static int heatRadiusFor(BlockState state, int strongRadius, int weakRadius) {
        String id = blockId(state);
        if (id.equals("minecraft:lava") || id.equals("minecraft:fire") || id.equals("minecraft:soul_fire") || id.equals("minecraft:magma_block")) return strongRadius;
        if (id.equals("minecraft:campfire") || id.equals("minecraft:soul_campfire")) return state.hasProperty(CampfireBlock.LIT) && state.getValue(CampfireBlock.LIT) ? strongRadius : 0;
        if (id.equals("minecraft:furnace") || id.equals("minecraft:blast_furnace") || id.equals("minecraft:smoker")) return state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT) ? strongRadius : 0;
        if (id.equals("minecraft:torch") || id.equals("minecraft:wall_torch") || id.equals("minecraft:lantern") || id.equals("minecraft:glowstone") || id.equals("minecraft:jack_o_lantern")) return weakRadius;
        return 0;
    }
    public static boolean hasHeatNearby(ServerLevel level, BlockPos pos, int radius) {
        RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
        int weakRadius = Math.max(1, config.torchHeatRadius);
        for (BlockPos candidate : BlockPos.betweenClosed(pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius, pos.getX() + radius, pos.getY() + radius, pos.getZ() + radius)) {
            int reach = heatRadiusFor(level.getBlockState(candidate), radius, weakRadius);
            if (reach <= 0) continue;
            int dx = Math.abs(candidate.getX() - pos.getX());
            int dy = Math.abs(candidate.getY() - pos.getY());
            int dz = Math.abs(candidate.getZ() - pos.getZ());
            if (Math.max(dx, Math.max(dy, dz)) <= reach) return true;
        }
        return false;
    }
    public static float effectiveTemperature(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).value().getBaseTemperature() + SeasonsHook.temperatureOffset();
    }
    public static double tickScale(ServerLevel level) {
        try {
            int speed = level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED);
            return speed <= 0 ? 0.0 : speed / 3.0;
        } catch (Exception e) { return 1.0; }
    }
    public static boolean isSnowing(ServerLevel level, BlockPos pos) {
        return level.isRaining() && level.canSeeSky(pos) && level.getBiome(pos).value().hasPrecipitation() && effectiveTemperature(level, pos) < 0.15f;
    }
    public static boolean isSnowingInBiome(ServerLevel level, BlockPos pos) {
        return (level.isRaining() && level.getBiome(pos).value().hasPrecipitation() && effectiveTemperature(level, pos) < 0.15f) || SeasonsHook.forceSnowPrecipitation();
    }
    public static boolean isForcedSnowPrecipitation(ServerLevel level, BlockPos pos) {
        if (!SeasonsHook.forceSnowPrecipitation()) return false;
        if (!level.isRaining() || !level.canSeeSky(pos)) return false;
        return level.getBiome(pos).value().hasPrecipitation();
    }
    public static boolean canAccumulatePrecipitation(ServerLevel level, BlockPos pos) {
        return isSnowing(level, pos) || isForcedSnowPrecipitation(level, pos);
    }
    public static boolean isLeafCoveredUncached(ServerLevel level, BlockPos pos) {
        for (int y = pos.getY() + 1; y <= pos.getY() + 20; y++) {
            BlockState s = level.getBlockState(new BlockPos(pos.getX(), y, pos.getZ()));
            if (s.isAir()) continue;
            if (isSnow(s)) continue;
            String id = blockId(s);
            return id.endsWith("_leaves");
        }
        return false;
    }
    public static boolean isLeafCovered(ServerLevel level, BlockPos pos) {
        long k = pos.asLong();
        Boolean cached = leafCache.get(k);
        if (cached != null) return cached;
        boolean v = isLeafCoveredUncached(level, pos);
        leafCache.put(k, v);
        return v;
    }
    public static boolean canAccumulateUnderLeaves(ServerLevel level, BlockPos pos) {
        boolean snowing = (level.isRaining() && level.getBiome(pos).value().hasPrecipitation() && effectiveTemperature(level, pos) < 0.15f) || isForcedSnowPrecipitation(level, pos);
        if (!snowing) return false;
        if (level.canSeeSky(pos)) return false;
        return isLeafCovered(level, pos);
    }
    private static boolean canHoldSnow(ServerLevel level, BlockPos below) {
        BlockState state = level.getBlockState(below);
        if (state.isAir()) return false;
        String id = blockId(state);
        if (id.equals("minecraft:water") || id.equals("minecraft:ice") || id.equals("minecraft:packed_ice") || id.equals("minecraft:blue_ice") || id.equals("minecraft:powder_snow")) return false;
        return state.isFaceSturdy(level, below, net.minecraft.core.Direction.UP);
    }
    public static boolean isValleyUncached(ServerLevel level, BlockPos pos) {
        BlockPos cursor = pos;
        for (int i = 0; i < 6; i++) {
            BlockState below = level.getBlockState(cursor.below());
            if (isSnow(below) || PlantStore.isPlant(below)) { cursor = cursor.below(); continue; }
            break;
        }
        int groundY = cursor.below().getY();
        int higher = 0; int totalDiff = 0; int count = 0; int maxDiff = -100;
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
            if (dx == 0 && dz == 0) continue;
            int nx = pos.getX() + dx; int nz = pos.getZ() + dz;
            Integer cachedGround = groundYCache.get(((long) nx << 32) | (nz & 0xffffffffL));
            int ny;
            if (cachedGround != null) {
                ny = cachedGround;
            } else {
            int topY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, nx, nz);
            ny = topY;
            for (int y = topY; y >= topY - 10; y--) {
                BlockState s = level.getBlockState(new BlockPos(nx, y, nz));
                if (isSnow(s) || PlantStore.isPlant(s)) continue;
                if (s.isAir()) continue;
                if (blockId(s).endsWith("_leaves")) continue;
                ny = y; break;
            }
            }
            int diff = ny - groundY;
            if (diff >= 1) higher++;
            totalDiff += diff; count++;
            if (diff > maxDiff) maxDiff = diff;
        }
        if (count == 0) return false;
        // Valley if at least one neighbour in radius 6 is higher, and the column is not above average terrain.
        // Old avg>=1 rule failed for small pits and wide valley floors, where most neighbours are flat and only walls are higher.
        return maxDiff >= 1 && totalDiff >= 0;
    }
    public static boolean isValley(ServerLevel level, BlockPos pos) {
        long k = pos.asLong();
        Boolean cached = valleyCache.get(k);
        if (cached != null) return cached;
        boolean v = isValleyUncached(level, pos);
        valleyCache.put(k, v);
        return v;
    }
    public static String valleyDiagnose(ServerLevel level, BlockPos pos) {
        BlockPos cursor = pos;
        for (int i = 0; i < 6; i++) {
            BlockState below = level.getBlockState(cursor.below());
            if (isSnow(below) || PlantStore.isPlant(below)) { cursor = cursor.below(); continue; }
            break;
        }
        int groundY = cursor.below().getY();
        int maxDiff = -100; int totalDiff = 0; int count = 0;
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
            if (dx == 0 && dz == 0) continue;
            int nx = pos.getX() + dx; int nz = pos.getZ() + dz;
            Integer cachedGround = groundYCache.get(((long) nx << 32) | (nz & 0xffffffffL));
            int ny;
            if (cachedGround != null) {
                ny = cachedGround;
            } else {
            int topY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, nx, nz);
            ny = topY;
            for (int y = topY; y >= topY - 10; y--) {
                BlockState s = level.getBlockState(new BlockPos(nx, y, nz));
                if (isSnow(s) || PlantStore.isPlant(s)) continue;
                if (s.isAir()) continue;
                if (blockId(s).endsWith("_leaves")) continue;
                ny = y; break;
            }
            }
            int diff = ny - groundY; totalDiff += diff; count++; if (diff > maxDiff) maxDiff = diff;
        }
        int snowDepthLayers = 0; int snowBlocks = 0; String topSnow = "none";
        for (int y = pos.getY() + 10; y >= groundY; y--) {
            BlockState s = level.getBlockState(new BlockPos(pos.getX(), y, pos.getZ()));
            if (isSnowBlock(s)) { snowBlocks++; snowDepthLayers += 8; if (topSnow.equals("none")) topSnow = "block@" + y; }
            else if (isSnowLayer(s)) { snowDepthLayers += s.getValue(SnowLayerBlock.LAYERS); if (topSnow.equals("none")) topSnow = "layers" + s.getValue(SnowLayerBlock.LAYERS) + "@" + y + " canSeeSky=" + level.canSeeSky(new BlockPos(pos.getX(), y, pos.getZ())) + " canAccum=" + canAccumulatePrecipitation(level, new BlockPos(pos.getX(), y, pos.getZ())) + " isValleyAtSnow=" + isValley(level, new BlockPos(pos.getX(), y, pos.getZ())); }
        }
        return "groundY=" + groundY + " maxDiff=" + maxDiff + " totalDiff=" + totalDiff + " count=" + count + " isValley=" + isValley(level, pos) + " leafCovered=" + isLeafCovered(level, pos) + " snowingInBiome=" + isSnowingInBiome(level, pos) + " raining=" + level.isRaining() + " biomeTemp=" + effectiveTemperature(level, pos) + " snowDepthLayers=" + snowDepthLayers + " snowBlocks=" + snowBlocks + " topSnow=" + topSnow;
    }
    public static boolean accumulate(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isSnowLayer(state)) {
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            int max = RealisticSnowPhysicsConfig.get().maxLayers;
            if (layers >= max) {
                if (!isValley(level, pos)) return false;
                int snowBlocksBelow = 0; BlockPos cursor = pos.below();
                for (int i = 0; i < 8; i++) { if (isSnowBlock(level.getBlockState(cursor))) { snowBlocksBelow++; cursor = cursor.below(); } else break; }
                if (snowBlocksBelow >= 8) return false;
                level.setBlock(pos, Blocks.SNOW_BLOCK.defaultBlockState(), 3); return true;
            }
            level.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, layers + 1), 3); return true;
        }
        if (isSnowBlock(state)) {
            BlockPos above = pos.above();
            if (!level.getBlockState(above).isAir()) return false;
            return accumulate(level, above);
        }
        if (!state.isAir() && !PlantStore.isPlant(state)) return false;
        BlockPos below = pos.below();
        if (!canHoldSnow(level, below)) return false;
        BlockState current = level.getBlockState(pos);
        if (PlantStore.isPlant(current)) {
            PlantStore.store(level, pos, current);
            if (PlantStore.isTallPlant(current)) level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
        } else if (!current.isAir()) return false;
        level.setBlock(pos, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1), 3); return true;
    }
    public static boolean melt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isSnow(level.getBlockState(pos.above()))) return false;
        if (isSnowLayer(state)) {
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            if (layers <= 1) {
                PlantStore.StoredPlant plant = PlantStore.take(level, pos);
                if (plant != null) {
                    BlockState restored = PlantStore.restoreState(plant);
                    if (restored != null) {
                        level.setBlock(pos, restored, 3);
                        if (PlantStore.isTallPlant(restored) && level.getBlockState(pos.above()).isAir()) {
                            BlockState upper = restored.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
                            level.setBlock(pos.above(), upper, 3);
                        }
                        PlantStore.saveIfDirty(); return true;
                    }
                }
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            } else {
                level.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, layers - 1), 3);
            }
            return true;
        }
        if (isSnowBlock(state)) {
            int max = RealisticSnowPhysicsConfig.get().maxLayers;
            level.setBlock(pos, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, Math.max(1, max - 1)), 3); return true;
        }
        return false;
    }
    private static int passCounter = 0;
    private static final java.util.Map<Long, Boolean> valleyCache = new java.util.HashMap<>();
    private static final java.util.Map<Long, Integer> groundYCache = new java.util.HashMap<>();
    private static final java.util.Map<Long, Boolean> leafCache = new java.util.HashMap<>();
    public static void processArea(ServerLevel level, BlockPos centre, int radius, int batchSize) { processArea(level, centre, radius, batchSize, new java.util.HashSet<Long>()); }
    public static void processArea(ServerLevel level, BlockPos centre, int radius, int batchSize, java.util.Set<Long> processedColumns) {
        RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
        if (!config.enabled) return;
        double scale = tickScale(level);
        if (scale <= 0.0) return;
        passCounter++;
        if (passCounter % 30 != 0) return;
        valleyCache.clear(); leafCache.clear(); groundYCache.clear();
        // Wide area, 96: 193x193 columns, covers nearby biomes, with per-pass caches to keep cost down.
        int wideRadius = Math.max(radius, 96);
        // Collect every column in the wide area, grouped by ground Y, lowest first.
        // Ground Y is the Y of the top solid block in that column, found by direct scan down from centre+10 and world surface.
        java.util.TreeMap<Integer, java.util.ArrayList<BlockPos>> byGroundY = new java.util.TreeMap<>();
        java.util.Set<Long> leafCoveredSet = new java.util.HashSet<>();
        java.util.ArrayList<BlockPos> allSnow = new java.util.ArrayList<>();
        java.util.ArrayList<BlockPos> bareByY = new java.util.ArrayList<>();
        java.util.Map<Long, net.minecraft.world.level.chunk.LevelChunk> chunkCache = new java.util.HashMap<>();
        for (int x = centre.getX() - wideRadius; x <= centre.getX() + wideRadius; x++) {
            for (int z = centre.getZ() - wideRadius; z <= centre.getZ() + wideRadius; z++) {
                if (!level.isLoaded(new BlockPos(x, centre.getY(), z))) continue;
                int topY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                // Scan down from top to find ground and snow in this column, within a reasonable band around the player and terrain.
                // Use topY as terrain height, not player Y, so hills and valleys are both covered.
                boolean leafAbove = false;
                for (int y = topY + 3; y >= topY - 40 && y >= level.getMinY(); y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (isSnow(state)) { allSnow.add(pos); continue; }
                    if (state.isAir()) continue;
                    String sid = blockId(state);
                    if (sid.endsWith("_leaves")) {
                        leafAbove = true;
                        BlockPos above = pos.above();
                        if (level.getBlockState(above).isAir() && canHoldSnow(level, pos)) {
                            byGroundY.computeIfAbsent(pos.getY(), k -> new java.util.ArrayList<>()).add(above);
                        }
                        continue;
                    }
                    if (!state.isAir()) {
                        if (PlantStore.isPlant(state)) {
                            BlockPos lowest = pos;
                            while (PlantStore.isPlant(level.getBlockState(lowest.below()))) {
                                lowest = lowest.below();
                            }
                            BlockPos below = lowest.below();
                            if (canHoldSnow(level, below)) {
                                groundYCache.put(((long) x << 32) | (z & 0xffffffffL), below.getY());
                                byGroundY.computeIfAbsent(below.getY(), k -> new java.util.ArrayList<>()).add(lowest);
                                if (leafAbove) leafCoveredSet.add(lowest.asLong());
                            }
                            break;
                        }
                        BlockPos above = pos.above();
                        BlockState aboveState = level.getBlockState(above);
                        if ((aboveState.isAir() || PlantStore.isPlant(aboveState)) && canHoldSnow(level, pos)) {
                            groundYCache.put(((long) x << 32) | (z & 0xffffffffL), pos.getY());
                            byGroundY.computeIfAbsent(pos.getY(), k -> new java.util.ArrayList<>()).add(above);
                            if (leafAbove) leafCoveredSet.add(above.asLong());
                        }
                        break;
                    }
                }
            }
        }
        // Melt: topmost snow of each continuous snow segment, not just topmost in the column.
        // A column can have leaf snow on top of leaves and ground snow below leaves, separated by leaves.
        // Melting only the first segment left ground snow under leaves never melting, so it grew to the leaves.
        // Now each segment melts independently, so snow touching leaves melts too.
        // Reuses allSnow from the heightmap scan, no second direct box scan, which was a major overload cause.
        // Randomization: 15% skip per segment per melt pass, same as accumulation.
        if (config.meltEnabled) {
            boolean day = level.isBrightOutside();
            // Group allSnow by column, sort y descending, melt top of each continuous run
            java.util.Map<Long, java.util.ArrayList<BlockPos>> byCol = new java.util.HashMap<>();
            for (BlockPos pos : allSnow) {
                long key = ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
                byCol.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(pos);
            }
            for (java.util.ArrayList<BlockPos> col : byCol.values()) {
                col.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
                int prevY = Integer.MAX_VALUE;
                for (BlockPos pos : col) {
                    if (prevY != Integer.MAX_VALUE && pos.getY() != prevY - 1) {
                        // Gap in snow, this is start of a new segment below, melt its top too, then continue to next segment
                        // Actually we want top of each segment, so melt this pos if it is top of its segment, which it is after a gap
                    }
                    boolean isTopOfSegment = (prevY == Integer.MAX_VALUE || pos.getY() != prevY - 1);
                    prevY = pos.getY();
                    if (!isTopOfSegment) continue;
                    boolean snowingInBiome = (level.isRaining() && level.getBiome(pos).value().hasPrecipitation() && effectiveTemperature(level, pos) < 0.15f) || SeasonsHook.forceSnowPrecipitation();
                    if (snowingInBiome) continue;
                    if (level.getRandom().nextDouble() < 0.15) continue;
                    boolean heated = false;
                    if (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos) > 0) {
                        heated = hasHeatNearby(level, pos, config.heatRadius);
                    }
                    if (heated) { melt(level, pos); continue; }
                    if (!day) continue;
                    melt(level, pos);
                }
            }
        }
        // Plant restoration after melt, for bare spots where snow is gone and it is not snowing.
        for (java.util.ArrayList<BlockPos> group : byGroundY.values()) {
            for (BlockPos pos : group) {
                if (canAccumulatePrecipitation(level, pos)) continue;
                if (!level.getBlockState(pos).isAir()) continue;
                PlantStore.StoredPlant plant = PlantStore.take(level, pos);
                if (plant != null) {
                    BlockState restored = PlantStore.restoreState(plant);
                    if (restored != null) {
                        level.setBlock(pos, restored, 3);
                        if (PlantStore.isTallPlant(restored) && level.getBlockState(pos.above()).isAir()) {
                            BlockState upper = restored.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
                            level.setBlock(pos.above(), upper, 3);
                        }
                    }
                }
            }
        }
        PlantStore.saveIfDirty();
        if (!config.accumulationEnabled) return;
        // Accumulation: all at once, slowly, one layer per accumulation pass, grouped by Y level lowest first.
        // One accumulation pass every 30 seconds: each layer spreads to every column in that one pass, with no stagger between neighbours, then a 30 second pause before the next layer. every bare ground column in the wide area gets 1 layer at the same time,
        // then next pass every existing snow column gets 1 more layer, also all at once.
        // This is not 16 random samples, and it does not pile one column up multiple times while others stay bare.
        // When a column gains a layer, columns below it in Y are in earlier Y groups and were already given that layer in the same pass,
        // because groups are processed lowest first and all groups are processed in one pass.
        if (passCounter % 30 != 0) return;
        // First, check if there is any bare ground that can snow. If yes, blanket all of it, one layer each, lowest Y first.
        java.util.ArrayList<BlockPos> accumBare = new java.util.ArrayList<>();
        boolean leafPass = passCounter % 60 == 0;
        for (java.util.Map.Entry<Integer, java.util.ArrayList<BlockPos>> e : byGroundY.entrySet()) {
            for (BlockPos pos : e.getValue()) {
                if (canAccumulatePrecipitation(level, pos)) accumBare.add(pos);
                else if (leafPass && isSnowingInBiome(level, pos) && (leafCoveredSet.contains(pos.asLong()) || isLeafCovered(level, pos))) accumBare.add(pos);
            }
        }
        for (BlockPos pos : accumBare) {
            BlockState s = level.getBlockState(pos);
            if (PlantStore.isCrop(s)) continue;
            if (level.getRandom().nextDouble() < 0.15) continue;
            accumulate(level, pos);
        }
        // No bare ground left, so pile up: every top snow column gets 1 more layer, all at once, lowest Y first.
        // Group top snow by ground Y: ground Y is snow Y minus snow height, approximate by snow Y itself for ordering.
        allSnow.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
        java.util.Map<Long, Integer> topSnowY = new java.util.HashMap<>();
        for (BlockPos sp : allSnow) {
            long k = ((long) sp.getX() << 32) | (sp.getZ() & 0xffffffffL);
            topSnowY.merge(k, sp.getY(), Math::max);
        }
        for (BlockPos pos : allSnow) {
            boolean leafCol = leafCoveredSet.contains(pos.asLong()) || isLeafCovered(level, pos);
            if (leafCol) { if (!leafPass || !isSnowingInBiome(level, pos)) continue; }
            else { if (!isSnowingInBiome(level, pos)) continue; }
            // Only pile on the top snow in a column: if there is snow directly above, skip, that upper snow will pile instead.
            if (isSnow(level.getBlockState(pos.above()))) continue;
            if (isValley(level, pos)) {
                int myTop = topSnowY.getOrDefault(((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL), pos.getY());
                int minNeighbourTop = Integer.MAX_VALUE;
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Integer nt = topSnowY.get(((long) (pos.getX() + dx) << 32) | ((pos.getZ() + dz) & 0xffffffffL));
                    if (nt != null && nt < minNeighbourTop) minNeighbourTop = nt;
                }
                if (minNeighbourTop != Integer.MAX_VALUE && myTop > minNeighbourTop + 1) continue;
            }
            if (leafCoveredSet.contains(pos.asLong()) || isLeafCovered(level, pos)) {
                BlockState st = level.getBlockState(pos);
                if (isSnowBlock(st)) continue;
                if (isSnowLayer(st) && st.getValue(SnowLayerBlock.LAYERS) >= 4) continue;
            }
            if (level.getRandom().nextDouble() < 0.15) continue;
            accumulate(level, pos);
        }
    }
    public static void process(ServerLevel level, BlockPos pos) {
        RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
        BlockState state = level.getBlockState(pos);
        if (config.meltEnabled && isSnow(state)) melt(level, pos);
    }
}
