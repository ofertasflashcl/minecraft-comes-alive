package net.mca.entity.ai.brain.tasks.chore;

import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.ai.FuzzyTargeting;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.ai.brain.task.MultiTickTask;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.Optional;

public abstract class AbstractChoreTask extends MultiTickTask<VillagerEntityMCA> {
    protected VillagerEntityMCA villager;
    protected int failedTicks, walkingTicks;
    protected int lastAge;
    protected static final int FAILED_COOLDOWN = 100;
    protected static final int WALKING_THRESHOLD = 200;

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
        if (getAssigningPlayer().isEmpty()) {
            MCA.LOGGER.info("Force-stopped chore because assigning player was not present.");
            villager.getVillagerBrain().abandonJob();
        }
    }

    @Override
    protected void run(ServerWorld world, VillagerEntityMCA entity, long time) {
        this.villager = entity;
    }

    Optional<PlayerEntity> getAssigningPlayer() {
        return villager.getVillagerBrain().getJobAssigner();
    }

    void abandonJobWithMessage(String message) {
        getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, message));
        villager.getVillagerBrain().abandonJob();
    }
}
