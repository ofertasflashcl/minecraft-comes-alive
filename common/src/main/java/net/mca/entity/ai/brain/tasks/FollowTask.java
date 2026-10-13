package net.mca.entity.ai.brain.tasks;

import com.google.common.collect.ImmutableMap;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.MemoryModuleTypeMCA;
import net.mca.entity.ai.Threats;
import net.mca.entity.ai.VillagerEyes;
import net.mca.entity.ai.WorkTools;
import net.mca.util.InventoryUtils;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.task.LookTargetUtil;
import net.minecraft.entity.ai.brain.task.MultiTickTask;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Stay with the player. Break formation only to hit whatever is coming at them.
 */
public class FollowTask extends MultiTickTask<VillagerEntityMCA> {
    private int attackCooldown;
    private int stuckTicks;
    private boolean warned;

    public FollowTask() {
        super(ImmutableMap.of(
                MemoryModuleTypeMCA.PLAYER_FOLLOWING.get(), MemoryModuleState.VALUE_PRESENT
        ));
    }

    @Override
    protected boolean shouldRun(ServerWorld world, VillagerEntityMCA villager) {
        return villager.getBrain().getOptionalMemory(MemoryModuleTypeMCA.PLAYER_FOLLOWING.get()).isPresent();
    }

    @Override
    protected boolean shouldKeepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        return this.shouldRun(world, villager);
    }

    @Override
    protected void keepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        villager.getBrain().getOptionalMemory(MemoryModuleTypeMCA.PLAYER_FOLLOWING.get()).ifPresent(playerToFollow -> {
            if (villager.getBrain().getOptionalMemory(MemoryModuleType.HURT_BY_ENTITY).filter(livingEntity -> livingEntity == playerToFollow).isPresent()) {
                villager.getBrain().forget(MemoryModuleTypeMCA.PLAYER_FOLLOWING.get());
                return;
            }

            MobEntity threat = Threats.nearest(world, playerToFollow.getBlockPos(), 16, villager, playerToFollow);
            if (threat == null) {
                threat = Threats.nearest(world, villager.getBlockPos(), 8, villager, playerToFollow);
            }
            if (threat != null && threat.isAlive()) {
                fight(villager, playerToFollow, threat);
                return;
            }
            BlockPos lost = VillagerEyes.searchWhere(world, villager);
            if (lost == null) {
                lost = Threats.footstep(world, villager, playerToFollow);
            }
            if (lost != null) {
                villager.moveTowards(lost, 1.0F, 1);
                if ((villager.age & 3) == 0) {
                    villager.getNavigation().startMovingTo(lost.getX() + 0.5, lost.getY(), lost.getZ() + 0.5, 1.0);
                }
                return;
            }
            villager.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
            follow(villager, playerToFollow);
        });
    }

    private void fight(VillagerEntityMCA villager, PlayerEntity player, MobEntity threat) {
        equip(villager);
        if (!warned) {
            warned = true;
            villager.sendChatMessage(player, "chore.escort.engaging");
        }
        villager.getBrain().remember(MemoryModuleType.ATTACK_TARGET, threat);

        if (threat instanceof CreeperEntity && villager.squaredDistanceTo(threat) < 16) {
            Vec3d away = villager.getPos().subtract(threat.getPos());
            if (away.lengthSquared() < 0.01) {
                away = new Vec3d(1, 0, 0);
            }
            away = away.normalize().multiply(5);
            BlockPos flee = BlockPos.ofFloored(villager.getPos().add(away));
            villager.moveTowards(flee, 1.15F, 0);
            villager.getNavigation().startMovingTo(flee.getX() + 0.5, flee.getY(), flee.getZ() + 0.5, 1.15);
            return;
        }

        villager.moveTowards(threat.getBlockPos(), 1.05F, 1);
        if ((villager.age & 3) == 0) {
            villager.getNavigation().startMovingTo(threat, 1.05);
        }
        if (villager.squaredDistanceTo(threat) > 6.25F || attackCooldown-- > 0) {
            return;
        }
        attackCooldown = 10;
        villager.swingHand(Hand.MAIN_HAND);
        villager.tryAttack(threat);
        ItemStack weapon = villager.getMainHandStack();
        if (WorkTools.isWeapon(weapon)) {
            weapon.damage(1, villager, e -> e.sendEquipmentBreakStatus(EquipmentSlot.MAINHAND));
        }
    }

    private void follow(VillagerEntityMCA villager, PlayerEntity player) {
        double distance = villager.distanceTo(player);
        if (distance > 40) {
            stuckTicks++;
            if (stuckTicks > 30) {
                villager.requestTeleport(player.getX(), player.getY(), player.getZ());
                villager.fallDistance = 0;
                stuckTicks = 0;
                return;
            }
        } else {
            stuckTicks = 0;
        }
        float dist = (float) distance - 2;
        float speed = Math.min(1.15f, Math.max(0.7f, dist * 0.4f * 0.25f));
        LookTargetUtil.walkTowards(villager, player, (villager.hasVehicle() ? 1.7f : 0.9f) * speed, 2);
        if (distance > 8 && (villager.age & 7) == 0) {
            villager.getNavigation().startMovingTo(player, 1.05);
        }
    }

    private static void equip(VillagerEntityMCA villager) {
        InventoryUtils.holdMain(villager, WorkTools::isWeapon);
    }
}
