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
import org.jetbrains.annotations.Nullable;

/**
 * Stay with the player and fight at their side: whatever hurts them, whatever they are hitting, and any
 * threat the eye catches near them. With a bow or crossbow the villager shoots from range; with a sword or
 * axe it closes in. When the way to the player is cut (a gap, a ledge) and walking cannot get there, it
 * bridges or pillars with the blocks it carries, the way a player would.
 */
public class FollowTask extends MultiTickTask<VillagerEntityMCA> {
    /** Ticks a ward may be off its feet (a jump, a hop down a ledge) and still count as standing where it last stood. */
    private static final int WARD_AIR_GRACE = 20;

    private int stuckTicks;
    private int unreachableTicks;
    private boolean warned;
    private final Motor motor = new Motor();
    /** Where the ward last stood on something: the goal of a dug or built way, never a point in the air. */
    @Nullable
    private BlockPos wardFeet;
    private long wardFootedAt;

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
        release(world, villager);
    }

    /**
     * Lets go of the body: the fight, the half-made step, where the ward stood. For a driver of {@link #guard}
     * that hands the body to another step machine (a connected villager's will going back to its own life).
     */
    public void release(ServerWorld world, VillagerEntityMCA villager) {
        CombatMind.disengage(villager);
        motor.reset(world, villager);
        wardFeet = null;
        stuckTicks = 0;
        unreachableTicks = 0;
        warned = false;
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
        long now = world.getTime();
        if (ward.isOnGround() || ward.isClimbing()) {
            wardFeet = ward.getBlockPos().toImmutable();
            wardFootedAt = now;
        }
        // a ward flying or falling for long gives no place to build toward: no pillar into the sky after it
        boolean wardStanding = wardFeet != null && now - wardFootedAt <= WARD_AIR_GRACE;
        // a jump or a step up already under way is finished first: never cut off in the air
        if (unreachableTicks >= 40 && wardStanding || motor.midStep(villager)) {
            Retina.memory(villager).doing("abriéndose camino hacia " + ward.getName().getString(), now);
            Motor.Result result = motor.step(world, villager, wardFeet != null ? wardFeet : ward.getBlockPos(), false, true);
            if (result != Motor.Result.BLOCKED) {
                return;
            }
            unreachableTicks = 0;
            motor.reset(world, villager);
        } else if (motor.busy()) {
            // walking can get there again: no half-made step left behind for next time
            motor.reset(world, villager);
        }

        float dist = (float) distance - 2;
        float speed = Math.min(1.15f, Math.max(0.7f, dist * 0.4f * 0.25f));
        LookTargetUtil.walkTowards(villager, ward, (villager.hasVehicle() ? 1.7f : 0.9f) * speed, 2);
        if (distance > 8 && (villager.age & 7) == 0) {
            villager.getNavigation().startMovingTo(ward, 1.05);
        }
    }
}
