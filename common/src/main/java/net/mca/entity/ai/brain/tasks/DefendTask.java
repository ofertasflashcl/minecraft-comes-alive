package net.mca.entity.ai.brain.tasks;

import com.google.common.collect.ImmutableMap;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.MemoryModuleTypeMCA;
import net.mca.entity.ai.Threats;
import net.mca.entity.ai.WorkTools;
import net.mca.util.InventoryUtils;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.brain.task.MultiTickTask;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Closes on whatever is attacking this villager. Following and mining have their own plans.
 */
public class DefendTask extends MultiTickTask<VillagerEntityMCA> {
    private int attackCooldown;

    public DefendTask() {
        super(ImmutableMap.of(), 12000);
    }

    @Override
    protected boolean shouldRun(ServerWorld world, VillagerEntityMCA villager) {
        return target(world, villager) != null;
    }

    @Override
    protected boolean shouldKeepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        return shouldRun(world, villager);
    }

    @Override
    protected void keepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        MobEntity threat = target(world, villager);
        if (threat == null) {
            return;
        }
        equip(villager);
        if (villager.getHealth() < villager.getMaxHealth() * 0.25F || threat instanceof CreeperEntity && villager.squaredDistanceTo(threat) < 16) {
            Vec3d away = villager.getPos().subtract(threat.getPos());
            if (away.lengthSquared() < 0.01) {
                away = new Vec3d(1, 0, 0);
            }
            away = away.normalize().multiply(6);
            BlockPos flee = BlockPos.ofFloored(villager.getPos().add(away));
            villager.moveTowards(flee, 1.1F, 0);
            villager.getNavigation().startMovingTo(flee.getX() + 0.5, flee.getY(), flee.getZ() + 0.5, 1.1);
            return;
        }
        villager.moveTowards(threat.getBlockPos(), 1.0F, 1);
        if ((villager.age & 3) == 0) {
            villager.getNavigation().startMovingTo(threat, 1.0);
        }
        if (villager.squaredDistanceTo(threat) > 6.25F || attackCooldown-- > 0) {
            return;
        }
        attackCooldown = 10;
        villager.swingHand(Hand.MAIN_HAND);
        villager.tryAttack(threat);
        ItemStack weapon = villager.getStackInHand(Hand.MAIN_HAND);
        if (!weapon.isEmpty() && WorkTools.isWeapon(weapon)) {
            weapon.damage(1, villager, e -> e.sendEquipmentBreakStatus(EquipmentSlot.MAINHAND));
        }
    }

    private static MobEntity target(ServerWorld world, VillagerEntityMCA villager) {
        Chore job = villager.getVillagerBrain().getCurrentJob();
        if (job == Chore.PROSPECT || job == Chore.ESCORT) {
            return null;
        }
        if (villager.getBrain().getOptionalMemory(MemoryModuleTypeMCA.PLAYER_FOLLOWING.get()).isPresent()) {
            return null;
        }
        boolean staying = villager.getBrain().getOptionalMemory(MemoryModuleTypeMCA.STAYING.get()).isPresent();
        return Threats.nearest(world, villager.getBlockPos(), staying ? 6 : 14, villager, null);
    }

    private static void equip(VillagerEntityMCA villager) {
        InventoryUtils.holdMain(villager, WorkTools::isWeapon);
    }
}
