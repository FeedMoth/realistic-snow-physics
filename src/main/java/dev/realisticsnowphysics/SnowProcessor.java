package dev.realisticsnowphysics;

import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * The snow rules, kept separate from tick scheduling so they can be
 * read and tested on their own.
 *
 * This works on pre-generated chunks, not just fresh ones. There is
 * no world generation in this mod at all: accumulation and melt
 * only look at blocks already in the world, at the surface, when a
 * player is nearby. A player who installs the mod on an existing
 * world gets the same behaviour on their existing terrain as a
 * player starting a new world, because the rules run on loaded
 * chunks, not on chunks as they generate.
 *
 * Accumulation only happens where snow is actually falling: the
 * world is raining, the biome has precipitation, the position has
 * sky above it, and the ground below can hold snow. A snow layer
 * gains one layer at a time, up to the configured maximum.
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
 * Melt happens near heat, or in warm daylight, also one layer at
 * a time. A snow block first becomes a seven-layer pile, then
 * melts down from there.
 */
public final class SnowProcessor {
    private SnowProcessor() {
    }

    public static boolean isSnowLayer(BlockState state) {
        return state.isOf(Blocks.SNOW);
    }

    public static boolean isSnowBlock(BlockState state) {
        return state.isOf(Blocks.SNOW_BLOCK);
    }

    private static boolean isHeat(BlockState state) {
        if (state.isOf(Blocks.LAVA) || state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)) {
            return true;
        }
        if (state.isOf(Blocks.MAGMA_BLOCK)) {
            return true;
        }
        if (state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE)) {
            return state.get(CampfireBlock.LIT);
        }
        if (state.isOf(Blocks.FURNACE) || state.isOf(Blocks.BLAST_FURNACE) || state.isOf(Blocks.SMOKER)) {
            return state.get(Properties.LIT);
        }
        return false;
    }

    public static boolean hasHeatNearby(ServerWorld world, BlockPos pos, int radius) {
        for (BlockPos candidate : BlockPos.iterate(
                pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius,
                pos.getX() + radius, pos.getY() + radius, pos.getZ() + radius)) {
            if (isHeat(world.getBlockState(candidate))) {
                return true;
            }
        }
        return false;
    }

    private static boolean canHoldSnow(ServerWorld world, BlockPos below) {
        BlockState state = world.getBlockState(below);
        if (state.isAir()) {
            return false;
        }
        if (state.isOf(Blocks.WATER) || state.isOf(Blocks.ICE) || state.isOf(Blocks.PACKED_ICE)
                || state.isOf(Blocks.BLUE_ICE) || state.isOf(Blocks.POWDER_SNOW)) {
            return false;
        }
        return state.isSideSolidFullSquare(world, below, Direction.UP);
    }

    public static boolean accumulate(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (isSnowLayer(state)) {
            int layers = state.get(SnowBlock.LAYERS);
            int max = RealisticSnowPhysicsConfig.get().maxLayers;
            if (layers >= max) {
                return false;
            }
            world.setBlockState(pos, state.with(SnowBlock.LAYERS, layers + 1), 3);
            return true;
        }
        if (!state.isAir()) {
            return false;
        }
        BlockPos below = pos.down();
        if (!canHoldSnow(world, below)) {
            return false;
        }
        world.setBlockState(pos, Blocks.SNOW.getDefaultState().with(SnowBlock.LAYERS, 1), 3);
        return true;
    }

    public static boolean melt(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (isSnowLayer(state)) {
            int layers = state.get(SnowBlock.LAYERS);
            if (layers <= 1) {
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
            } else {
                world.setBlockState(pos, state.with(SnowBlock.LAYERS, layers - 1), 3);
            }
            return true;
        }
        if (isSnowBlock(state)) {
            int max = RealisticSnowPhysicsConfig.get().maxLayers;
            world.setBlockState(pos, Blocks.SNOW.getDefaultState()
                    .with(SnowBlock.LAYERS, Math.max(1, max - 1)), 3);
            return true;
        }
        return false;
    }

    public static void processArea(ServerWorld world, BlockPos centre, int radius, int batchSize) {
        RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
        java.util.ArrayList<BlockPos> snowPositions = new java.util.ArrayList<>();
        java.util.ArrayList<BlockPos> barePositions = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> snowHeights = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> bareHeights = new java.util.ArrayList<>();
        for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
            for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
                int topY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z);
                BlockPos surface = new BlockPos(x, topY, z);
                BlockPos snowPos = null;
                if (isSnowLayer(world.getBlockState(surface)) || isSnowBlock(world.getBlockState(surface))) {
                    snowPos = surface;
                } else if (isSnowLayer(world.getBlockState(surface.up()))
                        || isSnowBlock(world.getBlockState(surface.up()))) {
                    snowPos = surface.up();
                }
                if (snowPos != null) {
                    snowPositions.add(snowPos);
                    snowHeights.add(snowPos.getY());
                    continue;
                }
                BlockPos bare = surface.up();
                if (world.getBlockState(bare).isAir() && canHoldSnow(world, surface)) {
                    barePositions.add(bare);
                    bareHeights.add(bare.getY());
                }
            }
        }
        sortByHeight(snowPositions, snowHeights);
        sortByHeight(barePositions, bareHeights);
        if (config.meltEnabled) {
            int melted = 0;
            for (BlockPos pos : snowPositions) {
                if (melted >= Math.max(1, batchSize)) {
                    break;
                }
                BlockState state = world.getBlockState(pos);
                boolean warmDaylight = world.isDay()
                        && world.getBiome(pos).value().getTemperature() >= config.warmBiomeTemperature
                        && world.isSkyVisible(pos);
                boolean heated = hasHeatNearby(world, pos, config.heatRadius);
                if ((warmDaylight || heated) && world.getRandom().nextDouble() < config.meltChance) {
                    if (melt(world, pos)) {
                        melted++;
                    }
                }
            }
        }
        if (!config.accumulationEnabled) {
            return;
        }
        java.util.ArrayList<BlockPos> accumulableBare = new java.util.ArrayList<>();
        for (BlockPos pos : barePositions) {
            if (world.isRaining() && world.getBiome(pos).value().hasPrecipitation()
                    && world.getBiome(pos).value().getTemperature() < config.warmBiomeTemperature
                    && world.isSkyVisible(pos)) {
                accumulableBare.add(pos);
            }
        }
        if (!accumulableBare.isEmpty()) {
            int done = 0;
            for (BlockPos pos : accumulableBare) {
                if (done >= Math.max(1, batchSize)) {
                    break;
                }
                if (accumulate(world, pos)) {
                    done++;
                }
            }
            return;
        }
        int done = 0;
        for (BlockPos pos : snowPositions) {
            if (done >= Math.max(1, batchSize)) {
                break;
            }
            if (!isSnowLayer(world.getBlockState(pos))) {
                continue;
            }
            if (world.isRaining() && world.getBiome(pos).value().hasPrecipitation()
                    && world.getBiome(pos).value().getTemperature() < config.warmBiomeTemperature
                    && world.isSkyVisible(pos)) {
                if (accumulate(world, pos)) {
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

    public static void process(ServerWorld world, BlockPos pos) {
        RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
        BlockState state = world.getBlockState(pos);

        if (config.meltEnabled && (isSnowLayer(state) || isSnowBlock(state))) {
            boolean warmDaylight = world.isDay()
                    && world.getBiome(pos).value().getTemperature() >= config.warmBiomeTemperature
                    && world.isSkyVisible(pos);
            boolean heated = hasHeatNearby(world, pos, config.heatRadius);
            if ((warmDaylight || heated) && world.getRandom().nextDouble() < config.meltChance) {
                melt(world, pos);
                return;
            }
        }

        if (!config.accumulationEnabled) {
            return;
        }
        if (!world.isRaining() || !world.getBiome(pos).value().hasPrecipitation()) {
            return;
        }
        if (world.getBiome(pos).value().getTemperature() >= config.warmBiomeTemperature) {
            return;
        }
        if (!world.isSkyVisible(pos)) {
            return;
        }
        if (world.getRandom().nextDouble() >= config.accumulationChance) {
            return;
        }
        accumulate(world, pos);
    }
}
