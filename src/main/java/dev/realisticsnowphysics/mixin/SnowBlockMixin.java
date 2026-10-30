package dev.realisticsnowphysics.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.WorldView;
import net.minecraft.world.tick.ScheduledTickView;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.realisticsnowphysics.FallingSnow;
import dev.realisticsnowphysics.SnowProcessor;

// Snow blocks have no canSurvive, unlike snow layers, so vanilla never
// removes them when support is dug. Block itself does not declare updateShape
// in 26.3, AbstractBlock does, so the mixin targets AbstractBlock. Previously they only fell on the
// 5-second periodic scan, which is why blocks took a bit to fall while
// layers fell immediately via the layer mixin. This mixin queues a snow
// block the moment a neighbour update shows its support is air, same
// tick as layers, without changing its return value or any other block.
@Mixin(AbstractBlock.class)
public class SnowBlockMixin {
    @Inject(method = "getStateForNeighborUpdate", at = @At("RETURN"))
    private void realisticSnowPhysics$onUpdateShape(BlockState state, WorldView level, ScheduledTickView tick, BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState, Random random, CallbackInfoReturnable<BlockState> cir) {
        if (!(level instanceof ServerWorld sl)) return;
        if (!SnowProcessor.isSnowBlock(state)) return;
        if (!sl.getBlockState(pos.down()).isAir()) return;
        FallingSnow.queue(sl, pos);
    }
}
