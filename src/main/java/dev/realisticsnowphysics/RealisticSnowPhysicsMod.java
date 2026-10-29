package dev.realisticsnowphysics;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
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
 * each player gets a bounded batch of ground positions in a radius
 * around them, processed blanket first and lowest surface first,
 * so snow covers the ground evenly instead of appearing as random
 * spots. Snow only matters where a player can see it, and
 * vanilla only ticks loaded chunks, so player-centred sampling
 * keeps the cost flat no matter how large the world gets. This is
 * also why the mod works on pre-generated chunks: it never relies
 * on world generation, it only processes blocks already in a
 * loaded chunk, whether that chunk was generated yesterday or
 * five minutes ago.
 *
 * Runs on both sides: environment is "*", because the rules live
 * on the server side of a level, whether that level is a dedicated
 * server or the integrated server in singleplayer.
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
            if (server.getTicks() % Math.max(1, config.processIntervalTicks) != 0) {
                return;
            }
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                ServerWorld world = (ServerWorld) player.getEntityWorld();
                BlockPos centre = player.getBlockPos();
                int radius = Math.max(1, config.sampleRadius);
                try {
                    SnowProcessor.processArea(world, centre, radius, config.samplesPerPlayer);
                } catch (Exception e) {
                    LOGGER.error("[Realistic Snow Physics] error processing snow at {}", centre, e);
                }
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("realistic-snow-physics")
                    .requires(source -> source.getEntity() == null || (source.getEntity() instanceof ServerPlayerEntity opPlayer && source.getServer().getPlayerManager().isOperator(new net.minecraft.server.PlayerConfigEntry(opPlayer.getGameProfile()))))
                    .then(CommandManager.literal("reload")
                            .executes(context -> {
                                RealisticSnowPhysicsConfig.load();
                                context.getSource().sendFeedback(
                                        () -> Text.literal("Realistic Snow Physics: config reloaded"),
                                        false);
                                return 1;
                            })));
        });
    }
}
