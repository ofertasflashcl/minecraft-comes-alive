package net.mca.mixin;

import net.mca.entity.ai.sense.Obras;
import net.minecraft.entity.ai.brain.task.FarmerVillagerTask;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A farmer looks after the village fields, not a player's: crops a player planted and ground a player tilled are
 * never picked or replanted on its own (asked to harvest, it is a different story, see HarvestingTask).
 */
@Mixin(FarmerVillagerTask.class)
abstract class MixinFarmerVillagerTask {
    @Inject(method = "isSuitableTarget", at = @At("HEAD"), cancellable = true)
    private void mca$notPlayersFields(BlockPos pos, ServerWorld world, CallbackInfoReturnable<Boolean> cir) {
        if (Obras.playersField(world, pos)) {
            cir.setReturnValue(false);
        }
    }
}
