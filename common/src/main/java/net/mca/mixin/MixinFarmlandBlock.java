package net.mca.mixin;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.block.BlockState;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Villagers watch their step on a field: landing on farmland still hurts like a fall, but never tramples it. */
@Mixin(FarmlandBlock.class)
abstract class MixinFarmlandBlock {
    @Inject(method = "onLandedUpon", at = @At("HEAD"), cancellable = true)
    private void mca$treadLightly(World world, BlockState state, BlockPos pos, Entity entity, float fallDistance, CallbackInfo ci) {
        if (entity instanceof VillagerEntityMCA) {
            entity.handleFallDamage(fallDistance, 1.0F, entity.getDamageSources().fall());
            ci.cancel();
        }
    }
}
