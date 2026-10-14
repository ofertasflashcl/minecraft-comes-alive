package net.mca.entity.ai.sense;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Everything one villager's eyes have recorded. Nothing gets in here except through {@link Retina}:
 * a block is known only once a ray has hit its real shape, and an entity only once a ray reached it.
 * A cell that a ray later crosses as empty is corrected, so a mined or changed block is forgotten
 * the moment the villager looks at the hole.
 */
public final class VisualMemory {
    public static final int MAX_BLOCKS = 8192;
    public static final int ENTITY_MEMORY_TICKS = 600;

    private final Long2ObjectLinkedOpenHashMap<SeenBlock> blocks = new Long2ObjectLinkedOpenHashMap<>();
    private final Map<Integer, SeenEntity> entities = new HashMap<>();

    @Nullable
    private Retina.Pixel[] frame;
    private int frameWidth;
    private int frameHeight;
    private long frameTick = -1;
    private int interlace;
    @Nullable
    private Retina.Focus focus;
    private String doing = "";
    private long doingTick;

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
    }

    /** A ray went through this cell without touching anything: whatever was remembered there is gone. */
    void empty(BlockPos pos) {
        blocks.remove(pos.asLong());
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

    int nextInterlace(int bands) {
        interlace = (interlace + 1) % bands;
        return interlace;
    }

    void focus(@Nullable Retina.Focus focus) {
        this.focus = focus;
    }

    @Nullable
    public SeenBlock block(BlockPos pos) {
        return blocks.get(pos.asLong());
    }

    @Nullable
    public BlockState stateAt(BlockPos pos) {
        SeenBlock seen = blocks.get(pos.asLong());
        return seen == null ? null : seen.state;
    }

    public boolean saw(BlockPos pos, long since) {
        SeenBlock seen = blocks.get(pos.asLong());
        return seen != null && seen.lastSeen >= since;
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

    void prune(long now, int maxAge) {
        ObjectIterator<SeenBlock> it = blocks.values().iterator();
        while (it.hasNext()) {
            if (now - it.next().lastSeen > maxAge) {
                it.remove();
            }
        }
    }
}
