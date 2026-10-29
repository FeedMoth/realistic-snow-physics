package dev.realisticsnowphysics;

/**
 * Compatibility hook for a future, separate Seasons mod. This is not
 * a seasons system. Snow on its own has no seasons, no winter, no
 * summer, and no seasonal rates. It only asks this hook, and if no
 * seasons mod is running, every answer is neutral and Snow follows
 * vanilla precipitation rules exactly.
 *
 * A seasons mod, when it is built, registers a provider here, for
 * example through SeasonsHook.setProvider(...), and Snow will then
 * use the values that mod supplies, without Snow knowing what a
 * season is, what season it is, or how seasons are decided. That
 * decision lives entirely in the seasons mod.
 *
 * The provider can supply:
 * - a temperature offset, added to a biome's base temperature when
 *   Snow decides if precipitation is snow or if snow melts, so a
 *   seasons mod can make a place colder in winter and warmer in
 *   summer, without Snow hardcoding any season;
 * - accumulation and melt multipliers, so a seasons mod can make
 *   snow build up a little faster and melt a little slower in
 *   winter, and the opposite in summer, without Snow hardcoding
 *   those numbers;
 * - a forced snow precipitation flag, so a seasons mod can make
 *   precipitation fall as snow in biomes that would normally rain,
 *   in winter, while Snow itself still excludes biomes with no
 *   precipitation at all, like deserts, from that rule.
 */
public final class SeasonsHook {
    public interface Provider {
        float temperatureOffset();
        double accumulationMultiplier();
        double meltMultiplier();
        boolean forceSnowPrecipitation();
    }

    private static volatile Provider provider;

    private SeasonsHook() {
    }

    public static void setProvider(Provider newProvider) {
        provider = newProvider;
    }

    public static void clearProvider() {
        provider = null;
    }

    public static float temperatureOffset() {
        return provider == null ? 0.0f : provider.temperatureOffset();
    }

    public static double accumulationMultiplier() {
        return provider == null ? 1.0 : provider.accumulationMultiplier();
    }

    public static double meltMultiplier() {
        return provider == null ? 1.0 : provider.meltMultiplier();
    }

    public static boolean forceSnowPrecipitation() {
        return provider != null && provider.forceSnowPrecipitation();
    }
}
