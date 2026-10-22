package net.mca.entity.ai.sense;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.mca.entity.ai.body.Mining;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PillarBlock;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Direction.Axis;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The land as far as one villager's eyes have seen it: a coarse map of {@value #CELL}x{@value #CELL} columns, filled
 * only by what rays actually hit ({@link Retina}) and by where its own feet walked. Each cell keeps when it was last
 * seen, the highest standable top seen in it and what showed there (trees, water, lava, open land, rock, buildings,
 * a dark opening). Exploration reads it to go where it has not looked yet; nothing in here comes from the world
 * directly.
 */
public final class MapaVisto {
    public static final int CELL = 4;
    /** Cells kept before the oldest are forgotten. */
    public static final int MAX_CELLS = 16384;

    /** What showed in a cell (bit flags). */
    public static final int TREE = 1;
    public static final int WATER = 2;
    public static final int LAVA = 4;
    public static final int LAND = 8;
    public static final int ROCK = 16;
    public static final int BUILT = 32;
    public static final int DARK = 64;
    public static final int WALKED = 128;

    /**
     * A tree the eye keeps looking past without seeing it again is gone (chopped, or never more than a glimpse):
     * after this many ticks of the cell being seen with no trunk or leaves in it, it stops counting as a tree.
     */
    public static final int TREE_FADE = 1200;
    /** Ground lit by this much sky or more is the open land, not the floor of a cave. */
    public static final int SUNLIT = 12;

    private static final int NONE = Integer.MIN_VALUE;
    private static final Map<Block, Boolean> TRUNK = new IdentityHashMap<>();

    private final Long2ObjectLinkedOpenHashMap<Cell> cells = new Long2ObjectLinkedOpenHashMap<>();

    /** One cell as the explorer and the scene read it. {@code land} is the standable top, or null. */
    public record Celda(int x, int z, int flags, long lastSeen, int trees, @Nullable BlockPos land, int surface) {
        public BlockPos center(int y) {
            return land != null ? land : new BlockPos(x, y, z);
        }
    }

    private static final class Cell {
        final int cx;
        final int cz;
        long lastSeen;
        long treeTick = Long.MIN_VALUE;
        int flags;
        /** highest standable top of natural ground (or where the feet walked): x, y, z */
        int topX;
        int topY = NONE;
        int topZ;
        /** highest sunlit ground or water surface: the level of the open land here */
        int surface = NONE;
        /** columns of the cell where an upright trunk showed (4x4 bits) */
        int trunks;

        Cell(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }

        int flags() {
            int f = flags;
            if ((f & TREE) != 0 && lastSeen - treeTick > TREE_FADE) {
                f &= ~TREE;
            }
            return f;
        }

        Celda view() {
            int f = flags();
            BlockPos land = topY == NONE ? null : new BlockPos(topX, topY, topZ);
            return new Celda(cx * CELL + CELL / 2, cz * CELL + CELL / 2, f, lastSeen, (f & TREE) == 0 ? 0 : Math.max(1, Integer.bitCount(trunks)), land, surface);
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private Cell cell(int x, int z, long tick) {
        int cx = x >> 2;
        int cz = z >> 2;
        long key = key(cx, cz);
        Cell cell = cells.getAndMoveToLast(key);
        if (cell == null) {
            cell = new Cell(cx, cz);
            cells.putAndMoveToLast(key, cell);
            if (cells.size() > MAX_CELLS) {
                cells.removeFirst();
            }
        }
        cell.lastSeen = Math.max(cell.lastSeen, tick);
        return cell;
    }

    @Nullable
    private Cell peekCell(int x, int z) {
        return cells.get(key(x >> 2, z >> 2));
    }

    /** A ray hit {@code state} at {@code pos} on {@code face}. Called by {@link Retina} only. */
    void saw(BlockPos pos, BlockState state, @Nullable Direction face, long tick) {
        saw(pos, state, face, tick, -1);
    }

    /**
     * The same, with the sky light on the face it hit when that face looks up ({@code -1} when not known): ground in
     * daylight is the open land, ground in the dark is a cave floor.
     */
    void saw(BlockPos pos, BlockState state, @Nullable Direction face, long tick, int sky) {
        Cell cell = cell(pos.getX(), pos.getZ(), tick);
        boolean up = face == Direction.UP;
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            if (fluid.isIn(FluidTags.LAVA)) {
                cell.flags |= LAVA;
            } else if (fluid.isIn(FluidTags.WATER)) {
                cell.flags |= WATER;
                if (up && sky >= SUNLIT) {
                    cell.surface = Math.max(cell.surface, pos.getY() + 1);
                }
            }
            if (state.getBlock() == fluid.getBlockState().getBlock()) {
                return; // a plain fluid block, nothing else to it
            }
        }
        if (state.isIn(BlockTags.LEAVES)) {
            if (Obras.natural(state)) {
                cell.flags |= TREE;
                cell.treeTick = tick;
            }
            return;
        }
        if (trunk(state)) {
            cell.flags |= TREE;
            cell.treeTick = tick;
            cell.trunks |= 1 << (((pos.getX() & 3) << 2) | (pos.getZ() & 3));
            return;
        }
        boolean ground = ground(state);
        if (ground && up) {
            cell.flags |= LAND;
        } else if (Mining.isRock(state) && !ground) {
            cell.flags |= ROCK;
        } else if (Obras.made(state)) {
            cell.flags |= BUILT;
        }
        if (up && (ground || (Mining.isRock(state) && Obras.natural(state)))) {
            if (pos.getY() + 1 > cell.topY) {
                cell.topX = pos.getX();
                cell.topY = pos.getY() + 1;
                cell.topZ = pos.getZ();
            }
            if (sky >= SUNLIT) {
                cell.surface = Math.max(cell.surface, pos.getY() + 1);
            }
        }
    }

    /** A dark hole the eye looked into and could not see the end of: a cave mouth, a pit, a mine. */
    void dark(BlockPos pos, long tick) {
        cell(pos.getX(), pos.getZ(), tick).flags |= DARK;
    }

    /** Grass, dirt, sand, snow and the like: what the open land is made of. */
    static boolean ground(BlockState state) {
        return state.isIn(BlockTags.DIRT) || state.isIn(BlockTags.SAND) || state.isOf(Blocks.SNOW_BLOCK) || state.isOf(Blocks.SNOW)
                || state.isOf(Blocks.POWDER_SNOW) || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.CLAY) || state.isOf(Blocks.DIRT_PATH)
                || state.isOf(Blocks.FARMLAND) || state.isOf(Blocks.MYCELIUM) || state.isOf(Blocks.PODZOL);
    }

    /** An upright natural log, judged by its tag and axis alone (a ray cannot afford to walk the whole tree). */
    static boolean trunk(BlockState state) {
        if (!state.isIn(BlockTags.LOGS)) {
            return false;
        }
        if (state.contains(PillarBlock.AXIS) && state.get(PillarBlock.AXIS) != Axis.Y) {
            return false;
        }
        return TRUNK.computeIfAbsent(state.getBlock(), b -> !Registries.BLOCK.getId(b).getPath().startsWith("stripped_"));
    }

    /** Its feet stood here. */
    public void walked(BlockPos feet, long tick) {
        Cell cell = cell(feet.getX(), feet.getZ(), tick);
        cell.flags |= WALKED;
        if (cell.topY == NONE) {
            cell.topX = feet.getX();
            cell.topY = feet.getY();
            cell.topZ = feet.getZ();
        }
    }

    /** Anything at all seen or walked in the cell holding column (x, z). */
    public boolean known(int x, int z) {
        return peekCell(x, z) != null;
    }

    /** The flags seen in the cell holding column (x, z), 0 when unknown. */
    public int flags(int x, int z) {
        Cell cell = peekCell(x, z);
        return cell == null ? 0 : cell.flags();
    }

    /** The highest standable top its eye saw in the cell holding (x, z) (the cell above the ground), or null. */
    @Nullable
    public BlockPos land(int x, int z) {
        Cell cell = peekCell(x, z);
        return cell == null || cell.topY == NONE ? null : new BlockPos(cell.topX, cell.topY, cell.topZ);
    }

    /** The level of the open land seen in the cell holding (x, z): sunlit ground or water surface, or null. */
    @Nullable
    public Integer surface(int x, int z) {
        Cell cell = peekCell(x, z);
        return cell == null || cell.surface == NONE ? null : cell.surface;
    }

    /** When the cell holding (x, z) was last seen, or -1. */
    public long lastSeen(int x, int z) {
        Cell cell = peekCell(x, z);
        return cell == null ? -1 : cell.lastSeen;
    }

    /**
     * How much of a sector is still unknown, 0 (all seen) to 1 (nothing seen): the cells between {@code near} and
     * {@code far} blocks from {@code from} within 22.5 degrees of {@code yaw} (degrees, Minecraft yaw).
     */
    public double novelty(BlockPos from, float yaw, int near, int far) {
        int total = 0;
        int unknown = 0;
        double cos = Math.cos(Math.toRadians(22.5));
        double fx = -MathHelper.sin(yaw * MathHelper.RADIANS_PER_DEGREE);
        double fz = MathHelper.cos(yaw * MathHelper.RADIANS_PER_DEGREE);
        int c0x = (from.getX() - far) >> 2;
        int c1x = (from.getX() + far) >> 2;
        int c0z = (from.getZ() - far) >> 2;
        int c1z = (from.getZ() + far) >> 2;
        for (int cx = c0x; cx <= c1x; cx++) {
            for (int cz = c0z; cz <= c1z; cz++) {
                double dx = cx * CELL + CELL / 2.0 - from.getX();
                double dz = cz * CELL + CELL / 2.0 - from.getZ();
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < near || d > far || d < 1.0E-3) {
                    continue;
                }
                if ((dx * fx + dz * fz) / d < cos) {
                    continue;
                }
                total++;
                if (!cells.containsKey(key(cx, cz))) {
                    unknown++;
                }
            }
        }
        return total == 0 ? 1.0 : (double) unknown / total;
    }

    /** Centres (standable tops when known) of cells where trees showed, nearest to {@code near} first, within {@code radius}. */
    public List<BlockPos> trees(BlockPos near, int radius) {
        return find(near, radius, TREE);
    }

    /** Cells with any of {@code flags}, nearest first, within {@code radius}. */
    public List<BlockPos> find(BlockPos near, int radius, int flags) {
        List<BlockPos> found = new ArrayList<>();
        for (Celda c : cells(near, radius)) {
            if ((c.flags() & flags) != 0) {
                found.add(c.center(near.getY()));
            }
        }
        return found;
    }

    /** Every known cell whose centre lies within {@code radius} blocks of {@code near} (horizontally), nearest first. */
    public List<Celda> cells(BlockPos near, int radius) {
        List<Celda> found = new ArrayList<>();
        long r2 = (long) radius * radius;
        int span = (2 * radius) / CELL + 1;
        if ((long) span * span < cells.size()) {
            int c0x = (near.getX() - radius) >> 2;
            int c1x = (near.getX() + radius) >> 2;
            int c0z = (near.getZ() - radius) >> 2;
            int c1z = (near.getZ() + radius) >> 2;
            for (int cx = c0x; cx <= c1x; cx++) {
                for (int cz = c0z; cz <= c1z; cz++) {
                    Cell cell = cells.get(key(cx, cz));
                    if (cell != null && within(cell, near, r2)) {
                        found.add(cell.view());
                    }
                }
            }
        } else {
            for (Cell cell : cells.values()) {
                if (within(cell, near, r2)) {
                    found.add(cell.view());
                }
            }
        }
        found.sort(Comparator.comparingDouble(c -> sq(c.x() - near.getX()) + sq(c.z() - near.getZ())));
        return found;
    }

    private static boolean within(Cell cell, BlockPos near, long r2) {
        double dx = cell.cx * CELL + CELL / 2.0 - near.getX();
        double dz = cell.cz * CELL + CELL / 2.0 - near.getZ();
        return dx * dx + dz * dz <= r2;
    }

    private static double sq(double v) {
        return v * v;
    }

    /**
     * The level of the open land around (x, z) as its eye saw it: the median of the sunlit ground seen in the cells
     * within {@code radius} blocks, or null when it saw none there. The median, not the highest: beside a cliff
     * the land is where most of it is, while down a shaft or a pit it is high on every side.
     */
    @Nullable
    public Integer landLevel(int x, int z, int radius) {
        List<Integer> levels = new ArrayList<>();
        int c0x = (x - radius) >> 2;
        int c1x = (x + radius) >> 2;
        int c0z = (z - radius) >> 2;
        int c1z = (z + radius) >> 2;
        for (int cx = c0x; cx <= c1x; cx++) {
            for (int cz = c0z; cz <= c1z; cz++) {
                Cell cell = cells.get(key(cx, cz));
                if (cell != null && cell.surface != NONE) {
                    levels.add(cell.surface);
                }
            }
        }
        if (levels.isEmpty()) {
            return null;
        }
        levels.sort(Integer::compare);
        return levels.get(levels.size() / 2);
    }

    public int size() {
        return cells.size();
    }

    public void clear() {
        cells.clear();
    }
}
