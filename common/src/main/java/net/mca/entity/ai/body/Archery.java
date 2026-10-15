package net.mca.entity.ai.body;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ArrowItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * Shooting the way the arrow really flies. The launch angle is solved against the exact arrow physics
 * (it moves, then loses 1% speed, then falls 0.05 blocks per tick), the target's motion is led by the
 * flight time, and the path is checked block by block and body by body before letting go, so a
 * friend in the way stops the shot. Spread is a player's (divergence 1), far tighter than a skeleton's.
 */
public final class Archery {
    public static final double GRAVITY = 0.05;
    public static final double DRAG = 0.99;
    public static final float BOW_SPEED = 3.0F;
    public static final float CROSSBOW_SPEED = 3.15F;
    public static final float DIVERGENCE = 1.0F;
    public static final int BOW_DRAW_TICKS = 20;
    public static final int CROSSBOW_CHARGE_TICKS = 25;
    /** Beyond this the arrow drops too much to aim reliably. */
    public static final double MAX_RANGE = 48.0;

    private Archery() {
    }

    public record Solution(Vec3d direction, double flightTicks) {
    }

    public static boolean isRanged(ItemStack stack) {
        return stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem;
    }

    public static float speedOf(ItemStack weapon) {
        return weapon.getItem() instanceof CrossbowItem ? CROSSBOW_SPEED : BOW_SPEED;
    }

    /** Where the arrow is spawned, as in {@code PersistentProjectileEntity(type, owner, world)}. */
    public static Vec3d muzzle(LivingEntity shooter) {
        return new Vec3d(shooter.getX(), shooter.getEyeY() - 0.1, shooter.getZ());
    }

    /**
     * The launch direction (low arc) that puts an arrow fired at {@code speed} from {@code from} through
     * {@code target}. Null if that is out of range.
     */
    @Nullable
    public static Solution solve(Vec3d from, Vec3d target, double speed) {
        double dx = target.x - from.x;
        double dz = target.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = target.y - from.y;
        if (horizontal < 0.25) {
            Vec3d straight = target.subtract(from);
            return straight.lengthSquared() < 1.0E-6 ? null : new Solution(straight.normalize(), straight.length() / speed);
        }
        double low = -1.45;
        double high = 0.78;
        double[] top = flight(high, speed, horizontal);
        if (top[0] < dy) {
            return null;
        }
        double[] bottom = flight(low, speed, horizontal);
        if (bottom[0] > dy) {
            low = -1.55;
        }
        double angle = high;
        double ticks = top[1];
        for (int i = 0; i < 48; i++) {
            double mid = (low + high) * 0.5;
            double[] at = flight(mid, speed, horizontal);
            if (at[0] < dy) {
                low = mid;
            } else {
                high = mid;
            }
            angle = mid;
            ticks = at[1];
        }
        double cos = Math.cos(angle);
        Vec3d direction = new Vec3d(dx / horizontal * cos, Math.sin(angle), dz / horizontal * cos);
        return new Solution(direction, ticks);
    }

    /** Height of the arrow when it has covered {@code horizontal} blocks, and the ticks it took. */
    static double[] flight(double angle, double speed, double horizontal) {
        double vx = Math.cos(angle) * speed;
        double vy = Math.sin(angle) * speed;
        double x = 0;
        double y = 0;
        for (int t = 0; t < 400; t++) {
            double nx = x + vx;
            double ny = y + vy;
            if (nx >= horizontal) {
                double f = (horizontal - x) / Math.max(1.0E-9, nx - x);
                return new double[]{y + (ny - y) * f, t + f};
            }
            x = nx;
            y = ny;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            if (vx < 1.0E-3) {
                break;
            }
        }
        return new double[]{Double.NEGATIVE_INFINITY, 400};
    }

    /** Aim point on the target with its motion led by the flight time. */
    @Nullable
    public static Solution lead(LivingEntity shooter, Entity target, double speed) {
        Vec3d from = muzzle(shooter);
        Vec3d center = target.getBoundingBox().getCenter();
        Vec3d velocity = new Vec3d(target.getX() - target.prevX, target.getY() - target.prevY, target.getZ() - target.prevZ);
        if (target.isOnGround()) {
            velocity = new Vec3d(velocity.x, 0, velocity.z);
        }
        Solution solution = solve(from, center, speed);
        for (int i = 0; i < 3 && solution != null; i++) {
            solution = solve(from, center.add(velocity.multiply(solution.flightTicks())), speed);
        }
        return solution;
    }

    /**
     * Whether an arrow launched along {@code solution} reaches the target before any block or any body
     * the shooter must not hit.
     */
    public static boolean clearShot(ServerWorld world, LivingEntity shooter, Entity target, Solution solution, double speed, Predicate<Entity> spare) {
        Vec3d position = muzzle(shooter);
        Vec3d velocity = solution.direction().multiply(speed);
        double reach = Math.max(2.0, solution.flightTicks() + 2);
        for (int t = 0; t < reach; t++) {
            Vec3d next = position.add(velocity);
            BlockHitResult block = world.raycast(new RaycastContext(position, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, shooter));
            Vec3d stop = block.getType() == HitResult.Type.MISS ? next : block.getPos();
            Box sweep = new Box(position, stop).expand(0.6);
            if (target.getBoundingBox().expand(0.3).raycast(position, stop).isPresent()) {
                return true;
            }
            for (Entity other : world.getOtherEntities(shooter, sweep, e -> e != target && e.isAlive() && e.canHit())) {
                if (other.getBoundingBox().expand(0.3).raycast(position, stop).isPresent() && spare.test(other)) {
                    return false;
                }
            }
            if (block.getType() != HitResult.Type.MISS) {
                return false;
            }
            position = next;
            velocity = new Vec3d(velocity.x * DRAG, velocity.y * DRAG - GRAVITY, velocity.z * DRAG);
        }
        return false;
    }

    /** Arrows in the bag. */
    private static int arrowSlot(VillagerEntityMCA shooter) {
        return Hands.findSlot(shooter.getInventory(), s -> s.getItem() instanceof ArrowItem);
    }

    /** Guards and archers draw from the village armoury, as MCA's always did; everyone else needs real arrows. */
    public static boolean armoury(VillagerEntityMCA shooter) {
        var profession = shooter.getVillagerData().getProfession();
        return profession == net.mca.ProfessionsMCA.GUARD.get() || profession == net.mca.ProfessionsMCA.ARCHER.get();
    }

    /** Something to shoot: arrows in the bag, or the armoury. */
    public static boolean hasAmmo(VillagerEntityMCA shooter) {
        return arrowSlot(shooter) >= 0 || armoury(shooter);
    }

    /** Lets an arrow go along {@code solution}. {@code pull} is the bow draw, 0 to 1. */
    public static void fire(ServerWorld world, VillagerEntityMCA shooter, ItemStack weapon, Solution solution, float pull) {
        boolean crossbow = weapon.getItem() instanceof CrossbowItem;
        int slot = arrowSlot(shooter);
        if (slot < 0 && !armoury(shooter)) {
            return; // no arrow, no shot
        }
        ItemStack ammo = slot >= 0 ? shooter.getInventory().getStack(slot) : new ItemStack(Items.ARROW);
        boolean infinite = slot < 0 || !crossbow && ammo.isOf(Items.ARROW) && EnchantmentHelper.getLevel(Enchantments.INFINITY, weapon) > 0;

        PersistentProjectileEntity arrow = ProjectileUtil.createArrowProjectile(shooter, ammo, crossbow ? 1.0F : pull);
        float speed = (crossbow ? CROSSBOW_SPEED : BOW_SPEED) * (crossbow ? 1.0F : Math.max(0.1F, pull));
        arrow.setVelocity(solution.direction().x, solution.direction().y, solution.direction().z, speed, DIVERGENCE);
        if (crossbow) {
            arrow.setShotFromCrossbow(true);
            arrow.setSound(SoundEvents.ITEM_CROSSBOW_HIT);
            int piercing = EnchantmentHelper.getLevel(Enchantments.PIERCING, weapon);
            if (piercing > 0) {
                arrow.setPierceLevel((byte) piercing);
            }
        } else if (pull >= 1.0F) {
            arrow.setCritical(true);
        }
        arrow.pickupType = infinite ? PersistentProjectileEntity.PickupPermission.CREATIVE_ONLY : PersistentProjectileEntity.PickupPermission.ALLOWED;
        world.spawnEntity(arrow);
        if (crossbow) {
            world.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), SoundEvents.ITEM_CROSSBOW_SHOOT, SoundCategory.NEUTRAL, 1.0F, 1.0F);
        } else {
            world.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.NEUTRAL, 1.0F,
                    1.0F / (shooter.getRandom().nextFloat() * 0.4F + 1.2F) + pull * 0.5F);
        }
        weapon.damage(crossbow ? 1 : 1, shooter, e -> e.sendToolBreakStatus(Hand.MAIN_HAND));
        if (!infinite) {
            ammo.decrement(1);
        }
        SHOTS.record(shooter);
    }

    /** One shot aimed with the full solution, for archers driven by the vanilla ranged-attack hook. */
    public static boolean shootAt(ServerWorld world, VillagerEntityMCA shooter, LivingEntity target, float pull) {
        ItemStack weapon = shooter.getMainHandStack();
        if (!isRanged(weapon)) {
            return false;
        }
        float speed = (weapon.getItem() instanceof CrossbowItem ? CROSSBOW_SPEED : BOW_SPEED) * (weapon.getItem() instanceof CrossbowItem ? 1.0F : Math.max(0.1F, pull));
        Solution solution = lead(shooter, target, speed);
        if (solution == null) {
            return false;
        }
        Hands.aim(shooter, muzzle(shooter).add(solution.direction().multiply(8)));
        fire(world, shooter, weapon, solution, pull);
        return true;
    }

    /** Shot counter, used by the in-game checks to measure accuracy. */
    public static final class Shots {
        private final java.util.Map<java.util.UUID, Integer> count = new java.util.HashMap<>();

        void record(LivingEntity shooter) {
            count.merge(shooter.getUuid(), 1, Integer::sum);
        }

        public int of(LivingEntity shooter) {
            return count.getOrDefault(shooter.getUuid(), 0);
        }
    }

    public static final Shots SHOTS = new Shots();
}
