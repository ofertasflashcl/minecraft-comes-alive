package net.mca.entity.ai.body;

import com.google.common.collect.Multimap;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Threats;
import net.mca.entity.ai.WorkTools;
import net.mca.entity.ai.relationship.AgeState;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.mca.util.InventoryUtils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.FlyingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.VexEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.item.AxeItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * How a villager fights, for followers, escorts, rescuers and anyone defending themselves.
 * <p>
 * It picks the weapon a player would: the bow or crossbow when the enemy is far, flies, cannot be reached on
 * foot or is a creeper; the sword or axe up close. Melee hits land only within a player's 3 block reach and
 * follow the weapon's real attack speed (a sword every 13 ticks, an axe every 20). Ranged shots use
 * {@link Archery}. Friends, the person being protected and anyone tamed are never targets and never in the
 * line of fire. Everything starts from what the eye saw: a target is engaged only while seen, and looked for
 * where it was last seen when it slips away.
 */
public final class CombatMind {
    public enum Style {MELEE, RANGED, FISTS}

    private static final Map<VillagerEntityMCA, State> STATES = new WeakHashMap<>();
    /** Within this of its target it counts as getting somewhere, whatever happened before. */
    private static final double ENTITY_REACH_FOR_PROGRESS = Retina.ENTITY_REACH;

    private static final class State {
        Style style = Style.FISTS;
        int cooldown;
        int draw;
        @Nullable
        Entity target;
        int strafe = 1;
        int strafeTicks;
        long lastFight = -1000;
        int lastSeenTick;
        boolean reachable = true;
        int reachTick = -1000;
        @Nullable
        Entity reachFor;
        /** Closest it has got to the current target, and when it last got closer. */
        double closest = Double.MAX_VALUE;
        int closerTick;
        /** Enemies it could not get at, left alone until the tick stored (unless they come close or hit it). */
        final Map<Integer, Long> unreachable = new HashMap<>();
    }

    private CombatMind() {
    }

    private static State state(VillagerEntityMCA villager) {
        return STATES.computeIfAbsent(villager, v -> new State());
    }

    /**
     * Ticks it has been chasing its target without getting any closer while out of reach: a wall, a gap or a
     * hole is in between and its legs alone will not get it there. 0 while it gains ground or hits.
     */
    public static int stuck(VillagerEntityMCA villager) {
        State state = STATES.get(villager);
        if (state == null || state.target == null) {
            return 0;
        }
        return Math.max(0, villager.age - state.closerTick);
    }

    /** It cannot get at this one: no chasing it for {@code ticks}, unless it comes within reach or hurts it. */
    public static void giveUp(VillagerEntityMCA villager, Entity target, int ticks) {
        State state = state(villager);
        state.unreachable.put(target.getId(), villager.getWorld().getTime() + ticks);
        if (state.target == target) {
            disengage(villager);
        }
    }

    /** True while an enemy it gave up on is left alone: far enough, and not the one that just hurt it. */
    public static boolean ignoring(VillagerEntityMCA villager, Entity target) {
        State state = STATES.get(villager);
        if (state == null) {
            return false;
        }
        Long until = state.unreachable.get(target.getId());
        if (until == null) {
            return false;
        }
        long now = villager.getWorld().getTime();
        if (until < now) {
            state.unreachable.remove(target.getId());
            return false;
        }
        boolean hurtBy = villager.getAttacker() == target && villager.age - villager.getLastAttackedTime() < 40;
        return !hurtBy && target.squaredDistanceTo(villager) > 2.5 * 2.5;
    }

    /** Whether this villager fought during the last few seconds (its hands belong to this class then). */
    public static boolean busy(VillagerEntityMCA villager) {
        State state = STATES.get(villager);
        return state != null && villager.getWorld().getTime() - state.lastFight < 100;
    }

    public static boolean isFriend(Entity entity, @Nullable LivingEntity ward) {
        if (entity == null) {
            return true;
        }
        if (entity == ward || entity instanceof PlayerEntity || entity instanceof VillagerEntity || entity instanceof IronGolemEntity || entity instanceof ArmorStandEntity) {
            return true;
        }
        return entity instanceof TameableEntity tame && tame.isTamed();
    }

    /**
     * The enemy to deal with now: whatever is hurting the ward or this villager, then what the ward is fighting,
     * then the nearest threat the eye can see.
     */
    @Nullable
    public static LivingEntity pickTarget(ServerWorld world, VillagerEntityMCA villager, @Nullable LivingEntity ward, double radius) {
        State state = state(villager);
        if (state.target instanceof LivingEntity current && current.isAlive() && !current.isRemoved() && current.getWorld() == world
                && current.squaredDistanceTo(villager) < (radius + 8) * (radius + 8) && Retina.canSee(world, villager, current)
                && !ignoring(villager, current)) {
            return current;
        }
        if (ward != null) {
            LivingEntity attacker = ward.getAttacker();
            if (valid(world, villager, ward, attacker, radius)) {
                return attacker;
            }
            LivingEntity attacking = ward.getAttacking();
            if (attacking != null && ward.age - ward.getLastAttackTime() < 100 && valid(world, villager, ward, attacking, radius)) {
                return attacking;
            }
        }
        LivingEntity own = villager.getAttacker();
        if (valid(world, villager, ward, own, radius)) {
            return own;
        }
        PlayerEntity player = ward instanceof PlayerEntity p ? p : null;
        BlockPos around = ward != null ? ward.getBlockPos() : villager.getBlockPos();
        MobEntity threat = Threats.nearest(world, around, radius, villager, player);
        if (threat == null && ward != null) {
            threat = Threats.nearest(world, villager.getBlockPos(), hasRanged(villager) ? radius : Math.min(8, radius), villager, player);
        }
        return threat != null && ignoring(villager, threat) ? null : threat;
    }

    private static boolean valid(ServerWorld world, VillagerEntityMCA villager, @Nullable LivingEntity ward, @Nullable LivingEntity target, double radius) {
        return target != null && target.isAlive() && !target.isRemoved() && target.getWorld() == world
                && !isFriend(target, ward) && target != villager && !ignoring(villager, target)
                && target.squaredDistanceTo(villager) < (radius + 8) * (radius + 8)
                && Retina.canSee(world, villager, target);
    }

    /** Drops the fight: lowers the bow and forgets the target. */
    public static void disengage(VillagerEntityMCA villager) {
        State state = STATES.get(villager);
        if (state != null) {
            state.target = null;
            state.draw = 0;
        }
        if (villager.isUsingItem()) {
            villager.clearActiveItem();
        }
        villager.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
    }

    public static boolean hasRanged(VillagerEntityMCA villager) {
        return Archery.isRanged(villager.getMainHandStack()) || Hands.findSlot(villager.getInventory(), Archery::isRanged) >= 0;
    }

    public static boolean hasMelee(VillagerEntityMCA villager) {
        return WorkTools.isWeapon(villager.getMainHandStack()) || Hands.findSlot(villager.getInventory(), WorkTools::isWeapon) >= 0;
    }

    private static boolean flies(Entity target) {
        return target instanceof FlyingEntity || target instanceof BlazeEntity || target instanceof VexEntity || !target.isOnGround() && !target.isTouchingWater() && target.fallDistance == 0 && target.getVelocity().y > -0.1 && target.getY() - target.getWorld().getBottomY() > 0 && airBelow(target);
    }

    private static boolean airBelow(Entity target) {
        BlockPos below = target.getBlockPos();
        for (int i = 0; i < 3; i++) {
            below = below.down();
            if (!Mining.passable(target.getWorld(), below)) {
                return false;
            }
        }
        return true;
    }

    private static boolean reachableOnFoot(VillagerEntityMCA villager, Entity target) {
        if (flies(target)) {
            return false;
        }
        State state = state(villager);
        if (state.reachFor == target && villager.age - state.reachTick < 20) {
            return state.reachable;
        }
        state.reachFor = target;
        state.reachTick = villager.age;
        if (Math.abs(target.getY() - villager.getY()) <= 1.6 && villager.squaredDistanceTo(target) < 9) {
            state.reachable = true;
        } else {
            var path = villager.getNavigation().findPathTo(target, 1);
            state.reachable = path != null && path.reachesTarget();
        }
        return state.reachable;
    }

    /** Ticks between full-strength hits, from the weapon's attack speed like a player's cooldown bar. */
    public static int cooldownTicks(ItemStack weapon) {
        double speed = 4.0;
        Multimap<EntityAttribute, EntityAttributeModifier> modifiers = weapon.getAttributeModifiers(EquipmentSlot.MAINHAND);
        for (EntityAttributeModifier modifier : modifiers.get(EntityAttributes.GENERIC_ATTACK_SPEED)) {
            if (modifier.getOperation() == EntityAttributeModifier.Operation.ADDITION) {
                speed += modifier.getValue();
            }
        }
        if (speed <= 0.1) {
            speed = 0.1;
        }
        return Math.max(4, (int) Math.ceil(20.0 / speed));
    }

    private static double meleeScore(ItemStack stack) {
        double damage = 0;
        for (EntityAttributeModifier modifier : stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(EntityAttributes.GENERIC_ATTACK_DAMAGE)) {
            damage += modifier.getValue();
        }
        double perSecond = damage * 20.0 / cooldownTicks(stack);
        return perSecond + (stack.getItem() instanceof SwordItem ? 0.5 : 0) + (stack.getItem() instanceof AxeItem ? 0.2 : 0);
    }

    /** The strongest melee weapon in the bag goes to the main hand. */
    private static boolean holdBestMelee(VillagerEntityMCA villager) {
        ItemStack held = villager.getMainHandStack();
        int best = -1;
        double bestScore = WorkTools.isWeapon(held) ? meleeScore(held) : -1;
        for (int i = 0; i < villager.getInventory().size(); i++) {
            ItemStack stack = villager.getInventory().getStack(i);
            if (stack != held && WorkTools.isWeapon(stack)) {
                double score = meleeScore(stack);
                if (score > bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
        }
        if (best >= 0) {
            ItemStack chosen = villager.getInventory().getStack(best);
            return InventoryUtils.holdMain(villager, s -> s == chosen);
        }
        return WorkTools.isWeapon(held);
    }

    private static boolean holdRanged(VillagerEntityMCA villager) {
        return InventoryUtils.holdMain(villager, Archery::isRanged);
    }

    /**
     * One tick of fighting {@code target}. {@code ward} is who is being protected (may be null).
     * Returns false when the villager decided to back off instead.
     */
    public static boolean fight(ServerWorld world, VillagerEntityMCA villager, @Nullable LivingEntity ward, LivingEntity target) {
        State state = state(villager);
        long now = world.getTime();
        state.lastFight = now;
        if (state.target != target) {
            state.target = target;
            state.closest = Double.MAX_VALUE;
            state.closerTick = villager.age;
            state.draw = 0;
            if (villager.isUsingItem()) {
                villager.clearActiveItem();
            }
        }
        if (state.cooldown > 0) {
            state.cooldown--;
        }
        villager.getBrain().remember(MemoryModuleType.ATTACK_TARGET, target);
        villager.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(target, true));
        VisualMemory memory = Retina.memory(villager);
        boolean seen = Retina.canSee(world, villager, target);
        if (seen) {
            state.lastSeenTick = villager.age;
        }

        double distance = Retina.distanceToBox(villager.getEyePos(), target.getBoundingBox());
        if (distance < state.closest - 0.5 || distance <= ENTITY_REACH_FOR_PROGRESS) {
            state.closest = Math.min(state.closest, distance);
            state.closerTick = villager.age;
        }
        // a bow with arrows always wins: an archer keeps its distance and never walks up to hit
        boolean ranged = hasRanged(villager) && Archery.hasAmmo(villager);
        boolean melee = hasMelee(villager);

        // children and the badly hurt step away; nobody stands next to a hissing creeper. Armed with a blade it only
        // runs when it is really low, and not from an enemy that is one hit from dying
        boolean child = villager.getAgeState().ordinal() < AgeState.TEEN.ordinal();
        boolean bladed = hasMelee(villager);
        float low = bladed ? 0.2F : 0.3F;
        boolean almostDead = target.getHealth() <= 4.0F && villager.getHealth() >= villager.getMaxHealth() * 0.12F;
        boolean hurt = villager.getHealth() < villager.getMaxHealth() * low && !almostDead;
        boolean creeperClose = target instanceof CreeperEntity && distance < 4.5;
        if (child || hurt && !ranged || creeperClose) {
            memory.doing("alejándose de " + target.getName().getString(), now);
            backOff(villager, target, ward, 6, 1.15);
            if (creeperClose && ranged && !child) {
                holdRanged(villager);
                shoot(world, villager, ward, target, state, seen);
            }
            return false;
        }

        Style style;
        if (ranged) {
            style = Style.RANGED;
        } else if (melee) {
            style = Style.MELEE;
        } else {
            style = Style.FISTS;
        }
        if (state.style != style) {
            state.draw = 0;
            if (villager.isUsingItem()) {
                villager.clearActiveItem();
            }
        }
        state.style = style;

        if (style == Style.RANGED) {
            holdRanged(villager);
            memory.doing("disparando a " + target.getName().getString() + String.format(" a %.1f bloques", distance), now);
            keepRange(villager, target, ward, state, distance, seen);
            shoot(world, villager, ward, target, state, seen);
            return true;
        }

        if (style == Style.MELEE) {
            holdBestMelee(villager);
        }
        // a shield goes in the off hand; it is raised while closing in on anything that shoots
        boolean shooter = shoots(target);
        boolean shield = style != Style.RANGED && equipShield(villager);
        if (distance > Retina.ENTITY_REACH && villager.age - state.closerTick > 200) {
            // ten seconds and not a step closer: it cannot get there on foot, so it stops staring at it
            memory.doing("no llega a " + target.getName().getString(), now);
            villager.getNavigation().stop();
            giveUp(villager, target, 1200);
            return false;
        }
        memory.doing("peleando con " + target.getName().getString() + String.format(" a %.1f bloques", distance), now);
        if (distance > Retina.ENTITY_REACH - 0.4 || !seen) {
            // closing in: sprinting like a player, and against an archer weaving left and right so its arrows miss
            villager.setSprinting(distance > 3.0);
            if (shooter && seen && distance > 3.0 && villager.isOnGround()) {
                if (--state.strafeTicks <= 0) {
                    state.strafe = -state.strafe;
                    state.strafeTicks = 8 + villager.getRandom().nextInt(8);
                }
                Vec3d to = target.getPos().subtract(villager.getPos()).multiply(1, 0, 1);
                if (to.lengthSquared() > 1.0E-4) {
                    Vec3d side = new Vec3d(-to.z, 0, to.x).normalize().multiply(1.6 * state.strafe);
                    Vec3d aim = villager.getPos().add(to.normalize().multiply(2.5)).add(side);
                    villager.getNavigation().stop();
                    villager.getMoveControl().moveTo(aim.x, villager.getY(), aim.z, 1.25);
                }
            } else if ((villager.age & 3) == 0 || villager.getNavigation().isIdle()) {
                villager.getNavigation().startMovingTo(target, shooter ? 1.25 : 1.1);
            }
            if (shield && shooter && seen && !villager.isUsingItem()) {
                villager.setCurrentHand(Hand.OFF_HAND); // shield up while it walks into the arrows
            }
        } else {
            villager.setSprinting(false);
            villager.getNavigation().stop();
            Vec3d center = target.getBoundingBox().getCenter();
            villager.getMoveControl().moveTo(center.x, target.getY(), center.z, 0.6);
        }
        if (seen && distance <= Retina.ENTITY_REACH && state.cooldown <= 0 && villager.isUsingItem() && villager.getActiveHand() == Hand.OFF_HAND) {
            villager.clearActiveItem(); // the shield comes down to strike
        }
        if (seen && distance <= Retina.ENTITY_REACH && state.cooldown <= 0) {
            Hands.aim(villager, target.getBoundingBox().getCenter());
            villager.swingHand(Hand.MAIN_HAND);
            villager.tryAttack(target);
            ItemStack weapon = villager.getMainHandStack();
            if (!weapon.isEmpty()) {
                weapon.getItem().postHit(weapon, target, villager);
            }
            state.cooldown = cooldownTicks(weapon);
        }
        return true;
    }

    /** Skeletons, strays, pillagers, blazes...: anything that hurts from afar. */
    private static boolean shoots(Entity target) {
        return target instanceof net.minecraft.entity.ai.RangedAttackMob || target instanceof net.minecraft.entity.mob.AbstractSkeletonEntity
                || target instanceof BlazeEntity;
    }

    /** Puts a shield from the bag in the off hand (what was there goes back in the bag). True when it holds one. */
    private static boolean equipShield(VillagerEntityMCA villager) {
        if (villager.getOffHandStack().getItem() instanceof net.minecraft.item.ShieldItem) {
            return true;
        }
        var inv = villager.getInventory();
        int slot = Hands.findSlot(inv, s -> s.getItem() instanceof net.minecraft.item.ShieldItem);
        if (slot < 0) {
            return false;
        }
        ItemStack shield = inv.removeStack(slot, 1);
        ItemStack old = villager.getOffHandStack();
        villager.equipStack(EquipmentSlot.OFFHAND, shield);
        if (!old.isEmpty()) {
            ItemStack rest = inv.addStack(old);
            if (!rest.isEmpty()) {
                villager.dropStack(rest);
            }
        }
        return true;
    }

    private static void shoot(ServerWorld world, VillagerEntityMCA villager, @Nullable LivingEntity ward, LivingEntity target, State state, boolean seen) {
        ItemStack weapon = villager.getMainHandStack();
        if (!Archery.isRanged(weapon)) {
            return;
        }
        boolean crossbow = weapon.getItem() instanceof CrossbowItem;
        int needed = crossbow ? Math.max(5, Archery.CROSSBOW_CHARGE_TICKS - 5 * EnchantmentHelper.getLevel(Enchantments.QUICK_CHARGE, weapon)) : Archery.BOW_DRAW_TICKS;
        if (!crossbow && !villager.isUsingItem()) {
            villager.setCurrentHand(Hand.MAIN_HAND);
        }
        if (state.draw < needed) {
            state.draw++;
        }
        float speed = crossbow ? Archery.CROSSBOW_SPEED : Archery.BOW_SPEED;
        Archery.Solution solution = Archery.lead(villager, target, speed);
        if (solution == null) {
            return;
        }
        Hands.aim(villager, Archery.muzzle(villager).add(solution.direction().multiply(8)));
        if (!seen || state.draw < needed) {
            return;
        }
        if (!Archery.clearShot(world, villager, target, solution, speed, e -> isFriend(e, ward))) {
            // someone or something in the way: sidestep to open the line
            state.strafeTicks = 20;
            return;
        }
        Archery.fire(world, villager, weapon, solution, 1.0F);
        state.draw = 0;
        if (villager.isUsingItem()) {
            villager.clearActiveItem();
        }
    }

    /**
     * Where an archer stands: back off to a good range first (never closer than 8 blocks), close in when the
     * target is out of sight or past 18, strafe in between. Drawing the bow slows it down the way it slows a
     * player, but it keeps moving.
     */
    private static void keepRange(VillagerEntityMCA villager, LivingEntity target, @Nullable LivingEntity ward, State state, double distance, boolean seen) {
        boolean drawing = villager.isUsingItem() || state.draw > 0;
        double slow = drawing ? 0.5 : 1.0;
        if (!seen || distance > 18.0) {
            if ((villager.age & 3) == 0 || villager.getNavigation().isIdle()) {
                villager.getNavigation().startMovingTo(target, 1.0 * slow);
            }
            return;
        }
        if (distance < 8.0) {
            if (state.strafeTicks > 0) {
                // something in the line of fire: back away sideways to open it
                state.strafeTicks--;
                villager.getNavigation().stop();
                villager.getMoveControl().strafeTo((float) (-0.7 * slow), state.strafe * (float) (0.7 * slow));
                return;
            }
            backOff(villager, target, ward, 5, 1.15 * slow);
            return;
        }
        villager.getNavigation().stop();
        if (state.strafeTicks > 0) {
            state.strafeTicks--;
            if (state.strafeTicks == 0) {
                state.strafe = -state.strafe;
            }
        } else if ((villager.age % 40) == 0) {
            state.strafe = -state.strafe;
        }
        villager.getMoveControl().strafeTo(0.0F, state.strafe * (float) (0.5 * slow));
    }

    private static void backOff(VillagerEntityMCA villager, Entity threat, @Nullable LivingEntity ward, double amount, double speed) {
        Vec3d away = villager.getPos().subtract(threat.getPos());
        if (away.lengthSquared() < 0.01) {
            away = new Vec3d(1, 0, 0);
        }
        Vec3d goal = villager.getPos().add(away.normalize().multiply(amount));
        if (ward != null && ward.squaredDistanceTo(goal) < ward.squaredDistanceTo(threat)) {
            // behind the ward is safest
            // behind the ward, but off to one side so the ward is never in the line of fire
            Vec3d behind = ward.getPos().subtract(threat.getPos()).multiply(1, 0, 1);
            if (behind.lengthSquared() > 0.01) {
                Vec3d back = behind.normalize();
                Vec3d side = new Vec3d(-back.z, 0, back.x);
                if (side.dotProduct(villager.getPos().subtract(ward.getPos())) < 0) {
                    side = side.multiply(-1);
                }
                goal = ward.getPos().add(back.multiply(1.5)).add(side.multiply(2.5));
            }
        }
        villager.getNavigation().startMovingTo(goal.x, goal.y, goal.z, speed);
    }
}
