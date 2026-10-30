package net.mca.entity.ai.sense;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.StainedGlassBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The villager's eye, built the way a player's view works.
 * <p>
 * A {@link #WIDTH}x{@link #HEIGHT} grid of rays leaves the exact eye position through a perspective
 * image plane (70 degrees vertical, 16:9, the default player camera) along the head's yaw and pitch.
 * Every ray walks the voxel grid cell by cell and is tested against each block's real outline shape,
 * the same shape the crosshair uses: a ray can pass over a bottom slab, between fence posts or through
 * the open half of a trapdoor, and it stops on the exact face it touches. Glass, panes, bars, plants,
 * torches and water let the look through; lava and every other surface stop it. Far away things need
 * light to be seen. What a ray touches goes into the villager's {@link VisualMemory}: block, full state,
 * face, hit point and exact distance, and into its coarse {@link MapaVisto} of the land. Nothing else does.
 * <p>
 * Like a real eye it never holds still: every band is cast through a fresh sub-pixel offset (a saccade, from
 * a low-discrepancy sequence so successive frames fill the gaps between rays), so a trunk forty blocks off,
 * thinner than a ray spacing, is hit within a few frames. The middle third of the view also has a fovea, a
 * denser {@link #FOVEA_WIDTH}x{@link #FOVEA_HEIGHT} grid scanned with the bands; its hits go to memory only,
 * never into the {@link #WIDTH}x{@link #HEIGHT} frame. One look costs one band of the frame (192 rays) plus
 * one band of the fovea (48 rays).
 */
public final class Retina {
    public static final int WIDTH = 32;
    public static final int HEIGHT = 18;
    public static final float VERTICAL_FOV = 70.0F;
    public static final float ASPECT = 16.0F / 9.0F;
    public static final double RANGE = 48.0;
    public static final double ENTITY_RANGE = 40.0;
    /** Survival player reach in 1.20.1, from the eye to the point on the block. */
    public static final double BLOCK_REACH = 4.5;
    /** Survival player attack reach. */
    public static final double ENTITY_REACH = 3.0;
    /** Rows are scanned interlaced: one band per look, a full frame every {@link #BANDS} looks. */
    public static final int BANDS = 3;
    /** Closer than this nothing is hidden by darkness, like the minimum brightness of a player's screen. */
    public static final double DARK_SIGHT = 6.0;
    public static final int MIN_LIGHT = 3;
    /** The fovea: a denser grid over the middle third of the view, in both directions. */
    public static final int FOVEA_WIDTH = 16;
    public static final int FOVEA_HEIGHT = 9;
    /** A ray this close to straight up that reaches nothing has seen the open sky overhead. */
    public static final double SKY_UP = 0.7;
    /** A dark hit where the sky light is under this is a hole the sun does not reach: a cave mouth, a mine. */
    public static final int DARK_SKY = 8;

    private static final Map<LivingEntity, VisualMemory> OTHERS = new WeakHashMap<>();

    private Retina() {
    }

    public record Pixel(@Nullable BlockPos pos, @Nullable BlockState state, @Nullable Direction face, @Nullable Vec3d hit,
                        double distance, int light, boolean dark, boolean water, @Nullable Entity entity) {
        public boolean sky() {
            return pos == null && entity == null && !dark;
        }
    }

    public record Focus(@Nullable BlockPos pos, @Nullable BlockState state, @Nullable Direction face, @Nullable Vec3d hit,
                        double distance, @Nullable Entity entity) {
        public boolean inReach() {
            return entity != null ? distance <= ENTITY_REACH : pos != null && distance <= BLOCK_REACH;
        }
    }

    /** Result of one exact sight ray. */
    public record Sight(@Nullable BlockHitResult hit, double distance, boolean water) {
        public boolean blocked() {
            return hit != null;
        }
    }

    public static VisualMemory memory(LivingEntity viewer) {
        if (viewer instanceof VillagerEntityMCA villager) {
            return villager.getVisualMemory();
        }
        return OTHERS.computeIfAbsent(viewer, v -> new VisualMemory());
    }

    public static Vec3d eye(LivingEntity viewer) {
        return viewer.getEyePos();
    }

    public static Vec3d forward(LivingEntity viewer) {
        return Vec3d.fromPolar(viewer.getPitch(), viewer.getHeadYaw());
    }

    public static boolean nightVision(LivingEntity viewer) {
        return viewer.hasStatusEffect(StatusEffects.NIGHT_VISION);
    }

    public static double range(LivingEntity viewer) {
        if (viewer.hasStatusEffect(StatusEffects.BLINDNESS) || viewer.hasStatusEffect(StatusEffects.DARKNESS)) {
            return 5.0;
        }
        return RANGE;
    }

    public static boolean perceives(int light, double distance, boolean nightVision) {
        return nightVision || distance <= DARK_SIGHT || light >= MIN_LIGHT;
    }

    /** One band of the frame, plus the crosshair and the bodies in view. Cheap enough to call every few ticks. */
    public static void look(World world, LivingEntity viewer) {
        VisualMemory memory = memory(viewer);
        scan(world, viewer, memory, memory.nextInterlace(BANDS), 1);
    }

    /** The whole frame at once. */
    public static void lookAll(World world, LivingEntity viewer) {
        scan(world, viewer, memory(viewer), 0, BANDS);
    }

    private static void scan(World world, LivingEntity viewer, VisualMemory memory, int firstBand, int bands) {
        long tick = world.getTime();
        Vec3d eye = eye(viewer);
        Vec3d forward = forward(viewer);
        Vec3d[] axes = axes(forward);
        Vec3d right = axes[0];
        Vec3d up = axes[1];
        double tanV = Math.tan(Math.toRadians(VERTICAL_FOV / 2.0));
        double tanH = tanV * ASPECT;
        double range = range(viewer);
        boolean night = nightVision(viewer);

        Pixel[] frame = memory.frame();
        if (frame == null || frame.length != WIDTH * HEIGHT) {
            frame = new Pixel[WIDTH * HEIGHT];
        }
        List<Entity> bodies = bodiesAround(world, viewer, eye, Math.min(range, ENTITY_RANGE));
        BlockPos feet = viewer.getBlockPos();
        memory.looked(tick);
        memory.mapa().walked(feet, tick);

        for (int band = firstBand; band < firstBand + bands; band++) {
            // a saccade: the whole band shifts by a fresh sub-pixel offset
            int k = memory.nextSaccade();
            double jx = frac(k * 0.7548776662466927) - 0.5;
            double jy = frac(k * 0.5698402909980532) - 0.5;
            for (int row = band % BANDS; row < HEIGHT; row += BANDS) {
                double v = (1.0 - (row + 0.5 + jy) / HEIGHT * 2.0) * tanV;
                for (int col = 0; col < WIDTH; col++) {
                    double u = ((col + 0.5 + jx) / WIDTH * 2.0 - 1.0) * tanH;
                    Vec3d dir = forward.add(right.multiply(u)).add(up.multiply(v)).normalize();
                    Pixel pixel = pixel(world, viewer, memory, eye, dir, range, night, tick, bodies);
                    frame[row * WIDTH + col] = pixel;
                    if (dir.y >= SKY_UP && pixel.sky()) {
                        memory.sawSky(feet, tick);
                    }
                }
            }
            // the fovea, over the middle third: memory only
            for (int row = band % BANDS; row < FOVEA_HEIGHT; row += BANDS) {
                double v = (1.0 - (row + 0.5 + jy) / FOVEA_HEIGHT * 2.0) * tanV / 3.0;
                for (int col = 0; col < FOVEA_WIDTH; col++) {
                    double u = ((col + 0.5 + jx) / FOVEA_WIDTH * 2.0 - 1.0) * tanH / 3.0;
                    Vec3d dir = forward.add(right.multiply(u)).add(up.multiply(v)).normalize();
                    Pixel pixel = pixel(world, viewer, memory, eye, dir, range, night, tick, bodies);
                    if (dir.y >= SKY_UP && pixel.sky()) {
                        memory.sawSky(feet, tick);
                    }
                }
            }
        }
        memory.frame(frame, WIDTH, HEIGHT, tick);
        memory.focus(focus(world, viewer, memory, tick));
        for (Entity body : bodies) {
            if (canSee(world, viewer, body)) {
                memory.seeEntity(body, distanceToBox(eye, body.getBoundingBox()), tick);
            }
        }
        if ((tick % 200) == 0) {
            memory.prune(tick, 24000);
        }
    }

    private static double frac(double x) {
        return x - Math.floor(x);
    }

    /** The sky light on a face that looks up, the cue that tells sunlit land from a cave floor; -1 otherwise. */
    static int skyOn(World world, BlockPos pos, @Nullable Direction face) {
        return face == Direction.UP ? world.getLightLevel(LightType.SKY, pos.up()) : -1;
    }

    private static Pixel pixel(World world, LivingEntity viewer, VisualMemory memory, Vec3d eye, Vec3d dir, double range, boolean night, long tick, List<Entity> bodies) {
        Vec3d end = eye.add(dir.multiply(range));
        Sight sight = cast(world, eye, end, viewer, memory, tick, night);
        double blockDistance = sight.hit() == null ? range : sight.distance();

        // the nearest body this ray touches before the wall
        Entity body = null;
        double bodyDistance = blockDistance;
        for (Entity candidate : bodies) {
            Box box = candidate.getBoundingBox().expand(candidate.getTargetingMargin());
            var point = box.raycast(eye, end);
            if (point.isPresent()) {
                double d = eye.distanceTo(point.get());
                if (d < bodyDistance) {
                    bodyDistance = d;
                    body = candidate;
                }
            }
        }
        if (body != null) {
            int light = world.getLightLevel(body.getBlockPos());
            boolean dark = !perceives(light, bodyDistance, night) && !body.isGlowing() && !body.isOnFire();
            return new Pixel(null, null, null, eye.add(dir.multiply(bodyDistance)), bodyDistance, light, dark, sight.water(), dark ? null : body);
        }
        return blockPixel(world, memory, sight, range, night, tick);
    }

    /**
     * The pixel of one exact ray that touched no body, recorded the way every frame of the eye records it: a lit
     * surface goes into memory and the map, a dark one only marks the map when the sun does not reach it. Shared
     * with {@link Pantalla}, so both views remember the same things the same way.
     */
    static Pixel blockPixel(World world, VisualMemory memory, Sight sight, double range, boolean night, long tick) {
        BlockHitResult hit = sight.hit();
        if (hit == null) {
            return new Pixel(null, null, null, null, range, 15, false, sight.water(), null);
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = world.getBlockState(pos);
        int light = lightAt(world, pos, hit.getSide(), state);
        boolean dark = !perceives(light, sight.distance(), night);
        if (!dark) {
            memory.see(pos, state, hit.getSide(), hit.getPos(), sight.distance(), light, tick, false, skyOn(world, pos, hit.getSide()));
        } else if (world.getLightLevel(LightType.SKY, pos.offset(hit.getSide())) < DARK_SKY) {
            memory.mapa().dark(pos, tick);
        }
        return new Pixel(dark ? null : pos, dark ? null : state, dark ? null : hit.getSide(), hit.getPos(), sight.distance(), light, dark, sight.water(), null);
    }

    /**
     * The exact ray of the eye from {@code from} to {@code to}. Returns the first surface that stops the look.
     * See-through surfaces on the way are recorded into {@code memory} when one is given; cells crossed as empty
     * are erased from it.
     */
    public static Sight cast(World world, Vec3d from, Vec3d to, @Nullable Entity viewer, @Nullable VisualMemory memory, long tick, boolean night) {
        return cast(world, from, to, viewer, memory, tick, night, null);
    }

    /**
     * What a picture wants of the exact ray, and nothing the memory gets: every see-through surface it goes through
     * (a plant, a pane, a torch), and the first water. The ray, its hit and the memory are the same with or without it.
     */
    interface Paso {
        void through(BlockPos pos, BlockState state, BlockHitResult hit, boolean fluid);
    }

    static Sight cast(World world, Vec3d from, Vec3d to, @Nullable Entity viewer, @Nullable VisualMemory memory, long tick, boolean night,
                      @Nullable Paso paso) {
        ShapeContext context = viewer == null ? ShapeContext.absent() : ShapeContext.of(viewer);
        boolean[] water = new boolean[1];
        int[] chunk = {Integer.MIN_VALUE, Integer.MIN_VALUE, 1};
        BlockHitResult hit = BlockView.raycast(from, to, context, (ctx, pos) -> {
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            if (cx != chunk[0] || cz != chunk[1]) {
                chunk[0] = cx;
                chunk[1] = cz;
                chunk[2] = world.isChunkLoaded(cx, cz) ? 1 : 0;
            }
            if (chunk[2] == 0) {
                // the world ends here for the eye: nothing loaded, nothing seen
                return BlockHitResult.createMissed(to, Direction.UP, pos);
            }
            BlockState state = world.getBlockState(pos);
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                VoxelShape fluidShape = fluid.getShape(world, pos);
                BlockHitResult fluidHit = fluidShape.raycast(from, to, pos);
                if (fluidHit != null) {
                    if (fluid.isIn(FluidTags.LAVA)) {
                        return fluidHit;
                    }
                    if (!water[0] && memory != null) {
                        memory.see(pos, state, fluidHit.getSide(), fluidHit.getPos(), from.distanceTo(fluidHit.getPos()), world.getLightLevel(pos), tick, true, skyOn(world, pos, fluidHit.getSide()));
                    }
                    if (!water[0] && paso != null) {
                        paso.through(pos, state, fluidHit, true);
                    }
                    water[0] = true;
                }
            }
            if (state.isAir()) {
                if (memory != null) {
                    memory.empty(pos);
                }
                return null;
            }
            VoxelShape shape = state.getOutlineShape(world, pos, ctx);
            BlockHitResult blockHit = shape.isEmpty() ? null : shape.raycast(from, to, pos);
            if (blockHit == null) {
                if (memory != null && fluid.isEmpty()) {
                    memory.empty(pos);
                }
                return null;
            }
            if (seeThrough(world, pos, state)) {
                if (memory != null) {
                    double d = from.distanceTo(blockHit.getPos());
                    int light = lightAt(world, pos, blockHit.getSide(), state);
                    if (perceives(light, d, night)) {
                        memory.see(pos, state, blockHit.getSide(), blockHit.getPos(), d, light, tick, true, skyOn(world, pos, blockHit.getSide()));
                    }
                }
                if (paso != null) {
                    paso.through(pos, state, blockHit, false);
                }
                return null;
            }
            return blockHit;
        }, ctx -> null);
        if (hit != null && hit.getType() == HitResult.Type.MISS) {
            return new Sight(null, from.distanceTo(to), water[0]);
        }
        return new Sight(hit, hit == null ? from.distanceTo(to) : from.distanceTo(hit.getPos()), water[0]);
    }

    /**
     * A quick look straight into one cell and its six neighbours, the glance a player gives a hole they just
     * opened. Whatever the rays touch is recorded.
     */
    public static void peek(World world, LivingEntity viewer, BlockPos cell) {
        VisualMemory memory = memory(viewer);
        long tick = world.getTime();
        boolean night = nightVision(viewer);
        Vec3d eye = eye(viewer);
        List<Vec3d> points = new ArrayList<>(7);
        points.add(Vec3d.ofCenter(cell));
        for (Direction dir : Direction.values()) {
            points.add(Vec3d.ofCenter(cell.offset(dir)));
        }
        for (Vec3d point : points) {
            Vec3d d = point.subtract(eye);
            if (d.lengthSquared() < 1.0E-4) {
                continue;
            }
            Vec3d end = point.add(d.normalize().multiply(0.6));
            record(world, memory, cast(world, eye, end, viewer, memory, tick, night), tick, night);
        }
    }

    private static void record(World world, VisualMemory memory, Sight sight, long tick, boolean night) {
        BlockHitResult hit = sight.hit();
        if (hit == null) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = world.getBlockState(pos);
        int light = lightAt(world, pos, hit.getSide(), state);
        if (perceives(light, sight.distance(), night)) {
            memory.see(pos, state, hit.getSide(), hit.getPos(), sight.distance(), light, tick, false, skyOn(world, pos, hit.getSide()));
        }
    }

    /** Glass, panes, bars, and anything without a body (plants, torches, rails, signs). Tinted glass does not. */
    public static boolean seeThrough(World world, BlockPos pos, BlockState state) {
        if (state.isOf(Blocks.TINTED_GLASS)) {
            return false;
        }
        if (state.isOf(Blocks.GLASS) || state.getBlock() instanceof StainedGlassBlock || state.getBlock() instanceof PaneBlock) {
            return true;
        }
        return state.getCollisionShape(world, pos).isEmpty();
    }

    static int lightAt(World world, BlockPos pos, Direction face, BlockState state) {
        if (state.getLuminance() > 0) {
            return state.getLuminance();
        }
        return world.getLightLevel(pos.offset(face));
    }

    /** What the crosshair is on: the first outline the center ray touches, or a body before it. Like Jade. */
    @Nullable
    public static Focus focus(World world, LivingEntity viewer, @Nullable VisualMemory memory, long tick) {
        Vec3d eye = eye(viewer);
        Vec3d dir = forward(viewer);
        double range = range(viewer);
        Vec3d end = eye.add(dir.multiply(range));
        BlockHitResult blockHit = world.raycast(new net.minecraft.world.RaycastContext(eye, end,
                net.minecraft.world.RaycastContext.ShapeType.OUTLINE, net.minecraft.world.RaycastContext.FluidHandling.NONE, viewer));
        double blockDistance = blockHit.getType() == HitResult.Type.MISS ? range : eye.distanceTo(blockHit.getPos());
        Box area = viewer.getBoundingBox().stretch(dir.multiply(blockDistance)).expand(1.0);
        EntityHitResult entityHit = ProjectileUtil.raycast(viewer, eye, eye.add(dir.multiply(blockDistance)), area,
                e -> !e.isSpectator() && e.canHit(), blockDistance * blockDistance);
        if (entityHit != null) {
            double d = eye.distanceTo(entityHit.getPos());
            return new Focus(null, null, null, entityHit.getPos(), d, entityHit.getEntity());
        }
        if (blockHit.getType() == HitResult.Type.MISS) {
            return null;
        }
        BlockPos pos = blockHit.getBlockPos();
        BlockState state = world.getBlockState(pos);
        int light = lightAt(world, pos, blockHit.getSide(), state);
        if (!perceives(light, blockDistance, nightVision(viewer))) {
            return null;
        }
        if (memory != null) {
            memory.see(pos, state, blockHit.getSide(), blockHit.getPos(), blockDistance, light, tick, seeThrough(world, pos, state), skyOn(world, pos, blockHit.getSide()));
        }
        return new Focus(pos, state, blockHit.getSide(), blockHit.getPos(), blockDistance, null);
    }

    /**
     * Whether the eye can see this body right now: inside the field of view, lit enough, and at least one of
     * nine points on its box reachable by an exact ray. Touching distance always counts.
     */
    public static boolean canSee(World world, LivingEntity viewer, Entity target) {
        if (target == null || target == viewer || target.isRemoved()) {
            return false;
        }
        Vec3d eye = eye(viewer);
        Box box = target.getBoundingBox();
        double distance = distanceToBox(eye, box);
        if (distance <= 1.5) {
            return true;
        }
        double range = Math.min(range(viewer), ENTITY_RANGE);
        if (distance > range) {
            return false;
        }
        if (target.isInvisible() && !target.isGlowing()) {
            return false;
        }
        boolean night = nightVision(viewer);
        if (!night && distance > DARK_SIGHT && !target.isGlowing() && !target.isOnFire()) {
            int light = world.getLightLevel(BlockPos.ofFloored(box.getCenter()));
            if (light < MIN_LIGHT) {
                return false;
            }
        }
        Vec3d forward = forward(viewer);
        Vec3d[] axes = axes(forward);
        double tanV = Math.tan(Math.toRadians(VERTICAL_FOV / 2.0));
        double tanH = tanV * ASPECT;
        for (Vec3d point : samplePoints(target, box)) {
            if (!inView(eye, forward, axes[0], axes[1], tanH, tanV, point)) {
                continue;
            }
            Sight sight = cast(world, eye, point, viewer, null, 0, night);
            if (!sight.blocked()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a point lies inside the camera frustum. */
    public static boolean inView(LivingEntity viewer, Vec3d point) {
        Vec3d forward = forward(viewer);
        Vec3d[] axes = axes(forward);
        double tanV = Math.tan(Math.toRadians(VERTICAL_FOV / 2.0));
        return inView(eye(viewer), forward, axes[0], axes[1], tanV * ASPECT, tanV, point);
    }

    private static boolean inView(Vec3d eye, Vec3d forward, Vec3d right, Vec3d up, double tanH, double tanV, Vec3d point) {
        Vec3d d = point.subtract(eye);
        double z = d.dotProduct(forward);
        if (z <= 0.05) {
            return false;
        }
        return Math.abs(d.dotProduct(right) / z) <= tanH && Math.abs(d.dotProduct(up) / z) <= tanV;
    }

    private static List<Vec3d> samplePoints(Entity target, Box box) {
        Vec3d c = box.getCenter();
        double inset = 0.08;
        List<Vec3d> points = new ArrayList<>(9);
        points.add(new Vec3d(target.getX(), target.getEyeY(), target.getZ()));
        points.add(c);
        points.add(new Vec3d(c.x, box.maxY - inset, c.z));
        points.add(new Vec3d(c.x, box.minY + inset, c.z));
        points.add(new Vec3d(box.minX + inset, c.y, c.z));
        points.add(new Vec3d(box.maxX - inset, c.y, c.z));
        points.add(new Vec3d(c.x, c.y, box.minZ + inset));
        points.add(new Vec3d(c.x, c.y, box.maxZ - inset));
        points.add(new Vec3d(c.x, box.minY + (box.maxY - box.minY) * 0.25, c.z));
        return points;
    }

    private static List<Entity> bodiesAround(World world, LivingEntity viewer, Vec3d eye, double range) {
        Box area = new Box(eye, eye).expand(range);
        List<Entity> bodies = world.getOtherEntities(viewer, area, e -> !e.isSpectator() && (e instanceof LivingEntity || e instanceof ItemEntity) && e.isAlive());
        if (bodies.size() > 32) {
            bodies.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(eye)));
            return new ArrayList<>(bodies.subList(0, 32));
        }
        return bodies;
    }

    /** right and up of the camera for a forward direction */
    static Vec3d[] axes(Vec3d forward) {
        Vec3d right = forward.crossProduct(new Vec3d(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) {
            right = new Vec3d(-1, 0, 0);
        }
        right = right.normalize();
        Vec3d up = right.crossProduct(forward).normalize();
        return new Vec3d[]{right, up};
    }

    public static double distanceToBox(Vec3d point, Box box) {
        double x = MathHelper.clamp(point.x, box.minX, box.maxX);
        double y = MathHelper.clamp(point.y, box.minY, box.maxY);
        double z = MathHelper.clamp(point.z, box.minZ, box.maxZ);
        return point.distanceTo(new Vec3d(x, y, z));
    }

    /**
     * Where on this block the eye can put its crosshair: the first sample point of its outline that an exact ray
     * reaches without touching anything else, within {@code reach}. Null when it cannot be seen from here.
     */
    @Nullable
    public static BlockHitResult visibleFace(World world, LivingEntity viewer, BlockPos target, double reach) {
        BlockState state = world.getBlockState(target);
        if (state.isAir()) {
            return null;
        }
        VoxelShape shape = state.getOutlineShape(world, target, ShapeContext.of(viewer));
        if (shape.isEmpty()) {
            return null;
        }
        Vec3d eye = eye(viewer);
        Box bounds = shape.getBoundingBox().offset(target);
        if (distanceToBox(eye, bounds) > reach) {
            return null;
        }
        for (Vec3d point : facePoints(eye, bounds)) {
            if (eye.distanceTo(point) > reach + 0.01) {
                continue;
            }
            Vec3d past = point.add(point.subtract(eye).normalize().multiply(0.05));
            BlockHitResult hit = world.raycast(new net.minecraft.world.RaycastContext(eye, past,
                    net.minecraft.world.RaycastContext.ShapeType.OUTLINE, net.minecraft.world.RaycastContext.FluidHandling.NONE, viewer));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target) && eye.distanceTo(hit.getPos()) <= reach) {
                return hit;
            }
        }
        return null;
    }

    /** Center of the box, then the center of each face turned toward the eye, then points near their corners. */
    private static List<Vec3d> facePoints(Vec3d eye, Box b) {
        List<Vec3d> points = new ArrayList<>(32);
        Vec3d c = b.getCenter();
        points.add(c);
        double ix = (b.maxX - b.minX) * 0.3;
        double iy = (b.maxY - b.minY) * 0.3;
        double iz = (b.maxZ - b.minZ) * 0.3;
        double e = 1.0E-3;
        List<Vec3d> faces = new ArrayList<>(6);
        if (eye.x < b.minX) faces.add(new Vec3d(b.minX + e, c.y, c.z));
        if (eye.x > b.maxX) faces.add(new Vec3d(b.maxX - e, c.y, c.z));
        if (eye.y < b.minY) faces.add(new Vec3d(c.x, b.minY + e, c.z));
        if (eye.y > b.maxY) faces.add(new Vec3d(c.x, b.maxY - e, c.z));
        if (eye.z < b.minZ) faces.add(new Vec3d(c.x, c.y, b.minZ + e));
        if (eye.z > b.maxZ) faces.add(new Vec3d(c.x, c.y, b.maxZ - e));
        faces.sort(Comparator.comparingDouble(eye::squaredDistanceTo));
        points.addAll(faces);
        for (Vec3d f : faces) {
            boolean xFace = Math.abs(f.x - b.minX) < 2 * e || Math.abs(f.x - b.maxX) < 2 * e;
            boolean yFace = Math.abs(f.y - b.minY) < 2 * e || Math.abs(f.y - b.maxY) < 2 * e;
            for (int a = -1; a <= 1; a += 2) {
                for (int s = -1; s <= 1; s += 2) {
                    if (xFace) points.add(new Vec3d(f.x, f.y + a * iy, f.z + s * iz));
                    else if (yFace) points.add(new Vec3d(f.x + a * ix, f.y, f.z + s * iz));
                    else points.add(new Vec3d(f.x + a * ix, f.y + s * iy, f.z));
                }
            }
        }
        return points;
    }
}
