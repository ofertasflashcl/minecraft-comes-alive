package net.mca.entity.ai.brain.tasks.chore;

import com.google.common.collect.ImmutableMap;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.DistressCalls;
import net.mca.entity.ai.Threats;
import net.mca.entity.ai.VillagerEyes;
import net.mca.entity.ai.WorkTools;
import net.mca.entity.ai.body.Archery;
import net.mca.entity.ai.body.CombatMind;
import net.mca.entity.ai.relationship.AgeState;
import net.mca.entity.ai.sense.Retina;
import net.minecraft.entity.LivingEntity;
import net.mca.util.InventoryUtils;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * Bodyguard chore. Several villagers on this job form a ring around the player
 * and break it only to hit whatever is attacking them, or to answer a miner.
 */
public class EscortTask extends AbstractChoreTask {
    private int attackCooldown;
    private int stuckTicks;
    private boolean warnedNoWeapon;
    private UUID answeringCaller;
    private LivingEntity focus;

    public EscortTask() {
        super(ImmutableMap.of(
                MemoryModuleType.LOOK_TARGET, MemoryModuleState.REGISTERED,
                MemoryModuleType.WALK_TARGET, MemoryModuleState.REGISTERED
        ), 12000);
    }

    @Override
    protected boolean allowsWorkWhileAfraid() {
        return true;
    }

    @Override
    protected boolean shouldRun(ServerWorld world, VillagerEntityMCA villager) {
        return villager.getVillagerBrain().getCurrentJob() == Chore.ESCORT && super.shouldRun(world, villager);
    }

    @Override
    protected boolean shouldKeepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        return shouldRun(world, villager);
    }

    @Override
    protected void run(ServerWorld world, VillagerEntityMCA villager, long time) {
        super.run(world, villager, time);
        if (villager.getAgeState().ordinal() <= AgeState.TODDLER.ordinal()) {
            abandonJobWithMessage("chore.escort.tooyoung");
            return;
        }
        equipWeapon(villager);
    }

    @Override
    protected void finishRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        ItemStack stack = villager.getMainHandStack();
        if (WorkTools.isWeapon(stack) || Archery.isRanged(stack)) {
            villager.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
        }
        CombatMind.disengage(villager);
    }

    @Override
    protected void keepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        super.keepRunning(world, villager, time);
        if (this.villager == null) {
            this.villager = villager;
        }
        if (villager.getVillagerBrain().getCurrentJob() != Chore.ESCORT) {
            return;
        }
        PlayerEntity player = getAssigningPlayer().orElse(null);
        if (player == null) {
            return;
        }
        equipWeapon(villager);

        if ((villager.age & 1) == 0) {
            Retina.look(world, villager);
        }
        LivingEntity threat = CombatMind.pickTarget(world, villager, player, 16);
        boolean playerSafe = threat == null;

        if (threat == null && playerSafe) {
            DistressCalls.Call call = DistressCalls.nearestForAssigner(world, player.getUuid(), villager.getBlockPos(), 96).orElse(null);
            if (call != null && shouldAnswer(world, villager, player, call)) {
                answer(world, villager, player, call);
                return;
            }
        }

        if (threat != null && threat.isAlive()) {
            fight(world, villager, player, threat);
            stuckTicks = 0;
            return;
        }

        BlockPos lost = VillagerEyes.searchWhere(world, villager);
        if (lost == null) {
            lost = Threats.footstep(world, villager, player);
        }
        if (lost != null) {
            villager.moveTowards(lost, 1.0F, 1);
            if ((villager.age & 3) == 0) {
                villager.getNavigation().startMovingTo(lost.getX() + 0.5, lost.getY(), lost.getZ() + 0.5, 1.0);
            }
            return;
        }

        focus = null;
        CombatMind.disengage(villager);
        holdFormation(villager, player);
    }

    private boolean shouldAnswer(ServerWorld world, VillagerEntityMCA villager, PlayerEntity player, DistressCalls.Call call) {
        if (call.caller().equals(villager.getUuid())) {
            return false;
        }
        double mine = villager.squaredDistanceTo(call.pos().getX() + 0.5, call.pos().getY(), call.pos().getZ() + 0.5);
        for (VillagerEntityMCA other : world.getNonSpectatingEntities(VillagerEntityMCA.class, player.getBoundingBox().expand(64))) {
            if (other == villager || other.getVillagerBrain().getCurrentJob() != Chore.ESCORT) {
                continue;
            }
            if (other.squaredDistanceTo(call.pos().getX() + 0.5, call.pos().getY(), call.pos().getZ() + 0.5) < mine) {
                return false;
            }
        }
        return true;
    }

    private void answer(ServerWorld world, VillagerEntityMCA villager, PlayerEntity player, DistressCalls.Call call) {
        if (!call.caller().equals(answeringCaller)) {
            answeringCaller = call.caller();
            villager.sendChatMessage(player, "chore.escort.answering", call.name());
        }
        MobEntity threat = Threats.nearest(world, call.pos(), 12, villager, player);
        if (threat != null) {
            fight(world, villager, null, threat);
            return;
        }
        double distance = villager.squaredDistanceTo(call.pos().getX() + 0.5, call.pos().getY(), call.pos().getZ() + 0.5);
        if (distance > 32 * 32) {
            stuckTicks++;
            if (stuckTicks > 80) {
                villager.teleport(call.pos().getX() + 0.5, call.pos().getY(), call.pos().getZ() + 0.5);
                villager.fallDistance = 0;
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
        villager.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(call.pos(), 1.05F, 2));
    }

    private void fight(ServerWorld world, VillagerEntityMCA villager, PlayerEntity player, LivingEntity threat) {
        if (focus != threat) {
            focus = threat;
            if (player != null) {
                villager.sendChatMessage(player, "chore.escort.engaging");
            }
        }
        CombatMind.fight(world, villager, player, threat);
    }

    private void holdFormation(VillagerEntityMCA villager, PlayerEntity player) {
        int slot = Math.floorMod(villager.getUuid().hashCode(), 8);
        double angle = (Math.PI * 2.0 * slot) / 8.0;
        double radius = 2.15 + (slot % 2) * 0.45;
        double x = player.getX() + Math.cos(angle) * radius;
        double z = player.getZ() + Math.sin(angle) * radius;
        double distance = villager.distanceTo(player);
        if (distance > 40) {
            stuckTicks++;
            if (stuckTicks > 40) {
                villager.teleport(x, player.getY(), z);
                villager.fallDistance = 0;
                stuckTicks = 0;
                return;
            }
        } else {
            stuckTicks = 0;
        }
        float speed = distance > 10 ? 1.15F : (distance > 4 ? 0.9F : 0.65F);
        villager.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(player, true));
        villager.moveTowards(BlockPos.ofFloored(x, player.getY(), z), speed, 1);
        if (distance > 3.2 && (villager.age & 3) == 0) {
            villager.getNavigation().startMovingTo(x, player.getY(), z, speed);
        }
    }

    private void equipWeapon(VillagerEntityMCA villager) {
        if (CombatMind.busy(villager) || InventoryUtils.holdMain(villager, s -> WorkTools.isWeapon(s) || Archery.isRanged(s))) {
            return;
        }
        if (!warnedNoWeapon) {
            warnedNoWeapon = true;
            getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, "chore.escort.noweapon"));
        }
    }
}
