package dev.realisticsnowphysics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;

/**
 * Remembers plants buried under snow, so they can come back when the
 * snow melts. A plant is only stored when Snow itself replaces it.
 * Plants the player breaks, or snow the player places by hand, are
 * not stored, so this does not resurrect things the player removed
 * on purpose.
 *
 * Stored in config/realistic-snow-physics-plants.json, so a plant
 * survives a server restart while it is still buried. The key is
 * dimension plus block position, the value is the plant's block id,
 * plus its half for two-block plants like tall grass and large fern.
 */
public final class PlantStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, StoredPlant> PLANTS = new HashMap<>();
    private static boolean loaded = false;
    private static boolean dirty = false;

    public static final class StoredPlant {
        public String dimension;
        public int x;
        public int y;
        public int z;
        public String block;
        public String half;
    }

    private static final class FileData {
        public List<StoredPlant> plants = new ArrayList<>();
    }

    private PlantStore() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path path = FabricLoader.getInstance().getConfigDir().resolve("realistic-snow-physics-plants.json");
            if (Files.exists(path)) {
                FileData data = GSON.fromJson(Files.readString(path), FileData.class);
                if (data != null && data.plants != null) {
                    for (StoredPlant plant : data.plants) {
                        PLANTS.put(key(plant.dimension, plant.x, plant.y, plant.z), plant);
                    }
                }
            }
        } catch (Exception e) {
            RealisticSnowPhysicsMod.LOGGER.error("[Realistic Snow Physics] plant store could not be read", e);
        }
    }

    public static synchronized void saveIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        try {
            Path path = FabricLoader.getInstance().getConfigDir().resolve("realistic-snow-physics-plants.json");
            Files.createDirectories(path.getParent());
            FileData data = new FileData();
            data.plants.addAll(PLANTS.values());
            Files.writeString(path, GSON.toJson(data));
        } catch (IOException e) {
            RealisticSnowPhysicsMod.LOGGER.error("[Realistic Snow Physics] plant store could not be saved", e);
        }
    }

    private static String key(String dimension, int x, int y, int z) {
        return dimension + "|" + x + "|" + y + "|" + z;
    }

    private static String dimensionOf(World level) {
        ResourceKey<Level> key = level.getRegistryKey();
        return key == null ? "minecraft:overworld" : key.getValue().toString();
    }

    public static String blockId(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).toString();
    }

    public static boolean isCrop(BlockState state) {
        String id = blockId(state);
        return id.equals("minecraft:wheat") || id.equals("minecraft:carrots")
                || id.equals("minecraft:potatoes") || id.equals("minecraft:beetroots")
                || id.equals("minecraft:melon_stem") || id.equals("minecraft:pumpkin_stem")
                || id.equals("minecraft:attached_melon_stem") || id.equals("minecraft:attached_pumpkin_stem")
                || id.equals("minecraft:nether_wart") || id.equals("minecraft:cocoa")
                || id.equals("minecraft:sugar_cane") || id.equals("minecraft:bamboo")
                || id.equals("minecraft:chorus_plant") || id.equals("minecraft:chorus_flower")
                || id.equals("minecraft:kelp") || id.equals("minecraft:seagrass")
                || id.equals("minecraft:tall_seagrass");
    }

    public static boolean isPlant(BlockState state) {
        if (isCrop(state)) {
            return false;
        }
        String id = blockId(state);
        if (id.equals("minecraft:grass_block") || id.equals("minecraft:dirt")) {
            return false;
        }
        if (id.equals("minecraft:short_grass") || id.equals("minecraft:grass")
                || id.equals("minecraft:tall_grass") || id.equals("minecraft:fern")
                || id.equals("minecraft:large_fern") || id.equals("minecraft:dead_bush")
                || id.equals("minecraft:red_mushroom") || id.equals("minecraft:brown_mushroom")
                || id.equals("minecraft:crimson_fungus") || id.equals("minecraft:warped_fungus")
                || id.equals("minecraft:crimson_roots") || id.equals("minecraft:warped_roots")
                || id.equals("minecraft:nether_sprouts")) {
            return true;
        }
        if (id.endsWith("_flower") || id.endsWith("_tulip") || id.equals("minecraft:poppy")
                || id.equals("minecraft:dandelion") || id.equals("minecraft:blue_orchid")
                || id.equals("minecraft:allium") || id.equals("minecraft:azure_bluet")
                || id.equals("minecraft:oxeye_daisy") || id.equals("minecraft:cornflower")
                || id.equals("minecraft:lily_of_the_valley") || id.equals("minecraft:wither_rose")
                || id.equals("minecraft:sunflower") || id.equals("minecraft:lilac")
                || id.equals("minecraft:rose_bush") || id.equals("minecraft:peony")) {
            return true;
        }
        return false;
    }

    public static boolean isTallPlant(BlockState state) {
        String id = blockId(state);
        return id.equals("minecraft:tall_grass") || id.equals("minecraft:large_fern")
                || id.equals("minecraft:sunflower") || id.equals("minecraft:lilac")
                || id.equals("minecraft:rose_bush") || id.equals("minecraft:peony");
    }

    public static synchronized void store(World level, BlockPos pos, BlockState state) {
        load();
        StoredPlant plant = new StoredPlant();
        plant.dimension = dimensionOf(level);
        plant.x = pos.getX();
        plant.y = pos.getY();
        plant.z = pos.getZ();
        plant.block = blockId(state);
        plant.half = null;
        if (isTallPlant(state)) {
            plant.half = "lower";
        }
        PLANTS.put(key(plant.dimension, plant.x, plant.y, plant.z), plant);
        dirty = true;
    }

    public static synchronized StoredPlant take(World level, BlockPos pos) {
        load();
        StoredPlant plant = PLANTS.remove(key(dimensionOf(level), pos.getX(), pos.getY(), pos.getZ()));
        if (plant != null) {
            dirty = true;
        }
        return plant;
    }

    public static synchronized StoredPlant peek(World level, BlockPos pos) {
        load();
        return PLANTS.get(key(dimensionOf(level), pos.getX(), pos.getY(), pos.getZ()));
    }

    public static BlockState restoreState(StoredPlant plant) {
        Block block = Registries.BLOCK.get(net.minecraft.util.Identifier.of(plant.block));
        if (block == null || block == Blocks.AIR) {
            return null;
        }
        BlockState state = block.defaultBlockState();
        if (isTallPlant(state) && state.contains(net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF)) {
            state = state.with(net.minecraft.state.property.Properties.DOUBLE_BLOCK_HALF, net.minecraft.block.enums.DoubleBlockHalf.LOWER);
        }
        return state;
    }
}
