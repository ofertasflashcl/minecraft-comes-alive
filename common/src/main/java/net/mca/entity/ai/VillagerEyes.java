package net.mca.entity.ai;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.StainedGlassBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Memory of where bodies were last seen, and hearing. Seeing itself is done by
 * {@link net.mca.entity.ai.sense.Retina}: exact rays against real block shapes, a player's
 * field of view and reach. A body that slips out of view is looked for where it was last seen,
 * not through the wall. Someone sneaking is not heard.
 */
public final class VillagerEyes {
    public static final int GLIMPSE = 10;
    public static final int INTUITION = 120;
    public static final int FORGET_TRACK = 400;
    public static final int FORGET_AGE = 1800;
    public static final float FORGET_CONFIDENCE = 0.035F;
    public static final float DIRECT_DECAY = 0.0018F;
    public static final int ACTIVE_CADENCE = 3;
    public static final int STILL_CADENCE = 10;

    private static final Map<LivingEntity, Book> BOOKS = new WeakHashMap<>();

    private VillagerEyes() {
    }

    public static float fieldOfView(LivingEntity viewer) {
        if (viewer.hasStatusEffect(StatusEffects.BLINDNESS)) {
            return 40.0F;
        }
        return gene(viewer.getUuid(), 8, 70.0F, 160.0F);
    }

    public static int radius(LivingEntity viewer) {
        if (viewer.hasStatusEffect(StatusEffects.BLINDNESS)) {
            return 4;
        }
        return Math.round(gene(viewer.getUuid(), 9, 16.0F, 40.0F));
    }

    public static float nightVision(LivingEntity viewer) {
        if (viewer.hasStatusEffect(StatusEffects.BLINDNESS)) {
            return 0.0F;
        }
        if (viewer.hasStatusEffect(StatusEffects.NIGHT_VISION)) {
            return 1.0F;
        }
        return gene(viewer.getUuid(), 11, 0.0F, 1.0F);
    }

    /** Exact sight, see {@link net.mca.entity.ai.sense.Retina#canSee}. */
    public static boolean sees(ServerWorld world, LivingEntity viewer, Entity target) {
        if (target == null || target == viewer || !target.isAlive()) {
            return false;
        }
        return net.mca.entity.ai.sense.Retina.canSee(world, viewer, target);
    }

    /**
     * Footsteps nearby, not a name. Sneaking and silent bodies make none.
     * The spot is only a direction, smeared by distance.
     */
    public static boolean hears(LivingEntity viewer, Entity target) {
        if (target == null || target == viewer || !target.isAlive()) {
            return false;
        }
        if (target.isSneaking() || target.isSilent()) {
            return false;
        }
        if (target.distanceTo(viewer) > 10.0) {
            return false;
        }
        return target.getVelocity().horizontalLengthSquared() >= 0.002 || !target.isOnGround();
    }

    public static void remember(LivingEntity viewer, Entity target, long tick) {
        Book book = book(viewer);
        Spot spot = book.spots.computeIfAbsent(target.getId(), Spot::new);
        spot.x = target.getX();
        spot.feet = target.getY();
        spot.z = target.getZ();
        spot.lastSeen = tick;
        prune(book, tick);
    }

    @Nullable
    public static BlockPos searchWhere(ServerWorld world, LivingEntity viewer) {
        Book book = BOOKS.get(viewer);
        if (book == null) {
            return null;
        }
        long tick = world.getTime();
        Spot best = null;
        Iterator<Spot> it = book.spots.values().iterator();
        while (it.hasNext()) {
            Spot spot = it.next();
            long age = tick - spot.lastSeen;
            if (age > FORGET_TRACK) {
                it.remove();
                continue;
            }
            if (age <= 0 || age > INTUITION) {
                continue;
            }
            Entity entity = world.getEntityById(spot.id);
            if (entity == null || !entity.isAlive()) {
                it.remove();
                continue;
            }
            if (sees(world, viewer, entity)) {
                continue;
            }
            if (best == null || spot.lastSeen > best.lastSeen) {
                best = spot;
            }
        }
        if (best == null) {
            return null;
        }
        return BlockPos.ofFloored(best.x, best.feet, best.z);
    }

    public static boolean shouldScan(long now, long lastScan, boolean moved) {
        return now - lastScan >= (moved ? ACTIVE_CADENCE : STILL_CADENCE);
    }

    public static void trace(ServerWorld world, LivingEntity viewer, Look look) {
        Vec3d origin = eye(viewer);
        float yaw0 = viewer.getHeadYaw();
        float pitch0 = viewer.getPitch();
        float fov = fieldOfView(viewer);
        float vertical = fov * 9.0F / 16.0F;
        int range = radius(viewer);
        float night = nightVision(viewer);
        int cols = 11;
        int rows = 7;
        for (int row = 0; row < rows; row++) {
            float v = (row + 0.5F) / rows * 2.0F - 1.0F;
            for (int col = 0; col < cols; col++) {
                float u = (col + 0.5F) / cols * 2.0F - 1.0F;
                float yaw = yaw0 + u * fov / 2.0F;
                float pitch = MathHelper.clamp(pitch0 + v * vertical / 2.0F, -89.0F, 89.0F);
                cast(world, origin, direction(yaw, pitch), range, night, look);
            }
        }
    }

    public static float refresh(float previous, float seen, boolean visibleNow, long unseenTicks) {
        if (visibleNow) {
            return clamp(Math.max(previous * 0.72F, seen));
        }
        return clamp(previous - Math.max(1, unseenTicks) * DIRECT_DECAY);
    }

    public static boolean forgotten(float confidence, long age) {
        return confidence < FORGET_CONFIDENCE || age > FORGET_AGE;
    }

    public static float confidence(LivingEntity viewer, double distance) {
        int radius = radius(viewer);
        if (distance > radius) {
            return 0.0F;
        }
        float falloff = 1.0F - (float) (distance / Math.max(1, radius));
        return clamp(0.18F + 0.55F * falloff);
    }

    /** Glass, stained glass, panes and bars. Tinted glass does not. */
    public static boolean seeThrough(BlockState state) {
        if (state.isOf(Blocks.TINTED_GLASS)) {
            return false;
        }
        Block block = state.getBlock();
        return state.isOf(Blocks.GLASS) || block instanceof StainedGlassBlock || block instanceof PaneBlock;
    }

    private static void cast(ServerWorld world, Vec3d origin, Vec3d dir, int range, float night, Look look) {
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        int previousX = Integer.MIN_VALUE;
        int previousY = Integer.MIN_VALUE;
        int previousZ = Integer.MIN_VALUE;
        for (double step = 0.5; step <= range; step += 0.5) {
            double x = origin.x + dir.x * step;
            double y = origin.y + dir.y * step;
            double z = origin.z + dir.z * step;
            int bx = MathHelper.floor(x);
            int by = MathHelper.floor(y);
            int bz = MathHelper.floor(z);
            if (bx == previousX && by == previousY && bz == previousZ) {
                continue;
            }
            previousX = bx;
            previousY = by;
            previousZ = bz;
            if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
                return;
            }
            cursor.set(bx, by, bz);
            BlockState state = world.getBlockState(cursor);
            if (!lit(world, cursor, step, range, night)) {
                return;
            }
            BlockPos seen = cursor.toImmutable();
            if (blocksSight(state)) {
                look.solid(seen, state);
                return;
            }
            look.open(seen, state);
        }
    }

    private static boolean clear(ServerWorld world, Vec3d from, Vec3d to) {
        Vec3d delta = to.subtract(from);
        double length = delta.length();
        if (length < 0.05) {
            return true;
        }
        Vec3d dir = delta.multiply(1.0 / length);
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        int previousX = Integer.MIN_VALUE;
        int previousY = Integer.MIN_VALUE;
        int previousZ = Integer.MIN_VALUE;
        int endX = MathHelper.floor(to.x);
        int endY = MathHelper.floor(to.y);
        int endZ = MathHelper.floor(to.z);
        for (double step = 0.25; step < length - 0.2; step += 0.25) {
            int bx = MathHelper.floor(from.x + dir.x * step);
            int by = MathHelper.floor(from.y + dir.y * step);
            int bz = MathHelper.floor(from.z + dir.z * step);
            if (bx == previousX && by == previousY && bz == previousZ) {
                continue;
            }
            previousX = bx;
            previousY = by;
            previousZ = bz;
            if (bx == endX && by == endY && bz == endZ) {
                return true;
            }
            if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
                return false;
            }
            cursor.set(bx, by, bz);
            if (blocksSight(world.getBlockState(cursor))) {
                return false;
            }
        }
        return true;
    }

    private static boolean blocksSight(BlockState state) {
        if (state.isAir() || seeThrough(state)) {
            return false;
        }
        if (state.getFluidState().isIn(FluidTags.WATER)) {
            return false;
        }
        if (state.getFluidState().isIn(FluidTags.LAVA)) {
            return true;
        }
        return state.isOpaque();
    }

    private static boolean lit(ServerWorld world, BlockPos pos, double distance, int range, float night) {
        int light = world.getLightLevel(pos);
        double perception = light / 15.0 + night * 0.55 + (distance < 2.5 ? 0.4 : 0);
        return perception >= 0.12 + distance / Math.max(1, range) * 0.25;
    }

    private static Vec3d eye(LivingEntity viewer) {
        return new Vec3d(viewer.getX(), viewer.getEyeY(), viewer.getZ());
    }

    static Vec3d direction(float yawDegrees, float pitchDegrees) {
        double pitch = Math.toRadians(pitchDegrees);
        double yaw = -Math.toRadians(yawDegrees);
        double cosPitch = Math.cos(pitch);
        return new Vec3d(Math.sin(yaw) * cosPitch, -Math.sin(pitch), Math.cos(yaw) * cosPitch);
    }

    private static float gene(UUID id, int salt, float min, float max) {
        long mixed = id.getLeastSignificantBits() ^ Long.rotateLeft(id.getMostSignificantBits(), salt) ^ (salt * 0x9E3779B97F4A7C15L);
        float unit = ((mixed >>> 1) & 0xFFFF) / 65535.0F;
        return min + unit * (max - min);
    }

    private static Book book(LivingEntity viewer) {
        return BOOKS.computeIfAbsent(viewer, key -> new Book());
    }

    private static void prune(Book book, long tick) {
        book.spots.values().removeIf(spot -> tick - spot.lastSeen > FORGET_TRACK);
    }

    private static float clamp(float value) {
        if (value < 0.0F) {
            return 0.0F;
        }
        return Math.min(value, 1.0F);
    }

    private static final class Book {
        private final Map<Integer, Spot> spots = new java.util.HashMap<>();
    }

    private static final class Spot {
        private final int id;
        private double x;
        private double feet;
        private double z;
        private long lastSeen;

        private Spot(int id) {
            this.id = id;
        }
    }

    public interface Look {
        void open(BlockPos pos, BlockState state);

        void solid(BlockPos pos, BlockState state);
    }
}
