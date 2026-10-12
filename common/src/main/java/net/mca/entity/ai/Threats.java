package net.mca.entity.ai;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.Nullable;

/**
 * Shared threat test for escort, mining and family rescue.
 * A calm enderman is not a reason to abandon a shaft. Something already swinging is.
 */
public final class Threats {
    private Threats() {
    }

    public static boolean isThreat(MobEntity mob, LivingEntity self, @Nullable PlayerEntity ward) {
        if (mob == null || mob == self || !mob.isAlive() || mob.isRemoved()) {
            return false;
        }
        if (mob instanceof VillagerEntity) {
            return false;
        }
        if (mob instanceof TameableEntity tame && tame.isTamed()) {
            return false;
        }
        if (mob.getTarget() == self || (ward != null && mob.getTarget() == ward)) {
            return true;
        }
        if (mob.getTarget() instanceof VillagerEntity) {
            return true;
        }
        if (mob instanceof HostileEntity) {
            return !(mob instanceof Angerable anger) || anger.hasAngerTime();
        }
        return false;
    }

    @Nullable
    public static MobEntity nearest(ServerWorld world, BlockPos around, double radius, LivingEntity self, @Nullable PlayerEntity ward) {
        MobEntity best = null;
        double bestDistance = radius * radius;
        Box box = new Box(around).expand(radius);
        for (MobEntity mob : world.getNonSpectatingEntities(MobEntity.class, box)) {
            if (!isThreat(mob, self, ward)) {
                continue;
            }
            double distance = mob.squaredDistanceTo(around.getX() + 0.5, around.getY(), around.getZ() + 0.5);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = mob;
            }
        }
        return best;
    }

    public static boolean lavaNear(ServerWorld world, BlockPos pos) {
        if (world.getFluidState(pos).isIn(FluidTags.LAVA)) {
            return true;
        }
        for (BlockPos neighbor : BlockPos.iterate(pos.add(-1, -1, -1), pos.add(1, 1, 1))) {
            if (world.getFluidState(neighbor).isIn(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }
}
