package net.mca.entity.ai.body;

import net.mca.util.WorldUtils;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The way into a mine as the miner's feet know it: one breadcrumb for every cell it stood on solidly, from the
 * last open-sky cell it went in from (the door) down to where it is digging. Walking back over its own steps
 * erases the loop, so the trail is always the short way back. Climbing out follows it backwards, going back to
 * work follows it forwards. Only cells it stood on with a floor under its feet count: never the lip of a step
 * it was jumping from, so its own staircase never reads as a pillar. The trail is kept with the world, so a
 * miner that went home for food finds its mine and its work face again.
 */
public final class Mina {
    /** Crumbs kept at most; past that the oldest half (the door side) is dropped. */
    public static final int MAX = 1024;

    private long[] cells = new long[64];
    private int size;
    private final Map<Long, Integer> index = new HashMap<>();
    /** Crumb 0 is the open-sky cell it went in from. */
    private boolean door;
    /** Where the tunnel was heading and which side the next branch goes, when it left the work face. */
    public int dir;
    public boolean left;
    /** How many blocks above or below the usual layer it had moved its branch mine. */
    public int levelShift;
    /** World time it was last saved. */
    public long savedAt;

    public int size() {
        return size;
    }

    public BlockPos crumb(int i) {
        return BlockPos.fromLong(cells[i]);
    }

    /** The deepest crumb: where it was working. Null for an empty trail. */
    @Nullable
    public BlockPos last() {
        return size == 0 ? null : crumb(size - 1);
    }

    /** The open-sky cell the trail starts at, or null when the trail began underground. */
    @Nullable
    public BlockPos entrance() {
        return door && size > 0 ? crumb(0) : null;
    }

    /** Whether this cell is one of the trail's crumbs. */
    public boolean has(BlockPos pos) {
        return index.containsKey(pos.asLong());
    }

    /** A trail it can walk in from the surface and back out: it knows the door and at least one step inside. */
    public boolean known() {
        return door && size >= 2;
    }

    public void clear() {
        size = 0;
        index.clear();
        door = false;
        levelShift = 0;
    }

    /**
     * It stands outside on the surface: the mine starts here, where the sky ends. True when it started a new trail
     * (a solid open-sky cell other than the door it already had).
     */
    public boolean outside(World world, LivingEntity miner) {
        BlockPos feet = miner.getBlockPos();
        if (!solid(world, miner, feet)) {
            return false;
        }
        long key = feet.asLong();
        if (door && size == 1 && cells[0] == key) {
            return false;
        }
        int shift = levelShift;
        clear();
        levelShift = shift;
        push(key);
        door = true;
        return true;
    }

    /**
     * The feet moved underground. Recorded only when it stands on a floor with room for its body; a cell it
     * already passed cuts the loop it walked since. True when the trail grew by a new cell.
     */
    public boolean walked(World world, LivingEntity miner) {
        BlockPos feet = miner.getBlockPos();
        if (!solid(world, miner, feet)) {
            return false;
        }
        return append(feet.asLong());
    }

    private static boolean solid(World world, LivingEntity miner, BlockPos feet) {
        return miner.isOnGround() && Mining.standable(world, feet.down()) && Mining.passable(world, feet) && Mining.passable(world, feet.up());
    }

    private boolean append(long key) {
        if (size > 0 && cells[size - 1] == key) {
            return false;
        }
        Integer at = index.get(key);
        if (at != null) {
            truncate(at + 1);
            return false;
        }
        if (size >= MAX) {
            // too long to keep it all: the deep half stays, the way out from it is found again by climbing
            int drop = MAX / 2;
            System.arraycopy(cells, drop, cells, 0, size - drop);
            size -= drop;
            door = false;
            reindex();
        }
        push(key);
        return true;
    }

    private void push(long key) {
        if (size == cells.length) {
            cells = Arrays.copyOf(cells, Math.min(MAX, cells.length * 2));
        }
        index.put(key, size);
        cells[size++] = key;
    }

    private void truncate(int newSize) {
        for (int i = newSize; i < size; i++) {
            index.remove(cells[i]);
        }
        size = Math.max(0, newSize);
    }

    private void reindex() {
        index.clear();
        for (int i = 0; i < size; i++) {
            index.put(cells[i], i);
        }
    }

    /** Room for the body in that crumb still: nothing it placed or that fell has filled it since. */
    public boolean open(World world, int i) {
        BlockPos cell = crumb(i);
        return Mining.passable(world, cell) && Mining.passable(world, cell.up());
    }

    /** The lowest-numbered open crumb at or touching the feet (the 27 cells around them), -1 when none: the way out. */
    public int nearLow(World world, BlockPos feet) {
        int best = -1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Integer at = index.get(BlockPos.asLong(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz));
                    if (at != null && (best < 0 || at < best) && open(world, at)) {
                        best = at;
                    }
                }
            }
        }
        return best;
    }

    /** The highest-numbered open crumb at or touching the feet, -1 when none: the way in. */
    public int nearHigh(World world, BlockPos feet) {
        int best = -1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Integer at = index.get(BlockPos.asLong(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz));
                    if (at != null && at > best && open(world, at)) {
                        best = at;
                    }
                }
            }
        }
        return best;
    }

    /**
     * It climbed out another way (a staircase of its own, recorded bottom to top in {@code climb}) after leaving
     * the trail at crumb {@code fromIndex} (-1: it was not on it). The new way in is that climb, then the deep part
     * of the old trail. Only a climb it could walk back down counts: every cell still open with its floor, each one
     * touching the next. A pillar fills the cells it stood on, so it never qualifies. True when the trail changed.
     */
    public boolean rebase(World world, List<BlockPos> climb, int fromIndex) {
        if (climb.isEmpty()) {
            return false;
        }
        BlockPos previous = fromIndex >= 0 && fromIndex < size ? crumb(fromIndex) : null;
        for (BlockPos cell : climb) {
            if (!Mining.passable(world, cell) || !Mining.passable(world, cell.up()) || !Mining.standable(world, cell.down())) {
                return false;
            }
            if (previous != null && !touching(previous, cell)) {
                return false;
            }
            previous = cell;
        }
        List<Long> order = new ArrayList<>();
        for (int i = climb.size() - 1; i >= 0; i--) {
            order.add(climb.get(i).asLong());
        }
        if (fromIndex >= 0) {
            for (int i = fromIndex; i < size; i++) {
                order.add(cells[i]);
            }
        }
        int keepDir = dir;
        boolean keepLeft = left;
        int keepShift = levelShift;
        clear();
        for (long key : order) {
            append(key);
        }
        door = true;
        dir = keepDir;
        left = keepLeft;
        levelShift = keepShift;
        return true;
    }

    private static boolean touching(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) <= 1 && Math.abs(a.getY() - b.getY()) <= 1 && Math.abs(a.getZ() - b.getZ()) <= 1;
    }

    // ------------------------------------------------------------------ kept with the world

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putLongArray("c", Arrays.copyOf(cells, size));
        nbt.putBoolean("door", door);
        nbt.putInt("dir", dir);
        nbt.putBoolean("left", left);
        nbt.putInt("shift", levelShift);
        nbt.putLong("t", savedAt);
        return nbt;
    }

    public static Mina fromNbt(NbtCompound nbt) {
        Mina mina = new Mina();
        for (long key : nbt.getLongArray("c")) {
            mina.append(key);
        }
        mina.door = nbt.getBoolean("door") && mina.size > 0;
        mina.dir = nbt.getInt("dir") & 3;
        mina.left = nbt.getBoolean("left");
        mina.levelShift = nbt.getInt("shift");
        mina.savedAt = nbt.getLong("t");
        return mina;
    }

    /** The mine this villager last worked in this world, or null. */
    @Nullable
    public static Mina load(ServerWorld world, UUID miner) {
        NbtCompound nbt = Registro.get(world).mines.get(miner);
        return nbt == null ? null : fromNbt(nbt);
    }

    public void save(ServerWorld world, UUID miner) {
        savedAt = world.getTime();
        Registro registro = Registro.get(world);
        registro.mines.put(miner, toNbt());
        registro.markDirty();
    }

    public static void forget(ServerWorld world, UUID miner) {
        Registro registro = Registro.get(world);
        if (registro.mines.remove(miner) != null) {
            registro.markDirty();
        }
    }

    /** Every miner's trail in one world, saved with it. */
    public static final class Registro extends PersistentState {
        private final Map<UUID, NbtCompound> mines = new HashMap<>();

        private Registro() {
        }

        private Registro(NbtCompound nbt) {
            NbtCompound all = nbt.getCompound("mines");
            for (String key : all.getKeys()) {
                try {
                    mines.put(UUID.fromString(key), all.getCompound(key));
                } catch (IllegalArgumentException ignored) {
                    // not a villager id: skip it
                }
            }
        }

        static Registro get(ServerWorld world) {
            return WorldUtils.loadData(world, Registro::new, w -> new Registro(), "mca_tropeles_minas");
        }

        @Override
        public NbtCompound writeNbt(NbtCompound nbt) {
            NbtCompound all = new NbtCompound();
            mines.forEach((id, mine) -> all.put(id.toString(), mine));
            nbt.put("mines", all);
            return nbt;
        }
    }
}
