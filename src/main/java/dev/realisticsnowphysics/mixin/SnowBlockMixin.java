package dev.realisticsnowphysics.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.realisticsnowphysics.FallingSnow;
import dev.realisticsnowphysics.SnowProcessor;

// Snow blocks have no canSurvive, unlike snow layers, so vanilla never
// removes them when support is dug. Previously they only fell on the
// 5-second periodic scan, which is why blocks took a bit to fall while
// layers fell immediately via the layer mixin. This mixin queues a snow
// block the moment a neighbour update shows its support is air, same
// tick as layers, without changing its return value or any other block.
@Mixin(Block.class)
public class SnowBlockMixin {
    @Inject(method = "updateShape", at = @At("RETURN"))
    private void realisticSnowPhysics$onUpdateShape(BlockState state, LevelReader level, ScheduledTickAccess tick, BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random, CallbackInfoReturnable<BlockState> cir) {
        if (!(level instanceof ServerLevel sl)) return;
        if (!SnowProcessor.isSnowBlock(state)) return;
        if (!sl.getBlockState(pos.below()).isAir()) return;
        FallingSnow.queue(sl, pos);
    }
}
