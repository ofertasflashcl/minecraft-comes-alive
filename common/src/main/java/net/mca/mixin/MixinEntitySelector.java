package net.mca.mixin;

import net.mca.server.ProspectorPresence;
import net.minecraft.command.EntitySelector;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.ServerCommandSource;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * A plain or quoted name ("/tp Inas", "/tp Inas @s", '/tp @s "Inés Prueba"') is read as a player-only selector
 * with playerName set. When no real player has that name, it resolves to the prospecting villager listed under it
 * ({@link ProspectorPresence}). Real players always win, and players()-only arguments (/msg, /give) go through
 * getPlayers and are untouched. Optional: a missed target only turns this off, and ProspectorPresence's start-up
 * check logs it.
 */
@Mixin(EntitySelector.class)
abstract class MixinEntitySelector {
    @Shadow
    @Final
    @Nullable
    private String playerName;

    @Inject(method = "getUnfilteredEntities", at = @At("HEAD"), cancellable = true, require = 0)
    private void mca$onlineVillagerByName(ServerCommandSource source, CallbackInfoReturnable<List<? extends Entity>> cir) {
        if (playerName == null || source.getServer().getPlayerManager().getPlayer(playerName) != null) {
            return;
        }
        Entity villager = ProspectorPresence.findByName(source.getServer(), playerName);
        if (villager != null) {
            cir.setReturnValue(List.of(villager));
        }
    }
}
