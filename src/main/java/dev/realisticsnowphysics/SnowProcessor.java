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

    private static int heatRadiusFor(BlockState state, int strongRadius, int weakRadius) {
        if (state.isOf(Blocks.LAVA) || state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)) {
            return strongRadius;
        }
        if (state.isOf(Blocks.MAGMA_BLOCK)) {
            return strongRadius;
        }
        if (state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE)) {
            return state.get(CampfireBlock.LIT) ? strongRadius : 0;
        }
        if (state.isOf(Blocks.FURNACE) || state.isOf(Blocks.BLAST_FURNACE) || state.isOf(Blocks.SMOKER)) {
            return state.get(Properties.LIT) ? strongRadius : 0;
        }
        if (state.isOf(Blocks.TORCH) || state.isOf(Blocks.WALL_TORCH) || state.isOf(Blocks.LANTERN)
                || state.isOf(Blocks.GLOWSTONE) || state.isOf(Blocks.JACK_O_LANTERN)) {
            return weakRadius;
        }
        return 0;
    }

    public static boolean hasHeatNearby(ServerWorld world, BlockPos pos, int radius) {
        RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
        int weakRadius = Math.max(1, config.torchHeatRadius);
        for (BlockPos candidate : BlockPos.iterate(
                pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius,
                pos.getX() + radius, pos.getY() + radius, pos.getZ() + radius)) {
            int reach = heatRadiusFor(world.getBlockState(candidate), radius, weakRadius);
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

    public static float effectiveTemperature(ServerWorld world, BlockPos pos) {
        return world.getBiome(pos).value().getTemperature() + SeasonsHook.temperatureOffset();
    }

    public static double tickScale(ServerWorld world) {
        try {
            int speed = world.getGameRules().getInt(net.minecraft.world.GameRules.RANDOM_TICK_SPEED);
            return speed <= 0 ? 0.0 : speed / 3.0;
        } catch (Exception e) {
            return 1.0;
        }
    }

    public static boolean isSnowing(ServerWorld world, BlockPos pos) {
        return world.isRaining()
                && world.getBiome(pos).value().hasPrecipitation()
                && effectiveTemperature(world, pos) < 0.15f
                && world.isSkyVisible(pos);
    }

    public static boolean isForcedSnowPrecipitation(ServerWorld world, BlockPos pos) {
        if (!SeasonsHook.forceSnowPrecipitation()) {
            return false;
        }
        return world.isRaining()
                && world.getBiome(pos).value().hasPrecipitation()
                && world.isSkyVisible(pos);
    }

    public static boolean canAccumulatePrecipitation(ServerWorld world, BlockPos pos) {
        return isSnowing(world, pos) || isForcedSnowPrecipitation(world, pos);
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
                if (!isValley(world, pos)) {
                    return false;
                }
                int snowBlocksBelow = 0;
                BlockPos cursor = pos.down();
                for (int i = 0; i < 3; i++) {
                    if (isSnowBlock(world.getBlockState(cursor))) {
                        snowBlocksBelow++;
                        cursor = cursor.down();
                    } else {
                        break;
                    }
                }
                if (snowBlocksBelow >= 2) {
                    return false;
                }
                world.setBlockState(pos, Blocks.SNOW_BLOCK.getDefaultState(), 3);
                return true;
            }
            world.setBlockState(pos, state.with(SnowBlock.LAYERS, layers + 1), 3);
            return true;
        }
        if (isSnowBlock(state)) {
            BlockPos above = pos.up();
            if (!world.getBlockState(above).isAir()) {
                return false;
            }
            return accumulate(world, above);
        }
        if (!state.isAir() && !PlantStore.isPlant(state)) {
            return false;
        }
        BlockPos below = pos.down();
        if (!canHoldSnow(world, below)) {
            return false;
        }
        BlockState current = world.getBlockState(pos);
        if (PlantStore.isPlant(current)) {
            PlantStore.store(world, pos, current);
            world.setBlockState(pos.up(), Blocks.AIR.getDefaultState(), 3);
        } else if (!current.isAir()) {
            return false;
        }
        world.setBlockState(pos, Blocks.SNOW.getDefaultState().with(SnowBlock.LAYERS, 1), 3);
        return true;
    }

    public static boolean isValley(ServerWorld world, BlockPos pos) {
        int groundY = pos.getY();
        BlockPos cursor = pos;
        for (int i = 0; i < 4; i++) {
            BlockState below = world.getBlockState(cursor.down());
            if (isSnowLayer(below) || isSnowBlock(below)) {
                cursor = cursor.down();
                continue;
            }
            groundY = cursor.down().getY();
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
                int neighbourY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, pos.getX() + dx, pos.getZ() + dz);
                int diff = neighbourY - groundY;
                if (diff >= 2) {
                    higher++;
                }
                totalDiff += diff;
                count++;
            }
        }
        return higher >= 8 || (count > 0 && totalDiff / count >= 2);
    }

    public static boolean melt(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (isSnowLayer(state)) {
            int layers = state.get(SnowBlock.LAYERS);
            if (layers <= 1) {
                PlantStore.StoredPlant plant = PlantStore.take(world, pos);
                if (plant != null) {
                    BlockState restored = PlantStore.restoreState(plant);
                    if (restored != null) {
                        world.setBlockState(pos, restored, 3);
                        if (PlantStore.isTallPlant(restored) && world.getBlockState(pos.up()).isAir()) {
                            world.setBlockState(pos.up(), restored.with(net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER), 3);
                        }
                        PlantStore.saveIfDirty();
                        return true;
                    }
                }
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
        processArea(world, centre, radius, batchSize, new java.util.HashSet<Long>());
    }

    public static void processArea(ServerWorld world, BlockPos centre, int radius, int batchSize, java.util.Set<Long> processedColumns) {
        RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
        double scale = tickScale(world);
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
                BlockState bareState = world.getBlockState(bare);
                if ((bareState.isAir() || PlantStore.isPlant(bareState)) && canHoldSnow(world, surface)) {
                    barePositions.add(bare);
                    bareHeights.add(bare.getY());
                }
            }
        }
        sortByHeight(snowPositions, snowHeights);
        sortByHeight(barePositions, bareHeights);
        snowPositions.sort((a, b) -> Boolean.compare(!isSnowBlock(world.getBlockState(a)), !isSnowBlock(world.getBlockState(b))));
        if (config.meltEnabled) {
            int melted = 0;
            int meltBatch = meltBatchScaled;
            for (BlockPos pos : snowPositions) {
                if (melted >= meltBatch) {
                    break;
                }
                processedColumns.add(((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL));
                BlockState state = world.getBlockState(pos);
                boolean bright = world.isDay() && world.isSkyVisible(pos);
                boolean warm = effectiveTemperature(world, pos) >= config.warmBiomeTemperature;
                boolean heated = hasHeatNearby(world, pos, config.heatRadius);
                double chance = 0.0;
                if (heated) {
                    chance = config.heatMeltChance;
                } else if (bright && warm) {
                    chance = config.warmMeltChance;
                } else if (bright) {
                    chance = config.coldSunMeltChance;
                }
                if (chance > 0.0 && world.getRandom().nextDouble() < chance) {
                    if (melt(world, pos)) {
                        melted++;
                    }
                }
            }
        }
        for (BlockPos pos : barePositions) {
            if (canAccumulatePrecipitation(world, pos)) {
                continue;
            }
            if (!world.getBlockState(pos).isAir()) {
                continue;
            }
            PlantStore.StoredPlant plant = PlantStore.take(world, pos);
            if (plant != null) {
                BlockState restored = PlantStore.restoreState(plant);
                if (restored != null) {
                    world.setBlockState(pos, restored, 3);
                    if (PlantStore.isTallPlant(restored) && world.getBlockState(pos.up()).isAir()) {
                        world.setBlockState(pos.up(), restored.with(net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER), 3);
                    }
                }
            }
        }
        PlantStore.saveIfDirty();
        if (!config.accumulationEnabled) {
            return;
        }
        java.util.ArrayList<BlockPos> accumulableBare = new java.util.ArrayList<>();
        for (BlockPos pos : barePositions) {
            if (canAccumulatePrecipitation(world, pos)) {
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
                if (accumulate(world, pos)) {
                    done++;
                }
            }
            return;
        }
        int done = 0;
        for (BlockPos pos : snowPositions) {
            if (done >= Math.max(1, accumulationBatch)) {
                break;
            }
            processedColumns.add(((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL));
            if (isSnowBlock(world.getBlockState(pos))) {
                if (canAccumulatePrecipitation(world, pos)) {
                    if (accumulate(world, pos)) {
                        done++;
                    }
                }
                continue;
            }
            if (!isSnowLayer(world.getBlockState(pos))) {
                continue;
            }
            if (canAccumulatePrecipitation(world, pos)) {
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
            boolean bright = world.isDay() && world.isSkyVisible(pos);
            boolean warm = effectiveTemperature(world, pos) >= config.warmBiomeTemperature;
            boolean heated = hasHeatNearby(world, pos, config.heatRadius);
            double chance = heated ? config.heatMeltChance : (bright && warm ? config.warmMeltChance : (bright ? config.coldSunMeltChance : 0.0));
            if (chance > 0.0 && world.getRandom().nextDouble() < chance) {
                melt(world, pos);
                return;
            }
        }

        for (BlockPos pos : barePositions) {
            if (canAccumulatePrecipitation(world, pos)) {
                continue;
            }
            if (!world.getBlockState(pos).isAir()) {
                continue;
            }
            PlantStore.StoredPlant plant = PlantStore.take(world, pos);
            if (plant != null) {
                BlockState restored = PlantStore.restoreState(plant);
                if (restored != null) {
                    world.setBlockState(pos, restored, 3);
                    if (PlantStore.isTallPlant(restored) && world.getBlockState(pos.up()).isAir()) {
                        world.setBlockState(pos.up(), restored.with(net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER), 3);
                    }
                }
            }
        }
        PlantStore.saveIfDirty();
        if (!config.accumulationEnabled) {
            return;
        }
        if (!canAccumulatePrecipitation(world, pos)) {
            return;
        }
        if (world.getRandom().nextDouble() >= config.accumulationChance) {
            return;
        }
        accumulate(world, pos);
    }
}
