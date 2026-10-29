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
 * Accumulation only happens where snow is actually falling: the level
 * is precipitating at that position, the position has sky above it,
 * and the ground below can hold snow. A snow layer gains one layer
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

    private static boolean isHeat(BlockState state) {
        if (state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) {
            return true;
        }
        if (state.is(Blocks.MAGMA_BLOCK)) {
            return true;
        }
        if (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) {
            return state.hasProperty(CampfireBlock.LIT) && state.getValue(CampfireBlock.LIT);
        }
        if (state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER)) {
            return state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT);
        }
        return false;
    }

    public static boolean hasHeatNearby(ServerLevel level, BlockPos pos, int radius) {
        for (BlockPos candidate : BlockPos.betweenClosed(
                pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius,
                pos.getX() + radius, pos.getY() + radius, pos.getZ() + radius)) {
            if (isHeat(level.getBlockState(candidate))) {
                return true;
            }
        }
        return false;
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
    public static boolean accumulate(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isSnowLayer(state)) {
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            int max = RealisticSnowPhysicsConfig.get().maxLayers;
            if (layers >= max) {
                return false;
            }
            level.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, layers + 1), 3);
            return true;
        }
        if (!state.isAir()) {
            return false;
        }
        BlockPos below = pos.below();
        if (!canHoldSnow(level, below)) {
            return false;
        }
        // Do not bury plants, torches, or other small blocks: snow
        // should land on ground, not replace what is already there.
        // Air above a solid block is the only accumulation case.
        level.setBlock(pos, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1), 3);
        return true;
    }

    /**
     * Try to remove one layer of snow at {@code pos}. Returns true
     * when the world changed.
     */
    public static boolean melt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isSnowLayer(state)) {
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            if (layers <= 1) {
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
        RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
        java.util.ArrayList<BlockPos> snowPositions = new java.util.ArrayList<>();
        java.util.ArrayList<BlockPos> barePositions = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> snowHeights = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> bareHeights = new java.util.ArrayList<>();
        for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
            for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
                BlockPos surface = level.getHeightmapPos(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
                BlockPos snowPos = null;
                if (isSnowLayer(level.getBlockState(surface)) || isSnowBlock(level.getBlockState(surface))) {
                    snowPos = surface;
                } else if (isSnowLayer(level.getBlockState(surface.above()))
                        || isSnowBlock(level.getBlockState(surface.above()))) {
                    snowPos = surface.above();
                }
                if (snowPos != null) {
                    snowPositions.add(snowPos);
                    snowHeights.add(snowPos.getY());
                    continue;
                }
                BlockPos bare = surface.above();
                if (level.getBlockState(bare).isAir() && canHoldSnow(level, surface)) {
                    barePositions.add(bare);
                    bareHeights.add(bare.getY());
                }
            }
        }
        // Sort both lists by height, lowest first. Sorting by index
        // in parallel lists keeps this simple without a new record type.
        sortByHeight(snowPositions, snowHeights);
        sortByHeight(barePositions, bareHeights);
        if (config.meltEnabled) {
            int melted = 0;
            for (BlockPos pos : snowPositions) {
                if (melted >= Math.max(1, batchSize)) {
                    break;
                }
                BlockState state = level.getBlockState(pos);
                boolean warmDaylight = level.isBrightOutside()
                        && level.getBiome(pos).value().getBaseTemperature() >= config.warmBiomeTemperature
                        && level.canSeeSky(pos);
                boolean heated = hasHeatNearby(level, pos, config.heatRadius);
                if ((warmDaylight || heated) && level.getRandom().nextDouble() < config.meltChance) {
                    if (melt(level, pos)) {
                        melted++;
                    }
                }
            }
        }
        if (!config.accumulationEnabled) {
            return;
        }
        // Bare ground wins: blanket first, no piling until covered.
        java.util.ArrayList<BlockPos> accumulableBare = new java.util.ArrayList<>();
        for (BlockPos pos : barePositions) {
            if (level.isRainingAt(pos)
                    && level.getBiome(pos).value().getBaseTemperature() < config.warmBiomeTemperature
                    && level.canSeeSky(pos)) {
                accumulableBare.add(pos);
            }
        }
        if (!accumulableBare.isEmpty()) {
            int done = 0;
            for (BlockPos pos : accumulableBare) {
                if (done >= Math.max(1, batchSize)) {
                    break;
                }
                if (accumulate(level, pos)) {
                    done++;
                }
            }
            return;
        }
        // Nothing bare left that can take snow, so pile up, lowest first.
        int done = 0;
        for (BlockPos pos : snowPositions) {
            if (done >= Math.max(1, batchSize)) {
                break;
            }
            if (!isSnowLayer(level.getBlockState(pos))) {
                continue;
            }
            if (level.isRainingAt(pos)
                    && level.getBiome(pos).value().getBaseTemperature() < config.warmBiomeTemperature
                    && level.canSeeSky(pos)) {
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
            boolean warmDaylight = level.isBrightOutside()
                    && level.getBiome(pos).value().getBaseTemperature() >= config.warmBiomeTemperature
                    && level.canSeeSky(pos);
            boolean heated = hasHeatNearby(level, pos, config.heatRadius);
            if ((warmDaylight || heated) && level.getRandom().nextDouble() < config.meltChance) {
                melt(level, pos);
                return;
            }
        }

        if (!config.accumulationEnabled) {
            return;
        }
        if (!level.isRainingAt(pos)) {
            return;
        }
        if (level.getBiome(pos).value().getBaseTemperature() >= config.warmBiomeTemperature) {
            return;
        }
        if (!level.canSeeSky(pos)) {
            return;
        }
        if (level.getRandom().nextDouble() >= config.accumulationChance) {
            return;
        }
        accumulate(level, pos);
    }
}
