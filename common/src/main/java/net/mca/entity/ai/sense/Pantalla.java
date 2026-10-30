package net.mca.entity.ai.sense;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.body.Mining;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.StainedGlassBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Util;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The villager's screen: what a player standing where it stands would see on their monitor, one per connected
 * villager. A {@value #WIDTH}x{@value #HEIGHT} framebuffer of RGB pixels, each with what it shows (the block, its
 * state, the face, the distance, or the body, or the sky), rendered from the exact eye through the player's camera
 * (70 degrees vertical, 16:9) along the head's yaw and pitch, coloured like {@link Foto}. It sees as far as a player
 * with a render distance of {@value #RENDER_CHUNKS} chunks: {@value #FAR} blocks out on open land.
 * <p>
 * Every pixel is one ray in two passes:
 * <ul>
 * <li>The near pass, out to {@value #NEAR} blocks, is the {@link Retina}'s own exact ray ({@link Retina#cast}): real
 * outline shapes, glass and plants looked through, and every hit recorded into {@link VisualMemory} and
 * {@link MapaVisto} exactly as the retina records it (ores included, when the ray really touches them).</li>
 * <li>The far pass, past that, is a plain voxel walk that reads the world's height columns first
 * ({@link Heightmap.Type#MOTION_BLOCKING}, which counts leaves and water): cells above a column's top are passed
 * without reading a block, and whole chunks the ray flies over are skipped. A cell under the top is read and stops the
 * ray when it blocks movement, so the terrain hides what is behind it, and the ray only gets under the surface where
 * there really is an opening. Far hits feed only the coarse map (trees, water, land, buildings), never the block
 * memory, and an ore seen far away is taken for plain rock. A chunk that is not loaded is fog: the far pass never
 * loads or generates one ({@link net.minecraft.world.chunk.ChunkManager#getWorldChunk(int, int)} only returns what
 * is already there).</li>
 * </ul>
 * The range of each frame follows the space around it, the way a mine closes in on a miner: out in the open (or with
 * the sky or the far land showing through a door or a window) it is the whole {@value #FAR}; down in the rock, in a
 * tunnel or in a closed room it is the size of the space, the farthest thing the last frame saw times 1.5 plus 8,
 * kept between {@value #MIN_RANGE} and {@value #ENCLOSED}.
 * <p>
 * The work is spread over ticks: the rows are drawn interlaced (each tick draws the next rows of a fixed permutation,
 * every row through the camera of that tick, the way a rolling shutter does), at most a budget of rays a tick, sized
 * for a whole frame every {@value #FRAME_TICKS} ticks and cut down when a tick costs more than {@value #TARGET_MICROS}
 * microseconds; all the screens of the server together stop for the tick after {@value #SERVER_MICROS} microseconds.
 * Each frame is shifted by a fresh sub-pixel offset, so a trunk thinner than a pixel is caught within a few frames.
 * Rays, ticks and nanoseconds are counted ({@link #costLine()}).
 * <p>
 * While the villager is connected the latest frame is written every {@value #SAVE_TICKS} ticks as a PNG into the world
 * folder ({@code <world>/tropeles/vista/<name>.png}), drawn {@value #SCALE} times larger, off the server thread.
 */
public final class Pantalla {
    public static final int WIDTH = 128;
    public static final int HEIGHT = 72;
    public static final int PIXELS = WIDTH * HEIGHT;
    public static final int RENDER_CHUNKS = 12;
    /** A player's sight with {@value #RENDER_CHUNKS} chunks of render distance. */
    public static final int FAR = RENDER_CHUNKS * 16;
    /** The exact near pass reaches this far (the retina's own range). */
    public static final int NEAR = (int) Retina.RANGE;
    public static final int MIN_RANGE = 12;
    /** The largest range of a frame in an enclosed space. */
    public static final int ENCLOSED = 48;
    /** A frame with at least this share of rays that got out (sky, fog, the end of the range) is open. */
    public static final double OPEN_SHARE = 0.02;
    /** A whole frame every this many ticks, when the ticks are cheap enough. */
    public static final int FRAME_TICKS = 30;
    public static final int TARGET_MICROS = 1500;
    public static final int SERVER_MICROS = 6000;
    public static final int MIN_BUDGET = 48;
    public static final int MAX_BUDGET = (PIXELS + FRAME_TICKS - 1) / FRAME_TICKS;
    /** Bodies are seen this far. */
    public static final double BODY_RANGE = 64.0;
    public static final int SAVE_TICKS = 40;
    public static final int SCALE = 3;
    /** A screen whose villager stopped being connected this long ago is dropped. */
    public static final int DROP_TICKS = 200;

    /** What a pixel shows. */
    public static final byte NADA = 0;
    public static final byte CIELO = 1;
    public static final byte BLOQUE = 2;
    public static final byte AGUA = 3;
    public static final byte CUERPO = 4;
    public static final byte OSCURO = 5;
    public static final byte NIEBLA = 6;

    private static final int FOG = 0xC8D0D8;
    private static final int DARK = 0x050505;
    private static final Direction[] DIRS = Direction.values();
    private static final Map<LivingEntity, Pantalla> ALL = new WeakHashMap<>();
    private static final Map<Block, Boolean> CLEAR = new IdentityHashMap<>();
    /** the rows in the order they are drawn: a permutation that spreads every few ticks over the whole height */
    private static final int[] ROWS = new int[HEIGHT];

    static {
        int stride = 29; // coprime with 72
        for (int k = 0; k < HEIGHT; k++) {
            ROWS[k] = (k * stride) % HEIGHT;
        }
    }

    private static long serverTick = Long.MIN_VALUE;
    private static long serverNanos;
    private static boolean loggedSaveError;
    private static boolean loggedAhoraError;

    /** One pixel: what it shows, where and how far. {@code far}: seen by the far pass (the map was told, not the memory). */
    public record Hit(byte kind, @Nullable BlockPos pos, @Nullable BlockState state, @Nullable Direction face, double distance,
                      int entityId, boolean far, int rgb) {
        public boolean sky() {
            return kind == CIELO;
        }
    }

    // ---------------------------------------------------------------- the framebuffer

    private final int[] rgb = new int[PIXELS];
    private final byte[] kind = new byte[PIXELS];
    private final long[] pos = new long[PIXELS];
    private final BlockState[] state = new BlockState[PIXELS];
    private final byte[] face = new byte[PIXELS];
    private final float[] dist = new float[PIXELS];
    private final int[] entity = new int[PIXELS];
    private final boolean[] far = new boolean[PIXELS];

    // ---------------------------------------------------------------- the frame being drawn

    private int cursor;
    private double range = FAR;
    private double jx;
    private double jy;
    private int saccade;
    private int frames;
    private long frameTick = -1;
    private long frameBegan;
    private int escaped;
    private int skies;
    private double farthest;

    // the last whole frame
    private double lastRange;
    private double lastFarthest;
    private double lastEscapedShare;
    private double lastSkyShare;
    private int lastFrameTicks;

    // ---------------------------------------------------------------- cost

    private int budget = MAX_BUDGET;
    private long rays;
    private long farRays;
    private long nanos;
    private long ticked;
    private double avgNanos;
    private double avgRays;
    private long maxNanos;

    private long lastOn = Long.MIN_VALUE / 2;
    private long lastSave = Long.MIN_VALUE / 2;
    /** A write of this screen still under way on the IO thread: the next one waits, never two on the same file. */
    private final java.util.concurrent.atomic.AtomicBoolean writing = new java.util.concurrent.atomic.AtomicBoolean();
    @Nullable
    private Path file;

    // ---------------------------------------------------------------- per tick scratch (never kept across ticks)

    private final Long2ObjectOpenHashMap<WorldChunk> chunks = new Long2ObjectOpenHashMap<>();
    /** the highest column top of each chunk seen this frame (only numbers, kept for the frame) */
    private final Long2IntOpenHashMap chunkTop = new Long2IntOpenHashMap();
    private final BlockPos.Mutable cell = new BlockPos.Mutable();
    private final BlockPos.Mutable beside = new BlockPos.Mutable();
    private List<Entity> bodies = List.of();
    // the far pass's answer
    private byte farKind;
    private int hitX;
    private int hitY;
    private int hitZ;
    private int hitAxis;
    private double hitT;
    @Nullable
    private BlockState hitState;
    @Nullable
    private WorldChunk hitChunk;
    private int stepX;
    private int stepY;
    private int stepZ;

    // ---------------------------------------------------------------- the picture only (textures): never the mind's
    /** How many blocks one pixel spans per block of distance (its footprint, for the texture's mip level). */
    private static final double PIXEL = 2.0 * Math.tan(Math.toRadians(Retina.VERTICAL_FOV / 2.0)) / HEIGHT;
    /** Nothing nearer than this is painted over the view (the camera's own near plane, a little more). */
    private static final double NEAR_PLANE = 0.2;
    /** this tick's brush: null without textures (a dedicated server), and then every colour is the old one */
    @Nullable
    private Texturas.Pincel brush;
    @Nullable
    private ServerWorld rayWorld;
    private Vec3d rayEye = Vec3d.ZERO;
    private Vec3d rayDir = Vec3d.ZERO;
    private boolean rayNight;
    private int raySky;
    // what the exact ray went through on its way, for this pixel's colour: a plant or a torch, a pane, the water
    private int frontColor;
    private double frontT = Double.POSITIVE_INFINITY;
    private int veilColor;
    private double veilAlpha;
    private double veilT = Double.POSITIVE_INFINITY;
    private int waterColor = -1;
    private final Retina.Paso paso = this::through;

    private Pantalla() {
        chunkTop.defaultReturnValue(Integer.MIN_VALUE);
        java.util.Arrays.fill(face, (byte) -1);
    }

    // ---------------------------------------------------------------- who has one

    /** The screen of this viewer, made if it has none yet. */
    public static Pantalla of(LivingEntity viewer) {
        return ALL.computeIfAbsent(viewer, v -> new Pantalla());
    }

    /** The screen of this viewer, or null when it has none (it never was connected, or not for a while). */
    @Nullable
    public static Pantalla get(LivingEntity viewer) {
        return ALL.get(viewer);
    }

    /** Whether a villager has its own screen running: connected ({@link net.mca.entity.ai.mente.Voluntad#conectado}) and awake. */
    public static boolean on(VillagerEntityMCA villager) {
        return !villager.isSleeping() && net.mca.entity.ai.mente.Voluntad.conectado(villager);
    }

    /**
     * Every tick of every villager on the server: a connected one draws the next rows of its screen; one that stopped
     * being connected keeps its last frame a little while, then lets it go.
     */
    public static void tick(ServerWorld world, VillagerEntityMCA villager) {
        long now = world.getTime();
        // the moment it understands, once a second for a villager whose eye is working (no rays of its own)
        try {
            Ahora.tick(world, villager);
        } catch (RuntimeException e) {
            if (!loggedAhoraError) {
                loggedAhoraError = true;
                MCA.LOGGER.warn("[Tropeles] {} no pudo entender lo que ve: {}", villager.getName().getString(), e.toString());
            }
        }
        Pantalla screen = ALL.get(villager);
        if (!on(villager)) {
            if (screen != null && now - screen.lastOn > DROP_TICKS) {
                ALL.remove(villager);
            }
            return;
        }
        if (screen == null) {
            screen = of(villager);
        }
        screen.step(world, villager);
        if (now - screen.lastSave >= SAVE_TICKS && screen.frames > 0) {
            screen.save(world, villager);
        }
    }

    // ---------------------------------------------------------------- drawing

    /** One tick's share of the frame: as many rays as the budget allows. */
    public void step(ServerWorld world, LivingEntity viewer) {
        long now = world.getTime();
        lastOn = now; // in use: whoever drives it keeps it
        if (serverTick != now) {
            serverTick = now;
            serverNanos = 0;
        }
        if (serverNanos >= SERVER_MICROS * 1000L) {
            return; // the other screens used up this tick
        }
        long began = System.nanoTime();
        int cast = draw(world, viewer, budget);
        long spent = System.nanoTime() - began;
        serverNanos += spent;
        account(cast, spent);
    }

    /** A whole frame at once (for a command or a check): costs a frame's worth of rays in one tick. */
    public void drawAll(ServerWorld world, LivingEntity viewer) {
        lastOn = world.getTime();
        long began = System.nanoTime();
        int cast = draw(world, viewer, PIXELS - cursor);
        account(cast, System.nanoTime() - began);
    }

    private void account(int cast, long spent) {
        rays += cast;
        nanos += spent;
        ticked++;
        maxNanos = Math.max(maxNanos, spent);
        avgNanos = avgNanos == 0 ? spent : avgNanos * 0.95 + spent * 0.05;
        avgRays = avgRays == 0 ? cast : avgRays * 0.95 + cast * 0.05;
        long target = TARGET_MICROS * 1000L;
        if (spent > target && cast > 0) {
            budget = Math.max(MIN_BUDGET, (int) (cast * (double) target / spent));
        } else if (spent < target / 2) {
            budget = Math.min(MAX_BUDGET, budget + Math.max(8, budget / 4));
        }
    }

    private int draw(ServerWorld world, LivingEntity viewer, int count) {
        long now = world.getTime();
        VisualMemory memory = Retina.memory(viewer);
        chunks.clear();
        if (cursor == 0) {
            begin(world, viewer, now);
        }
        Vec3d eye = Retina.eye(viewer);
        Vec3d forward = Retina.forward(viewer);
        Vec3d[] axes = Retina.axes(forward);
        Vec3d right = axes[0];
        Vec3d up = axes[1];
        double tanV = Math.tan(Math.toRadians(Retina.VERTICAL_FOV / 2.0));
        double tanH = tanV * Retina.ASPECT;
        boolean night = Retina.nightVision(viewer);
        int sky = Foto.skyColor(world);
        BlockPos feet = viewer.getBlockPos();
        bodies = bodiesAround(world, viewer, eye, Math.min(range, BODY_RANGE));
        if ((now % 10) == 0) {
            memory.mapa().walked(feet, now);
        }
        brush = Texturas.pincel(world);
        rayWorld = world;
        rayNight = night;
        raySky = sky;
        int cast = 0;
        try {
            while (cast < count) {
                int row = ROWS[cursor / WIDTH];
                int col = cursor % WIDTH;
                double v = (1.0 - (row + 0.5 + jy) / HEIGHT * 2.0) * tanV;
                double u = ((col + 0.5 + jx) / WIDTH * 2.0 - 1.0) * tanH;
                Vec3d dir = forward.add(right.multiply(u)).add(up.multiply(v)).normalize();
                int i = row * WIDTH + col;
                pixel(world, viewer, memory, eye, dir, i, night, sky, now);
                if (dir.y >= Retina.SKY_UP && kind[i] == CIELO) {
                    memory.sawSky(feet, now);
                }
                cast++;
                if (++cursor >= PIXELS) {
                    end(now);
                    if (cast < count) {
                        begin(world, viewer, now);
                    }
                }
            }
        } finally {
            chunks.clear();
            hitChunk = null;
            bodies = List.of();
            brush = null;
            rayWorld = null;
        }
        return cast;
    }

    /** A new frame: its range from the space the last one saw, and a fresh sub-pixel shift. */
    private void begin(ServerWorld world, LivingEntity viewer, long now) {
        cursor = 0;
        frameBegan = now;
        escaped = 0;
        skies = 0;
        farthest = 0;
        chunkTop.clear();
        int k = ++saccade;
        jx = frac(k * 0.7548776662466927) - 0.5;
        jy = frac(k * 0.5698402909980532) - 0.5;
        double cap = Retina.range(viewer);
        if (cap < NEAR) {
            range = cap; // blind: the near pass only, and only that far
            return;
        }
        Lugar lugar = Entorno.lugar(world, viewer);
        boolean open;
        if (lugar == Lugar.AFUERA) {
            open = true;
        } else if (frames == 0) {
            open = lugar == Lugar.BAJO_TECHO; // the first frame under a roof looks as far as it can, the next ones follow it
        } else {
            open = lugar != Lugar.BAJO_TIERRA && lastEscapedShare >= OPEN_SHARE;
        }
        if (open) {
            range = FAR;
        } else if (frames == 0) {
            range = ENCLOSED;
        } else {
            range = Math.max(MIN_RANGE, Math.min(ENCLOSED, lastFarthest * 1.5 + 8.0));
        }
    }

    private void end(long now) {
        frames++;
        lastFrameTicks = (int) Math.max(1, now - frameBegan + 1);
        frameTick = now;
        lastRange = range;
        lastFarthest = farthest;
        lastEscapedShare = escaped / (double) PIXELS;
        lastSkyShare = skies / (double) PIXELS;
        cursor = 0;
    }

    private static double frac(double x) {
        return x - Math.floor(x);
    }

    private void pixel(ServerWorld world, LivingEntity viewer, VisualMemory memory, Vec3d eye, Vec3d dir, int i, boolean night, int sky, long now) {
        double near = Math.min(range, NEAR);
        Vec3d end = eye.add(dir.multiply(near));
        frontT = Double.POSITIVE_INFINITY;
        veilT = Double.POSITIVE_INFINITY;
        waterColor = -1;
        rayEye = eye;
        rayDir = dir;
        // a ray that climbs from above every column top it passes over has nothing to touch: no exact walk for it
        Retina.Sight sight = end.y > eye.y && clearAbove(world, eye, end) ? new Retina.Sight(null, near, false)
                : Retina.cast(world, eye, end, viewer, memory, now, night, brush == null ? null : paso);
        boolean nearHit = sight.hit() != null;
        double limit = nearHit ? sight.distance() : Math.min(range, BODY_RANGE);

        // the nearest body the ray touches before whatever stops it
        Entity body = null;
        double bodyT = limit;
        for (Entity candidate : bodies) {
            Box box = candidate.getBoundingBox().expand(candidate.getTargetingMargin());
            var point = box.raycast(eye, eye.add(dir.multiply(limit)));
            if (point.isPresent()) {
                double d = eye.distanceTo(point.get());
                if (d < bodyT) {
                    bodyT = d;
                    body = candidate;
                }
            }
        }
        if (body != null && (nearHit || bodyT <= near)) {
            body(world, memory, body, bodyT, i, night, sky, sight.water(), now);
            return;
        }
        int water = waterColor >= 0 ? waterColor : Foto.WATER;
        if (nearHit) {
            Retina.Pixel p = Retina.blockPixel(world, memory, sight, near, night, now);
            far[i] = false;
            entity[i] = 0;
            dist[i] = (float) p.distance();
            farthest = Math.max(farthest, p.distance());
            if (p.dark()) {
                set(i, OSCURO, sight.hit().getBlockPos().asLong(), null, -1, DARK);
                return;
            }
            BlockState s = p.state();
            BlockPos at = p.pos();
            Vec3d point = p.hit() != null ? p.hit() : Vec3d.ofCenter(at);
            int color = blockColor(world, at, s, p.face(), p.light(), p.distance(), night, sky,
                    point.x - at.getX(), point.y - at.getY(), point.z - at.getZ());
            if (sight.water()) {
                color = Foto.mix(color, water, 0.45);
            }
            set(i, BLOQUE, at.asLong(), s, p.face().getId(), color);
            return;
        }
        if (range <= near + 1.0E-3) {
            // the end of an enclosed frame's reach: the space goes on past it
            farthest = Math.max(farthest, near);
            escaped++;
            boolean open = openAbove(world, end);
            if (open) {
                skies++;
            }
            far[i] = false;
            dist[i] = (float) near;
            set(i, open ? CIELO : OSCURO, 0, null, -1, open ? (sight.water() ? Foto.mix(sky, water, 0.3) : sky) : DARK);
            return;
        }
        // past the near pass: the far one, over the height columns
        march(world, eye, dir, near, range);
        if (body != null && bodyT < hitT) {
            body(world, memory, body, bodyT, i, night, sky, sight.water(), now);
            return;
        }
        far[i] = true;
        entity[i] = 0;
        dist[i] = (float) hitT;
        switch (farKind) {
            case BLOQUE, AGUA -> {
                farthest = Math.max(farthest, hitT);
                Direction side = faceOf(hitAxis, dir);
                BlockPos at = new BlockPos(hitX, hitY, hitZ);
                BlockState s = hitState;
                if (Mining.isOre(s)) {
                    s = hitY < 0 ? Blocks.DEEPSLATE.getDefaultState() : Blocks.STONE.getDefaultState(); // too far to tell an ore
                }
                beside.set(hitX + side.getOffsetX(), hitY + side.getOffsetY(), hitZ + side.getOffsetZ());
                int light = s.getLuminance() > 0 ? s.getLuminance() : world.getLightLevel(beside);
                if (!Retina.perceives(light, hitT, night)) {
                    if (world.getLightLevel(LightType.SKY, beside) < Retina.DARK_SKY) {
                        memory.mapa().dark(at, now);
                    }
                    set(i, OSCURO, at.asLong(), null, -1, DARK);
                    return;
                }
                int skyLight = side == Direction.UP ? world.getLightLevel(LightType.SKY, beside) : -1;
                memory.mapa().saw(at, s, side, now, skyLight);
                int color = blockColor(world, at, s, side, light, hitT, night, sky,
                        eye.x + dir.x * hitT - hitX, eye.y + dir.y * hitT - hitY, eye.z + dir.z * hitT - hitZ);
                if (sight.water()) {
                    color = Foto.mix(color, water, 0.45);
                }
                set(i, farKind == AGUA ? AGUA : BLOQUE, at.asLong(), s, side.getId(), color);
            }
            case NIEBLA -> {
                escaped++;
                set(i, NIEBLA, 0, null, -1, Foto.mix(sky, FOG, 0.5));
            }
            case OSCURO -> {
                farthest = Math.max(farthest, hitT);
                set(i, OSCURO, 0, null, -1, DARK);
            }
            default -> {
                escaped++;
                skies++;
                set(i, CIELO, 0, null, -1, sight.water() ? Foto.mix(sky, water, 0.3) : sky);
            }
        }
    }

    private void set(int i, byte k, long p, @Nullable BlockState s, int f, int color) {
        kind[i] = k;
        pos[i] = p;
        state[i] = s;
        face[i] = (byte) f;
        rgb[i] = brush == null ? color : dress(color, dist[i]);
    }

    /** The colour of a pixel with what the ray went through in front of it: a plant, a torch, the frame of a pane. */
    private int dress(int color, double d) {
        if (frontT < d) {
            color = frontColor;
            d = frontT;
        }
        if (veilT < d) {
            color = Foto.mix(color, veilColor, veilAlpha);
        }
        return color;
    }

    /**
     * A see-through surface the exact ray went through ({@link Retina.Paso}): its texel, where the model really is
     * (the planes of a flower, the stick of a torch), kept when it is the nearest; the first water gives its tint.
     * Only the colour of the pixel: what the pixel shows, and the memory, are the ray's own.
     */
    private void through(BlockPos pos, BlockState s, BlockHitResult hit, boolean fluid) {
        Texturas.Pincel b = brush;
        ServerWorld world = rayWorld;
        if (b == null || world == null) {
            return;
        }
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        if (fluid) {
            if (waterColor < 0 && s.getFluidState().isIn(FluidTags.WATER)) {
                waterColor = b.water(s, x, y, z);
            }
            return;
        }
        int argb = b.shape(s, x, y, z, rayEye.x, rayEye.y, rayEye.z, rayDir.x, rayDir.y, rayDir.z, PIXEL * Texturas.SIZE);
        double t = b.t;
        int alpha = argb >>> 24;
        if (alpha < Texturas.HOLE || t < NEAR_PLANE || t >= frontT || t >= range) {
            return;
        }
        int light = s.getLuminance() > 0 ? s.getLuminance() : world.getLightLevel(pos);
        if (!Retina.perceives(light, t, rayNight)) {
            return;
        }
        double lit = s.getLuminance() >= 10 ? 1.0 : (b.side == null ? 0.9 : shade(b.side)) * Foto.lightShade(light, t, rayNight);
        int color = Foto.mix(Foto.scale(argb & 0xFFFFFF, lit), raySky, haze(t));
        if (alpha >= Texturas.OPAQUE) {
            frontColor = color;
            frontT = t;
        } else if (t < veilT) {
            veilColor = color;
            veilAlpha = alpha / 255.0 * 0.85;
            veilT = t;
        }
    }

    private void body(ServerWorld world, VisualMemory memory, Entity body, double t, int i, boolean night, int sky, boolean water, long now) {
        int light = world.getLightLevel(body.getBlockPos());
        boolean dark = !Retina.perceives(light, t, night) && !body.isGlowing() && !body.isOnFire();
        far[i] = t > NEAR;
        dist[i] = (float) t;
        farthest = Math.max(farthest, t);
        if (dark) {
            entity[i] = 0;
            set(i, OSCURO, 0, null, -1, DARK);
            return;
        }
        memory.seeEntity(body, t, now);
        int color = Foto.scale(Foto.bodyColor(body), Foto.lightShade(light, t, night || body.isGlowing() || body.isOnFire()));
        color = Foto.mix(color, sky, haze(t));
        if (water) {
            color = Foto.mix(color, Foto.WATER, 0.45);
        }
        entity[i] = body.getId();
        set(i, CUERPO, 0, null, -1, color);
    }

    /** {@code lx, ly, lz}: where on the block (0..1 inside it) the ray touched it, for its texture. */
    private int blockColor(ServerWorld world, BlockPos at, BlockState s, Direction side, int light, double t, boolean night, int sky,
                           double lx, double ly, double lz) {
        int base;
        FluidState fluid = s.getFluidState();
        boolean lava = false;
        if (!fluid.isEmpty() && fluid.isIn(FluidTags.LAVA)) {
            base = Foto.LAVA;
            lava = true;
        } else if (!fluid.isEmpty() && s.getBlock() == fluid.getBlockState().getBlock()) {
            base = Foto.WATER;
        } else {
            MapColor map = s.getMapColor(world, at);
            base = map == MapColor.CLEAR || map.color == 0 ? 0x8A8A8A : map.color;
        }
        if (brush != null) {
            int texel = brush.color(s, side, at.getX(), at.getY(), at.getZ(), lx, ly, lz, Texturas.level(t * PIXEL * Texturas.SIZE));
            if (texel >= 0) {
                base = texel;
            }
        }
        double shade = lava ? 1.0 : shade(side) * Foto.lightShade(light, t, night);
        return Foto.mix(Foto.scale(base, shade), sky, haze(t));
    }

    /** Distance haze of the player's fog: nothing up close, the sky's colour at the edge of the render distance. */
    private static double haze(double t) {
        double f = Math.min(1.0, t / FAR);
        return f * f * 0.75;
    }

    /** Like the game's own flat shading: tops full, north and south faces 0.8, east and west 0.6, bottoms 0.5. */
    private static double shade(@Nullable Direction side) {
        if (side == null) {
            return 1.0;
        }
        return switch (side) {
            case UP -> 1.0;
            case DOWN -> 0.5;
            case NORTH, SOUTH -> 0.8;
            default -> 0.6;
        };
    }

    private static Direction faceOf(int axis, Vec3d dir) {
        return switch (axis) {
            case 0 -> dir.x > 0 ? Direction.WEST : Direction.EAST;
            case 1 -> dir.y > 0 ? Direction.DOWN : Direction.UP;
            case 2 -> dir.z > 0 ? Direction.NORTH : Direction.SOUTH;
            default -> Direction.getFacing(-dir.x, -dir.y, -dir.z);
        };
    }

    /**
     * Whether a rising segment starts above the highest column top of every loaded chunk under it (then it cannot
     * touch a block). False when any of those chunks is not loaded.
     */
    private boolean clearAbove(ServerWorld world, Vec3d from, Vec3d to) {
        int c0x = ((int) Math.floor(Math.min(from.x, to.x))) >> 4;
        int c1x = ((int) Math.floor(Math.max(from.x, to.x))) >> 4;
        int c0z = ((int) Math.floor(Math.min(from.z, to.z))) >> 4;
        int c1z = ((int) Math.floor(Math.max(from.z, to.z))) >> 4;
        for (int cx = c0x; cx <= c1x; cx++) {
            for (int cz = c0z; cz <= c1z; cz++) {
                WorldChunk chunk = chunk(world, cx, cz);
                if (chunk == null || from.y < chunkTop(chunk, cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether the open sky is over this point (nothing that stops movement above it in its column), when loaded. */
    private boolean openAbove(ServerWorld world, Vec3d point) {
        int x = (int) Math.floor(point.x);
        int z = (int) Math.floor(point.z);
        WorldChunk chunk = chunk(world, x >> 4, z >> 4);
        if (chunk == null) {
            return false;
        }
        return point.y >= chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, x & 15, z & 15);
    }

    // ---------------------------------------------------------------- the far pass

    @Nullable
    private WorldChunk chunk(ServerWorld world, int cx, int cz) {
        long key = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
        WorldChunk chunk = chunks.get(key);
        if (chunk == null && !chunks.containsKey(key)) {
            // only what is already loaded: never a load, never a generation
            chunk = world.isChunkLoaded(cx, cz) ? world.getChunkManager().getWorldChunk(cx, cz) : null;
            chunks.put(key, chunk);
        }
        return chunk;
    }

    /** The highest column top of a chunk (computed once a frame, 256 reads of its height map). */
    private int chunkTop(WorldChunk chunk, int cx, int cz) {
        long key = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
        int top = chunkTop.get(key);
        if (top == Integer.MIN_VALUE) {
            top = Integer.MIN_VALUE + 1;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    top = Math.max(top, chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, x, z));
                }
            }
            chunkTop.put(key, top);
        }
        return top;
    }

    /**
     * Walks the ray from {@code t0} to {@code t1} through the voxel grid, reading a block only where the ray is under
     * its column's top, and skipping every chunk it flies over. Leaves the answer in the far fields.
     */
    private void march(ServerWorld world, Vec3d eye, Vec3d dir, double t0, double t1) {
        farRays++;
        double dx = dir.x;
        double dy = dir.y;
        double dz = dir.z;
        int bottom = world.getBottomY();
        int top = world.getTopY();
        stepX = dx > 0 ? 1 : -1;
        stepY = dy > 0 ? 1 : -1;
        stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);

        double t = t0;
        double px = eye.x + dx * t;
        double py = eye.y + dy * t;
        double pz = eye.z + dz * t;
        int x = (int) Math.floor(px);
        int y = (int) Math.floor(py);
        int z = (int) Math.floor(pz);
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : t + (dx > 0 ? x + 1 - px : px - x) * tDeltaX;
        double tMaxY = dy == 0 ? Double.POSITIVE_INFINITY : t + (dy > 0 ? y + 1 - py : py - y) * tDeltaY;
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : t + (dz > 0 ? z + 1 - pz : pz - z) * tDeltaZ;
        int axis = -1;
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;
        WorldChunk chunk = null;
        int colX = Integer.MIN_VALUE;
        int colZ = Integer.MIN_VALUE;
        int colTop = 0;
        hitState = null;
        while (t <= t1) {
            if (y >= top && dy >= 0) {
                farKind = CIELO; // above everything: the sky
                hitT = t1;
                return;
            }
            if (y < bottom && dy <= 0) {
                farKind = OSCURO;
                hitT = t;
                return;
            }
            int cx = x >> 4;
            int cz = z >> 4;
            if (cx != chunkX || cz != chunkZ) {
                chunkX = cx;
                chunkZ = cz;
                chunk = chunk(world, cx, cz);
                if (chunk == null) {
                    farKind = NIEBLA; // not loaded: the edge of what it can see
                    hitT = t;
                    return;
                }
                // a chunk it flies over is crossed in one go
                int chunkMax = chunkTop(chunk, cx, cz);
                double exit = chunkExit(eye, dx, dz, cx, cz);
                double lowest = Math.min(eye.y + dy * t, eye.y + dy * Math.min(exit, t1));
                if (lowest >= chunkMax + 1.0E-6) {
                    if (exit >= t1) {
                        break;
                    }
                    t = exit + 1.0E-6;
                    px = eye.x + dx * t;
                    py = eye.y + dy * t;
                    pz = eye.z + dz * t;
                    x = (int) Math.floor(px);
                    y = (int) Math.floor(py);
                    z = (int) Math.floor(pz);
                    tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : t + (dx > 0 ? x + 1 - px : px - x) * tDeltaX;
                    tMaxY = dy == 0 ? Double.POSITIVE_INFINITY : t + (dy > 0 ? y + 1 - py : py - y) * tDeltaY;
                    tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : t + (dz > 0 ? z + 1 - pz : pz - z) * tDeltaZ;
                    axis = exitAxisX ? 0 : 2;
                    continue;
                }
            }
            if (x != colX || z != colZ) {
                colX = x;
                colZ = z;
                colTop = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, x & 15, z & 15);
            }
            if (y < colTop && y >= bottom) {
                cell.set(x, y, z);
                BlockState s = chunk.getBlockState(cell);
                byte stop = stops(s);
                if (stop != NADA) {
                    farKind = stop;
                    hitX = x;
                    hitY = y;
                    hitZ = z;
                    hitAxis = axis;
                    hitT = t;
                    hitState = s;
                    return;
                }
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
                axis = 0;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
                axis = 1;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                axis = 2;
            }
        }
        // the edge of the render distance: the sky above the horizon, the player's fog below it
        farKind = dy < -0.02 ? NIEBLA : CIELO;
        hitT = t1;
    }

    private boolean exitAxisX;

    /** Where the ray leaves the chunk (cx, cz) horizontally, and by which axis ({@link #exitAxisX}). */
    private double chunkExit(Vec3d eye, double dx, double dz, int cx, int cz) {
        double tx = Double.POSITIVE_INFINITY;
        double tz = Double.POSITIVE_INFINITY;
        if (dx > 0) {
            tx = ((cx << 4) + 16 - eye.x) / dx;
        } else if (dx < 0) {
            tx = ((cx << 4) - eye.x) / dx;
        }
        if (dz > 0) {
            tz = ((cz << 4) + 16 - eye.z) / dz;
        } else if (dz < 0) {
            tz = ((cz << 4) - eye.z) / dz;
        }
        exitAxisX = tx <= tz;
        return Math.min(tx, tz);
    }

    /** What a block under the column's top does to a far ray: nothing (plants, glass), stop as a block, or as water. */
    private static byte stops(BlockState s) {
        if (s.isAir()) {
            return NADA;
        }
        FluidState fluid = s.getFluidState();
        if (!fluid.isEmpty()) {
            if (fluid.isIn(FluidTags.WATER) && s.getBlock() == fluid.getBlockState().getBlock()) {
                return AGUA;
            }
            if (fluid.isIn(FluidTags.LAVA)) {
                return BLOQUE;
            }
        }
        if (CLEAR.computeIfAbsent(s.getBlock(), b -> b == Blocks.GLASS || b instanceof StainedGlassBlock || b instanceof PaneBlock)) {
            return NADA;
        }
        return s.blocksMovement() ? BLOQUE : NADA;
    }

    private static List<Entity> bodiesAround(ServerWorld world, LivingEntity viewer, Vec3d eye, double reach) {
        List<Entity> found = world.getOtherEntities(viewer, new Box(eye, eye).expand(reach),
                e -> !e.isSpectator() && e.isAlive() && !e.isInvisible() && (e instanceof LivingEntity || e instanceof ItemEntity));
        if (found.size() > 32) {
            found.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(eye)));
            return new ArrayList<>(found.subList(0, 32));
        }
        return found;
    }

    // ---------------------------------------------------------------- reading the screen

    public int width() {
        return WIDTH;
    }

    public int height() {
        return HEIGHT;
    }

    /** The pixel at column x, row y (0, 0 is the top left), as last drawn. */
    public Hit hit(int x, int y) {
        int i = y * WIDTH + x;
        byte k = kind[i];
        BlockPos p = k == BLOQUE || k == AGUA ? BlockPos.fromLong(pos[i]) : null;
        Direction f = face[i] >= 0 ? DIRS[face[i]] : null;
        return new Hit(k, p, k == BLOQUE || k == AGUA ? state[i] : null, f, dist[i], k == CUERPO ? entity[i] : 0, far[i], rgb[i]);
    }

    /** A copy of the pixels, 0xRRGGBB, row by row. */
    public int[] rgb() {
        return rgb.clone();
    }

    /** The range of the frame being drawn. */
    public double range() {
        return range;
    }

    /** The range of the last whole frame (0 before the first). */
    public double lastRange() {
        return lastRange;
    }

    /** The farthest thing the last whole frame saw. */
    public double lastFarthest() {
        return lastFarthest;
    }

    public double lastSkyShare() {
        return lastSkyShare;
    }

    /** Whole frames drawn so far. */
    /** The share of the drawn pixels too dark to make out (sky and fog left out); 0 when nothing is drawn. */
    public double darkShare() {
        int n = 0;
        int dark = 0;
        for (byte k : kind) {
            if (k != NADA && k != CIELO && k != NIEBLA) {
                n++;
                if (k == OSCURO) {
                    dark++;
                }
            }
        }
        return n == 0 ? 0 : dark / (double) n;
    }

    public int frames() {
        return frames;
    }

    /** The tick the last whole frame was finished, or -1. */
    public long frameTick() {
        return frameTick;
    }

    /** Ticks the last whole frame took. */
    public int frameTicks() {
        return lastFrameTicks;
    }

    public long rays() {
        return rays;
    }

    public long farRays() {
        return farRays;
    }

    /** Average nanoseconds of the ticks it drew in. */
    public double nanosPerTick() {
        return ticked == 0 ? 0 : nanos / (double) ticked;
    }

    public double nanosPerRay() {
        return rays == 0 ? 0 : nanos / (double) rays;
    }

    /** The cost, measured: rays a tick, microseconds a tick (mean, recent, worst), microseconds a ray, ticks a frame. */
    public String costLine() {
        return String.format(Locale.ROOT, "%d cuadros de %dx%d, %.0f rayos/tick (presupuesto %d), %.0f us/tick de media (reciente %.0f, peor %.0f), %.2f us/rayo, %d ticks/cuadro, %d rayos (%d lejanos)",
                frames, WIDTH, HEIGHT, ticked == 0 ? 0.0 : rays / (double) ticked, budget, nanosPerTick() / 1000.0, avgNanos / 1000.0, maxNanos / 1000.0,
                nanosPerRay() / 1000.0, lastFrameTicks, rays, farRays);
    }

    @Nullable
    public Path file() {
        return file;
    }

    /**
     * What is on the screen, in a few words with directions (left, ahead, right of the view): how much is sky, the
     * ground, trees, water, beings, and how far the view reaches.
     */
    public String resumen(ServerWorld world, boolean es) {
        int drawn = 0;
        int sky = 0;
        int fog = 0;
        Map<String, Integer> ground = new HashMap<>();
        double[] treeNear = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        int trees = 0;
        double[] waterNear = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        int water = 0;
        Map<Integer, double[]> beings = new LinkedHashMap<>(); // id -> {distance, third}
        for (int row = 0; row < HEIGHT; row++) {
            for (int col = 0; col < WIDTH; col++) {
                int i = row * WIDTH + col;
                byte k = kind[i];
                if (k == NADA) {
                    continue;
                }
                drawn++;
                int third = col * 3 / WIDTH;
                switch (k) {
                    case CIELO -> sky++;
                    case NIEBLA -> fog++;
                    case AGUA -> {
                        water++;
                        waterNear[third] = Math.min(waterNear[third], dist[i]);
                    }
                    case CUERPO -> beings.merge(entity[i], new double[]{dist[i], third}, (a, b) -> a[0] <= b[0] ? a : b);
                    case BLOQUE -> {
                        BlockState s = state[i];
                        if (s == null) {
                            break;
                        }
                        if (s.isIn(BlockTags.LEAVES) || MapaVisto.trunk(s)) {
                            trees++;
                            treeNear[third] = Math.min(treeNear[third], dist[i]);
                        } else if (!s.getFluidState().isEmpty() && s.getFluidState().isIn(FluidTags.WATER)) {
                            water++;
                            waterNear[third] = Math.min(waterNear[third], dist[i]);
                        } else if (face[i] == Direction.UP.getId() && row > HEIGHT / 2) {
                            ground.merge(Tacto.nombre(s, es), 1, Integer::sum);
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        if (drawn == 0) {
            return es ? "Todavía no hay nada en la pantalla." : "Nothing on the screen yet.";
        }
        List<String> parts = new ArrayList<>();
        parts.add(String.format(Locale.ROOT, es ? "cielo %d%%" : "sky %d%%", Math.round(100.0 * sky / drawn)));
        if (fog > 0) {
            parts.add(String.format(Locale.ROOT, es ? "niebla (sin cargar) %d%%" : "fog (not loaded) %d%%", Math.round(100.0 * fog / drawn)));
        }
        ground.entrySet().stream().max(Map.Entry.comparingByValue())
                .ifPresent(g -> parts.add((es ? "suelo de " : "ground of ") + g.getKey()));
        if (trees > 0) {
            parts.add((es ? "árboles " : "trees ") + where(treeNear, es));
        }
        if (water > 0) {
            parts.add((es ? "agua " : "water ") + where(waterNear, es));
        }
        for (Map.Entry<Integer, double[]> b : beings.entrySet()) {
            Entity e = world.getEntityById(b.getKey());
            if (e == null) {
                continue;
            }
            String name = e.getName().getString();
            boolean monster = e.getType().getSpawnGroup() == SpawnGroup.MONSTER;
            parts.add((monster ? (es ? "¡" : "") : "") + name + " " + third((int) b.getValue()[1], es)
                    + String.format(Locale.ROOT, es ? " a %.0f bloques" : " %.0f blocks away", b.getValue()[0]) + (monster && es ? "!" : ""));
            if (parts.size() > 10) {
                break;
            }
        }
        String reach = String.format(Locale.ROOT, es ? "alcance %.0f bloques" : "range %.0f blocks", lastRange > 0 ? lastRange : range);
        return String.join(", ", parts) + "; " + reach + ".";
    }

    private static String where(double[] near, boolean es) {
        List<String> out = new ArrayList<>();
        for (int t = 0; t < 3; t++) {
            if (near[t] < Double.MAX_VALUE) {
                out.add(third(t, es) + String.format(Locale.ROOT, es ? " (desde %.0f bloques)" : " (from %.0f blocks)", near[t]));
            }
        }
        return String.join(es ? " y " : " and ", out);
    }

    private static String third(int t, boolean es) {
        return switch (t) {
            case 0 -> es ? "a la izquierda" : "to the left";
            case 1 -> es ? "al frente" : "ahead";
            default -> es ? "a la derecha" : "to the right";
        };
    }

    // ---------------------------------------------------------------- the PNG for the player

    /** Where this viewer's screen is written: {@code <world>/tropeles/vista/<name>.png}. */
    public static Path path(ServerWorld world, LivingEntity viewer) {
        String name = viewer.getName().getString().replaceAll("[^A-Za-z0-9_\\-áéíóúñÁÉÍÓÚÑ]", "_");
        if (name.isEmpty() || name.chars().allMatch(c -> c == '_')) {
            name = viewer.getUuidAsString();
        }
        return world.getServer().getSavePath(WorldSavePath.ROOT).resolve("tropeles").resolve("vista").resolve(name + ".png").toAbsolutePath().normalize();
    }

    /** Writes the latest frame, {@value #SCALE} times larger, off the server thread. */
    public void save(ServerWorld world, LivingEntity viewer) {
        lastSave = world.getTime();
        Path target = path(world, viewer);
        file = target;
        if (!writing.compareAndSet(false, true)) {
            return; // the last frame is still being written: two writers on one .tmp lose it (NoSuchFileException)
        }
        int[] pixels = rgb.clone();
        Util.getIoWorkerExecutor().execute(() -> {
            try {
                int w = WIDTH * SCALE;
                int h = HEIGHT * SCALE;
                int[] big = new int[w * h];
                for (int y = 0; y < h; y++) {
                    int src = (y / SCALE) * WIDTH;
                    for (int x = 0; x < w; x++) {
                        big[y * w + x] = pixels[src + x / SCALE];
                    }
                }
                byte[] png = Foto.encode(big, w, h);
                Path dir = target.getParent();
                if (dir != null) {
                    Files.createDirectories(dir);
                }
                // a temporary file of its own for every write (two viewers with the same name, a write from before a
                // reload): never someone else's .tmp moved away under it
                Path tmp = target.resolveSibling(target.getFileName() + "." + Long.toHexString(System.nanoTime()) + ".tmp");
                try {
                    Files.write(tmp, png);
                } catch (java.nio.file.NoSuchFileException gone) {
                    // the folder went away in between (a world copied or cleaned while running): made again, once
                    if (dir != null) {
                        Files.createDirectories(dir);
                    }
                    Files.write(tmp, png);
                }
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomic) {
                    try {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException locked) {
                        // the PNG is open in a viewer on Windows: written straight over it instead
                        Files.write(target, png);
                        Files.deleteIfExists(tmp);
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (!loggedSaveError) {
                    loggedSaveError = true;
                    MCA.LOGGER.warn("[Tropeles] no pude escribir la pantalla en {}: {}", target, e.toString());
                }
            } finally {
                writing.set(false);
            }
        });
    }
}
