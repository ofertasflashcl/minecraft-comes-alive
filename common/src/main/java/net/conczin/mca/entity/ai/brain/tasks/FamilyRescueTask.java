package net.conczin.mca.entity.ai.brain.tasks;

import com.google.common.collect.ImmutableMap;
import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.entity.ai.DistressCalls;
import net.conczin.mca.entity.ai.Threats;
import net.conczin.mca.entity.ai.relationship.AgeState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * A parent or sibling drops what they were doing and goes to a relative who called from the mine.
 */
public class FamilyRescueTask extends Behavior<VillagerEntityMCA> {
    private UUID announcedCaller;
    private int attackCooldown;
    private int stuckTicks;
    private DistressCalls.Call tracking;

    public FamilyRescueTask() {
        super(ImmutableMap.of(), 12000);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel world, VillagerEntityMCA villager) {
        return DistressCalls.nearestFamilyCall(villager, 96).isPresent()
                && villager.getAgeState().ordinal() >= AgeState.TEEN.ordinal();
    }

    @Override
    protected boolean canStillUse(ServerLevel world, VillagerEntityMCA villager, long time) {
        return checkExtraStartConditions(world, villager);
    }

    @Override
    protected void tick(ServerLevel world, VillagerEntityMCA villager, long time) {
        Optional<DistressCalls.Call> call = DistressCalls.nearestFamilyCall(villager, 96);
        if (call.isEmpty()) {
            return;
        }
        tracking = call.get();
        announce(world, villager, tracking);

        Mob threat = Threats.nearest(world, tracking.pos(), 14, villager, null);
        if (threat != null && villager.distanceToSqr(threat) < 18 * 18) {
            fight(world, villager, threat);
            stuckTicks = 0;
            return;
        }

        double distance = villager.distanceToSqr(tracking.pos().getX() + 0.5, tracking.pos().getY(), tracking.pos().getZ() + 0.5);
        if (distance > 24 * 24) {
            stuckTicks++;
            if (stuckTicks > 160) {
                villager.teleportTo(tracking.pos().getX() + 0.5, tracking.pos().getY(), tracking.pos().getZ() + 0.5);
                villager.resetFallDistance();
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }

        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(tracking.pos()));
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(tracking.pos(), distance > 64 ? 1.05F : 0.75F, 2));
    }

    private void announce(ServerLevel world, VillagerEntityMCA villager, DistressCalls.Call call) {
        if (call.caller().equals(announcedCaller)) {
            return;
        }
        announcedCaller = call.caller();
        Player assigner = world.getServer().getPlayerList().getPlayer(call.assigner());
        if (assigner != null) {
            villager.sendChatMessage(assigner, "chore.rescue.coming", call.name());
        } else if (world.getNearestPlayer(villager, 32) instanceof ServerPlayer nearby) {
            villager.sendChatMessage(nearby, "chore.rescue.coming", call.name());
        }
    }

    private void fight(ServerLevel world, VillagerEntityMCA villager, Mob threat) {
        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(threat, true));
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(new EntityTracker(threat, false), 1.05F, 1));
        villager.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, threat);
        if (villager.distanceToSqr(threat) > 6.25F) {
            return;
        }
        if (attackCooldown-- > 0) {
            return;
        }
        attackCooldown = 12;
        villager.swing(InteractionHand.MAIN_HAND);
        float damage = (float) villager.getAttributeValue(Attributes.ATTACK_DAMAGE);
        if (damage < 1.0F) {
            damage = 3.0F;
        }
        threat.hurt(world.damageSources().mobAttack(villager), damage);
    }
}
