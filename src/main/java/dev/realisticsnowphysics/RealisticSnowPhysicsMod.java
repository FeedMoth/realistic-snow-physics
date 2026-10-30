package dev.realisticsnowphysics;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * each player gets a bounded batch of ground positions in a radius,
 * with columns already processed this tick by another player skipped,
 * so nearby players do not stack snow or melt on the same ground
 * around them, processed blanket first and lowest surface first,
 * so snow covers the ground evenly instead of appearing as random
 * spots. Snow only matters where a player can see it, and vanilla
 * only ticks loaded chunks, so player-centred processing keeps the
 * cost flat no matter how large the world gets. Empty regions are
 * simply left for the next time a player walks there, which matches
 * how the vanilla game already treats them.
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
        PlantStore.load();
        LOGGER.info("[Realistic Snow Physics] loaded: snow builds up in snowfall and melts near heat");

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
            if (!config.enabled) {
                return;
            }
            if (server.getTickCount() % Math.max(1, config.processIntervalTicks) != 0) {
                return;
            }
            java.util.Map<ServerLevel, java.util.Set<Long>> processedByLevel = new java.util.HashMap<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ServerLevel level = player.level();
                BlockPos centre = player.blockPosition();
                int radius = Math.max(1, config.sampleRadius);
                try {
                    java.util.Set<Long> processed = processedByLevel.computeIfAbsent(level, k -> new java.util.HashSet<Long>());
                    try { FallingSnow.process(level, centre); } catch (Exception e) { LOGGER.error("[Realistic Snow Physics] error processing falling snow at {}", centre, e); }
                    SnowProcessor.processArea(level, centre, radius, config.samplesPerPlayer, processed);
                } catch (Exception e) {
                    LOGGER.error("[Realistic Snow Physics] error processing snow at {}", centre, e);
                }
            }
        });

        // Operator-only reload, so config can be tuned without a
        // restart. It only re-reads the config file; it never
        // changes blocks by itself.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("realistic-snow-physics")
                    .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                    .then(Commands.literal("scan")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                ServerLevel level = player.level();
                                BlockPos centre = player.blockPosition();
                                int radius = RealisticSnowPhysicsConfig.get().sampleRadius;
                                int blocks = 0, layers = 0;
                                for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
                                    for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
                                        for (int y = centre.getY() - 10; y <= centre.getY() + 10; y++) {
                                            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(new BlockPos(x, y, z));
                                            String id = SnowProcessor.blockId(st);
                                            if (id.equals("minecraft:snow_block")) { blocks++; }
                                            if (id.equals("minecraft:snow")) { layers++; }
                                        }
                                    }
                                }
                                int b = blocks, l = layers;
                                context.getSource().sendSuccess(() -> Component.literal("Snow scan: " + b + " snow blocks, " + l + " snow layers in radius " + radius), false);
                                return 1;
                            })));
            dispatcher.register(Commands.literal("realistic-snow-physics")
                    .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                    .then(Commands.literal("diagnose")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                ServerLevel level = player.level();
                                BlockPos centre = player.blockPosition();
                                String diag = SnowProcessor.valleyDiagnose(level, centre);
                                context.getSource().sendSuccess(() -> Component.literal("Snow diagnose at " + centre.getX() + "," + centre.getY() + "," + centre.getZ() + ": " + diag), false);
                                return 1;
                            })));
            dispatcher.register(Commands.literal("realistic-snow-physics")
                    .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                    .then(Commands.literal("reload")
                            .executes(context -> {
                                RealisticSnowPhysicsConfig.load();
        PlantStore.load();
                                context.getSource().sendSuccess(
                                        () -> Component.literal("Realistic Snow Physics: config reloaded"),
                                        false);
                                return 1;
                            })));
        });
    }
}