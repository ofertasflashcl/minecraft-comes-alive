package net.conczin.mca.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Shared threat test for escort, mining and family rescue.
 * A calm enderman is not a reason to abandon a shaft. Something already swinging is.
 */
public final class Threats {
    private Threats() {
    }

    public static boolean isThreat(Mob mob, LivingEntity self, @Nullable Player ward) {
        if (mob == null || mob == self || !mob.isAlive() || mob.isRemoved()) {
            return false;
        }
        if (mob instanceof Villager) {
            return false;
        }
        if (mob instanceof TamableAnimal tame && tame.isTame()) {
            return false;
        }
        if (mob.getTarget() == self || (ward != null && mob.getTarget() == ward)) {
            return true;
        }
        if (mob.getTarget() instanceof Villager) {
            return true;
        }
        if (mob instanceof Enemy) {
            return !(mob instanceof NeutralMob neutral) || neutral.isAngry();
        }
        return false;
    }

    @Nullable
    public static Mob nearest(ServerLevel world, BlockPos around, double radius, LivingEntity self, @Nullable Player ward) {
        Mob best = null;
        double bestDistance = radius * radius;
        AABB box = new AABB(around).inflate(radius);
        for (Mob mob : world.getEntitiesOfClass(Mob.class, box)) {
            if (!isThreat(mob, self, ward)) {
                continue;
            }
            double distance = mob.distanceToSqr(around.getX() + 0.5, around.getY(), around.getZ() + 0.5);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = mob;
            }
        }
        return best;
    }

    public static boolean lavaNear(ServerLevel world, BlockPos pos) {
        if (world.getFluidState(pos).is(FluidTags.LAVA)) {
            return true;
        }
        for (BlockPos neighbor : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
            if (world.getFluidState(neighbor).is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }
}
