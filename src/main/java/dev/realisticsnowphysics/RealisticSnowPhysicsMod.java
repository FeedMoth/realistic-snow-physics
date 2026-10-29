package dev.realisticsnowphysics;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Realistic Snow Physics: an original implementation written from scratch.
 *
 * Snow builds up layer by layer during snowfall, and melts layer by
 * layer near heat or in warm daylight. It does not appear as a full
 * block in one tick, and it does not vanish in one tick either.
 *
 * Processing is deliberately bounded. Every configured interval,
 * each player gets a small number of random samples in a radius
 * around them. Snow only matters where a player can see it, and
 * vanilla only ticks loaded chunks, so player-centred sampling
 * keeps the cost flat no matter how large the world gets. Empty
 * regions are simply left for the next time a player walks there,
 * which matches how the vanilla game already treats them.
 *
 * Runs on both sides: environment is "*", because the rules live
 * on the server side of a level, whether that level is a dedicated
 * server or the integrated server in singleplayer. No client-only
 * code is needed, and no custom rendering is used.
 */
public class RealisticSnowPhysicsMod implements ModInitializer {
    public static final String MOD_ID = "realistic-snow-physics";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        RealisticSnowPhysicsConfig.load();
        LOGGER.info("[Realistic Snow Physics] loaded: snow builds up in snowfall and melts near heat");

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
            if (!config.enabled) {
                return;
            }
            if (server.getTickCount() % Math.max(1, config.processIntervalTicks) != 0) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ServerLevel level = player.serverLevel();
                RandomSource random = level.getRandom();
                BlockPos centre = player.blockPosition();
                int radius = Math.max(1, config.sampleRadius);
                for (int i = 0; i < config.samplesPerPlayer; i++) {
                    int x = centre.getX() + random.nextInt(radius * 2 + 1) - radius;
                    int z = centre.getZ() + random.nextInt(radius * 2 + 1) - radius;
                    // Start at the top of the world at that column and
                    // walk down to the first non-air block. Snow lives
                    // on the surface, so sampling the surface directly
                    // is cheaper and more accurate than sampling
                    // random heights and hoping to hit the ground.
                    BlockPos surface = level.getHeightmapPos(
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
                    // The surface block itself may be snow, which is
                    // exactly what melt needs to see. Accumulation
                    // needs the air just above it, so try both.
                    try {
                        SnowProcessor.process(level, surface);
                        SnowProcessor.process(level, surface.above());
                    } catch (Exception e) {
                        LOGGER.error("[Realistic Snow Physics] error processing snow at {}", surface, e);
                    }
                }
            }
        });

        // Operator-only reload, so config can be tuned without a
        // restart. It only re-reads the config file; it never
        // changes blocks by itself.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("realistic-snow-physics")
                    .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                    .then(Commands.literal("reload")
                            .executes(context -> {
                                RealisticSnowPhysicsConfig.load();
                                context.getSource().sendSuccess(
                                        () -> Component.literal("Realistic Snow Physics: config reloaded"),
                                        false);
                                return 1;
                            })));
        });
    }
}
