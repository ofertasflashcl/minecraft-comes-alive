package net.mca.mixin;

import net.minecraft.server.world.SleepManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The world's sleep counter, so connected villagers count as players (see net.mca.entity.ai.sense.Dormir). */
@Mixin(SleepManager.class)
public interface MixinSleepManagerAccessor {
    @Accessor("total")
    int mca$getTotal();

    @Accessor("total")
    void mca$setTotal(int total);

    @Accessor("sleeping")
    int mca$getSleeping();

    @Accessor("sleeping")
    void mca$setSleeping(int sleeping);
}
