package net.mca.entity.ai.body;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The body's short memory of what its hands did: every block it broke and every block it placed, where and when
 * ({@link Hands} records both). A player notices when they keep digging and filling the same hole; so does this:
 * a cell it touched {@link #LOOP_TOUCHES} times within {@link #LOOP_WINDOW} ticks is a loop, and that cell is left
 * alone for {@link #FORBID} ticks ({@link Hands} refuses it, {@link Motor} plans round it and reports BLOCKED), so
 * whoever drives the body has to choose another way.
 */
public final class Toques {
    /** A block it broke is not filled again (nor one it placed dug out again) for this long, unless on purpose. */
    public static final int RECENT = 200;
    public static final int LOOP_WINDOW = 400;
    public static final int LOOP_TOUCHES = 3;
    public static final int FORBID = 1200;
    /** What a touch was for: anything its hands did, a step or bridge block {@link Motor} set as its floor, a block of a
     * pillar it climbs on purpose (remembered, but never a sign of a loop), a cell {@link Motor} dug to get through. */
    public static final int HAND = 0;
    public static final int STEP = 1;
    public static final int PILLAR = 2;
    public static final int DIG = 3;
    /** Touches kept at most: older ones are forgotten first. */
    private static final int KEEP = 256;

    private static final class Toque {
        final long pos;
        final long at;
        final boolean placed;
        /** What the touch was for: {@link #HAND}, {@link #STEP}, {@link #PILLAR} or {@link #DIG}. */
        int kind = HAND;

        Toque(long pos, long at, boolean placed) {
            this.pos = pos;
            this.at = at;
            this.placed = placed;
        }

        long pos() {
            return pos;
        }

        long at() {
            return at;
        }

        boolean placed() {
            return placed;
        }
    }

    private static final class Memoria {
        final Deque<Toque> toques = new ArrayDeque<>();
        final Map<Long, Long> forbidden = new HashMap<>();
        @Nullable
        BlockPos lastLoop;
        long lastLoopAt;
    }

    private static final Map<VillagerEntityMCA, Memoria> ALL = new WeakHashMap<>();

    private Toques() {
    }

    private static synchronized Memoria of(VillagerEntityMCA villager) {
        return ALL.computeIfAbsent(villager, v -> new Memoria());
    }

    public static void broke(VillagerEntityMCA villager, BlockPos pos, long now) {
        touch(villager, pos, now, false);
    }

    public static void placed(VillagerEntityMCA villager, BlockPos pos, long now) {
        touch(villager, pos, now, true);
    }

    private static void touch(VillagerEntityMCA villager, BlockPos pos, long now, boolean placed) {
        Memoria m = of(villager);
        long key = pos.asLong();
        m.toques.addLast(new Toque(key, now, placed));
        while (m.toques.size() > KEEP || !m.toques.isEmpty() && now - m.toques.peekFirst().at() > Math.max(LOOP_WINDOW, RECENT)) {
            m.toques.removeFirst();
        }
        int count = count(m, key, now);
        if (count >= LOOP_TOUCHES && !m.forbidden.containsKey(key)) {
            m.forbidden.put(key, now + FORBID);
            m.lastLoop = pos.toImmutable();
            m.lastLoopAt = now;
            net.mca.MCA.LOGGER.info("[Tropeles] {} rompe y pone {} una y otra vez ({} veces en {} ticks): deja ese bloque en paz {} ticks",
                    villager.getName().getString(), pos.toShortString(), count, LOOP_WINDOW, FORBID);
            net.mca.entity.ai.sense.Retina.memory(villager).doing("deja de romper y poner el mismo bloque: busca otro camino", now);
        }
    }

    private static boolean recently(VillagerEntityMCA villager, BlockPos pos, long now, boolean placed) {
        Memoria m = ALL.get(villager);
        if (m == null) {
            return false;
        }
        long key = pos.asLong();
        Iterator<Toque> it = m.toques.descendingIterator();
        while (it.hasNext()) {
            Toque t = it.next();
            if (now - t.at() > RECENT) {
                return false;
            }
            if (t.pos() == key) {
                // only the latest touch of that cell counts: broken then set again is "placed" now
                return t.placed() == placed;
            }
        }
        return false;
    }

    /** It broke {@code pos} within {@link #RECENT} ticks (and has not set anything there since). */
    public static boolean brokeRecently(VillagerEntityMCA villager, BlockPos pos, long now) {
        return recently(villager, pos, now, false);
    }

    /** It placed {@code pos} within {@link #RECENT} ticks (and has not broken it since). */
    public static boolean placedRecently(VillagerEntityMCA villager, BlockPos pos, long now) {
        return recently(villager, pos, now, true);
    }

    /** Touched {@link #LOOP_TOUCHES} times or more within {@link #LOOP_WINDOW} ticks: left alone for {@link #FORBID}. */
    public static boolean forbidden(VillagerEntityMCA villager, BlockPos pos, long now) {
        Memoria m = ALL.get(villager);
        if (m == null || m.forbidden.isEmpty()) {
            return false;
        }
        Long until = m.forbidden.get(pos.asLong());
        if (until == null) {
            return false;
        }
        if (now >= until) {
            m.forbidden.remove(pos.asLong());
            return false;
        }
        return true;
    }

    public static Set<BlockPos> forbiddenCells(VillagerEntityMCA villager, long now) {
        Memoria m = ALL.get(villager);
        Set<BlockPos> cells = new HashSet<>();
        if (m == null) {
            return cells;
        }
        m.forbidden.values().removeIf(until -> now >= until);
        for (long key : m.forbidden.keySet()) {
            cells.add(BlockPos.fromLong(key));
        }
        return cells;
    }

    /** The last cell that turned out to be a loop, or null. */
    @Nullable
    public static BlockPos lastLoop(VillagerEntityMCA villager) {
        Memoria m = ALL.get(villager);
        return m == null ? null : m.lastLoop;
    }

    /** The last loop cell, forgotten as it is handed over. */
    @Nullable
    public static BlockPos takeLoop(VillagerEntityMCA villager) {
        Memoria m = ALL.get(villager);
        if (m == null) {
            return null;
        }
        BlockPos loop = m.lastLoop;
        m.lastLoop = null;
        return loop;
    }

    private static int count(Memoria m, long key, long now) {
        int count = 0;
        for (Toque t : m.toques) {
            if (t.pos() == key && t.kind != PILLAR && now - t.at() <= LOOP_WINDOW) {
                count++;
            }
        }
        return count;
    }

    /**
     * What the latest touch of {@code pos} was for ({@link #STEP}, {@link #PILLAR}, {@link #DIG}). A {@link #PILLAR}
     * block still counts as placed just now, but not toward a loop.
     */
    public static void mark(VillagerEntityMCA villager, BlockPos pos, int kind) {
        Memoria m = ALL.get(villager);
        if (m == null) {
            return;
        }
        long key = pos.asLong();
        Iterator<Toque> it = m.toques.descendingIterator();
        while (it.hasNext()) {
            Toque t = it.next();
            if (t.pos() == key) {
                t.kind = kind;
                return;
            }
        }
    }

    /**
     * The latest touch of {@code pos} within {@link #RECENT} ticks was this one ({@code placed} or broken, for
     * {@code kind}); false when it touched it otherwise since, or not lately.
     */
    public static boolean lately(VillagerEntityMCA villager, BlockPos pos, long now, boolean placed, int kind) {
        Memoria m = ALL.get(villager);
        if (m == null) {
            return false;
        }
        long key = pos.asLong();
        Iterator<Toque> it = m.toques.descendingIterator();
        while (it.hasNext()) {
            Toque t = it.next();
            if (now - t.at() > RECENT) {
                return false;
            }
            if (t.pos() == key) {
                return t.placed() == placed && t.kind == kind;
            }
        }
        return false;
    }

    /** How many times it touched {@code pos} within the last {@link #LOOP_WINDOW} ticks, pillar blocks aside (for the checks). */
    public static int touches(VillagerEntityMCA villager, BlockPos pos, long now) {
        Memoria m = ALL.get(villager);
        return m == null ? 0 : count(m, pos.asLong(), now);
    }

    public static void clear(VillagerEntityMCA villager) {
        synchronized (Toques.class) {
            ALL.remove(villager);
        }
    }
}
