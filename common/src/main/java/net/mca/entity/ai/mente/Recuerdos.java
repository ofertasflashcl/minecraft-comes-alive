package net.mca.entity.ai.mente;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mca.MCA;
import net.mca.entity.ai.body.Mining;
import net.mca.entity.ai.sense.Entorno;
import net.mca.entity.ai.sense.Lugar;
import net.mca.entity.ai.sense.VisualMemory;
import net.mca.util.WorldUtils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * What a villager remembers of its own life, the way a player remembers the way they came: every cell its feet stood
 * on solidly (up to {@value #MAX_PASOS}, the least recently visited forgotten first), with when it was there last and
 * what kind of place it was ({@link Lugar}, and how far below the land); and a diary of what happened to it (who said
 * what to it and what it answered, what it found, fights, trips home, chests, crafts, getting stuck), the last
 * {@value #MAX_HECHOS} of them. Plus the places that matter to it by name: its chest, its home, the mouth of its mine,
 * its work face.
 * <p>
 * The walked cells are a graph it can find its way through: {@link #camino} looks for a way over cells it really
 * walked, each one checked at touch level that it still has room for the body and a floor, to a cell that was out at
 * the level of the land ({@link #fuera}) or to a named spot. That is how it walks back up a staircase it dug days
 * ago instead of cutting another one beside it.
 * <p>
 * Kept with the world (one record per dimension, like {@link net.mca.entity.ai.body.Mina}), so a relog or a restart
 * keeps every step. Server thread only.
 */
public final class Recuerdos {
    /** Walked cells kept at most; past that the one visited longest ago goes. */
    public static final int MAX_PASOS = 30000;
    /** Diary entries kept at most; past that the oldest goes. */
    public static final int MAX_HECHOS = 300;
    /** A cell walked at most this many blocks under the land, not down in the rock, counts as out at the land's level. */
    public static final int LAND_DEPTH = 2;
    /** Cells looked at in one search for a way, at most (a long mine and a walk home fit). */
    public static final int SEARCH_NODES = 16000;
    /** Villagers whose memories are kept per world, at most; the one that walked longest ago is forgotten first. */
    private static final int MAX_VILLAGERS = 256;

    /** One walked cell: when it stood there last, how many times, and what kind of place it was. */
    public static final class Paso {
        long t;
        byte lugar;
        byte hondo;
        int veces;

        public long cuando() {
            return t;
        }

        public Lugar lugar() {
            Lugar[] all = Lugar.values();
            return lugar >= 0 && lugar < all.length ? all[lugar] : Lugar.BAJO_TIERRA;
        }

        /** Blocks under the land when it stood here. */
        public int hondo() {
            return hondo;
        }

        public int veces() {
            return veces;
        }

        /** Out, or under a roof, at the level of the land: a way up out of the rock can end here. */
        public boolean fuera() {
            return lugar() != Lugar.BAJO_TIERRA && hondo <= LAND_DEPTH;
        }
    }

    /** Something that happened: when, what kind ({@code charla, dijo, hallazgo, pelea, viaje, cofre, oficio, camino, atasco}), in its words, where. */
    public record Hecho(long t, String tipo, String texto, @Nullable BlockPos donde) {
    }

    private final LinkedHashMap<Long, Paso> pasos = new LinkedHashMap<>(1024, 0.75F, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Paso> eldest) {
            return size() > MAX_PASOS;
        }
    };
    private final Deque<Hecho> hechos = new ArrayDeque<>();
    private final Map<String, BlockPos> sitios = new LinkedHashMap<>();
    private final Map<String, Long> sitiosCuando = new HashMap<>();
    /** Cells found shut on a way it tried (a block fell in, someone built there): skipped until then. Not kept. */
    private final Map<Long, Long> cerrados = new HashMap<>();
    private long ultimoPie = Long.MIN_VALUE;
    private long tocado;
    @Nullable
    private Registro registro;

    private static final Map<Entity, Recuerdos> VIVOS = new WeakHashMap<>();

    private Recuerdos() {
    }

    // ------------------------------------------------------------------ finding them

    /** The memories of this villager in this world, as kept with it. */
    public static Recuerdos of(ServerWorld world, LivingEntity villager) {
        Recuerdos r = VIVOS.get(villager);
        if (r != null && r.registro != null && r.registro.world == world && r.registro.vivos.get(villager.getUuid()) == r) {
            return r;
        }
        Registro registro = Registro.get(world);
        r = registro.of(villager.getUuid());
        VIVOS.put(villager, r);
        return r;
    }

    /** The memories of this villager where it is, or null when it is not in a server world. */
    @Nullable
    public static Recuerdos of(LivingEntity villager) {
        return villager.getWorld() instanceof ServerWorld world ? of(world, villager) : null;
    }

    /** Forgets a villager entirely (a check's stand-in when it is done). */
    public static void olvidar(ServerWorld world, LivingEntity villager) {
        VIVOS.remove(villager);
        Registro registro = Registro.get(world);
        if (registro.vivos.remove(villager.getUuid()) != null | registro.crudos.remove(villager.getUuid()) != null) {
            registro.markDirty();
        }
    }

    /** Notes something that happened to {@code villager} (server thread); never throws. */
    public static void anotar(LivingEntity villager, String tipo, String texto, @Nullable BlockPos donde) {
        try {
            Recuerdos r = of(villager);
            if (r != null) {
                r.anotar(villager.getWorld().getTime(), tipo, texto, donde);
            }
        } catch (RuntimeException e) {
            MCA.LOGGER.warn("[Tropeles] {} no pudo recordar «{}»: {}", villager.getName().getString(), texto, e.toString());
        }
    }

    /** Names a place that matters to {@code villager} ("cofre", "casa", "mina", "frente"); never throws. */
    public static void sitio(LivingEntity villager, String nombre, BlockPos pos) {
        try {
            Recuerdos r = of(villager);
            if (r != null) {
                r.sitio(nombre, pos, villager.getWorld().getTime());
            }
        } catch (RuntimeException e) {
            MCA.LOGGER.warn("[Tropeles] {} no pudo recordar su {}: {}", villager.getName().getString(), nombre, e.toString());
        }
    }

    // ------------------------------------------------------------------ the steps

    /**
     * The feet moved: the cell it stands on now is remembered, when it stands there solidly (on the ground, a floor
     * under it, room for its body). Cheap when the feet did not move. True when the cell is one it never stood on before.
     */
    public boolean paso(ServerWorld world, LivingEntity villager) {
        BlockPos feet = villager.getBlockPos();
        long key = feet.asLong();
        if (key == ultimoPie || !villager.isOnGround()) {
            return false;
        }
        if (!Mining.standable(world, feet.down()) || !Mining.passable(world, feet) || !Mining.passable(world, feet.up())) {
            return false;
        }
        ultimoPie = key;
        long now = world.getTime();
        Paso p = pasos.remove(key);
        boolean nuevo = p == null;
        if (nuevo) {
            p = new Paso();
        }
        p.t = now;
        p.veces++;
        Lugar lugar = Entorno.lugar(world, villager);
        p.lugar = (byte) lugar.ordinal();
        p.hondo = (byte) Math.min(127, Entorno.profundidad(world, villager));
        // put back last: the most recently visited cells are the last ones to be forgotten
        pasos.put(key, p);
        cerrados.remove(key);
        dirty(now);
        return nuevo;
    }

    /** For the checks: remembers {@code feet} as walked now, as {@link #paso} would, the place judged where the villager stands. */
    public void pisado(ServerWorld world, LivingEntity villager, BlockPos feet) {
        long key = feet.asLong();
        Paso p = pasos.remove(key);
        if (p == null) {
            p = new Paso();
        }
        p.t = world.getTime();
        p.veces++;
        p.lugar = (byte) Entorno.lugar(world, villager).ordinal();
        p.hondo = (byte) Math.min(127, Entorno.profundidad(world, villager));
        pasos.put(key, p);
        dirty(p.t);
    }

    public int size() {
        return pasos.size();
    }

    @Nullable
    public Paso paso(BlockPos pos) {
        return pasos.get(pos.asLong());
    }

    public boolean recuerda(BlockPos pos) {
        return pasos.containsKey(pos.asLong());
    }

    /** That cell was found shut on a way it tried: it is not part of any way until {@code until}. */
    public void cerrar(BlockPos pos, long until) {
        cerrados.put(pos.asLong(), until);
        if (cerrados.size() > 512) {
            Iterator<Long> it = cerrados.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    private boolean cerrado(long key, long now) {
        Long until = cerrados.get(key);
        if (until == null) {
            return false;
        }
        if (until < now) {
            cerrados.remove(key);
            return false;
        }
        return true;
    }

    /**
     * A way out: over walked cells to the nearest one that was right on the land (out or under a roof, not a block
     * below it), else to the nearest one at most {@value #LAND_DEPTH} below it; never lower than the feet.
     * {@code skip}: ends already tried this trip.
     */
    public List<BlockPos> caminoAfuera(ServerWorld world, BlockPos from, Set<Long> skip) {
        return caminoAfuera(world, from, skip, null);
    }

    /**
     * The same, the way a player looks for the way up before cutting a new staircase: the remembered way may be a
     * block or two to the side of where it stands ({@value #GAP} cells it never stood on, in a row, are crossed by
     * touch), and with {@code vista} a way over cells whose floor its eyes saw (a staircase, a ramp, steps it never
     * walked) counts too, up to {@value #SEEN_GAP} of them in a row, and may end at a cell that is out on open ground.
     */
    public List<BlockPos> caminoAfuera(ServerWorld world, BlockPos from, Set<Long> skip, @Nullable VisualMemory vista) {
        int y = from.getY();
        Predicate<BlockPos> openEnd = vista == null ? null
                : pos -> pos.getY() >= y + 2 && !skip.contains(pos.asLong()) && Mining.onSurface(world, pos) && Mining.openGround(world, pos);
        List<BlockPos> way = camino(world, from, (pos, p) -> p.fuera() && p.hondo == 0 && pos.getY() >= y && !skip.contains(pos.asLong()),
                SEARCH_NODES, vista, openEnd);
        if (!way.isEmpty()) {
            return way;
        }
        return camino(world, from, (pos, p) -> p.fuera() && pos.getY() >= y && !skip.contains(pos.asLong()), SEARCH_NODES, vista, openEnd);
    }

    /** A way over walked cells to within {@code near} blocks of {@code target} (its work face, the mouth of its mine). */
    public List<BlockPos> caminoHacia(ServerWorld world, BlockPos from, BlockPos target, int near) {
        double d2 = (double) near * near;
        return camino(world, from, (pos, p) -> pos.getSquaredDistance(target) <= d2, SEARCH_NODES);
    }

    /** What a way's end must be. */
    @FunctionalInterface
    public interface Meta {
        boolean es(BlockPos pos, Paso paso);
    }

    /** Cells it never stood on that a remembered way may cross in a row: the way is a block or two to the side. */
    public static final int GAP = 3;
    /** The same, over cells whose floor the eye saw: a staircase or a ramp beside it that it never walked. */
    public static final int SEEN_GAP = 12;

    /**
     * The shortest way, counted in steps, over cells it walked, from the feet (or a walked cell touching them) to the
     * first cell {@code meta} accepts. Every cell of the way is checked now, by touch: room for the body and a floor,
     * headroom for the jump up a step or the drop down one, a corner it can get round on a diagonal. Empty when there
     * is no such way (or the feet are already at one).
     */
    public List<BlockPos> camino(ServerWorld world, BlockPos from, Meta meta, int maxNodes) {
        return camino(world, from, meta, maxNodes, null, null);
    }

    /**
     * As above, but a way may also cross cells it never stood on: up to {@value #GAP} in a row (the remembered way is
     * a little to the side), or up to {@value #SEEN_GAP} in a row whose floor {@code vista} saw. Such a cell can end the
     * way only when {@code openEnd} accepts it. Without {@code vista} only walked cells, as above.
     */
    public List<BlockPos> camino(ServerWorld world, BlockPos from, Meta meta, int maxNodes, @Nullable VisualMemory vista,
                                 @Nullable Predicate<BlockPos> openEnd) {
        long now = world.getTime();
        boolean gaps = vista != null;
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, Integer> gap = new HashMap<>();
        Map<Long, Boolean> room = new HashMap<>();
        ArrayDeque<Long> open = new ArrayDeque<>();
        long start = from.asLong();
        parent.put(start, Long.MIN_VALUE);
        gap.put(start, 0);
        open.add(start);
        int nodes = 0;
        BlockPos.Mutable a = new BlockPos.Mutable();
        BlockPos.Mutable b = new BlockPos.Mutable();
        while (!open.isEmpty() && nodes++ < maxNodes) {
            long at = open.poll();
            a.set(at);
            Paso here = pasos.get(at);
            if (at != start) {
                if (here != null && meta.es(a, here) || here == null && openEnd != null && openEnd.test(a)) {
                    return back(parent, at, start);
                }
            }
            int gapHere = gap.getOrDefault(at, 0);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        long next = BlockPos.asLong(a.getX() + dx, a.getY() + dy, a.getZ() + dz);
                        if (parent.containsKey(next) || cerrado(next, now)) {
                            continue;
                        }
                        boolean walked = pasos.containsKey(next);
                        int gapNext = 0;
                        if (!walked) {
                            if (!gaps) {
                                continue;
                            }
                            gapNext = gapHere + 1;
                            if (gapNext > SEEN_GAP || gapNext > GAP && !seenFloor(vista, BlockPos.fromLong(next))) {
                                continue;
                            }
                        }
                        b.set(next);
                        if (!room(world, room, b) || !transit(world, a, b, dx, dy, dz)) {
                            continue;
                        }
                        parent.put(next, at);
                        gap.put(next, gapNext);
                        open.add(next);
                    }
                }
            }
        }
        return List.of();
    }

    /** The eye saw something to stand on under that cell. */
    private static boolean seenFloor(@Nullable VisualMemory vista, BlockPos cell) {
        if (vista == null) {
            return false;
        }
        net.minecraft.block.BlockState floor = vista.stateAt(cell.down());
        return floor != null && !floor.isAir() && floor.getFluidState().isEmpty();
    }

    private static List<BlockPos> back(Map<Long, Long> parent, long end, long start) {
        List<BlockPos> way = new ArrayList<>();
        for (long at = end; at != start && at != Long.MIN_VALUE; at = parent.get(at)) {
            way.add(BlockPos.fromLong(at));
        }
        Collections.reverse(way);
        return way;
    }

    /** Room for the body in that cell and something under it, by touch (cached for one search). */
    private static boolean room(ServerWorld world, Map<Long, Boolean> cache, BlockPos cell) {
        return cache.computeIfAbsent(cell.asLong(), k -> Mining.passable(world, cell) && Mining.passable(world, cell.up())
                && Mining.standable(world, cell.down()) && !Mining.heat(world, cell));
    }

    /** It can get from {@code a} to the touching cell {@code b}: head room for a step up or down, a corner open on a diagonal. */
    private static boolean transit(ServerWorld world, BlockPos a, BlockPos b, int dx, int dy, int dz) {
        if (dy > 0 && !Mining.passable(world, a.up(2))) {
            return false;
        }
        if (dy < 0 && !Mining.passable(world, b.up(2))) {
            return false;
        }
        if (dx != 0 && dz != 0) {
            int y = Math.max(a.getY(), b.getY());
            BlockPos c1 = new BlockPos(a.getX() + dx, y, a.getZ());
            BlockPos c2 = new BlockPos(a.getX(), y, a.getZ() + dz);
            return Mining.passable(world, c1) && Mining.passable(world, c1.up()) || Mining.passable(world, c2) && Mining.passable(world, c2.up());
        }
        return true;
    }

    /**
     * The nearest walked cell (other than the feet and the cells touching them) that still has room for the body, within
     * {@code radius}; out at the land's level first when {@code fuera}. Null when none.
     */
    @Nullable
    public BlockPos abiertoCerca(ServerWorld world, BlockPos feet, int radius, boolean fuera) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        double r2 = (double) radius * radius;
        int looked = 0;
        // the most recent first: what it walked lately is what is around it now
        List<Long> keys = new ArrayList<>(pasos.keySet());
        for (int i = keys.size() - 1; i >= 0 && looked < 4000; i--, looked++) {
            BlockPos pos = BlockPos.fromLong(keys.get(i));
            double d2 = pos.getSquaredDistance(feet);
            if (d2 > r2 || Math.abs(pos.getX() - feet.getX()) <= 1 && Math.abs(pos.getY() - feet.getY()) <= 1 && Math.abs(pos.getZ() - feet.getZ()) <= 1) {
                continue;
            }
            Paso p = pasos.get(keys.get(i));
            double score = d2 - (fuera && p.fuera() ? 400 : 0);
            if (score < bestScore && Mining.passable(world, pos) && Mining.passable(world, pos.up())) {
                bestScore = score;
                best = pos;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the diary and the places

    public void anotar(long t, String tipo, String texto, @Nullable BlockPos donde) {
        Hecho last = hechos.peekLast();
        if (last != null && last.tipo().equals(tipo) && last.texto().equals(texto) && t - last.t() < 200) {
            return; // the same thing said again a moment later is one memory
        }
        hechos.addLast(new Hecho(t, tipo, texto.length() > 240 ? texto.substring(0, 240) + "…" : texto, donde == null ? null : donde.toImmutable()));
        while (hechos.size() > MAX_HECHOS) {
            hechos.removeFirst();
        }
        dirty(t);
    }

    /** The last {@code n} things that happened, oldest first. */
    public List<Hecho> hechos(int n) {
        List<Hecho> all = new ArrayList<>(hechos);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    /** The last {@code n} of one kind (or kinds, "a|b"), oldest first. */
    public List<Hecho> hechos(String tipos, int n) {
        Set<String> want = Set.of(tipos.split("\\|"));
        List<Hecho> out = new ArrayList<>();
        Iterator<Hecho> it = hechos.descendingIterator();
        while (it.hasNext() && out.size() < n) {
            Hecho h = it.next();
            if (want.contains(h.tipo())) {
                out.add(h);
            }
        }
        Collections.reverse(out);
        return out;
    }

    public void sitio(String nombre, BlockPos pos, long t) {
        BlockPos old = sitios.get(nombre);
        if (pos.equals(old)) {
            sitiosCuando.put(nombre, t);
            return;
        }
        sitios.put(nombre, pos.toImmutable());
        sitiosCuando.put(nombre, t);
        dirty(t);
    }

    @Nullable
    public BlockPos sitio(String nombre) {
        return sitios.get(nombre);
    }

    public Map<String, BlockPos> sitios() {
        return Collections.unmodifiableMap(sitios);
    }

    /** What a chest it set down away from home, to empty a full bag into, is called among its places ("cofre de campo 2"). */
    public static final String COFRE_CAMPO = "cofre de campo";

    /** The name of its field chest at {@code pos}: the one it already has, or the next "cofre de campo N". */
    public String nombreCofreDeCampo(BlockPos pos) {
        int n = 0;
        for (Map.Entry<String, BlockPos> e : sitios.entrySet()) {
            if (e.getKey().startsWith(COFRE_CAMPO)) {
                if (e.getValue().equals(pos)) {
                    return e.getKey();
                }
                try {
                    n = Math.max(n, Integer.parseInt(e.getKey().substring(COFRE_CAMPO.length()).trim()));
                } catch (NumberFormatException ignored) {
                    n = Math.max(n, 1);
                }
            }
        }
        return COFRE_CAMPO + " " + (n + 1);
    }

    /** Its field chests by name, as it remembers them (they may be gone since). */
    public Map<String, BlockPos> cofresDeCampo() {
        Map<String, BlockPos> out = new LinkedHashMap<>();
        sitios.forEach((name, pos) -> {
            if (name.startsWith(COFRE_CAMPO)) {
                out.put(name, pos);
            }
        });
        return out;
    }

    /** Forgets a named place (a field chest that is gone). */
    public void olvidarSitio(String nombre, long t) {
        if (sitios.remove(nombre) != null) {
            sitiosCuando.remove(nombre);
            dirty(t);
        }
    }

    /** "hace un momento", "hace 3 min", "hace 2 h", "hace 1 día" (in-game time). */
    public static String hace(long now, long t, boolean es) {
        long d = Math.max(0, now - t);
        if (d < 400) {
            return es ? "hace un momento" : "a moment ago";
        }
        if (d < 72000) {
            long m = Math.max(1, d / 1200);
            return es ? "hace " + m + " min" : m + " min ago";
        }
        if (d < 24000L * 20) {
            long h = d / 72000;
            return es ? "hace " + h + " h" : h + " h ago";
        }
        long days = d / (24000L * 20);
        return es ? "hace " + days + (days == 1 ? " día" : " días") : days + (days == 1 ? " day ago" : " days ago");
    }

    /** For the language models: the last few things that happened, the places it knows and how much ground it remembers. */
    public String forModel(long now, int n) {
        StringBuilder s = new StringBuilder();
        List<Hecho> last = hechos(n);
        if (!last.isEmpty()) {
            s.append("Your recent memories, oldest first (in Spanish): ");
            for (int i = 0; i < last.size(); i++) {
                Hecho h = last.get(i);
                s.append(i == 0 ? "" : "; ").append(hace(now, h.t(), true)).append(": ").append(h.texto());
            }
            s.append(". ");
        }
        if (!sitios.isEmpty()) {
            List<String> places = new ArrayList<>();
            sitios.forEach((name, pos) -> places.add(name + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ()));
            s.append("Places you know (exact X Y Z): ").append(String.join(", ", places)).append(". ");
        }
        if (!pasos.isEmpty()) {
            s.append("You remember ").append(pasos.size()).append(" spots you walked on, so you know your way back along them. ");
        }
        return s.toString();
    }

    // ------------------------------------------------------------------ the words of its own announcements

    private static Map<String, String> frases;

    /** One of its chore lines ("chore.mining.stored") as it says it in Spanish, its parameters filled in. */
    public static String frase(String key, Object... params) {
        if (frases == null) {
            Map<String, String> map = new HashMap<>();
            try (InputStream in = Recuerdos.class.getResourceAsStream("/assets/mca_dialogue/lang/es_es.json")) {
                if (in != null) {
                    JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                    json.entrySet().forEach(e -> {
                        if (e.getKey().startsWith("chore.") && e.getValue().isJsonPrimitive()) {
                            map.put(e.getKey(), e.getValue().getAsString());
                        }
                    });
                }
            } catch (Exception e) {
                MCA.LOGGER.warn("[Tropeles] no pude leer las frases en español: {}", e.toString());
            }
            frases = map;
        }
        String pattern = frases.get(key);
        Object[] args = new Object[params.length + 1];
        args[0] = "";
        for (int i = 0; i < params.length; i++) {
            args[i + 1] = params[i] instanceof Text text ? text.getString() : String.valueOf(params[i]);
        }
        if (pattern == null) {
            return key + (params.length == 0 ? "" : " " + String.join(" ", java.util.Arrays.stream(args).skip(1).map(String::valueOf).toList()));
        }
        try {
            return String.format(pattern, args);
        } catch (RuntimeException e) {
            return pattern;
        }
    }

    // ------------------------------------------------------------------ kept with the world

    private void dirty(long now) {
        tocado = now;
        if (registro != null) {
            registro.markDirty();
        }
    }

    NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        int n = pasos.size();
        long[] cells = new long[n];
        long[] meta = new long[n];
        int i = 0;
        for (Map.Entry<Long, Paso> e : pasos.entrySet()) {
            Paso p = e.getValue();
            cells[i] = e.getKey();
            // the time in the high bits, then how many visits (capped), the depth and the kind of place
            meta[i] = p.t << 24 | (long) Math.min(255, p.veces) << 16 | (long) (p.hondo & 0xff) << 8 | p.lugar & 0xff;
            i++;
        }
        nbt.putLongArray("c", cells);
        nbt.putLongArray("m", meta);
        NbtList diary = new NbtList();
        for (Hecho h : hechos) {
            NbtCompound e = new NbtCompound();
            e.putLong("t", h.t());
            e.putString("k", h.tipo());
            e.putString("s", h.texto());
            if (h.donde() != null) {
                e.putLong("p", h.donde().asLong());
            }
            diary.add(e);
        }
        nbt.put("h", diary);
        NbtCompound places = new NbtCompound();
        sitios.forEach((name, pos) -> places.putLong(name, pos.asLong()));
        nbt.put("s", places);
        nbt.putLong("tocado", tocado);
        return nbt;
    }

    static Recuerdos fromNbt(NbtCompound nbt) {
        Recuerdos r = new Recuerdos();
        long[] cells = nbt.getLongArray("c");
        long[] meta = nbt.getLongArray("m");
        for (int i = 0; i < cells.length && i < meta.length; i++) {
            Paso p = new Paso();
            long m = meta[i];
            p.t = m >>> 24;
            p.veces = (int) (m >>> 16 & 0xff);
            p.hondo = (byte) (m >>> 8 & 0xff);
            p.lugar = (byte) (m & 0xff);
            r.pasos.put(cells[i], p);
        }
        NbtList diary = nbt.getList("h", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < diary.size(); i++) {
            NbtCompound e = diary.getCompound(i);
            r.hechos.addLast(new Hecho(e.getLong("t"), e.getString("k"), e.getString("s"), e.contains("p") ? BlockPos.fromLong(e.getLong("p")) : null));
        }
        NbtCompound places = nbt.getCompound("s");
        for (String name : places.getKeys()) {
            r.sitios.put(name, BlockPos.fromLong(places.getLong(name)));
        }
        r.tocado = nbt.getLong("tocado");
        return r;
    }

    /** Every villager's memories in one world, saved with it; read from disk only when a villager is asked for. */
    static final class Registro extends PersistentState {
        private final Map<UUID, Recuerdos> vivos = new HashMap<>();
        private final Map<UUID, NbtCompound> crudos = new HashMap<>();
        @Nullable
        private ServerWorld world;

        private Registro() {
        }

        private Registro(NbtCompound nbt) {
            NbtCompound all = nbt.getCompound("aldeanos");
            for (String key : all.getKeys()) {
                try {
                    crudos.put(UUID.fromString(key), all.getCompound(key));
                } catch (IllegalArgumentException ignored) {
                    // not a villager id: skip it
                }
            }
        }

        static Registro get(ServerWorld world) {
            Registro r = WorldUtils.loadData(world, Registro::new, w -> new Registro(), "mca_tropeles_recuerdos");
            r.world = world;
            return r;
        }

        Recuerdos of(UUID id) {
            Recuerdos r = vivos.get(id);
            if (r == null) {
                NbtCompound raw = crudos.remove(id);
                try {
                    r = raw == null ? new Recuerdos() : fromNbt(raw);
                } catch (RuntimeException e) {
                    MCA.LOGGER.warn("[Tropeles] los recuerdos guardados de {} no se pudieron leer: {}", id, e.toString());
                    r = new Recuerdos();
                }
                r.registro = this;
                r.tocado = Math.max(r.tocado, world == null ? 0 : world.getTime());
                vivos.put(id, r);
                trim(id);
            }
            return r;
        }

        /** Too many villagers remembered: the one whose memories were touched longest ago is forgotten. */
        private void trim(UUID keep) {
            while (vivos.size() + crudos.size() > MAX_VILLAGERS) {
                if (!crudos.isEmpty()) {
                    UUID oldest = null;
                    long when = Long.MAX_VALUE;
                    for (Map.Entry<UUID, NbtCompound> e : crudos.entrySet()) {
                        long t = e.getValue().getLong("tocado");
                        if (t < when) {
                            when = t;
                            oldest = e.getKey();
                        }
                    }
                    crudos.remove(oldest);
                } else {
                    UUID oldest = null;
                    long when = Long.MAX_VALUE;
                    for (Map.Entry<UUID, Recuerdos> e : vivos.entrySet()) {
                        if (!e.getKey().equals(keep) && e.getValue().tocado < when) {
                            when = e.getValue().tocado;
                            oldest = e.getKey();
                        }
                    }
                    if (oldest == null) {
                        break;
                    }
                    vivos.remove(oldest);
                }
                markDirty();
            }
        }

        @Override
        public NbtCompound writeNbt(NbtCompound nbt) {
            NbtCompound all = new NbtCompound();
            crudos.forEach((id, raw) -> all.put(id.toString(), raw));
            Set<UUID> done = new HashSet<>();
            vivos.forEach((id, r) -> {
                if (done.add(id)) {
                    all.put(id.toString(), r.toNbt());
                }
            });
            nbt.put("aldeanos", all);
            return nbt;
        }
    }
}
