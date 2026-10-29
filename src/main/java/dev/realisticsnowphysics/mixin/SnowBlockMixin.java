package dev.realisticsnowphysics.mixin;

import net.minecraft.util.math.random.Random;
import net.minecraft.block.BlockState;
import net.minecraft.block.SnowBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.realisticsnowphysics.RealisticSnowPhysicsConfig;
import dev.realisticsnowphysics.SnowProcessor;

/**
 * Vanilla snow layers vanish entirely in one random tick when block
 * light is over 11, no matter how many layers they have. That is the
 * bug behind a torch melting a whole snow block at once. This mixin
 * turns that vanilla melt into one layer at a time, using the same
 * melt code as the mod's own tick handler, and no snowball drops.
 */
@Mixin(SnowBlock.class)
public class SnowBlockMixin {
    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void realisticSnowPhysics$onRandomTick(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        if (world.getLightLevel(LightType.BLOCK, pos) <= 11) {
            return;
        }
        if (!RealisticSnowPhysicsConfig.get().meltEnabled) {
            ci.cancel();
            return;
        }
        SnowProcessor.melt(world, pos);
        ci.cancel();
    }
}
