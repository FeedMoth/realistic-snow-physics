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
    public static boolean isForcedSnowPrecipitation(ServerLevel level, BlockPos pos) {
        if (!SeasonsHook.forceSnowPrecipitation()) return false;
        if (!level.isRaining() || !level.canSeeSky(pos)) return false;
        return level.getBiome(pos).value().hasPrecipitation();
    }
    public static boolean canAccumulatePrecipitation(ServerLevel level, BlockPos pos) {
        return isSnowing(level, pos) || isForcedSnowPrecipitation(level, pos);
    }
    private static boolean canHoldSnow(ServerLevel level, BlockPos below) {
        BlockState state = level.getBlockState(below);
        if (state.isAir()) return false;
        String id = blockId(state);
        if (id.equals("minecraft:water") || id.equals("minecraft:ice") || id.equals("minecraft:packed_ice") || id.equals("minecraft:blue_ice") || id.equals("minecraft:powder_snow")) return false;
        return state.isFaceSturdy(level, below, net.minecraft.core.Direction.UP);
    }
    public static boolean isValley(ServerLevel level, BlockPos pos) {
        int groundY = pos.getY();
        BlockPos cursor = pos;
        for (int i = 0; i < 4; i++) {
            BlockState below = level.getBlockState(cursor.below());
            if (isSnow(below)) { cursor = cursor.below(); continue; }
            groundY = cursor.below().getY(); break;
        }
        int higher = 0; int totalDiff = 0; int count = 0;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (dx == 0 && dz == 0) continue;
            int ny = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, pos.getX() + dx, pos.getZ() + dz);
            int diff = ny - groundY;
            if (diff >= 2) higher++;
            totalDiff += diff; count++;
        }
        return higher >= 8 || (count > 0 && totalDiff / count >= 2);
    }
    public static boolean accumulate(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isSnowLayer(state)) {
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            int max = RealisticSnowPhysicsConfig.get().maxLayers;
            if (layers >= max) {
                if (!isValley(level, pos)) return false;
                int snowBlocksBelow = 0; BlockPos cursor = pos.below();
                for (int i = 0; i < 3; i++) { if (isSnowBlock(level.getBlockState(cursor))) { snowBlocksBelow++; cursor = cursor.below(); } else break; }
                if (snowBlocksBelow >= 2) return false;
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
        if (isSnowBlock(level.getBlockState(pos.above()))) return false;
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
    public static void processArea(ServerLevel level, BlockPos centre, int radius, int batchSize) { processArea(level, centre, radius, batchSize, new java.util.HashSet<Long>()); }
    public static void processArea(ServerLevel level, BlockPos centre, int radius, int batchSize, java.util.Set<Long> processedColumns) {
        RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
        if (!config.enabled) return;
        double scale = tickScale(level);
        if (scale <= 0.0) return;
        // Collect snow and bare ground in a direct box, no heightmap trust.
        // Melt list is every snow in the box. Accumulation lists are top snow and bare ground per column.
        java.util.ArrayList<BlockPos> allSnow = new java.util.ArrayList<>();
        java.util.ArrayList<BlockPos> topSnow = new java.util.ArrayList<>();
        java.util.ArrayList<BlockPos> bare = new java.util.ArrayList<>();
        for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
            for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
                boolean topFound = false;
                for (int y = centre.getY() + 10; y >= centre.getY() - 10; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (isSnow(state)) {
                        allSnow.add(pos);
                        if (!topFound) { topSnow.add(pos); topFound = true; }
                        continue;
                    }
                    if (!topFound && !state.isAir()) {
                        // Top solid is ground, not snow. Bare is the air/plant above it.
                        BlockPos above = pos.above();
                        BlockState aboveState = level.getBlockState(above);
                        if ((aboveState.isAir() || PlantStore.isPlant(aboveState)) && canHoldSnow(level, pos)) {
                            bare.add(above);
                        }
                        topFound = true;
                    }
                }
            }
        }
        // Melt, nearest first, realistic chances, no batch cap so it is actually visible.
        // Heat melts fast, warm daylight melts steadily, cold sun melts slowly but surely.
        // Snow under a snow block is sheltered and does not melt.
        if (config.meltEnabled) {
            allSnow.sort((a, b) -> Double.compare(a.distSqr(centre), b.distSqr(centre)));
            for (BlockPos pos : allSnow) {
                if (isSnowBlock(level.getBlockState(pos.above()))) continue;
                boolean heated = hasHeatNearby(level, pos, config.heatRadius);
                boolean bright = level.isBrightOutside() && level.canSeeSky(pos);
                boolean warm = effectiveTemperature(level, pos) >= config.warmBiomeTemperature;
                double chance;
                if (heated) chance = config.heatMeltChance;
                else if (bright && warm) chance = config.warmMeltChance;
                else if (bright) chance = config.coldSunMeltChance;
                else chance = 0.0;
                chance = chance * SeasonsHook.meltMultiplier() * scale;
                if (chance > 0.0 && level.getRandom().nextDouble() < chance) melt(level, pos);
            }
        }
        // Plant restoration: snow gone, not snowing, stored plant comes back, unless player planted something there.
        for (BlockPos pos : bare) {
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
        PlantStore.saveIfDirty();
        if (!config.accumulationEnabled) return;
        // Accumulation, realistic and slow: blanket first, lowest first, then pile up.
        // Rain never snows. Crops are never replaced, snow goes around them.
        // Valleys can build past one block, flat ground caps at one block, then to a snow block in a valley.
        int accBatch = Math.max(1, (int) Math.round(batchSize * SeasonsHook.accumulationMultiplier() * scale));
        // Bare first
        java.util.ArrayList<BlockPos> accumBare = new java.util.ArrayList<>();
        for (BlockPos pos : bare) {
            if (canAccumulatePrecipitation(level, pos)) accumBare.add(pos);
        }
        accumBare.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
        if (!accumBare.isEmpty()) {
            int done = 0;
            for (BlockPos pos : accumBare) {
                if (done >= accBatch) break;
                BlockState s = level.getBlockState(pos);
                if (PlantStore.isCrop(s)) continue;
                if (accumulate(level, pos)) done++;
            }
            return;
        }
        // No bare ground left that can snow, so pile up existing snow, lowest first.
        topSnow.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
        int done = 0;
        for (BlockPos pos : topSnow) {
            if (done >= accBatch) break;
            if (!canAccumulatePrecipitation(level, pos)) continue;
            if (accumulate(level, pos)) done++;
        }
    }
    public static void process(ServerLevel level, BlockPos pos) {
        RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
        BlockState state = level.getBlockState(pos);
        if (config.meltEnabled && isSnow(state)) melt(level, pos);
    }
}
