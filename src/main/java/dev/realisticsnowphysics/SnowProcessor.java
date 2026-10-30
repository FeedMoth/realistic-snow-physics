package dev.realisticsnowphysics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The snow rules, kept separate from tick scheduling so they can be
 * read and tested on their own.
 *
 * This works on pre-generated chunks, not just fresh ones. There is
 * no world generation in this mod: accumulation and melt only look
 * at blocks already in a loaded chunk, when a player is nearby.
 *
 * Accumulation only happens where snow is actually falling: the
 * precipitation at that position is snow, not rain, the position
 * has sky above it, and the ground below can hold snow. Rain in
 * a biome never makes snow. If a separate seasons mod is registered through SeasonsHook
 * and forces snow precipitation, precipitation in any biome
 * that has precipitation at all can fall as snow, except
 * deserts and other no-precipitation biomes, which stay dry.
 * On its own, Snow has no seasons and does not do this. A snow layer gains one layer
 * at a time, up to the configured maximum. A flat solid block gains
 * its first layer.
 *
 * Blanket first, lowest first, then pile up. Snow does not appear
 * as random spots with green ground between them. Each pass scans
 * the ground around a player from the lowest surface upwards. If
 * any ground in that area is still bare, that pass only puts a
 * first layer on bare ground, lowest first. Only once every
 * accumulable spot already has at least one layer does a pass
 * start adding a second, third, and further layer, again lowest
 * first. Valleys fill before ridges, and the ground is covered
 * before it piles up, which is how a real snowfall looks.
 *
 * Melt happens in two cases, and also one layer at a time. A heat
 * source nearby melts snow in any light, and warm daylight melts
 * snow in biomes at or above the configured temperature. A snow
 * block does not vanish in one step: it first becomes a full
 * seven-layer pile, then melts down from there.
 *
 * Nothing here touches player inventory, movement, or rendering.
 * Snow that is already placed by a player or by world generation
 * follows the same melt rules, because melt is about the block,
 * not about how it got there.
 */
public final class SnowProcessor {
    private SnowProcessor() {
    }

    public static boolean isSnowLayer(BlockState state) {
        return state.is(Blocks.SNOW);
    }

    public static boolean isSnowBlock(BlockState state) {
        return state.is(Blocks.SNOW_BLOCK);
    }

    private static int heatRadiusFor(BlockState state, int strongRadius, int weakRadius) {
        if (state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) {
            return strongRadius;
        }
        if (state.is(Blocks.MAGMA_BLOCK)) {
            return strongRadius;
        }
        if (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) {
            return state.hasProperty(CampfireBlock.LIT) && state.getValue(CampfireBlock.LIT) ? strongRadius : 0;
        }
        if (state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER)) {
            return state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT) ? strongRadius : 0;
        }
        if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH) || state.is(Blocks.LANTERN)
                || state.is(Blocks.GLOWSTONE) || state.is(Blocks.JACK_O_LANTERN)) {
            return weakRadius;
        }
        return 0;
    }

    public static boolean hasHeatNearby(ServerLevel level, BlockPos pos, int radius) {
        RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
        int weakRadius = Math.max(1, config.torchHeatRadius);
        for (BlockPos candidate : BlockPos.betweenClosed(
                pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius,
                pos.getX() + radius, pos.getY() + radius, pos.getZ() + radius)) {
            int reach = heatRadiusFor(level.getBlockState(candidate), radius, weakRadius);
            if (reach <= 0) {
                continue;
            }
            int dx = Math.abs(candidate.getX() - pos.getX());
            int dy = Math.abs(candidate.getY() - pos.getY());
            int dz = Math.abs(candidate.getZ() - pos.getZ());
            if (Math.max(dx, Math.max(dy, dz)) <= reach) {
                return true;
            }
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
        } catch (Exception e) {
            return 1.0;
        }
    }

    public static boolean isSnowing(ServerLevel level, BlockPos pos) {
        return level.isRaining()
                && level.canSeeSky(pos)
                && level.getBiome(pos).value().hasPrecipitation()
                && effectiveTemperature(level, pos) < 0.15f;
    }

    public static boolean isForcedSnowPrecipitation(ServerLevel level, BlockPos pos) {
        if (!SeasonsHook.forceSnowPrecipitation()) {
            return false;
        }
        if (!level.isRaining() || !level.canSeeSky(pos)) {
            return false;
        }
        return level.getBiome(pos).value().hasPrecipitation();
    }

    public static boolean canAccumulatePrecipitation(ServerLevel level, BlockPos pos) {
        return isSnowing(level, pos) || isForcedSnowPrecipitation(level, pos);
    }

    private static boolean canHoldSnow(ServerLevel level, BlockPos below) {
        BlockState state = level.getBlockState(below);
        if (state.isAir()) {
            return false;
        }
        if (state.is(Blocks.WATER) || state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.BLUE_ICE) || state.is(Blocks.POWDER_SNOW)) {
            return false;
        }
        // Leaves hold snow in vanilla, and trees look right with a
        // dusting, so they are allowed. Full solid blocks are the
        // normal case. Slabs, stairs, and other partial blocks are
        // left alone: snow floating on a half slab looks wrong, and
        // vanilla already handles those cases inconsistently.
        return state.isFaceSturdy(level, below, net.minecraft.core.Direction.UP);
    }

    /**
     * Try to add one layer of snow at {@code pos}. Returns true when
     * the world changed. {@code pos} is the air or snow position
     * itself, not the ground below it.
     */
    public static boolean isValley(ServerLevel level, BlockPos pos) {
        int groundY = pos.getY();
        BlockPos cursor = pos;
        for (int i = 0; i < 4; i++) {
            BlockState below = level.getBlockState(cursor.below());
            if (isSnowLayer(below) || isSnowBlock(below)) {
                cursor = cursor.below();
                continue;
            }
            groundY = cursor.below().getY();
            break;
        }
        int higher = 0;
        int totalDiff = 0;
        int count = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos neighbour = level.getHeightmapPos(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                        new BlockPos(pos.getX() + dx, 0, pos.getZ() + dz));
                int diff = neighbour.getY() - groundY;
                if (diff >= 2) {
                    higher++;
                }
                totalDiff += diff;
                count++;
            }
        }
        return higher >= 8 || (count > 0 && totalDiff / count >= 2);
    }

    public static boolean accumulate(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isSnowLayer(state)) {
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            int max = RealisticSnowPhysicsConfig.get().maxLayers;
            if (layers >= max) {
                if (!isValley(level, pos)) {
                    return false;
                }
                int snowBlocksBelow = 0;
                BlockPos cursor = pos.below();
                for (int i = 0; i < 3; i++) {
                    if (isSnowBlock(level.getBlockState(cursor))) {
                        snowBlocksBelow++;
                        cursor = cursor.below();
                    } else {
                        break;
                    }
                }
                if (snowBlocksBelow >= 2) {
                    return false;
                }
                level.setBlock(pos, Blocks.SNOW_BLOCK.defaultBlockState(), 3);
                return true;
            }
            level.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, layers + 1), 3);
            return true;
        }
        if (isSnowBlock(state)) {
            BlockPos above = pos.above();
            if (!level.getBlockState(above).isAir()) {
                return false;
            }
            return accumulate(level, above);
        }
        if (!state.isAir() && !PlantStore.isPlant(state)) {
            return false;
        }
        BlockPos below = pos.below();
        if (!canHoldSnow(level, below)) {
            return false;
        }
        // Plants are buried, not left poking through snow, and they
        // are remembered so melt can bring them back. Torches and
        // other small blocks are still left alone.
        BlockState current = level.getBlockState(pos);
        if (PlantStore.isPlant(current)) {
            PlantStore.store(level, pos, current);
            if (PlantStore.isTallPlant(current) && PlantStore.isTallPlant(level.getBlockState(pos.above()))) {
                level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
            } else if (PlantStore.isTallPlant(current)) {
                level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
            }
        } else if (!current.isAir()) {
            return false;
        }
        level.setBlock(pos, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1), 3);
        return true;
    }

    /**
     * Try to remove one layer of snow at {@code pos}. Returns true
     * when the world changed.
     */
    public static boolean melt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isSnowBlock(level.getBlockState(pos.above()))) {
            return false;
        }
        if (isSnowLayer(state)) {
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            if (layers <= 1) {
                PlantStore.StoredPlant plant = PlantStore.take(level, pos);
                if (plant != null) {
                    BlockState restored = PlantStore.restoreState(plant);
                    if (restored != null) {
                        level.setBlock(pos, restored, 3);
                        if (PlantStore.isTallPlant(restored) && level.getBlockState(pos.above()).isAir()) {
                            BlockState upper = restored.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
                                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
                            level.setBlock(pos.above(), upper, 3);
                        }
                        PlantStore.saveIfDirty();
                        return true;
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
            level.setBlock(pos, Blocks.SNOW.defaultBlockState()
                    .setValue(SnowLayerBlock.LAYERS, Math.max(1, max - 1)), 3);
            return true;
        }
        return false;
    }

    /**
     * Process the ground around a player in blanket order, lowest
     * surface first. This is the accumulation path used by the tick
     * handler. It scans every column in the radius, sorts by surface
     * height, then melts a bounded batch, then either blankets bare
     * ground or, if nothing is bare, piles up existing snow. The
     * batch size is the configured samples-per-player value, so a
     * full blanket takes a few minutes to form instead of appearing
     * instantly, but it always forms evenly, with no green spots left
     * between random piles.
     */
    public static void processArea(ServerLevel level, BlockPos centre, int radius, int batchSize) {
        processArea(level, centre, radius, batchSize, new java.util.HashSet<Long>());
    }

    public static void processArea(ServerLevel level, BlockPos centre, int radius, int batchSize, java.util.Set<Long> processedColumns) {
        RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
        double scale = tickScale(level);
        if (scale <= 0.0) {
            return;
        }
        int accumulationBatch = Math.max(1, (int) Math.round(batchSize * SeasonsHook.accumulationMultiplier() * scale));
        int meltBatchScaled = Math.max(1, (int) Math.round(config.meltBatchSize * SeasonsHook.meltMultiplier() * scale));
        java.util.ArrayList<BlockPos> snowPositions = new java.util.ArrayList<>();
        java.util.ArrayList<BlockPos> barePositions = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> snowHeights = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> bareHeights = new java.util.ArrayList<>();
        for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
            for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
                long columnKey = ((long) x << 32) | (z & 0xffffffffL);
                if (processedColumns.contains(columnKey)) {
                    continue;
                }
                // Top-down scan, not heightmap trust: find the topmost snow
                // in this column, layer or full block, whatever the
                // heightmap thinks the surface is. All snow melts, period.
                // Full column scan: count EVERY snow block and layer
                // in this column, not just the top one. A snow cube
                // in a wall, under another block, or under a layer
                // is still snow, and it still melts, unless a snow
                // block is directly above it.
                int topY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos groundPos = null;
                for (int y = topY + 3; y >= level.getMinY() && y >= topY - 40; y--) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    BlockState candidateState = level.getBlockState(candidate);
                    if (isSnowLayer(candidateState) || isSnowBlock(candidateState)) {
                        if (!isSnowBlock(level.getBlockState(candidate.above()))) {
                            snowPositions.add(candidate);
                            snowHeights.add(candidate.getY());
                        }
                        continue;
                    }
                    if (groundPos == null && !candidateState.isAir()) {
                        groundPos = candidate;
                    }
                }
                if (groundPos == null) {
                    groundPos = new BlockPos(x, topY, z);
                }
                BlockPos bare = groundPos.above();
                BlockState bareState = level.getBlockState(bare);
                if ((bareState.isAir() || PlantStore.isPlant(bareState)) && canHoldSnow(level, groundPos)) {
                    barePositions.add(bare);
                    bareHeights.add(bare.getY());
                }
            }
        }
        // Simple melt, rewritten: every snow block and layer in a box
        // around the player melts in clear daytime, no heightmap,
        // no batch cap, no random shuffle. If it is day and the
        // snow is not under a snow block, it melts one layer per
        // second. That is the whole rule.
        if (config.meltEnabled) {
            for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
                for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
                    for (int y = centre.getY() - 10; y <= centre.getY() + 10; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        if (!isSnowLayer(state) && !isSnowBlock(state)) {
                            continue;
                        }
                        if (isSnowBlock(level.getBlockState(pos.above()))) {
                            continue;
                        }
                        double chance = 1.0;
                        if (chance > 0.0 && level.getRandom().nextDouble() < chance) {
                            melt(level, pos);
                        }
                    }
                }
            }
        }
        // Plants buried by Snow come back when their snow is gone and
        // it is not snowing again right now, so a player clearing snow
        // after a storm, or snow melting away, restores the flower,
        // grass, or mushroom that was underneath.
        for (BlockPos pos : barePositions) {
            if (canAccumulatePrecipitation(level, pos)) {
                continue;
            }
            if (!level.getBlockState(pos).isAir()) {
                continue;
            }
            PlantStore.StoredPlant plant = PlantStore.take(level, pos);
            if (plant != null) {
                BlockState restored = PlantStore.restoreState(plant);
                if (restored != null) {
                    level.setBlock(pos, restored, 3);
                    if (PlantStore.isTallPlant(restored) && level.getBlockState(pos.above()).isAir()) {
                        BlockState upper = restored.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
                                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
                        level.setBlock(pos.above(), upper, 3);
                    }
                }
            }
        }
        PlantStore.saveIfDirty();
        if (!config.accumulationEnabled) {
            return;
        }
        // Bare ground wins: blanket first, no piling until covered.
        java.util.ArrayList<BlockPos> accumulableBare = new java.util.ArrayList<>();
        for (BlockPos pos : barePositions) {
            if (canAccumulatePrecipitation(level, pos)) {
                accumulableBare.add(pos);
            }
        }
        if (!accumulableBare.isEmpty()) {
            int done = 0;
            for (BlockPos pos : accumulableBare) {
                if (done >= Math.max(1, accumulationBatch)) {
                    break;
                }
                processedColumns.add(((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL));
                if (accumulate(level, pos)) {
                    done++;
                }
            }
            return;
        }
        // Nothing bare left that can take snow, so pile up, lowest first.
        int done = 0;
        for (BlockPos pos : snowPositions) {
            if (done >= Math.max(1, accumulationBatch)) {
                break;
            }
            processedColumns.add(((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL));
            if (isSnowBlock(level.getBlockState(pos))) {
                if (canAccumulatePrecipitation(level, pos)) {
                    if (accumulate(level, pos)) {
                        done++;
                    }
                }
                continue;
            }
            if (!isSnowLayer(level.getBlockState(pos))) {
                continue;
            }
            if (canAccumulatePrecipitation(level, pos)) {
                if (accumulate(level, pos)) {
                    done++;
                }
            }
        }
    }

    private static void sortByHeight(java.util.ArrayList<BlockPos> positions, java.util.ArrayList<Integer> heights) {
        for (int i = 1; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            int height = heights.get(i);
            int j = i - 1;
            while (j >= 0 && heights.get(j) > height) {
                positions.set(j + 1, positions.get(j));
                heights.set(j + 1, heights.get(j));
                j--;
            }
            positions.set(j + 1, pos);
            heights.set(j + 1, height);
        }
    }

    /** Decide, for one sampled position, whether it should accumulate or melt. */
    public static void process(ServerLevel level, BlockPos pos) {
        RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
        BlockState state = level.getBlockState(pos);

        if (config.meltEnabled && (isSnowLayer(state) || isSnowBlock(state))) {
            boolean bright = level.isBrightOutside() && level.canSeeSky(pos);
            boolean warm = effectiveTemperature(level, pos) >= config.warmBiomeTemperature;
            boolean heated = hasHeatNearby(level, pos, config.heatRadius);
            double chance = heated ? config.heatMeltChance : (bright && warm ? config.warmMeltChance : (bright ? config.coldSunMeltChance : 0.0));
            if (chance > 0.0 && level.getRandom().nextDouble() < chance) {
                melt(level, pos);
                return;
            }
        }

        if (!config.accumulationEnabled) {
            return;
        }
        if (!canAccumulatePrecipitation(level, pos)) {
            return;
        }
        if (level.getRandom().nextDouble() >= config.accumulationChance) {
            return;
        }
        accumulate(level, pos);
    }
}
