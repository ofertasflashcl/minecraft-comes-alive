package net.mca.entity.ai.brain.tasks;

import com.google.common.collect.ImmutableMap;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.DistressCalls;
import net.mca.entity.ai.Threats;
import net.mca.entity.ai.WorkTools;
import net.mca.entity.ai.body.CombatMind;
import net.mca.entity.ai.relationship.AgeState;
import net.mca.util.InventoryUtils;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.ai.brain.task.MultiTickTask;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;

import java.util.Optional;
import java.util.UUID;

/**
 * A parent or sibling drops what they were doing and goes to a relative who called from the mine.
 */
public class FamilyRescueTask extends MultiTickTask<VillagerEntityMCA> {
    private UUID announcedCaller;
    private int attackCooldown;
    private int stuckTicks;
    private DistressCalls.Call tracking;

    public FamilyRescueTask() {
        super(ImmutableMap.of(), 12000);
    }

    @Override
    protected boolean shouldRun(ServerWorld world, VillagerEntityMCA villager) {
        return DistressCalls.nearestFamilyCall(villager, 96).isPresent()
                && villager.getAgeState().ordinal() >= AgeState.TEEN.ordinal();
    }

    @Override
    protected boolean shouldKeepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        return shouldRun(world, villager);
    }

    @Override
    protected void keepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        Optional<DistressCalls.Call> call = DistressCalls.nearestFamilyCall(villager, 96);
        if (call.isEmpty()) {
            return;
        }
        tracking = call.get();
        announce(world, villager, tracking);

        MobEntity threat = Threats.nearest(world, tracking.pos(), 14, villager, null);
        if (threat != null && villager.squaredDistanceTo(threat) < 18 * 18) {
            fight(world, villager, threat);
            stuckTicks = 0;
            return;
        }

        double distance = villager.squaredDistanceTo(tracking.pos().getX() + 0.5, tracking.pos().getY(), tracking.pos().getZ() + 0.5);
        if (distance > 24 * 24) {
            stuckTicks++;
            if (stuckTicks > 160) {
                villager.teleport(tracking.pos().getX() + 0.5, tracking.pos().getY(), tracking.pos().getZ() + 0.5);
                villager.fallDistance = 0;
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }

        villager.getBrain().remember(MemoryModuleType.LOOK_TARGET, new net.minecraft.entity.ai.brain.BlockPosLookTarget(tracking.pos()));
        float speed = distance > 16 * 16 ? 1.05F : 0.75F;
        villager.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(tracking.pos(), speed, 2));
        if ((villager.age & 3) == 0) {
            villager.getNavigation().startMovingTo(tracking.pos().getX() + 0.5, tracking.pos().getY(), tracking.pos().getZ() + 0.5, speed);
        }
    }

    private void announce(ServerWorld world, VillagerEntityMCA villager, DistressCalls.Call call) {
        if (call.caller().equals(announcedCaller)) {
            return;
        }
        announcedCaller = call.caller();
        PlayerEntity assigner = world.getServer().getPlayerManager().getPlayer(call.assigner());
        if (assigner != null) {
            villager.sendChatMessage(assigner, "chore.rescue.coming", call.name());
        } else if (world.getClosestPlayer(villager, 32) instanceof ServerPlayerEntity nearby) {
            villager.sendChatMessage(nearby, "chore.rescue.coming", call.name());
        }
    }

    private void fight(ServerWorld world, VillagerEntityMCA villager, MobEntity threat) {
        CombatMind.fight(world, villager, null, threat);
    }
}
