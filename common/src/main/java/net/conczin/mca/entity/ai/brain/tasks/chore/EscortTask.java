package net.conczin.mca.entity.ai.brain.tasks.chore;

import com.google.common.collect.ImmutableMap;
import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.entity.ai.Chore;
import net.conczin.mca.entity.ai.DistressCalls;
import net.conczin.mca.entity.ai.Threats;
import net.conczin.mca.entity.ai.relationship.AgeState;
import net.conczin.mca.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.phys.Vec3;

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
    private Mob focus;

    public EscortTask() {
        super(ImmutableMap.of(
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 12000);
    }

    @Override
    protected boolean allowsWorkWhileAfraid() {
        return true;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel world, VillagerEntityMCA villager) {
        return villager.getVillagerBrain().getCurrentJob() == Chore.ESCORT;
    }

    @Override
    protected boolean canStillUse(ServerLevel world, VillagerEntityMCA villager, long time) {
        return checkExtraStartConditions(world, villager);
    }

    @Override
    protected void start(ServerLevel world, VillagerEntityMCA villager, long time) {
        super.start(world, villager, time);
        if (villager.getAgeState().ordinal() <= AgeState.TODDLER.ordinal()) {
            abandonJobWithMessage("chore.escort.tooyoung");
            return;
        }
        equipWeapon(villager);
    }

    @Override
    protected void stop(ServerLevel world, VillagerEntityMCA villager, long time) {
        ItemStack stack = villager.getItemInHand(villager.getDominantHand());
        if (!stack.isEmpty()) {
            villager.setItemInHand(villager.getDominantHand(), ItemStack.EMPTY);
        }
        villager.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
    }

    @Override
    protected void tick(ServerLevel world, VillagerEntityMCA villager, long time) {
        super.tick(world, villager, time);
        if (this.villager == null) {
            this.villager = villager;
        }
        if (villager.getVillagerBrain().getCurrentJob() != Chore.ESCORT) {
            return;
        }
        Player player = getAssigningPlayer().orElse(null);
        if (player == null) {
            return;
        }
        equipWeapon(villager);

        boolean playerSafe = Threats.nearest(world, player.blockPosition(), 14, villager, player) == null;
        Mob threat = Threats.nearest(world, player.blockPosition(), 16, villager, player);
        if (threat == null) {
            threat = Threats.nearest(world, villager.blockPosition(), 8, villager, player);
        }

        if (threat == null && playerSafe) {
            DistressCalls.Call call = DistressCalls.nearestForAssigner(world, player.getUUID(), villager.blockPosition(), 96).orElse(null);
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
        villager.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        holdFormation(villager, player);
    }

    private boolean shouldAnswer(ServerLevel world, VillagerEntityMCA villager, Player player, DistressCalls.Call call) {
        if (call.caller().equals(villager.getUUID())) {
            return false;
        }
        double mine = villager.distanceToSqr(call.pos().getX() + 0.5, call.pos().getY(), call.pos().getZ() + 0.5);
        for (VillagerEntityMCA other : world.getEntitiesOfClass(VillagerEntityMCA.class, player.getBoundingBox().inflate(64))) {
            if (other == villager || other.getVillagerBrain().getCurrentJob() != Chore.ESCORT) {
                continue;
            }
            if (other.distanceToSqr(call.pos().getX() + 0.5, call.pos().getY(), call.pos().getZ() + 0.5) < mine) {
                return false;
            }
        }
        return true;
    }

    private void answer(ServerLevel world, VillagerEntityMCA villager, Player player, DistressCalls.Call call) {
        if (!call.caller().equals(answeringCaller)) {
            answeringCaller = call.caller();
            villager.sendChatMessage(player, "chore.escort.answering", call.name());
        }
        Mob threat = Threats.nearest(world, call.pos(), 12, villager, player);
        if (threat != null) {
            fight(world, villager, player, threat);
            return;
        }
        double distance = villager.distanceToSqr(call.pos().getX() + 0.5, call.pos().getY(), call.pos().getZ() + 0.5);
        if (distance > 32 * 32) {
            stuckTicks++;
            if (stuckTicks > 80) {
                villager.teleportTo(call.pos().getX() + 0.5, call.pos().getY(), call.pos().getZ() + 0.5);
                villager.resetFallDistance();
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(call.pos(), 1.05F, 2));
    }

    private void fight(ServerLevel world, VillagerEntityMCA villager, Player player, Mob threat) {
        if (focus != threat) {
            focus = threat;
            villager.sendChatMessage(player, "chore.escort.engaging");
        }
        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(threat, true));
        villager.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, threat);

        if (threat instanceof Creeper && villager.distanceToSqr(threat) < 16) {
            Vec3 away = villager.position().subtract(threat.position());
            if (away.lengthSqr() < 0.01) {
                away = new Vec3(1, 0, 0);
            }
            away = away.normalize().scale(5);
            villager.moveTowards(BlockPos.containing(villager.position().add(away)), 1.1F, 0);
            return;
        }

        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(new EntityTracker(threat, false), 1.1F, 1));
        if (villager.distanceToSqr(threat) > 6.25F) {
            return;
        }
        if (attackCooldown-- > 0) {
            return;
        }
        attackCooldown = 10;
        villager.swing(villager.getDominantHand());
        float damage = (float) villager.getAttributeValue(Attributes.ATTACK_DAMAGE);
        if (damage < 1.0F) {
            damage = 3.0F;
        }
        if (villager.getAgeState().ordinal() < AgeState.ADULT.ordinal()) {
            damage *= 0.65F;
        }
        threat.hurt(world.damageSources().mobAttack(villager), damage);
        ItemStack weapon = villager.getItemInHand(villager.getDominantHand());
        if (!weapon.isEmpty()) {
            weapon.hurtAndBreak(1, villager, villager.getDominantSlot());
        }
    }

    private void holdFormation(VillagerEntityMCA villager, Player player) {
        int slot = Math.floorMod(villager.getUUID().hashCode(), 8);
        double angle = (Math.PI * 2.0 * slot) / 8.0;
        double radius = 2.15 + (slot % 2) * 0.45;
        double x = player.getX() + Math.cos(angle) * radius;
        double z = player.getZ() + Math.sin(angle) * radius;
        double distance = villager.distanceTo(player);
        if (distance > 40) {
            stuckTicks++;
            if (stuckTicks > 40) {
                villager.teleportTo(x, player.getY(), z);
                villager.resetFallDistance();
                stuckTicks = 0;
                return;
            }
        } else {
            stuckTicks = 0;
        }
        float speed = distance > 10 ? 1.15F : (distance > 4 ? 0.9F : 0.65F);
        villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(player, true));
        villager.moveTowards(BlockPos.containing(x, player.getY(), z), speed, 1);
    }

    private void equipWeapon(VillagerEntityMCA villager) {
        ItemStack held = villager.getItemInHand(villager.getDominantHand());
        if (held.getItem() instanceof SwordItem || held.getItem() instanceof AxeItem) {
            return;
        }
        int sword = InventoryUtils.getFirstSlotContainingItem(villager.getInventory(), stack -> stack.getItem() instanceof SwordItem);
        int axe = InventoryUtils.getFirstSlotContainingItem(villager.getInventory(), stack -> stack.getItem() instanceof AxeItem);
        int slot = sword >= 0 ? sword : axe;
        if (slot >= 0) {
            villager.setItemInHand(villager.getDominantHand(), villager.getInventory().getItem(slot));
            return;
        }
        if (!warnedNoWeapon) {
            warnedNoWeapon = true;
            getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, "chore.escort.noweapon"));
        }
    }
}
