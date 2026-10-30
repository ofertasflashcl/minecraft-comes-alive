package net.mca.mixin;

import net.mca.entity.ai.sense.Obras;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers every block a player sets down, so villagers know it for somebody's build and leave it alone. */
@Mixin(BlockItem.class)
abstract class MixinBlockItem {
    @Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;", at = @At("RETURN"))
    private void mca$rememberPlacement(ItemPlacementContext context, CallbackInfoReturnable<ActionResult> cir) {
        if (context.getPlayer() == null || !cir.getReturnValue().isAccepted() || !(context.getWorld() instanceof ServerWorld world)) {
            return;
        }
        BlockPos pos = context.getBlockPos();
        Obras.placedByPlayer(world, pos);
        // the other half of a door, a bed or a tall flower
        BlockState placed = world.getBlockState(pos);
        if (!placed.contains(Properties.DOUBLE_BLOCK_HALF) && !placed.contains(Properties.BED_PART)) {
            return;
        }
        for (Direction dir : Direction.values()) {
            BlockPos other = pos.offset(dir);
            if (world.getBlockState(other).isOf(placed.getBlock())) {
                Obras.placedByPlayer(world, other);
            }
        }
    }
}
