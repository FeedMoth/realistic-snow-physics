package dev.realisticsnowphysics;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.text.Text;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
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

        // Falling queue runs every tick, not every 5 seconds, so a layer
        // whose support was just removed falls immediately, 4 per tick,
        // one at a time in waves for large collapses, instead of waiting
        // for a scan. Scan cost is gone from this path, the queue is
        // populated by the snow layer updateShape mixin and by the
        // periodic scan in the main handler below.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!RealisticSnowPhysicsConfig.get().enabled) return;
            for (ServerWorld level : server.getWorlds()) {
                try { FallingSnow.processQueue(level); } catch (Exception e) { LOGGER.error("[Realistic Snow Physics] error processing falling queue", e); }
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!RealisticSnowPhysicsConfig.get().enabled) return;
            java.util.List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
            if (players.isEmpty()) return;
            ServerPlayerEntity first = players.get(0);
            // Async pipeline, server-side threads only: snapshot on the server
            // thread, decisions on a daemon worker thread, applies back on the
            // server thread in 20 ms batches. No client code, no client mod.
            // Legacy SnowProcessor.tickStaggered remains in the tree, unused
            // in this branch, as the synchronous fallback reference.
            try { AsyncSnowPipeline.tick((ServerWorld) first.getEntityWorld(), first.getBlockPos(), server.getTicks()); } catch (Exception e) { LOGGER.error("[Realistic Snow Physics] error async snow", e); }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RealisticSnowPhysicsConfig config = RealisticSnowPhysicsConfig.get();
            if (!config.enabled) {
                return;
            }
            if (server.getTicks() % Math.max(1, config.processIntervalTicks) != 0) {
                return;
            }
            java.util.Map<ServerWorld, java.util.Set<Long>> processedByLevel = new java.util.HashMap<>();
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                ServerWorld level = (ServerWorld) player.getEntityWorld();
                BlockPos centre = player.getBlockPos();
                int radius = Math.max(1, config.sampleRadius);
                try {
                    java.util.Set<Long> processed = processedByLevel.computeIfAbsent(level, k -> new java.util.HashSet<Long>());
                    if (server.getTicks() % 100 == 0) { try { FallingSnow.process(level, centre); } catch (Exception e) { LOGGER.error("[Realistic Snow Physics] error processing falling snow at {}", centre, e); } }
                } catch (Exception e) {
                    LOGGER.error("[Realistic Snow Physics] error processing snow at {}", centre, e);
                }
            }
        });

        // Operator-only reload, so config can be tuned without a
        // restart. It only re-reads the config file; it never
        // changes blocks by itself.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("realistic-snow-physics")
                    .requires(source -> source.getEntity() == null || (source.getEntity() instanceof ServerPlayerEntity opPlayer && source.getServer().getPlayerManager().isOperator(new net.minecraft.server.PlayerConfigEntry(opPlayer.getGameProfile()))))
                    .then(CommandManager.literal("scan")
                            .executes(context -> {
                                ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                ServerWorld level = (ServerWorld) player.getEntityWorld();
                                BlockPos centre = player.getBlockPos();
                                int radius = RealisticSnowPhysicsConfig.get().sampleRadius;
                                int blocks = 0, layers = 0;
                                for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
                                    for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
                                        for (int y = centre.getY() - 10; y <= centre.getY() + 10; y++) {
                                            net.minecraft.block.BlockState st = level.getBlockState(new BlockPos(x, y, z));
                                            String id = SnowProcessor.blockId(st);
                                            if (id.equals("minecraft:snow_block")) { blocks++; }
                                            if (id.equals("minecraft:snow")) { layers++; }
                                        }
                                    }
                                }
                                int b = blocks, l = layers;
                                context.getSource().sendFeedback(() -> Text.literal("Snow scan: " + b + " snow blocks, " + l + " snow layers in radius " + radius), false);
                                return 1;
                            })));
            dispatcher.register(CommandManager.literal("realistic-snow-physics")
                    .requires(source -> source.getEntity() == null || (source.getEntity() instanceof ServerPlayerEntity opPlayer && source.getServer().getPlayerManager().isOperator(new net.minecraft.server.PlayerConfigEntry(opPlayer.getGameProfile()))))
                    .then(CommandManager.literal("diagnose")
                            .executes(context -> {
                                ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                ServerWorld level = (ServerWorld) player.getEntityWorld();
                                BlockPos centre = player.getBlockPos();
                                String diag = SnowProcessor.valleyDiagnose(level, centre);
                                context.getSource().sendFeedback(() -> Text.literal("Snow diagnose at " + centre.getX() + "," + centre.getY() + "," + centre.getZ() + ": " + diag), false);
                                return 1;
                            })));
            dispatcher.register(CommandManager.literal("realistic-snow-physics")
                    .requires(source -> source.getEntity() == null || (source.getEntity() instanceof ServerPlayerEntity opPlayer && source.getServer().getPlayerManager().isOperator(new net.minecraft.server.PlayerConfigEntry(opPlayer.getGameProfile()))))
                    .then(CommandManager.literal("async")
                            .executes(context -> {
                                context.getSource().sendFeedback(() -> Text.literal("Realistic Snow Physics: " + AsyncSnowPipeline.status()), false);
                                return 1;
                            })));
            dispatcher.register(CommandManager.literal("realistic-snow-physics")
                    .requires(source -> source.getEntity() == null || (source.getEntity() instanceof ServerPlayerEntity opPlayer && source.getServer().getPlayerManager().isOperator(new net.minecraft.server.PlayerConfigEntry(opPlayer.getGameProfile()))))
                    .then(CommandManager.literal("reload")
                            .executes(context -> {
                                RealisticSnowPhysicsConfig.load();
        PlantStore.load();
                                context.getSource().sendFeedback(
                                        () -> Text.literal("Realistic Snow Physics: config reloaded"),
                                        false);
                                return 1;
                            })));
        });
    }
}