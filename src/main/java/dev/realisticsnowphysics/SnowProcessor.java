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
    private static int passCounter = 0;
    public static void processArea(ServerLevel level, BlockPos centre, int radius, int batchSize) { processArea(level, centre, radius, batchSize, new java.util.HashSet<Long>()); }
    public static void processArea(ServerLevel level, BlockPos centre, int radius, int batchSize, java.util.Set<Long> processedColumns) {
        RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
        if (!config.enabled) return;
        double scale = tickScale(level);
        if (scale <= 0.0) return;
        passCounter++;
        // Wide area, not tiny chunks: use a larger radius for snow, so nearby biomes are covered too.
        int wideRadius = Math.max(radius, 96);
        // Collect every column in the wide area, grouped by ground Y, lowest first.
        // Ground Y is the Y of the top solid block in that column, found by direct scan down from centre+10 and world surface.
        java.util.TreeMap<Integer, java.util.ArrayList<BlockPos>> byGroundY = new java.util.TreeMap<>();
        java.util.ArrayList<BlockPos> allSnow = new java.util.ArrayList<>();
        java.util.ArrayList<BlockPos> bareByY = new java.util.ArrayList<>();
        for (int x = centre.getX() - wideRadius; x <= centre.getX() + wideRadius; x++) {
            for (int z = centre.getZ() - wideRadius; z <= centre.getZ() + wideRadius; z++) {
                int topY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                // Scan down from top to find ground and snow in this column, within a reasonable band around the player and terrain.
                // Use topY as terrain height, not player Y, so hills and valleys are both covered.
                for (int y = topY + 3; y >= topY - 40 && y >= level.getMinY(); y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (isSnow(state)) { allSnow.add(pos); continue; }
                    if (!state.isAir()) {
                        // This is ground, unless it is above snow already counted. Record its Y group.
                        BlockPos above = pos.above();
                        BlockState aboveState = level.getBlockState(above);
                        if ((aboveState.isAir() || PlantStore.isPlant(aboveState)) && canHoldSnow(level, pos)) {
                            byGroundY.computeIfAbsent(pos.getY(), k -> new java.util.ArrayList<>()).add(above);
                        }
                        break;
                    }
                }
            }
        }
        // Melt, remade: daytime, not snowing, every pass, one step for every snow in the area.
        // Heated snow melts every pass, day or night. Night snow and snowing snow do not melt.
        if (config.meltEnabled) {
            boolean day = level.isDay();
            for (BlockPos pos : allSnow) {
                if (isSnowBlock(level.getBlockState(pos.above()))) continue;
                if (canAccumulatePrecipitation(level, pos)) continue;
                boolean heated = hasHeatNearby(level, pos, config.heatRadius);
                if (heated) { melt(level, pos); continue; }
                if (!day) continue;
                melt(level, pos);
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
        // One accumulation pass every 20 seconds: every bare ground column in the wide area gets 1 layer at the same time,
        // then next pass every existing snow column gets 1 more layer, also all at once.
        // This is not 16 random samples, and it does not pile one column up multiple times while others stay bare.
        // When a column gains a layer, columns below it in Y are in earlier Y groups and were already given that layer in the same pass,
        // because groups are processed lowest first and all groups are processed in one pass.
        if (passCounter % 20 != 0) return;
        // First, check if there is any bare ground that can snow. If yes, blanket all of it, one layer each, lowest Y first.
        java.util.ArrayList<BlockPos> accumBare = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, java.util.ArrayList<BlockPos>> e : byGroundY.entrySet()) {
            for (BlockPos pos : e.getValue()) {
                if (canAccumulatePrecipitation(level, pos)) accumBare.add(pos);
            }
        }
        if (!accumBare.isEmpty()) {
            for (BlockPos pos : accumBare) {
                BlockState s = level.getBlockState(pos);
                if (PlantStore.isCrop(s)) continue;
                // Do not replace grass blocks themselves, snow sits on top of grass, it does not turn grass into snow.
                // Bare here is the air above grass, so accumulate() will place snow there, grass below stays grass.
                accumulate(level, pos);
            }
            return;
        }
        // No bare ground left, so pile up: every top snow column gets 1 more layer, all at once, lowest Y first.
        // Group top snow by ground Y: ground Y is snow Y minus snow height, approximate by snow Y itself for ordering.
        allSnow.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
        for (BlockPos pos : allSnow) {
            if (!canAccumulatePrecipitation(level, pos)) continue;
            // Only pile on the top snow in a column: if there is snow directly above, skip, that upper snow will pile instead.
            if (isSnow(level.getBlockState(pos.above()))) continue;
            accumulate(level, pos);
        }
    }
    public static void process(ServerLevel level, BlockPos pos) {
        RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
        BlockState state = level.getBlockState(pos);
        if (config.meltEnabled && isSnow(state)) melt(level, pos);
    }
}
