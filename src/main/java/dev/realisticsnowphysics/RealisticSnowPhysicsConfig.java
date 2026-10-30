package dev.realisticsnowphysics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * config/realistic-snow-physics.json, created with defaults on first run.
 *
 * All tuning knobs live here. The core rules do not change with config:
 * snow builds up layer by layer during snowfall, and melts layer by
 * layer near heat or in warm daylight. Config only changes how fast
 * those two processes run, and how far heat reaches.
 */
public final class RealisticSnowPhysicsConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static RealisticSnowPhysicsConfig instance = new RealisticSnowPhysicsConfig();

    public boolean enabled = true;
    public boolean accumulationEnabled = true;
    public boolean meltEnabled = true;

    /** Ticks between snow processing passes. 20 ticks is one second. */
    public int processIntervalTicks = 20;

    /** Random positions sampled per player per pass. Keeps the cost bounded. */
    public int samplesPerPlayer = 4;

    /** Horizontal radius around a player in which snow is processed. */
    public int sampleRadius = 48;

    /** Maximum snow layers on one block. Vanilla snow layers go to 8. */
    public int maxLayers = 8;

    /** Chance, per sample, that a qualifying snowfall position gains a layer. */
    public double accumulationChance = 0.6;

    /** Chance, per sample, that a qualifying melt position loses a layer. */
    public double meltChance = 0.5;

    /** Snow columns melted per pass. Melt is area-wide, so this is larger than accumulation. */
    public int meltBatchSize = 64;

    /** Melt chance per column near heat, in warm daylight, and in cold sun, per pass. */
    public double heatMeltChance = 0.6;
    public double warmMeltChance = 0.4;
    public double coldSunMeltChance = 0.3;

    /** How far heat is searched for, in blocks, in each direction. */
    public int heatRadius = 4;

    /** How far weak heat, torches, lanterns, and glowstone, reaches. */
    public int torchHeatRadius = 2;

    /**
     * Biome temperature at or above which snow melts in daylight.
     * 0.15 is the vanilla freezing point, the snow/rain split, so
     * normal taiga at 0.25 melts in daylight and never snows.
     */
    public float warmBiomeTemperature = 0.15f;

    private RealisticSnowPhysicsConfig() {
    }

    public static RealisticSnowPhysicsConfig get() {
        return instance;
    }

    public static void save() {
        try {
            Path path = FabricLoader.getInstance().getConfigDir().resolve("realistic-snow-physics.json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(instance));
        } catch (IOException e) {
            RealisticSnowPhysicsMod.LOGGER.error("[Realistic Snow Physics] config could not be saved", e);
        }
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("realistic-snow-physics.json");
        try {
            if (Files.exists(path)) {
                RealisticSnowPhysicsConfig loaded = GSON.fromJson(Files.readString(path), RealisticSnowPhysicsConfig.class);
                if (loaded != null) {
                    instance = loaded;
                }
                RealisticSnowPhysicsMod.LOGGER.info("[Realistic Snow Physics] config loaded from {}", path);
            } else {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(instance));
                RealisticSnowPhysicsMod.LOGGER.info("[Realistic Snow Physics] config created with defaults at {}", path);
            }
        } catch (IOException e) {
            RealisticSnowPhysicsMod.LOGGER.error("[Realistic Snow Physics] config could not be read, using defaults", e);
        }
    }
}
