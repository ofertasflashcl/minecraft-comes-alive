package net.mca.mixin;

import net.mca.entity.ai.sense.Obras;
import net.minecraft.item.HoeItem;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ground a player tills is their field: remembered like a block they placed. */
@Mixin(HoeItem.class)
abstract class MixinHoeItem {
    @Inject(method = "useOnBlock", at = @At("RETURN"))
    private void mca$rememberField(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
        if (context.getPlayer() != null && cir.getReturnValue().isAccepted() && context.getWorld() instanceof ServerWorld world) {
            Obras.placedByPlayer(world, context.getBlockPos());
        }
    }
}
