package net.mca.mixin;

import net.mca.entity.ai.sense.Dormir;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Connected villagers sleep like players: the sleep counter of the world counts them ({@link Dormir#actualizar} in
 * place of the vanilla recount, which only knows players and does nothing with none online), the night is skipped when
 * they are needed for it ({@link Dormir#tick}), and they get up with everyone else ({@link Dormir#nocheSaltada}).
 */
@Mixin(ServerWorld.class)
abstract class MixinServerWorldSleep {
    @Inject(method = "updateSleepingPlayers()V", at = @At("HEAD"), cancellable = true)
    private void mca$updateSleepingPlayers(CallbackInfo info) {
        Dormir.actualizar((ServerWorld) (Object) this);
        info.cancel();
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void mca$tickSleep(BooleanSupplier shouldKeepTicking, CallbackInfo info) {
        Dormir.tick((ServerWorld) (Object) this);
    }

    @Inject(method = "wakeSleepingPlayers()V", at = @At("TAIL"))
    private void mca$wakeSleepingPlayers(CallbackInfo info) {
        Dormir.nocheSaltada((ServerWorld) (Object) this);
    }
}
