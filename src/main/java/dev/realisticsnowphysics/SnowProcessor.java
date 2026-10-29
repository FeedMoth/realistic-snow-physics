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

    /** Decide, for one sampled position, whether it should accumulate or melt. */
    public static void process(ServerLevel level, BlockPos pos) {
        RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
        BlockState state = level.getBlockState(pos);

        if (config.meltEnabled && (isSnowLayer(state) || isSnowBlock(state))) {
            boolean warmDaylight = level.isDay()
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
