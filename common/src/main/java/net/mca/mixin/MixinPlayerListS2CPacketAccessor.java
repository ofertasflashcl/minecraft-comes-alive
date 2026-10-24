package net.mca.mixin;

import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Lets ProspectorPresence build Tab entries for villagers: the packet's own constructors only take real players. */
@Mixin(PlayerListS2CPacket.class)
public interface MixinPlayerListS2CPacketAccessor {
    @Accessor("entries")
    @Mutable
    void mca$setEntries(List<PlayerListS2CPacket.Entry> entries);
}
