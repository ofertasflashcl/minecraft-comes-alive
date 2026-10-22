package net.mca.entity.ai.sense;

import net.mca.entity.ai.body.Hands;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Looking around like a player turning the camera: a sweep of the whole horizon in steps, a glance down at the
 * ground and up at the sky, holding each heading long enough for the whole frame to be scanned. One per villager
 * task; the caller stops walking while it sweeps if it wants a steady look.
 * <p>
 * A sweep is {@value #HEADINGS} headings {@value #STEP} degrees apart starting where the head points, at each one a
 * look a little up ({@value #HIGH} degrees of pitch, Minecraft's sign: negative is up) and one a little down
 * ({@value #LOW}), then one look up at the sky
 * ({@value #UP}) and one down at the feet ({@value #DOWN}). Each stop is held {@value #HOLD} ticks with one
 * {@link Retina#look} per tick, so the interlaced bands make one whole frame per stop: 18 stops, 54 ticks.
 */
public final class Mirada {
    public static final int HEADINGS = 8;
    public static final float STEP = 45.0F;
    public static final float HIGH = -15.0F;
    public static final float LOW = 5.0F;
    public static final float UP = -60.0F;
    public static final float DOWN = 45.0F;
    public static final int HOLD = Retina.BANDS;
    /** The whole-at-once look around is not repeated sooner than this (ticks): it is the costly one. */
    public static final int AROUND_COOLDOWN = 20;
    /** The one pitch of each heading in {@link #lookAround}: a little up, the frame reaches the ground two steps off. */
    public static final float AROUND_PITCH = -5.0F;

    private static final int STOPS = HEADINGS * 2 + 2;

    private int stop = -1;
    private int held;
    private float startYaw;

    /** Starts a full sweep from where the head points now. */
    public void start(LivingEntity viewer) {
        stop = 0;
        held = 0;
        startYaw = viewer.getHeadYaw();
    }

    /** One tick of the sweep: turns the head and looks. True while the sweep is still going. */
    public boolean tick(ServerWorld world, LivingEntity viewer) {
        if (stop < 0) {
            return false;
        }
        turn(viewer, yaw(startYaw, stop), pitch(stop));
        Retina.look(world, viewer);
        if (++held >= HOLD) {
            held = 0;
            if (++stop >= STOPS) {
                stop = -1;
            }
        }
        return stop >= 0;
    }

    public boolean sweeping() {
        return stop >= 0;
    }

    public void stop() {
        stop = -1;
        held = 0;
    }

    /**
     * A whole sweep at once, for the chat ("¿qué ves?") and the checks: the head ends where it started.
     * <p>
     * At once it is a whole frame per stop, so it keeps to the stops that see something new: the eight headings
     * at a level look ({@value #AROUND_PITCH} degrees, the frame reaches 35 up and down from there), the sky and
     * the feet. Ten frames, about 7200 rays; not repeated within {@value #AROUND_COOLDOWN} ticks.
     */
    public static void lookAround(ServerWorld world, LivingEntity viewer) {
        VisualMemory memory = Retina.memory(viewer);
        long now = world.getTime();
        if (now - memory.lastSweep() < AROUND_COOLDOWN) {
            return;
        }
        memory.swept(now);
        float headYaw = viewer.getHeadYaw();
        float pitch = viewer.getPitch();
        float bodyYaw = viewer.getBodyYaw();
        float yaw = viewer.getYaw();
        try {
            for (int i = 0; i < HEADINGS; i++) {
                set(viewer, headYaw + i * STEP, AROUND_PITCH);
                Retina.lookAll(world, viewer);
            }
            set(viewer, headYaw, UP);
            Retina.lookAll(world, viewer);
            set(viewer, headYaw, DOWN);
            Retina.lookAll(world, viewer);
        } finally {
            viewer.setHeadYaw(headYaw);
            viewer.setPitch(pitch);
            viewer.setBodyYaw(bodyYaw);
            viewer.setYaw(yaw);
        }
    }

    private static float yaw(float start, int stop) {
        if (stop >= HEADINGS * 2) {
            return MathHelper.wrapDegrees(start + (HEADINGS - 1) * STEP);
        }
        return MathHelper.wrapDegrees(start + (stop / 2) * STEP);
    }

    private static float pitch(int stop) {
        if (stop == HEADINGS * 2) {
            return UP;
        }
        if (stop == HEADINGS * 2 + 1) {
            return DOWN;
        }
        return (stop & 1) == 0 ? HIGH : LOW;
    }

    private static void set(LivingEntity viewer, float yaw, float pitch) {
        viewer.setHeadYaw(MathHelper.wrapDegrees(yaw));
        viewer.setPitch(pitch);
    }

    /** Turns the head the way the hands aim it: head yaw and pitch, the body when the head would twist too far. */
    private static void turn(LivingEntity viewer, float yaw, float pitch) {
        if (viewer instanceof MobEntity mob) {
            Vec3d point = viewer.getEyePos().add(Vec3d.fromPolar(pitch, yaw).multiply(8.0));
            Hands.aim(mob, point);
            return;
        }
        viewer.setPitch(pitch);
        viewer.setHeadYaw(yaw);
        if (Math.abs(MathHelper.wrapDegrees(yaw - viewer.getBodyYaw())) > 45.0F) {
            viewer.setBodyYaw(yaw);
        }
    }
}
