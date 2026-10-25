package net.mca.mixin;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.SleepManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** What net.mca.entity.ai.sense.Dormir needs of a world to count connected villagers as sleeping players. */
@Mixin(ServerWorld.class)
public interface MixinServerWorldSleepAccessor {
    @Accessor("sleepManager")
    SleepManager mca$sleepManager();

    /** The "X/Y players sleeping" (or "skipping the night") action bar to every player of the world. */
    @Invoker("sendSleepingStatus")
    void mca$sendSleepingStatus();

    @Invoker("wakeSleepingPlayers")
    void mca$wakeSleepingPlayers();

    @Invoker("resetWeather")
    void mca$resetWeather();
}
