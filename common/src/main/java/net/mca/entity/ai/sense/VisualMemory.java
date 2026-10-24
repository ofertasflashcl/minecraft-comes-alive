package net.mca.entity.ai.sense;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.mca.entity.ai.body.Mining;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Everything one villager's eyes have recorded. Nothing gets in here except through {@link Retina} (and the near
 * pass of its {@link Pantalla}, which casts the retina's own rays and records them the same way):
 * a block is known only once a ray has hit its real shape, and an entity only once a ray reached it.
 * A cell that a ray later crosses as empty is corrected, so a mined or changed block is forgotten
 * the moment the villager looks at the hole.
 */
public final class VisualMemory {
    public static final int MAX_BLOCKS = 8192;
    public static final int ENTITY_MEMORY_TICKS = 600;
    /** Ore sightings kept apart from the rest: a diamond seen an hour ago is still worth remembering. */
    public static final int MAX_ORES = 1024;

    private final Long2ObjectLinkedOpenHashMap<SeenBlock> blocks = new Long2ObjectLinkedOpenHashMap<>();
    /**
     * Every ore the eye has seen and not yet seen gone, oldest first. It outlives the block memory's own limit,
     * and lets the miner look over its ores without going through every block it ever saw.
     */
    private final Long2ObjectLinkedOpenHashMap<SeenBlock> ores = new Long2ObjectLinkedOpenHashMap<>();
    private final Map<Integer, SeenEntity> entities = new HashMap<>();

    @Nullable
    private Retina.Pixel[] frame;
    private int frameWidth;
    private int frameHeight;
    private long frameTick = -1;
    private int interlace;
    private int saccade;
    @Nullable
    private Retina.Focus focus;
    private String doing = "";
    private long doingTick;
    private final MapaVisto mapa = new MapaVisto();
    private long lastLook = Long.MIN_VALUE / 2;
    private long lastSweep = Long.MIN_VALUE / 2;
    /** the last time the eye saw open sky nearly straight up, and where its feet were then */
    private long skyTick = Long.MIN_VALUE / 2;
    @Nullable
    private BlockPos skyFeet;
    /** the last place it stood outside at the level of the land */
    @Nullable
    private BlockPos outside;
    private long outsideTick = Long.MIN_VALUE / 2;
    /** {@link Entorno}'s answer for one tick, so asking it every tick costs one look-up */
    long placeTick = Long.MIN_VALUE;
    @Nullable
    Lugar place;
    long depthTick = Long.MIN_VALUE;
    int depth;
    long shelterTick = Long.MIN_VALUE;
    boolean shelter;

    public static final class SeenBlock {
        public final BlockPos pos;
        public BlockState state;
        public Direction face;
        public Vec3d hit;
        public double distance;
        public int light;
        public long lastSeen;
        public boolean seeThrough;

        SeenBlock(BlockPos pos) {
            this.pos = pos;
        }
    }

    public static final class SeenEntity {
        public final int id;
        public final EntityType<?> type;
        public Vec3d pos;
        public Vec3d velocity;
        public Box box;
        public double distance;
        public long lastSeen;

        SeenEntity(int id, EntityType<?> type) {
            this.id = id;
            this.type = type;
        }
    }

    /** Something the eye just looked at directly (the hands use this while they work on it). */
    public void see(BlockPos pos, BlockState state, Direction face, Vec3d hit, double distance, int light, long tick) {
        see(pos, state, face, hit, distance, light, tick, false);
    }

    void see(BlockPos pos, BlockState state, Direction face, Vec3d hit, double distance, int light, long tick, boolean seeThrough) {
        see(pos, state, face, hit, distance, light, tick, seeThrough, -1);
    }

    /** {@code sky}: the sky light on the face when it looks up, -1 when not known. Feeds the {@link #mapa()} too. */
    void see(BlockPos pos, BlockState state, Direction face, Vec3d hit, double distance, int light, long tick, boolean seeThrough, int sky) {
        mapa.saw(pos, state, face, tick, sky);
        long key = pos.asLong();
        SeenBlock seen = blocks.getAndMoveToLast(key);
        if (seen == null) {
            seen = new SeenBlock(pos.toImmutable());
            blocks.putAndMoveToLast(key, seen);
            if (blocks.size() > MAX_BLOCKS) {
                blocks.removeFirst();
            }
        }
        seen.state = state;
        seen.face = face;
        seen.hit = hit;
        seen.distance = distance;
        seen.light = light;
        seen.lastSeen = tick;
        seen.seeThrough = seeThrough;
        if (Mining.isOre(state)) {
            ores.putAndMoveToLast(key, seen);
            if (ores.size() > MAX_ORES) {
                ores.removeFirst();
            }
        } else {
            ores.remove(key);
        }
    }

    /** A ray went through this cell without touching anything: whatever was remembered there is gone. */
    void empty(BlockPos pos) {
        blocks.remove(pos.asLong());
        ores.remove(pos.asLong());
    }

    void seeEntity(Entity entity, double distance, long tick) {
        SeenEntity seen = entities.computeIfAbsent(entity.getId(), id -> new SeenEntity(id, entity.getType()));
        Vec3d previous = seen.pos;
        seen.pos = entity.getPos();
        seen.velocity = previous == null || tick - seen.lastSeen > 4 ? entity.getVelocity() : seen.pos.subtract(previous).multiply(1.0 / Math.max(1, tick - seen.lastSeen));
        seen.box = entity.getBoundingBox();
        seen.distance = distance;
        seen.lastSeen = tick;
    }

    void frame(Retina.Pixel[] pixels, int width, int height, long tick) {
        this.frame = pixels;
        this.frameWidth = width;
        this.frameHeight = height;
        this.frameTick = tick;
    }

    /** The next term of the saccade sequence. */
    int nextSaccade() {
        return ++saccade;
    }

    int nextInterlace(int bands) {
        interlace = (interlace + 1) % bands;
        return interlace;
    }

    void focus(@Nullable Retina.Focus focus) {
        this.focus = focus;
    }

    @Nullable
    public SeenBlock block(BlockPos pos) {
        long key = pos.asLong();
        SeenBlock seen = blocks.get(key);
        return seen != null ? seen : ores.get(key);
    }

    @Nullable
    public BlockState stateAt(BlockPos pos) {
        SeenBlock seen = block(pos);
        return seen == null ? null : seen.state;
    }

    public boolean saw(BlockPos pos, long since) {
        SeenBlock seen = block(pos);
        return seen != null && seen.lastSeen >= since;
    }

    /** The ores it remembers, oldest sighting first (a copy: the caller may forget some while going through it). */
    public Collection<SeenBlock> ores() {
        return new ArrayList<>(ores.values());
    }

    /** The ore it remembers at {@code pos}, or null when it remembers none there. */
    @Nullable
    public BlockState oreAt(BlockPos pos) {
        SeenBlock seen = ores.get(pos.asLong());
        return seen == null ? null : seen.state;
    }

    public int oreCount() {
        return ores.size();
    }

    public List<SeenBlock> blocks(Predicate<SeenBlock> filter) {
        List<SeenBlock> found = new ArrayList<>();
        for (SeenBlock seen : blocks.values()) {
            if (filter.test(seen)) {
                found.add(seen);
            }
        }
        return found;
    }

    public void forget(BlockPos pos) {
        blocks.remove(pos.asLong());
        ores.remove(pos.asLong());
    }

    public int blockCount() {
        return blocks.size();
    }

    @Nullable
    public SeenEntity entity(int id) {
        return entities.get(id);
    }

    public List<SeenEntity> entities(long now, int maxAge) {
        List<SeenEntity> found = new ArrayList<>();
        Iterator<SeenEntity> it = entities.values().iterator();
        while (it.hasNext()) {
            SeenEntity seen = it.next();
            if (now - seen.lastSeen > ENTITY_MEMORY_TICKS) {
                it.remove();
            } else if (now - seen.lastSeen <= maxAge) {
                found.add(seen);
            }
        }
        return found;
    }

    public void forgetEntity(int id) {
        entities.remove(id);
    }

    @Nullable
    public Retina.Pixel[] frame() {
        return frame;
    }

    public int frameWidth() {
        return frameWidth;
    }

    public int frameHeight() {
        return frameHeight;
    }

    public long frameTick() {
        return frameTick;
    }

    @Nullable
    public Retina.Focus focus() {
        return focus;
    }

    /** What the villager says it is doing, for the eye report. */
    public void doing(String what, long tick) {
        this.doing = what;
        this.doingTick = tick;
    }

    public String doing() {
        return doing;
    }

    public long doingTick() {
        return doingTick;
    }

    /** The coarse map of the land its eyes have seen, one per villager. */
    public MapaVisto mapa() {
        return mapa;
    }

    /** The eye scanned a band (or a whole frame) at this tick. */
    void looked(long tick) {
        lastLook = tick;
    }

    /** The last tick the eye scanned anything, so a second driver of the eye can stand aside. */
    public long lastLook() {
        return lastLook;
    }

    void swept(long tick) {
        lastSweep = tick;
    }

    /** The last tick it looked all around at once ({@link Mirada#lookAround}). */
    public long lastSweep() {
        return lastSweep;
    }

    /** The eye just saw open sky nearly straight overhead while its feet stood at {@code feet}. */
    void sawSky(BlockPos feet, long tick) {
        skyTick = tick;
        skyFeet = feet.toImmutable();
    }

    public long skyTick() {
        return skyTick;
    }

    /** Where its feet were when it last saw the sky overhead, or null. */
    @Nullable
    public BlockPos skyFeet() {
        return skyFeet;
    }

    /** It stood outside, at the level of the land, here. */
    void outside(BlockPos feet, long tick) {
        outside = feet.toImmutable();
        outsideTick = tick;
    }

    /** The last place it stood outside at the level of the land, or null. */
    @Nullable
    public BlockPos lastOutside() {
        return outside;
    }

    public long lastOutsideTick() {
        return outsideTick;
    }

    void prune(long now, int maxAge) {
        ObjectIterator<SeenBlock> it = blocks.values().iterator();
        while (it.hasNext()) {
            if (now - it.next().lastSeen > maxAge) {
                it.remove();
            }
        }
        // an ore stays in mind four times as long as rock does
        ObjectIterator<SeenBlock> ore = ores.values().iterator();
        while (ore.hasNext()) {
            if (now - ore.next().lastSeen > 4L * maxAge) {
                ore.remove();
            }
        }
    }
}
