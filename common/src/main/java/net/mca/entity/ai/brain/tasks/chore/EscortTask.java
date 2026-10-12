package net.mca.entity.ai.brain.tasks.chore;

import com.google.common.collect.ImmutableMap;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.DistressCalls;
import net.mca.entity.ai.Threats;
import net.mca.entity.ai.WorkTools;
import net.mca.entity.ai.relationship.AgeState;
import net.mca.util.InventoryUtils;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
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
    private MobEntity focus;

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
        ItemStack stack = villager.getStackInHand(villager.getDominantHand());
        if (!stack.isEmpty()) {
            villager.setStackInHand(villager.getDominantHand(), ItemStack.EMPTY);
        }
        villager.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
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

        boolean playerSafe = Threats.nearest(world, player.getBlockPos(), 14, villager, player) == null;
        MobEntity threat = Threats.nearest(world, player.getBlockPos(), 16, villager, player);
        if (threat == null) {
            threat = Threats.nearest(world, villager.getBlockPos(), 8, villager, player);
        }

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

        focus = null;
        villager.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
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
            fight(world, villager, player, threat);
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

    private void fight(ServerWorld world, VillagerEntityMCA villager, PlayerEntity player, MobEntity threat) {
        if (focus != threat) {
            focus = threat;
            villager.sendChatMessage(player, "chore.escort.engaging");
        }
        villager.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(threat, true));
        villager.getBrain().remember(MemoryModuleType.ATTACK_TARGET, threat);

        if (threat instanceof CreeperEntity && villager.squaredDistanceTo(threat) < 16) {
            Vec3d away = villager.getPos().subtract(threat.getPos());
            if (away.lengthSquared() < 0.01) {
                away = new Vec3d(1, 0, 0);
            }
            away = away.normalize().multiply(5);
            villager.moveTowards(BlockPos.ofFloored(villager.getPos().add(away)), 1.1F, 0);
            return;
        }

        villager.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(new EntityLookTarget(threat, false), 1.1F, 1));
        if (villager.squaredDistanceTo(threat) > 6.25F) {
            return;
        }
        if (attackCooldown-- > 0) {
            return;
        }
        attackCooldown = 10;
        villager.swingHand(villager.getDominantHand());
        float damage = (float) villager.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        if (damage < 1.0F) {
            damage = 3.0F;
        }
        if (villager.getAgeState().ordinal() < AgeState.ADULT.ordinal()) {
            damage *= 0.65F;
        }
        threat.damage(world.getDamageSources().mobAttack(villager), damage);
        ItemStack weapon = villager.getStackInHand(villager.getDominantHand());
        if (!weapon.isEmpty()) {
            weapon.damage(1, villager, e -> e.sendEquipmentBreakStatus(villager.getDominantSlot()));
        }
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
    }

    private void equipWeapon(VillagerEntityMCA villager) {
        ItemStack held = villager.getStackInHand(villager.getDominantHand());
        if (WorkTools.isWeapon(held)) {
            return;
        }
        int slot = InventoryUtils.getFirstSlotContainingItem(villager.getInventory(), WorkTools::isWeapon);
        if (slot >= 0) {
            villager.setStackInHand(villager.getDominantHand(), villager.getInventory().getStack(slot));
            return;
        }
        if (!warnedNoWeapon) {
            warnedNoWeapon = true;
            getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, "chore.escort.noweapon"));
        }
    }
}
