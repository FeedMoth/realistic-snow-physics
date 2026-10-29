package dev.realisticsnowphysics;

import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Seasons hook, ready for the Realistic Seasons system that will be
 * built next. Snow does not decide what season it is. It only asks
 * this class, so a seasons mod can take over without Snow changing.
 *
 * Resolution order, first match wins:
 * 1. An in-code provider, set by a seasons mod running in the same
 *    game through Seasons.setProvider(). This is the real hook.
 * 2. The season field in realistic-snow-physics.json, when it is
 *    not "auto". This is the manual override and test control.
 * 3. A file written by an external seasons system at
 *    config/realistic-seasons.json, with {"season": "winter"}.
 *    A seasons mod that cannot call into Snow yet can still drive
 *    it by writing that file, then Snow picks it up on reload.
 * 4. Otherwise UNKNOWN, which means vanilla rules: snow only where
 *    the biome itself is cold enough to snow.
 *
 * Winter rule, when the resolved season is WINTER: precipitation
 * in any biome that has precipitation at all can fall as snow.
 * Deserts, badlands, and other no-precipitation biomes are excluded
 * by the hasPrecipitation check in SnowProcessor, they stay dry.
 * Other seasons do not change snow rules yet, they are reserved
 * for the seasons system to use.
 */
public final class Seasons {
    public enum Season {
        UNKNOWN, SPRING, SUMMER, AUTUMN, WINTER;

        public static Season parse(String value) {
            if (value == null) {
                return UNKNOWN;
            }
            try {
                return Season.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }

    public interface SeasonProvider {
        Season currentSeason();
    }

    private static volatile SeasonProvider provider;
    private static volatile Season configSeason = Season.UNKNOWN;
    private static volatile Season fileSeason = Season.UNKNOWN;

    private Seasons() {
    }

    public static void setProvider(SeasonProvider newProvider) {
        provider = newProvider;
    }

    public static void setConfigSeason(Season season) {
        configSeason = season == null ? Season.UNKNOWN : season;
    }

    public static void refreshFromFile() {
        try {
            Path path = FabricLoader.getInstance().getConfigDir().resolve("realistic-seasons.json");
            if (!Files.exists(path)) {
                fileSeason = Season.UNKNOWN;
                return;
            }
            String text = Files.readString(path);
            int start = text.indexOf("\"season\"");
            if (start < 0) {
                fileSeason = Season.UNKNOWN;
                return;
            }
            int quote1 = text.indexOf('"', start + 8);
            int quote2 = quote1 < 0 ? -1 : text.indexOf('"', quote1 + 1);
            if (quote1 < 0 || quote2 < 0) {
                fileSeason = Season.UNKNOWN;
                return;
            }
            fileSeason = Season.parse(text.substring(quote1 + 1, quote2));
        } catch (Exception e) {
            fileSeason = Season.UNKNOWN;
        }
    }

    public static Season getSeason() {
        if (provider != null) {
            Season fromProvider = provider.currentSeason();
            if (fromProvider != null && fromProvider != Season.UNKNOWN) {
                return fromProvider;
            }
        }
        if (configSeason != Season.UNKNOWN) {
            return configSeason;
        }
        return fileSeason;
    }

    public static boolean isWinter() {
        return getSeason() == Season.WINTER;
    }

    public static double accumulationMultiplier() {
        switch (getSeason()) {
            case WINTER: return 1.25;
            case SUMMER: return 0.25;
            case AUTUMN: return 1.1;
            default: return 1.0;
        }
    }

    public static double meltMultiplier() {
        switch (getSeason()) {
            case WINTER: return 0.7;
            case SUMMER: return 1.5;
            case AUTUMN: return 0.9;
            default: return 1.0;
        }
    }

    public static float temperatureOffset() {
        switch (getSeason()) {
            case WINTER: return -0.5f;
            case SUMMER: return 0.3f;
            case AUTUMN: return -0.2f;
            default: return 0.0f;
        }
    }
}
