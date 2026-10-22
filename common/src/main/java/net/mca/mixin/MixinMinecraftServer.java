package net.mca.mixin;

import net.mca.server.GameSpeed;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * {@link GameSpeed}: scales the 50 ms tick of MinecraftServer.runServer(). 1.20.1 has no /tick, and the tick length
 * is the inlined constant 50L at exactly four places of each loop iteration (Forge adds hooks around the loop only):
 * (1) {@code l / 50L} and (2) {@code m * 50L}, in the "Can't keep up" branch only; (3) {@code timeReference += 50L},
 * before {@code tick()}; (4) {@code nextTickTimestamp = Math.max(getMeasuringTimeMs() + 50L, timeReference)}, after it.
 * {@code ticks++} happens inside {@code tick()}, so the first call that sees a new tick count is site 4, which plans
 * the next period; sites 1-3 of the next iteration reuse it. At 1x the vanilla constant is returned untouched.
 * IntegratedServer only overrides {@code tick()} (paused, it returns before counting), so single player works too.
 * <p>
 * Optional (require = 0): if another mod already took these constants, the game still starts at normal speed and
 * {@link GameSpeed} logs that the speed cannot be changed, and /mca-velocidad says so.
 */
@Mixin(MinecraftServer.class)
abstract class MixinMinecraftServer implements GameSpeed.Holder {
    /** The schedule never falls further behind than this: no catch-up burst after a lag spike, no "Can't keep up". */
    @Unique
    private static final long MCA$MAX_BACKLOG_MS = 1000L;
    /** nextTickTimestamp is also the time budget for chunk tasks after each tick: never less than this. */
    @Unique
    private static final long MCA$TASK_BUDGET_MS = 2L;

    @Shadow
    private int ticks;
    @Shadow
    private long timeReference;
    @Shadow
    private long lastTimeReference;
    @Shadow
    private long nextTickTimestamp;

    @Unique
    private float mca$speed = 1.0f;
    @Unique
    private long mca$period = 50L;
    /** The fraction of a millisecond the last periods left over (20x is 2.5 ms: 2, 3, 2, 3...). */
    @Unique
    private float mca$carry;
    @Unique
    private int mca$plannedAt;
    /** Set by the first call of the hook: the loop really runs through it. */
    @Unique
    private boolean mca$hookLive;

    @ModifyConstant(method = "runServer", constant = @Constant(longValue = 50L), require = 0)
    private long mca$tickPeriod(long vanilla) {
        mca$hookLive = true;
        float speed = mca$speed;
        if (speed == 1.0f || !(speed > 0.0f)) {
            // 1x (and, defensively, a speed never set) is byte-for-byte vanilla: 50 / 0 would stop the world
            return vanilla;
        }
        if (ticks == mca$plannedAt) {
            // sites 1-3, and site 4 while the single player pause menu holds the tick count still
            return mca$period;
        }
        // site 4 after a real tick: plan the next period
        mca$plannedAt = ticks;
        float exact = 50.0f / speed + mca$carry;
        mca$period = Math.max(1L, (long) exact);
        mca$carry = Math.max(0.0f, exact - mca$period);
        // the LDC of site 4 runs before its GETFIELD timeReference, so the cap is seen by Math.max right away;
        // the dedicated watchdog (now - timeReference) stays small too
        long now = Util.getMeasuringTimeMs();
        if (now - timeReference > MCA$MAX_BACKLOG_MS) {
            timeReference = now - MCA$MAX_BACKLOG_MS;
            lastTimeReference = timeReference;
        }
        return Math.max(mca$period, MCA$TASK_BUDGET_MS);
    }

    @Override
    public float mca$getSpeed() {
        return mca$speed > 0.0f ? mca$speed : 1.0f;
    }

    @Override
    public void mca$setSpeed(float speed) {
        mca$speed = speed;
        mca$carry = 0.0f;
        mca$period = Math.max(1L, (long) (50.0f / speed));
        mca$plannedAt = ticks;
        // start the new schedule from now: no burst to make up for the old one, and no wait left over from a slow
        // period (0.1x plans half a second ahead) while tasks keep the loop busy
        timeReference = Util.getMeasuringTimeMs();
        lastTimeReference = timeReference;
        nextTickTimestamp = timeReference;
    }

    @Override
    public boolean mca$speedHookLive() {
        return mca$hookLive;
    }
}
