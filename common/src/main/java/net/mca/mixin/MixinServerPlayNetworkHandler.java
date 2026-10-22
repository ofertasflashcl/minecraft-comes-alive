package net.mca.mixin;

import net.mca.Config;
import net.mca.MCA;
import net.mca.entity.ai.chatAI.ChatAI;
import net.mca.server.GameSpeed;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.apache.commons.lang3.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public class MixinServerPlayNetworkHandler {
    @Shadow
    public ServerPlayerEntity player;

    /**
     * A player's chat line, as it arrives (network thread): handed to the server thread, where the villager it is
     * for is found and starts answering ({@link ChatAI#onPlayerChat}). The line itself goes on to chat as usual.
     * Nothing here may throw: an exception on the network thread would disconnect the player.
     */
    @Inject(method = "onChatMessage", at = @At("HEAD"))
    public void sendMessage(ChatMessageC2SPacket message, CallbackInfo ci) {
        try {
            if (!Config.getInstance().enableVillagerChatAI) {
                return;
            }
            String msg = StringUtils.normalizeSpace(message.chatMessage());
            if (msg == null || msg.isEmpty() || msg.startsWith("/")) {
                return;
            }
            ServerPlayerEntity speaker = player;
            MinecraftServer server = speaker.getServer();
            if (server == null) {
                return;
            }
            long received = System.currentTimeMillis();
            server.execute(() -> ChatAI.onPlayerChat(speaker, msg, received));
        } catch (Throwable t) {
            MCA.LOGGER.error("[Tropeles] gancho del chat", t);
        }
    }

    /**
     * Sped up ({@link GameSpeed}), a jump or a slime bounce lasts more server ticks than the 80 a player may float
     * before being kicked for flying: the limit grows with the speed. Optional: without a match it does nothing.
     */
    @ModifyConstant(method = "tick", constant = @Constant(intValue = 80), require = 0)
    private int mca$floatingLimit(int vanilla) {
        MinecraftServer server = player.getServer();
        float speed = server instanceof GameSpeed.Holder ? GameSpeed.get(server) : 1.0f;
        return speed > 1.0f ? Math.round(vanilla * speed) : vanilla;
    }
}
