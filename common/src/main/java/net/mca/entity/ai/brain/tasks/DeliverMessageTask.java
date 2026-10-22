package net.mca.entity.ai.brain.tasks;

import com.google.common.collect.ImmutableMap;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.ConversationManager;
import net.mca.entity.ai.MemoryModuleTypeMCA;
import net.mca.entity.ai.MoveState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.task.LookTargetUtil;
import net.minecraft.entity.ai.brain.task.MultiTickTask;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/**
 * Says MCA's own queued lines (greetings and the like) to the one they are for. An idle villager walks over and
 * turns to them as in MCA; a villager at work, following or staying says it from where it is (within 32 blocks)
 * and is never steered: walking or looking somewhere else would pull it off its job, its hands and its eyes.
 * Chat answers do not come through here; they are said at once ({@link net.mca.entity.ai.chatAI.ChatAI}).
 */
public class DeliverMessageTask extends MultiTickTask<VillagerEntityMCA> {
    private static final int TALKING_TIME_MIN = 100;
    private static final int TALKING_TIME_MAX = 500;
    private static final long MIN_TIME_BETWEEN_SOUND = 20 * 60 * 5;
    /** How far a busy villager says its line from, without walking over. */
    private static final int BUSY_RANGE = 32;

    private ConversationManager.Message message = null;
    private Entity receiver = null;

    private int talked;

    private long lastInteraction = Long.MIN_VALUE;
    private Vec3d lastInteractionPos;
    /** Whether this run set the walk and look targets, so only then are they cleared after. */
    private boolean steered;

    public DeliverMessageTask() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryModuleState.REGISTERED,
                MemoryModuleType.LOOK_TARGET, MemoryModuleState.REGISTERED,
                MemoryModuleType.INTERACTION_TARGET, MemoryModuleState.REGISTERED
        ), 600);
    }

    @Override
    protected boolean shouldRun(ServerWorld world, VillagerEntityMCA villager) {
        if (villager.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).isPresent()) {
            return false;
        }

        // Get potential message
        Optional<ConversationManager.Message> optionalMessage = getMessage(villager);
        // Set new message if it exists
        optionalMessage.ifPresent((m) -> {
            message = m;
            receiver = message.getReceiver();
        });

        // We only run if a message exists and the villager can see the player
        return optionalMessage.isPresent() && isWithinSeeRange(villager, receiver);
    }

    @Override
    protected void run(ServerWorld world, VillagerEntityMCA villager, long time) {
        talked = 0;
        steered = false;
    }

    @Override
    protected boolean shouldKeepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        return  message != null
                && talked < getMaxTalkingTime()
                // a busy villager does not hang around after saying it
                && (isIdle(villager) || message.stillValid())
                && villager.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).isEmpty()
                && !villager.getVillagerBrain().isPanicking()
                && !villager.isSleeping();
    }

    /** Free to walk over and turn to someone: no job, not following, not told to stay. */
    private static boolean isIdle(VillagerEntityMCA villager) {
        return villager.getVillagerBrain().getCurrentJob() == Chore.NONE
                && villager.getVillagerBrain().getMoveState() == MoveState.MOVE;
    }

    private int getMaxTalkingTime() {
        if (lastInteractionPos != null) {
            Vec3d pos = receiver.getPos();
            if (lastInteractionPos.isInRange(pos, 1.0)) {
                return TALKING_TIME_MAX;
            }
        }
        return TALKING_TIME_MIN;
    }

    @Override
    protected void keepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        if (isIdle(villager)) {
            // Look at the Receiver
            if (receiver instanceof LivingEntity e) {
                villager.getBrain().remember(MemoryModuleType.INTERACTION_TARGET, e);
                LookTargetUtil.lookAt(villager, e);
            }

            // Walk towards receiver
            LookTargetUtil.walkTowards(villager, receiver, 0.5F, 2);
            steered = true;
        }

        // Wait until the next talk cycle if receiver changed
        if (message.getReceiver() != receiver) {
            return;
        }

        // If our message is still valid and we're next to the receiver, deliver it
        if (message.stillValid() && isWithinRange(villager, receiver)) {
            if (time - lastInteraction > MIN_TIME_BETWEEN_SOUND) {
                villager.playWelcomeSound();
            }
            lastInteraction = time;
            lastInteractionPos = receiver.getPos();
            message.deliver();
        } else if (!message.stillValid() && isWithinRange(villager, receiver)) {
            // Increase reply time timer
            talked++;
            // Message no longer valid. Get new one
            // Get potential new message
            Optional<ConversationManager.Message> optionalMessage = getMessage(villager);
            // Set new message if it exists. Don't set new receiver, so we can check it against the old one
            optionalMessage.ifPresent((m) -> {
                message = m;
                // Reset conversation timer if same receiver
                if (m.getReceiver() == receiver) {
                    talked = 0;
                }
            });
        }
    }

    @Override
    protected void finishRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        message = null;
        receiver = null;
        if (steered) {
            villager.getBrain().forget(MemoryModuleType.INTERACTION_TARGET);
            villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
            villager.getBrain().forget(MemoryModuleType.LOOK_TARGET);
            steered = false;
        }
    }

    private static Optional<ConversationManager.Message> getMessage(VillagerEntityMCA villager) {
        return villager.conversationManager.getCurrentMessage();
    }

    private static boolean isWithinRange(VillagerEntityMCA villager, Entity player) {
        // Staying or busy villagers don't come over: they say it from where they are, if you are near enough to hear
        if (!isIdle(villager) || villager.getBrain().getOptionalMemory(MemoryModuleTypeMCA.STAYING.get()).isPresent()) {
            return villager.getWorld() == player.getWorld() && villager.getBlockPos().isWithinDistance(player.getBlockPos(), BUSY_RANGE);
        }
        return villager.getBlockPos().isWithinDistance(player.getBlockPos(), 3);
    }

    private static boolean isWithinSeeRange(VillagerEntityMCA villager, Entity player) {
        return villager.getBlockPos().isWithinDistance(player.getBlockPos(), 64);
    }
}
