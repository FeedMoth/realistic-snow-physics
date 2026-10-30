package dev.realisticsnowphysics.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.realisticsnowphysics.FallingSnow;
import dev.realisticsnowphysics.RealisticSnowPhysicsConfig;
import dev.realisticsnowphysics.SnowProcessor;

/**
 * Vanilla snow layers vanish entirely in one random tick when block
 * light is over 11, no matter how many layers they have. That is the
 * bug behind a torch melting a whole snow block at once. This mixin
 * turns that vanilla melt into one layer at a time, using the same
 * melt code as the mod's own tick handler, and no snowball drops.
 * A full snow block first becomes a 7 layer pile with the mod's
 * melt, then vanilla can no longer wipe all 7 at once.
 */
@Mixin(SnowLayerBlock.class)
public class SnowLayerBlockMixin {
    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void realisticSnowPhysics$onRandomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (level.getBrightness(LightLayer.BLOCK, pos) <= 11) {
            return;
        }
        if (!RealisticSnowPhysicsConfig.get().meltEnabled) {
            ci.cancel();
            return;
        }
        SnowProcessor.melt(level, pos);
        ci.cancel();
    }

    // Vanilla destroys a snow layer instantly when its support is removed:
    // updateShape returns AIR when canSurvive fails, before any falling scan
    // can run, even a scan every tick. That is why layers never fell, while
    // full snow blocks, which have no canSurvive, stayed floating and fell.
    // Keep the layer instead, queue it for falling, and let the per-tick
    // queue move it down immediately, preserving layers. Placement is
    // unchanged, because this only intercepts a removal that already
    // happened, it does not change canSurvive for placement.
    @Inject(method = "updateShape", at = @At("RETURN"), cancellable = true)
    private void realisticSnowPhysics$onUpdateShape(BlockState state, LevelReader level, ScheduledTickAccess tick, BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random, CallbackInfoReturnable<BlockState> cir) {
        if (!cir.getReturnValue().isAir()) return;
        if (!(level instanceof ServerLevel sl)) return;
        cir.setReturnValue(state);
        if (sl.getBlockState(pos.below()).isAir()) FallingSnow.queue(sl, pos);
    }
}
