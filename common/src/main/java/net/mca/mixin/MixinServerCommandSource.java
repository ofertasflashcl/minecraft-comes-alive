package net.mca.mixin;

import net.mca.server.ProspectorPresence;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The prospecting villagers listed like online players ({@link ProspectorPresence}) are offered as names where the
 * server itself completes a command, e.g. "tp In" + Tab in a dedicated server's console. Players' own chat already
 * offers them, from the Tab list their client got. Optional: a missed target only drops these suggestions.
 */
@Mixin(ServerCommandSource.class)
abstract class MixinServerCommandSource {
    @Inject(method = "getPlayerNames", at = @At("RETURN"), cancellable = true, require = 0)
    private void mca$listedVillagerNames(CallbackInfoReturnable<Collection<String>> cir) {
        Collection<String> villagers = ProspectorPresence.profileNames(((ServerCommandSource) (Object) this).getServer());
        if (villagers.isEmpty() || cir.getReturnValue() == null) {
            return;
        }
        List<String> all = new ArrayList<>(cir.getReturnValue());
        all.addAll(villagers);
        cir.setReturnValue(all);
    }
}
