package net.mca.entity.ai.brain.tasks;

import net.mca.entity.ai.body.Mining;
import com.google.common.collect.ImmutableMap;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.MemoryModuleTypeMCA;
import net.mca.entity.ai.Threats;
import net.mca.entity.ai.VillagerEyes;
import net.mca.entity.ai.body.CombatMind;
import net.mca.entity.ai.body.Motor;
import net.mca.entity.ai.body.Postura;
import net.mca.entity.ai.sense.Retina;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.task.LookTargetUtil;
import net.minecraft.entity.ai.brain.task.MultiTickTask;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
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
    /** A player's pace over flat ground, in blocks a tick: walking, sprinting, sprint-jumping. */
    private static final double WALK = 0.216;
    private static final double SPRINT = 0.281;
    private static final double HOP = 0.33;
    /** Farther than this it runs and jumps as it goes to catch up; nearer than {@link #CLOSE} it only keeps their pace. */
    private static final double FAR = 10.0;
    private static final double CLOSE = 3.5;
    /** Where it likes to be: this far from the player. */
    private static final double KEEP = 2.6;
    /** Crouch reason in {@link Postura}: the player sneaks, so does it. */
    private static final String SNEAK = "como su jugador";

    private int stuckTicks;
    private int unreachableTicks;
    private boolean warned;
    private final Motor motor = new Motor();
    /** Where the ward last stood on something: the goal of a dug or built way, never a point in the air. */
    @Nullable
    private BlockPos wardFeet;
    private long wardFootedAt;
    /** The ward's pace over the ground (blocks a tick), smoothed: the pace it walks or runs at by them. */
    private double wardPace;
    @Nullable
    private Vec3d wardLast;
    private long wardLastAt;

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
        wardLast = null;
        wardPace = 0;
        villager.setSprinting(false);
        Postura.agachar(villager, SNEAK, false);
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
            villager.setSprinting(false);
            CombatMind.fight(world, villager, ward, threat);
            return;
        }
        warned = false;
        CombatMind.disengage(villager);

        BlockPos lost = VillagerEyes.searchWhere(world, villager);
        if (lost == null) {
            lost = Threats.footstep(world, villager, ward instanceof PlayerEntity p ? p : null);
        }
        if (lost != null && lost.isWithinDistance(Mining.pies(ward), 16)) {
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
            if (path == null || !path.reachesTarget() && path.getEnd() != null && !path.getEnd().getBlockPos().isWithinDistance(Mining.pies(ward), 2.5)) {
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
            wardFeet = Mining.pies(ward).toImmutable();
            wardFootedAt = now;
        }
        // a ward flying or falling for long gives no place to build toward: no pillar into the sky after it
        boolean wardStanding = wardFeet != null && now - wardFootedAt <= WARD_AIR_GRACE;
        // a jump or a step up already under way is finished first: never cut off in the air
        if (unreachableTicks >= 40 && wardStanding || motor.midStep(villager)) {
            Retina.memory(villager).doing("abriéndose camino hacia " + ward.getName().getString(), now);
            Motor.Result result = motor.step(world, villager, wardFeet != null ? wardFeet : Mining.pies(ward), false, true);
            if (result != Motor.Result.BLOCKED) {
                return;
            }
            unreachableTicks = 0;
            motor.reset(world, villager);
        } else if (motor.busy()) {
            // walking can get there again: no half-made step left behind for next time
            motor.reset(world, villager);
        }

        if (villager.hasVehicle()) {
            float speed = Math.min(1.15f, Math.max(0.7f, ((float) distance - 2) * 0.1f));
            LookTargetUtil.walkTowards(villager, ward, 1.7f * speed, 2);
            return;
        }
        pace(world, villager, ward, distance, now);
    }

    /**
     * At a player's pace, like another player by their side: walking with a walker and running with a runner (the same
     * speed, read off how fast they really go over the ground), a little faster to close a gap, sprinting and jumping
     * as it goes when it fell far behind, still beside a player who stands, and crouched while they sneak.
     */
    private void pace(ServerWorld world, VillagerEntityMCA villager, LivingEntity ward, double distance, long now) {
        Vec3d at = ward.getPos();
        if (wardLast != null && now > wardLastAt) {
            long dt = Math.min(20, now - wardLastAt);
            double dx = at.x - wardLast.x;
            double dz = at.z - wardLast.z;
            double v = Math.sqrt(dx * dx + dz * dz) / dt;
            wardPace = v > 1.2 ? wardPace : wardPace + (v - wardPace) * Math.min(1.0, 0.35 * dt); // a teleport is no pace
        }
        wardLast = at;
        wardLastAt = now;
        double pace = ward.isSprinting() ? Math.max(wardPace, SPRINT) : wardPace;
        boolean sneak = ward.isSneaking() && distance < FAR;
        Postura.agachar(villager, SNEAK, sneak);
        double want;
        if (distance > FAR) {
            want = Math.max(pace + 0.06, HOP);
        } else if (distance > CLOSE) {
            want = MathHelper.clamp(pace + 0.03 * (distance - KEEP), WALK * 0.75, Math.max(SPRINT, pace) + 0.04);
        } else if (pace < 0.03) {
            want = distance > KEEP + 0.4 ? WALK * 0.6 : 0.0;
        } else {
            want = distance > KEEP ? pace + 0.02 : pace * 0.9;
        }
        villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        if (want <= 0.0) {
            // by a player who stands still: it stands too, turned to them
            villager.setSprinting(false);
            villager.getNavigation().stop();
            villager.getLookControl().lookAt(ward, 20.0F, 20.0F);
            return;
        }
        boolean run = !sneak && want > WALK + 0.015;
        if (run != villager.isSprinting()) {
            villager.setSprinting(run); // the game's own sprint: +30% to the legs, and a jump carries it forward
        }
        // a mob covers about 2.2·(speed·multiplier)² blocks a tick on flat ground: the multiplier for that pace
        double legs = Math.max(0.05, villager.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED));
        double m = MathHelper.clamp(Math.sqrt(want / 2.2) / legs, 0.3, 1.8);
        EntityNavigation nav = villager.getNavigation();
        if (nav.isIdle() || (villager.age % 10) == 0) {
            nav.startMovingTo(ward, m);
        }
        nav.setSpeed(m);
        if (distance > FAR && run && villager.isOnGround() && nav.isFollowingPath() && headRoom(world, villager)) {
            villager.getJumpControl().setActive(); // sprint-jumping to make up the ground, like a player
        }
    }

    /** Room over the head here and a step ahead for a running jump. */
    private static boolean headRoom(ServerWorld world, VillagerEntityMCA villager) {
        BlockPos feet = Mining.pies(villager);
        BlockPos ahead = feet.offset(villager.getHorizontalFacing());
        return Mining.passable(world, feet.up(2)) && Mining.passable(world, ahead.up(2)) && Mining.passable(world, ahead.up())
                && Mining.passable(world, ahead) && !Mining.passable(world, ahead.down());
    }
}
