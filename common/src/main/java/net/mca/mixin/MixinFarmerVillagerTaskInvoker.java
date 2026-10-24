package net.mca.mixin;

import net.minecraft.entity.ai.brain.task.FarmerVillagerTask;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the in-game checks ask a farmer whether it would pick a crop. */
@Mixin(FarmerVillagerTask.class)
public interface MixinFarmerVillagerTaskInvoker {
    @Invoker("isSuitableTarget")
    boolean mca$isSuitableTarget(BlockPos pos, ServerWorld world);
}
