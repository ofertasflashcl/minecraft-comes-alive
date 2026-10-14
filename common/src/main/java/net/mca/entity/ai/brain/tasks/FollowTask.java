package net.mca.entity.ai.brain.tasks;

import com.google.common.collect.ImmutableMap;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.MemoryModuleTypeMCA;
import net.mca.entity.ai.Threats;
import net.mca.entity.ai.VillagerEyes;
import net.mca.entity.ai.body.CombatMind;
import net.mca.entity.ai.body.Motor;
import net.mca.entity.ai.sense.Retina;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.task.LookTargetUtil;
import net.minecraft.entity.ai.brain.task.MultiTickTask;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Stay with the player and fight at their side: whatever hurts them, whatever they are hitting, and any
 * threat the eye catches near them. With a bow or crossbow the villager shoots from range; with a sword or
 * axe it closes in. When the way to the player is cut (a gap, a ledge) and walking cannot get there, it
 * bridges or pillars with the blocks it carries, the way a player would.
 */
public class FollowTask extends MultiTickTask<VillagerEntityMCA> {
    private int stuckTicks;
    private int unreachableTicks;
    private boolean warned;
    private final Motor motor = new Motor();

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
    protected void finishRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        CombatMind.disengage(villager);
        motor.reset(world, villager);
    }

    @Override
    protected void keepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        villager.getBrain().getOptionalMemory(MemoryModuleTypeMCA.PLAYER_FOLLOWING.get()).ifPresent(player -> guard(world, villager, player));
    }

    /** One tick of following and protecting {@code ward}. */
    public void guard(ServerWorld world, VillagerEntityMCA villager, LivingEntity ward) {
        if (villager.getBrain().getOptionalMemory(MemoryModuleType.HURT_BY_ENTITY).filter(livingEntity -> livingEntity == ward).isPresent()) {
            villager.getBrain().forget(MemoryModuleTypeMCA.PLAYER_FOLLOWING.get());
            return;
        }
        if ((villager.age & 1) == 0) {
            Retina.look(world, villager);
        }

        // an archer covers more ground than a swordsman
        LivingEntity threat = CombatMind.pickTarget(world, villager, ward, CombatMind.hasRanged(villager) ? 24 : 16);
        if (threat != null && threat.isAlive()) {
            if (!warned && ward instanceof PlayerEntity player) {
                warned = true;
                villager.sendChatMessage(player, "chore.escort.engaging");
            }
            motor.reset(world, villager);
            CombatMind.fight(world, villager, ward, threat);
            return;
        }
        warned = false;
        CombatMind.disengage(villager);

        BlockPos lost = VillagerEyes.searchWhere(world, villager);
        if (lost == null) {
            lost = Threats.footstep(world, villager, ward instanceof PlayerEntity p ? p : null);
        }
        if (lost != null && lost.isWithinDistance(ward.getBlockPos(), 16)) {
            Retina.memory(villager).doing("buscando donde lo vio por última vez", world.getTime());
            villager.moveTowards(lost, 1.0F, 1);
            if ((villager.age & 3) == 0) {
                villager.getNavigation().startMovingTo(lost.getX() + 0.5, lost.getY(), lost.getZ() + 0.5, 1.0);
            }
            return;
        }
        follow(world, villager, ward);
    }

    private void follow(ServerWorld world, VillagerEntityMCA villager, LivingEntity ward) {
        double distance = villager.distanceTo(ward);
        if (distance > 40) {
            stuckTicks++;
            if (stuckTicks > 30) {
                villager.requestTeleport(ward.getX(), ward.getY(), ward.getZ());
                villager.fallDistance = 0;
                stuckTicks = 0;
                motor.reset(world, villager);
                return;
            }
        } else {
            stuckTicks = 0;
        }
        Retina.memory(villager).doing("siguiendo a " + ward.getName().getString(), world.getTime());

        // can walking get there at all? checked once a second
        if (distance > 3.0 && (villager.age % 20) == 0) {
            Path path = villager.getNavigation().findPathTo(ward, 1);
            if (path == null || !path.reachesTarget() && path.getEnd() != null && !path.getEnd().getBlockPos().isWithinDistance(ward.getBlockPos(), 2.5)) {
                unreachableTicks += 20;
            } else {
                unreachableTicks = 0;
            }
        }
        if (distance <= 3.0) {
            unreachableTicks = 0;
        }
        if (unreachableTicks >= 40 && ward.isOnGround()) {
            Retina.memory(villager).doing("abriéndose camino hacia " + ward.getName().getString(), world.getTime());
            Motor.Result result = motor.step(world, villager, ward.getBlockPos(), false, true);
            if (result == Motor.Result.BLOCKED) {
                unreachableTicks = 0;
                motor.reset(world, villager);
            }
            if (result != Motor.Result.BLOCKED) {
                return;
            }
        }

        float dist = (float) distance - 2;
        float speed = Math.min(1.15f, Math.max(0.7f, dist * 0.4f * 0.25f));
        LookTargetUtil.walkTowards(villager, ward, (villager.hasVehicle() ? 1.7f : 0.9f) * speed, 2);
        if (distance > 8 && (villager.age & 7) == 0) {
            villager.getNavigation().startMovingTo(ward, 1.05);
        }
    }
}
