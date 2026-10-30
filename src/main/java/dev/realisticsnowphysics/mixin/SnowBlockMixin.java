package dev.realisticsnowphysics.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.WorldAccess;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.realisticsnowphysics.FallingSnow;
import dev.realisticsnowphysics.SnowProcessor;

// Snow blocks have no canSurvive, unlike snow layers, so vanilla never
// removes them when support is dug. In 1.20.1 the neighbour-update hook
// is getStateForNeighborUpdate with (state, direction, neighborState,
// world, pos, neighborPos). This mixin queues a snow block the moment
// a neighbour update shows its support is air, same tick as layers,
// without changing its return value or any other block.
@Mixin(AbstractBlock.class)
public class SnowBlockMixin {
    @Inject(method = "getStateForNeighborUpdate", at = @At("RETURN"))
    private void realisticSnowPhysics$onUpdateShape(BlockState state, Direction direction, BlockState neighborState, WorldAccess level, BlockPos pos, BlockPos neighborPos, CallbackInfoReturnable<BlockState> cir) {
        if (!(level instanceof ServerWorld sl)) return;
        if (!SnowProcessor.isSnowBlock(state)) return;
        if (!sl.getBlockState(pos.down()).isAir()) return;
        FallingSnow.queue(sl, pos);
    }
}
