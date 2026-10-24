package net.mca.entity.ai.brain.tasks.chore;

import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.server.ProspectorPresence;
import net.minecraft.entity.ai.FuzzyTargeting;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.ai.brain.task.MultiTickTask;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public abstract class AbstractChoreTask extends MultiTickTask<VillagerEntityMCA> {
    protected VillagerEntityMCA villager;
    protected int failedTicks, walkingTicks;
    protected int lastAge;
    protected static final int FAILED_COOLDOWN = 100;
    protected static final int WALKING_THRESHOLD = 200;
    /**
     * Real time a prospector keeps working while the one who sent it is offline, so a dropped connection or a quick
     * relog does not end the job. Real time, not ticks: a sped-up world would otherwise give a reconnect seconds.
     */
    private static final long ASSIGNER_OFFLINE_GRACE_MS = 5 * 60_000L;

    /** Since when (real time) the assigner of a prospector has been offline; -1 whenever they are seen online. */
    private long assignerOfflineSinceMs = -1L;
    /** Whose absence that timer counts: a job from someone else starts its own grace. */
    private UUID assignerOfflineId;

    public AbstractChoreTask(Map<MemoryModuleType<?>, MemoryModuleState> requirements) {
        this(requirements, 400);
    }

    public AbstractChoreTask(Map<MemoryModuleType<?>, MemoryModuleState> requirements, int duration) {
        super(requirements, duration);
    }

    protected boolean allowsWorkWhileAfraid() {
        return false;
    }

    @Override
    protected boolean shouldRun(ServerWorld world, VillagerEntityMCA entity) {
        int diff = Math.max(0, entity.age - lastAge);
        lastAge = entity.age;

        // wander around
        if (failedTicks > 0) {
            failedTicks -= diff;
            walkingTicks += diff;

            if (walkingTicks > WALKING_THRESHOLD) {
                Optional<Vec3d> optional = Optional.ofNullable(FuzzyTargeting.find(entity, 10, 5));
                entity.getBrain().remember(MemoryModuleType.WALK_TARGET, optional.map(vec3d -> new WalkTarget(vec3d, 0.4f, 0)));
                walkingTicks = 0;
            }

            return false;
        }

        return villager == null || allowsWorkWhileAfraid() || !villager.getVillagerBrain().isPanicking();
    }

    @Override
    protected void keepRunning(ServerWorld world, VillagerEntityMCA entity, long time) {
        if (villager == null) {
            villager = entity;
        }
        if (getAssigningPlayer().isPresent()) {
            assignerOfflineSinceMs = -1L;
            return;
        }
        if (assignerStillAround(world, entity)) {
            return;
        }
        MCA.LOGGER.info("Force-stopped chore because assigning player was not present.");
        villager.getVillagerBrain().abandonJob();
    }

    /**
     * A prospector, like a player, keeps working while the one who sent it is in another dimension, and for a few
     * minutes after they went offline. The other chores stop as soon as their assigner is not in the same world.
     */
    private boolean assignerStillAround(ServerWorld world, VillagerEntityMCA entity) {
        if (entity.getVillagerBrain().getCurrentJob() != Chore.PROSPECT) {
            return false;
        }
        Optional<UUID> id = entity.getVillagerBrain().getJobAssignerUuid();
        if (id.isEmpty()) {
            return false;
        }
        if (world.getServer().getPlayerManager().getPlayer(id.get()) != null) {
            assignerOfflineSinceMs = -1L;
            return true;
        }
        long now = Util.getMeasuringTimeMs();
        if (assignerOfflineSinceMs < 0L || !id.get().equals(assignerOfflineId)) {
            assignerOfflineSinceMs = now;
            assignerOfflineId = id.get();
            MCA.LOGGER.info("[Tropeles] {} sigue minando aunque quien le dio el trabajo no está conectado (espera {} min)",
                    entity.getName().getString(), ASSIGNER_OFFLINE_GRACE_MS / 60_000L);
        }
        return now - assignerOfflineSinceMs < ASSIGNER_OFFLINE_GRACE_MS;
    }

    @Override
    protected void run(ServerWorld world, VillagerEntityMCA entity, long time) {
        // the offline timer is NOT reset here: a task restarts every few minutes of game time (ProspectingTask every
        // 12000 ticks, half a minute at 20x), and the grace must end however often that happens
        this.villager = entity;
    }

    /** The one who gave the job, when they are in the villager's own world. */
    Optional<PlayerEntity> getAssigningPlayer() {
        return villager.getVillagerBrain().getJobAssigner();
    }

    /**
     * Tells the one who gave the job: in person when they share the world, otherwise found on the whole server
     * (another dimension). Silent when they are offline.
     */
    protected void tellAssigner(String phraseId, Object... params) {
        Optional<PlayerEntity> assigner = getAssigningPlayer();
        if (assigner.isPresent()) {
            villager.sendChatMessage(assigner.get(), phraseId, params);
        } else {
            ProspectorPresence.sayToAssigner(villager, phraseId, params);
        }
    }

    void abandonJobWithMessage(String message) {
        // said before abandonJob, which forgets who gave the job
        tellAssigner(message);
        villager.getVillagerBrain().abandonJob();
    }
}
