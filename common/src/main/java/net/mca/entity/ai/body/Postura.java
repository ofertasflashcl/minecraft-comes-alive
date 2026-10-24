package net.mca.entity.ai.body;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * How the body holds itself, like a player's sneak key: crouched it moves slowly, quietly, shows the crouch to
 * everyone around, and its feet never walk off an edge. Anyone may ask for the crouch (caution in a cave, bridging,
 * a careful step at a drop); the reasons are counted so one asker letting go does not stand it up for another.
 * <p>
 * A reason lasts {@link #LEASE} ticks from the last time its asker asked for it: whoever wants the crouch asks again
 * every tick (or at least every few seconds) while it wants it, so a task that ends, is interrupted or forgets to let
 * go never leaves the villager crouched for good. An asker that really means "until I say so" holds the reason
 * ({@link #agachar(VillagerEntityMCA, String, boolean, boolean)} with {@code hold}); a held reason only goes with
 * {@code on = false} or {@link #erguir}. Dying, being unloaded (the map is weak, and the crouch is not saved) and a
 * reset all stand it up.
 */
public final class Postura {
    /** Ticks a reason that is not asked for again lasts (5 seconds). */
    public static final int LEASE = 100;
    /** Like a player's sneak: walking input times this. */
    public static final double SLOW = 0.3;
    /** A sneaking player's speed in blocks per tick (1.3 m/s): what the body itself moves at when it edges along. */
    public static final double SNEAK_SPEED = 0.065;

    /** Per villager: why it is crouched, with the world time each reason lasts until ({@link Long#MAX_VALUE}: held). */
    private static final Map<VillagerEntityMCA, Map<String, Long>> REASONS = new WeakHashMap<>();
    /** Per villager: the world time of a step down it means to take on purpose (the edge guard lets that one go). */
    private static final Map<VillagerEntityMCA, Long> STEP_DOWN = new WeakHashMap<>();

    private Postura() {
    }

    /** Asks for (or lets go of) the crouch for one reason ("cuidado", "puente", "borde"...). Server thread. */
    public static void agachar(VillagerEntityMCA villager, String why, boolean on) {
        agachar(villager, why, on, false);
    }

    /**
     * Asks for (or lets go of) the crouch for one reason. With {@code hold} the reason stays until it is let go of
     * (or {@link #erguir}); without it, until {@link #LEASE} ticks after the last time it was asked for.
     */
    public static void agachar(VillagerEntityMCA villager, String why, boolean on, boolean hold) {
        if (villager.getWorld().isClient) {
            return;
        }
        Map<String, Long> reasons = REASONS.get(villager);
        if (on) {
            if (reasons == null) {
                reasons = new HashMap<>();
                REASONS.put(villager, reasons);
            }
            long until = hold ? Long.MAX_VALUE : villager.getWorld().getTime() + LEASE;
            Long had = reasons.get(why);
            // a held reason asked for again without hold stays held
            reasons.put(why, had != null && had == Long.MAX_VALUE ? had : until);
        } else if (reasons != null) {
            reasons.remove(why);
            if (reasons.isEmpty()) {
                REASONS.remove(villager);
            }
        }
        apply(villager);
    }

    /** Crouched now, for whatever reason. */
    public static boolean agachado(VillagerEntityMCA villager) {
        return villager.isSneaking();
    }

    /** Crouched for this reason. */
    public static boolean agachado(VillagerEntityMCA villager, String why) {
        Map<String, Long> reasons = REASONS.get(villager);
        return reasons != null && reasons.containsKey(why) && villager.isSneaking();
    }

    /** Lets go of every reason: standing up straight (the job ended, it died, it was reset). */
    public static void erguir(VillagerEntityMCA villager) {
        REASONS.remove(villager);
        STEP_DOWN.remove(villager);
        if (villager.isSneaking()) {
            villager.setSneaking(false);
        }
        if (villager.isInPose(EntityPose.CROUCHING)) {
            villager.setPose(EntityPose.STANDING);
        }
    }

    /**
     * It steps down on purpose this tick (a stair it planned, a short drop it judged safe): the edge guard lets this
     * one step go, as a player lets go of the sneak key for it.
     */
    public static void bajar(VillagerEntityMCA villager) {
        STEP_DOWN.put(villager, villager.getWorld().getTime());
    }

    /** Every server tick of the villager: drops the reasons nobody renewed and keeps the body in step with the rest. */
    public static void tick(VillagerEntityMCA villager) {
        Map<String, Long> reasons = REASONS.get(villager);
        if (reasons != null) {
            long now = villager.getWorld().getTime();
            reasons.values().removeIf(until -> until < now);
            if (reasons.isEmpty()) {
                REASONS.remove(villager);
            }
        }
        apply(villager);
    }

    private static void apply(VillagerEntityMCA villager) {
        Map<String, Long> reasons = REASONS.get(villager);
        boolean want = reasons != null && !reasons.isEmpty() && villager.isAlive() && !villager.isSleeping() && !villager.hasVehicle();
        if (villager.isSneaking() != want) {
            villager.setSneaking(want);
        }
        // the pose is what clients draw; never over sleeping or another pose someone else set
        if (want && villager.isInPose(EntityPose.STANDING)) {
            villager.setPose(EntityPose.CROUCHING);
        } else if (!want && villager.isInPose(EntityPose.CROUCHING)) {
            villager.setPose(EntityPose.STANDING);
        }
    }

    // ------------------------------------------------------------------ what the crouch does to moving

    /** The walking input of a crouched body, like a sneaking player's: {@link #SLOW} of it. */
    public static Vec3d slowed(VillagerEntityMCA villager, Vec3d input) {
        if (!villager.isSneaking() || villager.isTouchingWater() || villager.isClimbing()) {
            return input;
        }
        return new Vec3d(input.x * SLOW, input.y, input.z * SLOW);
    }

    /**
     * The edge guard of {@code PlayerEntity#adjustMovementForSneaking}: crouched and on its feet, a move that would
     * leave nothing under the body within a step's height is cut back, 0.05 at a time, until something is there.
     * A step down it means to take (see {@link #bajar}, or the next node of the path it walks being lower) goes.
     */
    public static Vec3d edge(VillagerEntityMCA villager, Vec3d movement, MovementType type) {
        if (villager.getWorld().isClient || !villager.isSneaking() || movement.y > 0.0 || type != MovementType.SELF && type != MovementType.PLAYER
                || villager.isTouchingWater() || villager.isClimbing() || !aboveGround(villager) || steppingDown(villager)) {
            return movement;
        }
        double step = villager.getStepHeight();
        Box box = villager.getBoundingBox();
        double x = movement.x;
        double z = movement.z;
        while (x != 0.0 && villager.getWorld().isSpaceEmpty(villager, box.offset(x, -step, 0.0))) {
            x = shorten(x);
        }
        while (z != 0.0 && villager.getWorld().isSpaceEmpty(villager, box.offset(0.0, -step, z))) {
            z = shorten(z);
        }
        while (x != 0.0 && z != 0.0 && villager.getWorld().isSpaceEmpty(villager, box.offset(x, -step, z))) {
            x = shorten(x);
            z = shorten(z);
        }
        return new Vec3d(x, movement.y, z);
    }

    private static double shorten(double d) {
        if (d < 0.05 && d >= -0.05) {
            return 0.0;
        }
        return d > 0.0 ? d - 0.05 : d + 0.05;
    }

    /** On the ground, or just off it with the ground within a step below (the player's own test). */
    private static boolean aboveGround(VillagerEntityMCA villager) {
        if (villager.isOnGround()) {
            return true;
        }
        double step = villager.getStepHeight();
        return villager.fallDistance < step
                && !villager.getWorld().isSpaceEmpty(villager, villager.getBoundingBox().offset(0.0, villager.fallDistance - step, 0.0));
    }

    private static boolean steppingDown(VillagerEntityMCA villager) {
        Long at = STEP_DOWN.get(villager);
        if (at != null && villager.getWorld().getTime() - at <= 1) {
            return true;
        }
        // a path the pathfinder laid only drops where it is safe to: the next node lower than the feet is a step down
        Path path = villager.getNavigation().getCurrentPath();
        if (path == null || path.isFinished() || !villager.getNavigation().isFollowingPath()) {
            return false;
        }
        BlockPos next = path.getCurrentNodePos();
        return next.getY() < villager.getBlockY();
    }
}
