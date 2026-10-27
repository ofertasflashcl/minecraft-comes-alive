package net.mca.entity.ai.sense;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.body.Mining;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the villager understands of its surroundings right now, built only from its {@link VisualMemory} (recent
 * sightings) and its senses: where it is ({@link Lugar}), the time of day, the weather, the light, and the things it
 * saw grouped the way a person names them (a tree, a group of trees, water, lava, a cave opening, a building, a
 * field, animals, monsters, people, ores, chests), each with a direction relative to where it faces and a distance.
 * <p>
 * Grouping: the logs of one trunk (a 2x2 trunk too) are one tree, a crown seen without its trunk is a tree as well,
 * and six trees or more are a forest; water, lava, dark openings, made blocks, crops, ore veins and chests are
 * clustered by nearness; beings of one kind are counted together, people are named one by one. A {@link Cosa}'s
 * {@code nombre} is the Spanish noun without article ("roble", "bosque", "agua", "vaca", "mineral de hierro"), or
 * the person's name; {@code donde} is the nearest part of it and {@code distancia} the distance to that part.
 * <p>
 * Past {@link #MAP_RADIUS}, out to {@link #FAR_RADIUS}, the far land its {@link Pantalla} drew onto the map is named
 * too: woods and single trees, water and lakes, buildings and villages, mountains ({@link #MONTANA}), each group once,
 * by its nearest cell.
 */
public final class Escena {
    /** One named thing in view or seen lately. */
    public record Cosa(String tipo, String nombre, int cuantos, BlockPos donde, double distancia, String direccion, long hace) {
    }

    /** Kinds of {@link Cosa#tipo()}. */
    public static final String ARBOL = "arbol";
    public static final String AGUA = "agua";
    public static final String LAVA = "lava";
    public static final String CUEVA = "cueva";
    public static final String CONSTRUCCION = "construccion";
    public static final String CULTIVO = "cultivo";
    public static final String ANIMAL = "animal";
    public static final String MONSTRUO = "monstruo";
    public static final String PERSONA = "persona";
    public static final String MINERAL = "mineral";
    public static final String COFRE = "cofre";
    /** High land far off: a mountain or a hill, seen by the {@link Pantalla}. */
    public static final String MONTANA = "montana";
    /** A staircase: steps one up and one over, carved or its own ({@link Ahora}). */
    public static final String ESCALERA = "escalera";
    /** The tunnel it stands in ({@link Ahora}). */
    public static final String TUNEL = "tunel";
    /** A ravine: sunlit floors far below it ({@link Ahora}). */
    public static final String BARRANCO = "barranco";
    /** A dirt path ({@link Ahora}). */
    public static final String CAMINO = "camino";
    /** Rails, or an old mineshaft ({@link Ahora}). */
    public static final String VIAS = "vias";

    /** Trees from this many on are a forest. */
    public static final int FOREST = 6;
    /** How far the map's cells count (blocks). */
    public static final int MAP_RADIUS = 48;
    /**
     * Past {@link #MAP_RADIUS} and out to here (a player's 12 chunks), the far land the {@link Pantalla} drew onto the
     * map: woods, single trees, water, villages and high land, each group with its direction and distance.
     */
    public static final int FAR_RADIUS = Pantalla.FAR;
    /** Land this much higher than the feet, far off, is a mountain. */
    public static final int MOUNTAIN = 16;
    /** A building is at least this many made blocks seen together. */
    public static final int BUILDING = 10;
    /** At most this many things in {@link #describe(boolean)}. */
    public static final int SAY = 5;

    /** Words for one thing: Spanish singular with article and plural, English the same. Mass nouns take no count. */
    private record Words(String unEs, String variosEs, String oneEn, String manyEn, boolean mass) {
        static Words of(String unEs, String variosEs, String oneEn, String manyEn) {
            return new Words(unEs, variosEs, oneEn, manyEn, false);
        }

        static Words mass(String es, String en) {
            return new Words(es, es, en, en, true);
        }

        static Words name(String name) {
            return new Words(name, name, name, name, true);
        }

        String say(int n, boolean es) {
            if (mass || n <= 1) {
                return es ? unEs : oneEn;
            }
            return n + " " + (es ? variosEs : manyEn);
        }
    }

    private static final class Item {
        final Cosa cosa;
        final Words words;
        final double score;

        Item(Cosa cosa, Words words, double score) {
            this.cosa = cosa;
            this.words = words;
            this.score = score;
        }
    }

    private final Lugar lugar;
    private final int profundidad;
    private final List<Item> items;
    private final List<Cosa> cosas;
    private final long timeOfDay;
    private final boolean raining;
    private final boolean thundering;
    private final int light;
    private final String biome;
    private final String groundEs;
    private final String groundEn;
    private final String reliefEs;
    private final String reliefEn;
    private final Vec3d origin;
    private final float facing;
    @Nullable
    private final Ahora ahora;

    private Escena(Lugar lugar, int profundidad, List<Item> items, long timeOfDay, boolean raining, boolean thundering, int light,
                   String biome, String groundEs, String groundEn, String reliefEs, String reliefEn, Vec3d origin, float facing, @Nullable Ahora ahora) {
        this.lugar = lugar;
        this.profundidad = profundidad;
        this.items = items;
        List<Cosa> list = new ArrayList<>(items.size());
        for (Item item : items) {
            list.add(item.cosa);
        }
        this.cosas = Collections.unmodifiableList(list);
        this.timeOfDay = timeOfDay;
        this.raining = raining;
        this.thundering = thundering;
        this.light = light;
        this.biome = biome;
        this.groundEs = groundEs;
        this.groundEn = groundEn;
        this.reliefEs = reliefEs;
        this.reliefEn = reliefEn;
        this.origin = origin;
        this.facing = facing;
        this.ahora = ahora;
    }

    /** The scene now, from what it saw in the last {@code maxAge} ticks. Server thread. */
    public static Escena of(ServerWorld world, LivingEntity viewer, int maxAge) {
        long now = world.getTime();
        VisualMemory memory = Retina.memory(viewer);
        MapaVisto mapa = memory.mapa();
        BlockPos feet = viewer.getBlockPos();
        Vec3d origin = viewer.getPos();
        float facing = viewer.getBodyYaw();
        Builder b = new Builder(origin, facing, now);

        Lugar lugar = Entorno.lugar(world, viewer);
        int depth = Entorno.profundidad(world, viewer);

        // sort what the eye saw lately into kinds
        List<VisualMemory.SeenBlock> trunks = new ArrayList<>();
        List<VisualMemory.SeenBlock> leaves = new ArrayList<>();
        List<VisualMemory.SeenBlock> water = new ArrayList<>();
        List<VisualMemory.SeenBlock> lava = new ArrayList<>();
        List<VisualMemory.SeenBlock> made = new ArrayList<>();
        List<VisualMemory.SeenBlock> crops = new ArrayList<>();
        List<VisualMemory.SeenBlock> chests = new ArrayList<>();
        Map<String, List<VisualMemory.SeenBlock>> ores = new LinkedHashMap<>();
        Set<Long> oreSeen = new HashSet<>();
        for (VisualMemory.SeenBlock seen : memory.blocks(s -> s.state != null && now - s.lastSeen <= maxAge)) {
            BlockState state = seen.state;
            if (!state.getFluidState().isEmpty()) {
                if (state.getFluidState().isIn(FluidTags.LAVA)) {
                    lava.add(seen);
                } else if (state.getFluidState().isIn(FluidTags.WATER)) {
                    water.add(seen);
                }
                if (state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA)) {
                    continue;
                }
            }
            if (MapaVisto.trunk(state)) {
                trunks.add(seen);
            } else if (state.isIn(BlockTags.LEAVES)) {
                if (Obras.natural(state)) {
                    leaves.add(seen);
                }
            } else if (Mining.isOre(state)) {
                if (oreSeen.add(seen.pos.asLong())) {
                    ores.computeIfAbsent(oreKey(state), k -> new ArrayList<>()).add(seen);
                }
            } else if (chest(state)) {
                chests.add(seen);
            } else if (Obras.crop(state)) {
                crops.add(seen);
            } else if (!seen.seeThrough && Obras.made(state)) {
                made.add(seen);
            }
        }
        for (VisualMemory.SeenBlock seen : memory.ores()) {
            if (seen.state != null && now - seen.lastSeen <= maxAge && oreSeen.add(seen.pos.asLong())) {
                ores.computeIfAbsent(oreKey(seen.state), k -> new ArrayList<>()).add(seen);
            }
        }

        trees(b, mapa, trunks, leaves, now, maxAge);

        // water, lava
        List<List<VisualMemory.SeenBlock>> pools = clusters(water, 6);
        List<BlockPos> wet = new ArrayList<>();
        for (List<VisualMemory.SeenBlock> pool : pools) {
            Words w = pool.size() < 6 ? Words.mass("un poco de agua", "a little water")
                    : pool.size() >= 300 ? Words.of("un lago", "lagos", "a lake", "lakes") : Words.mass("agua", "water");
            VisualMemory.SeenBlock near = b.nearest(pool);
            wet.add(near.pos);
            b.add(AGUA, pool.size() >= 300 ? "lago" : "agua", 1, near.pos, near.lastSeen, w);
        }
        for (MapaVisto.Celda c : mapa.cells(feet, MAP_RADIUS)) {
            if ((c.flags() & MapaVisto.WATER) != 0 && now - c.lastSeen() <= maxAge && !near(wet, c.x(), c.z(), 12)) {
                BlockPos at = c.center(feet.getY());
                wet.add(at);
                b.add(AGUA, "agua", 1, at, c.lastSeen(), Words.mass("agua", "water"));
            }
        }
        for (List<VisualMemory.SeenBlock> pool : clusters(lava, 6)) {
            VisualMemory.SeenBlock near = b.nearest(pool);
            b.add(LAVA, "lava", 1, near.pos, near.lastSeen, Words.mass("lava", "lava"));
        }

        // dark openings: the map's dark cells and the dark spots of the last frame
        List<BlockPos> holes = new ArrayList<>();
        for (MapaVisto.Celda c : mapa.cells(feet, MAP_RADIUS)) {
            if ((c.flags() & MapaVisto.DARK) != 0 && now - c.lastSeen() <= maxAge) {
                holes.add(c.center(feet.getY()));
            }
        }
        Retina.Pixel[] frame = memory.frame();
        if (frame != null && now - memory.frameTick() <= maxAge) {
            for (Retina.Pixel pixel : frame) {
                if (pixel != null && pixel.dark() && pixel.entity() == null && pixel.hit() != null && pixel.distance() > Retina.DARK_SIGHT) {
                    BlockPos at = BlockPos.ofFloored(pixel.hit());
                    if (world.getLightLevel(LightType.SKY, at) < Retina.DARK_SKY) {
                        holes.add(at);
                    }
                }
            }
        }
        boolean deep = lugar == Lugar.BAJO_TIERRA;
        for (List<BlockPos> hole : clusterPositions(holes, 8)) {
            BlockPos at = b.nearestPos(hole);
            b.add(CUEVA, deep ? "hueco oscuro" : "cueva", 1, at, now,
                    deep ? Words.of("un hueco oscuro", "huecos oscuros", "a dark opening", "dark openings")
                            : Words.of("la entrada de una cueva", "entradas de cuevas", "a cave opening", "cave openings"));
        }

        // buildings, fields, chests, ores
        for (List<VisualMemory.SeenBlock> part : clusters(made, 8)) {
            if (part.size() >= BUILDING) {
                VisualMemory.SeenBlock near = b.nearest(part);
                b.add(CONSTRUCCION, "construcción", 1, near.pos, near.lastSeen,
                        Words.of("una construcción", "construcciones", "a building", "buildings"));
            }
        }
        for (List<VisualMemory.SeenBlock> field : clusters(crops, 8)) {
            VisualMemory.SeenBlock near = b.nearest(field);
            String[] crop = cropName(field);
            b.add(CULTIVO, crop[4], 1, near.pos, near.lastSeen, Words.of(crop[0], crop[1], crop[2], crop[3]));
        }
        for (List<VisualMemory.SeenBlock> group : clusters(chests, 4)) {
            VisualMemory.SeenBlock near = b.nearest(group);
            b.add(COFRE, "cofre", group.size(), near.pos, near.lastSeen, Words.of("un cofre", "cofres", "a chest", "chests"));
        }
        for (Map.Entry<String, List<VisualMemory.SeenBlock>> kind : ores.entrySet()) {
            String[] name = oreName(kind.getKey());
            for (List<VisualMemory.SeenBlock> vein : clusters(kind.getValue(), 4)) {
                VisualMemory.SeenBlock near = b.nearest(vein);
                b.add(MINERAL, name[0], vein.size(), near.pos, near.lastSeen, Words.mass(name[0], name[1]));
            }
        }

        beings(world, b, memory, viewer, now, maxAge);
        far(b, mapa, feet, now, maxAge);

        // what it understands of the moment: staircases, the tunnel it is in, paths, rails, ravines; light, sounds, dangers
        Ahora ahora = null;
        try {
            ahora = Ahora.of(world, viewer);
            for (Ahora.Estructura e : ahora.estructuras()) {
                if (CUEVA.equals(e.tipo())) {
                    continue; // the cave openings above already say it
                }
                b.add(e.tipo(), e.nombre(), 1, e.donde(), now, structureWords(e));
            }
        } catch (RuntimeException ignored) {
            // the scene goes on without it
        }

        // the ground under its feet and the lie of the land, felt and seen
        BlockState under = world.getBlockState(feet.down());
        BlockState at = world.getBlockState(feet);
        String[] ground = ground(under, at);
        String[] relief = relief(mapa, feet, lugar);

        int light = world.getLightLevel(feet);
        String biome = world.getBiome(feet).getKey().map(k -> k.getValue().getPath().replace('_', ' ')).orElse("unknown");
        b.items.sort(Comparator.comparingDouble(i -> i.score));
        return new Escena(lugar, depth, b.items, world.getTimeOfDay() % 24000L, world.isRaining(), world.isThundering(), light, biome,
                ground[0], ground[1], relief[0], relief[1], origin, facing, ahora);
    }

    private static Words structureWords(Ahora.Estructura e) {
        return switch (e.tipo()) {
            case ESCALERA -> {
                String es = (e.propia() ? "mi escalera" : "una escalera") + " de " + e.largo() + " escalones (" + (e.sube() ? "sube" : "baja") + ")";
                String en = (e.propia() ? "my own staircase" : "a staircase") + " of " + e.largo() + " steps (leading " + (e.sube() ? "up" : "down") + ")";
                yield new Words(es, es, en, en, true);
            }
            case TUNEL -> Words.mass("un túnel", "a tunnel");
            case BARRANCO -> Words.mass("un barranco", "a ravine");
            case CAMINO -> Words.mass("un camino", "a path");
            case VIAS -> "mina abandonada".equals(e.nombre()) ? Words.mass("una mina abandonada", "an old mineshaft") : Words.mass("unas vías", "some rails");
            default -> Words.mass(e.nombre(), e.name());
        };
    }

    /** Collects things with their direction and distance from the villager. */
    private static final class Builder {
        final Vec3d origin;
        final float facing;
        final long now;
        final List<Item> items = new ArrayList<>();

        Builder(Vec3d origin, float facing, long now) {
            this.origin = origin;
            this.facing = facing;
            this.now = now;
        }

        double distance(BlockPos pos) {
            return origin.distanceTo(Vec3d.ofBottomCenter(pos));
        }

        VisualMemory.SeenBlock nearest(List<VisualMemory.SeenBlock> group) {
            VisualMemory.SeenBlock best = group.get(0);
            double bd = Double.MAX_VALUE;
            for (VisualMemory.SeenBlock s : group) {
                double d = origin.squaredDistanceTo(Vec3d.ofCenter(s.pos));
                if (d < bd) {
                    bd = d;
                    best = s;
                }
            }
            return best;
        }

        BlockPos nearestPos(List<BlockPos> group) {
            BlockPos best = group.get(0);
            double bd = Double.MAX_VALUE;
            for (BlockPos p : group) {
                double d = origin.squaredDistanceTo(Vec3d.ofCenter(p));
                if (d < bd) {
                    bd = d;
                    best = p;
                }
            }
            return best;
        }

        void add(String tipo, String nombre, int cuantos, BlockPos donde, long lastSeen, Words words) {
            double d = distance(donde);
            long ago = Math.max(0, now - lastSeen);
            Cosa cosa = new Cosa(tipo, nombre, cuantos, donde.toImmutable(), d, direction(origin, facing, donde, true), ago);
            double score = d * weight(tipo) + ago / 100.0;
            items.add(new Item(cosa, words, score));
        }
    }

    private static double weight(String tipo) {
        return switch (tipo) {
            case MONSTRUO -> 0.4;
            case ESCALERA -> 0.7;
            case LAVA -> 0.6;
            case PERSONA -> 0.8;
            case ARBOL -> 0.9;
            case AGUA, CUEVA -> 1.0;
            case CONSTRUCCION -> 1.1;
            default -> 1.2;
        };
    }

    // ---------------------------------------------------------------- trees

    private static void trees(Builder b, MapaVisto mapa, List<VisualMemory.SeenBlock> trunks, List<VisualMemory.SeenBlock> leaves, long now, int maxAge) {
        // the logs of one trunk: columns side by side (a 2x2 trunk is one tree)
        Map<Long, VisualMemory.SeenBlock> columns = new HashMap<>();
        for (VisualMemory.SeenBlock log : trunks) {
            long key = BlockPos.asLong(log.pos.getX(), 0, log.pos.getZ());
            VisualMemory.SeenBlock had = columns.get(key);
            if (had == null || log.pos.getY() < had.pos.getY()) {
                columns.put(key, log);
            }
        }
        List<VisualMemory.SeenBlock> bases = new ArrayList<>(columns.values());
        List<List<VisualMemory.SeenBlock>> trunkGroups = new ArrayList<>();
        boolean[] used = new boolean[bases.size()];
        for (int i = 0; i < bases.size(); i++) {
            if (used[i]) {
                continue;
            }
            List<VisualMemory.SeenBlock> group = new ArrayList<>();
            List<Integer> open = new ArrayList<>();
            open.add(i);
            used[i] = true;
            while (!open.isEmpty()) {
                VisualMemory.SeenBlock s = bases.get(open.remove(open.size() - 1));
                group.add(s);
                for (int j = 0; j < bases.size(); j++) {
                    if (!used[j] && Math.abs(bases.get(j).pos.getX() - s.pos.getX()) <= 1 && Math.abs(bases.get(j).pos.getZ() - s.pos.getZ()) <= 1
                            && Math.abs(bases.get(j).pos.getY() - s.pos.getY()) <= 8) {
                        used[j] = true;
                        open.add(j);
                    }
                }
            }
            trunkGroups.add(group);
        }

        List<BlockPos> treeAt = new ArrayList<>();
        List<String> species = new ArrayList<>();
        long newest = Long.MIN_VALUE;
        for (List<VisualMemory.SeenBlock> group : trunkGroups) {
            VisualMemory.SeenBlock base = group.get(0);
            for (VisualMemory.SeenBlock s : group) {
                if (s.pos.getY() < base.pos.getY()) {
                    base = s;
                }
                newest = Math.max(newest, s.lastSeen);
            }
            treeAt.add(base.pos);
            species.add(species(base.state));
        }
        // crowns seen with no trunk under them
        for (List<VisualMemory.SeenBlock> crown : clusters(leaves, 4)) {
            if (crown.size() < 3) {
                continue;
            }
            double x = 0, z = 0;
            int lowY = Integer.MAX_VALUE;
            for (VisualMemory.SeenBlock s : crown) {
                x += s.pos.getX();
                z += s.pos.getZ();
                lowY = Math.min(lowY, s.pos.getY());
                newest = Math.max(newest, s.lastSeen);
            }
            int cx = MathHelper.floor(x / crown.size());
            int cz = MathHelper.floor(z / crown.size());
            if (!near(treeAt, cx, cz, 5)) {
                treeAt.add(new BlockPos(cx, lowY, cz));
                species.add(species(crown.get(0).state));
            }
        }
        // trees the map remembers that the block memory has let go
        BlockPos feet = BlockPos.ofFloored(b.origin);
        for (MapaVisto.Celda c : mapa.cells(feet, MAP_RADIUS)) {
            if ((c.flags() & MapaVisto.TREE) != 0 && now - c.lastSeen() <= maxAge && !near(treeAt, c.x(), c.z(), 6)) {
                for (int k = 0; k < Math.min(2, Math.max(1, c.trees())); k++) { // a 2x2 trunk fills four columns
                    treeAt.add(c.center(feet.getY()));
                    species.add("");
                }
                newest = Math.max(newest, c.lastSeen());
            }
        }
        if (treeAt.isEmpty()) {
            return;
        }
        int nearest = 0;
        for (int i = 1; i < treeAt.size(); i++) {
            if (b.distance(treeAt.get(i)) < b.distance(treeAt.get(nearest))) {
                nearest = i;
            }
        }
        int n = treeAt.size();
        BlockPos at = treeAt.get(nearest);
        long seen = newest == Long.MIN_VALUE ? b.now : newest;
        if (n >= FOREST) {
            b.add(ARBOL, "bosque", n, at, seen, Words.of("un bosque", "bosques", "a forest", "forests"));
            return;
        }
        String kind = species.get(nearest);
        boolean same = true;
        for (String s : species) {
            same &= s.equals(kind);
        }
        String[] w = treeWords(same ? kind : "");
        b.add(ARBOL, w[4], n, at, seen, Words.of(w[0], w[1], w[2], w[3]));
    }

    private static String species(@Nullable BlockState state) {
        if (state == null) {
            return "";
        }
        String path = Registries.BLOCK.getId(state.getBlock()).getPath();
        for (String suffix : new String[]{"_log", "_wood", "_leaves", "_stem", "_hyphae"}) {
            if (path.endsWith(suffix)) {
                return path.substring(0, path.length() - suffix.length());
            }
        }
        return path;
    }

    /** es one, es many, en one, en many, es noun */
    private static String[] treeWords(String species) {
        return switch (species) {
            case "oak" -> new String[]{"un roble", "robles", "an oak", "oaks", "roble"};
            case "birch" -> new String[]{"un abedul", "abedules", "a birch", "birches", "abedul"};
            case "spruce" -> new String[]{"un abeto", "abetos", "a spruce", "spruces", "abeto"};
            case "jungle" -> new String[]{"un árbol de la jungla", "árboles de la jungla", "a jungle tree", "jungle trees", "árbol de la jungla"};
            case "acacia" -> new String[]{"una acacia", "acacias", "an acacia", "acacias", "acacia"};
            case "dark_oak" -> new String[]{"un roble oscuro", "robles oscuros", "a dark oak", "dark oaks", "roble oscuro"};
            case "mangrove" -> new String[]{"un mangle", "mangles", "a mangrove", "mangroves", "mangle"};
            case "cherry" -> new String[]{"un cerezo", "cerezos", "a cherry tree", "cherry trees", "cerezo"};
            case "azalea", "flowering_azalea" -> new String[]{"un árbol de azalea", "árboles de azalea", "an azalea tree", "azalea trees", "azalea"};
            default -> new String[]{"un árbol", "árboles", "a tree", "trees", "árbol"};
        };
    }

    // ---------------------------------------------------------------- far off

    /**
     * What the far view put on the map between {@link #MAP_RADIUS} and {@link #FAR_RADIUS}: cells that showed trees,
     * water, made blocks or high land, joined into groups of touching cells (two cells apart at most), each named
     * once by its nearest cell.
     */
    private static void far(Builder b, MapaVisto mapa, BlockPos feet, long now, int maxAge) {
        List<MapaVisto.Celda> trees = new ArrayList<>();
        List<MapaVisto.Celda> water = new ArrayList<>();
        List<MapaVisto.Celda> built = new ArrayList<>();
        List<MapaVisto.Celda> high = new ArrayList<>();
        long inner = (long) MAP_RADIUS * MAP_RADIUS;
        for (MapaVisto.Celda c : mapa.cells(feet, FAR_RADIUS)) {
            long dx = c.x() - feet.getX();
            long dz = c.z() - feet.getZ();
            if (dx * dx + dz * dz <= inner || now - c.lastSeen() > maxAge) {
                continue;
            }
            int f = c.flags();
            if ((f & MapaVisto.TREE) != 0) {
                trees.add(c);
            }
            if ((f & MapaVisto.WATER) != 0) {
                water.add(c);
            }
            if ((f & MapaVisto.BUILT) != 0) {
                built.add(c);
            }
            int top = c.surface() != Integer.MIN_VALUE ? c.surface() : c.land() != null ? c.land().getY() : Integer.MIN_VALUE;
            if (top != Integer.MIN_VALUE && top - feet.getY() >= MOUNTAIN) {
                high.add(c);
            }
        }
        for (List<MapaVisto.Celda> wood : groups(trees)) {
            MapaVisto.Celda near = nearestCell(b, wood, feet);
            int n = Math.max(1, (wood.size() + 2) / 3);
            BlockPos at = near.center(feet.getY());
            if (wood.size() >= 10) {
                b.add(ARBOL, "bosque", Math.max(FOREST, n), at, lastSeen(wood), Words.of("un bosque", "bosques", "a forest", "forests"));
            } else {
                b.add(ARBOL, "árbol", n, at, lastSeen(wood), Words.of("un árbol", "árboles", "a tree", "trees"));
            }
        }
        for (List<MapaVisto.Celda> pool : groups(water)) {
            MapaVisto.Celda near = nearestCell(b, pool, feet);
            boolean lake = pool.size() >= 12;
            b.add(AGUA, lake ? "lago" : "agua", 1, near.center(feet.getY()), lastSeen(pool),
                    lake ? Words.of("un lago", "lagos", "a lake", "lakes") : Words.mass("agua", "water"));
        }
        for (List<MapaVisto.Celda> place : groups(built)) {
            if (place.size() < 2) {
                continue; // one made block far off is not much of a building
            }
            MapaVisto.Celda near = nearestCell(b, place, feet);
            boolean village = place.size() >= 8;
            b.add(CONSTRUCCION, village ? "aldea" : "construcción", 1, near.center(feet.getY()), lastSeen(place),
                    village ? Words.of("una aldea", "aldeas", "a village", "villages") : Words.of("una construcción", "construcciones", "a building", "buildings"));
        }
        for (List<MapaVisto.Celda> hill : groups(high)) {
            if (hill.size() < 4) {
                continue;
            }
            MapaVisto.Celda peak = hill.get(0);
            for (MapaVisto.Celda c : hill) {
                if (c.surface() > peak.surface()) {
                    peak = c;
                }
            }
            BlockPos at = peak.surface() != Integer.MIN_VALUE ? new BlockPos(peak.x(), peak.surface(), peak.z()) : peak.center(feet.getY());
            b.add(MONTANA, "montaña", 1, at, lastSeen(hill), Words.of("una montaña", "montañas", "a mountain", "mountains"));
        }
    }

    private static MapaVisto.Celda nearestCell(Builder b, List<MapaVisto.Celda> cells, BlockPos feet) {
        MapaVisto.Celda best = cells.get(0);
        double bd = Double.MAX_VALUE;
        for (MapaVisto.Celda c : cells) {
            double d = b.distance(c.center(feet.getY()));
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    private static long lastSeen(List<MapaVisto.Celda> cells) {
        long last = Long.MIN_VALUE;
        for (MapaVisto.Celda c : cells) {
            last = Math.max(last, c.lastSeen());
        }
        return last;
    }

    /** Cells joined into groups when they lie within two cells of each other. */
    private static List<List<MapaVisto.Celda>> groups(List<MapaVisto.Celda> cells) {
        Map<Long, MapaVisto.Celda> left = new HashMap<>();
        for (MapaVisto.Celda c : cells) {
            left.put(BlockPos.asLong(Math.floorDiv(c.x(), MapaVisto.CELL), 0, Math.floorDiv(c.z(), MapaVisto.CELL)), c);
        }
        List<List<MapaVisto.Celda>> out = new ArrayList<>();
        while (!left.isEmpty()) {
            Long start = left.keySet().iterator().next();
            List<MapaVisto.Celda> group = new ArrayList<>();
            List<Long> open = new ArrayList<>();
            open.add(start);
            group.add(left.remove(start));
            while (!open.isEmpty()) {
                long key = open.remove(open.size() - 1);
                int x = BlockPos.unpackLongX(key);
                int z = BlockPos.unpackLongZ(key);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        long next = BlockPos.asLong(x + dx, 0, z + dz);
                        MapaVisto.Celda c = left.remove(next);
                        if (c != null) {
                            group.add(c);
                            open.add(next);
                        }
                    }
                }
            }
            out.add(group);
        }
        return out;
    }

    // ---------------------------------------------------------------- beings

    private static void beings(ServerWorld world, Builder b, VisualMemory memory, LivingEntity viewer, long now, int maxAge) {
        Map<EntityType<?>, List<VisualMemory.SeenEntity>> kinds = new LinkedHashMap<>();
        for (VisualMemory.SeenEntity seen : memory.entities(now, maxAge)) {
            if (seen.id == viewer.getId() || seen.pos == null || seen.type == EntityType.ITEM) {
                continue;
            }
            Entity entity = world.getEntityById(seen.id);
            BlockPos at = BlockPos.ofFloored(seen.pos);
            if (seen.type == EntityType.PLAYER || entity instanceof PlayerEntity || entity instanceof VillagerEntityMCA) {
                String name = entity != null ? entity.getName().getString() : (seen.type == EntityType.PLAYER ? "alguien" : "un aldeano");
                b.add(PERSONA, name, 1, at, seen.lastSeen, Words.name(name));
                continue;
            }
            if (entity instanceof MerchantEntity || seen.type == EntityType.VILLAGER || seen.type == EntityType.WANDERING_TRADER) {
                b.add(PERSONA, "aldeano", 1, at, seen.lastSeen, seen.type == EntityType.WANDERING_TRADER
                        ? Words.of("un vendedor ambulante", "vendedores ambulantes", "a wandering trader", "wandering traders")
                        : Words.of("un aldeano", "aldeanos", "a villager", "villagers"));
                continue;
            }
            if (seen.type.getSpawnGroup() == SpawnGroup.MISC) {
                continue; // golems, carts, boats, arrows: not beings to tell about
            }
            kinds.computeIfAbsent(seen.type, t -> new ArrayList<>()).add(seen);
        }
        for (Map.Entry<EntityType<?>, List<VisualMemory.SeenEntity>> kind : kinds.entrySet()) {
            EntityType<?> type = kind.getKey();
            List<VisualMemory.SeenEntity> list = kind.getValue();
            VisualMemory.SeenEntity near = list.get(0);
            long last = near.lastSeen;
            for (VisualMemory.SeenEntity s : list) {
                if (b.origin.squaredDistanceTo(s.pos) < b.origin.squaredDistanceTo(near.pos)) {
                    near = s;
                }
                last = Math.max(last, s.lastSeen);
            }
            String tipo = type.getSpawnGroup() == SpawnGroup.MONSTER ? MONSTRUO : ANIMAL;
            Words w = beingWords(type);
            String nombre = w.unEs.replaceFirst("^(un|una) ", "");
            b.add(tipo, nombre, list.size(), BlockPos.ofFloored(near.pos), last, w);
        }
    }

    /** The noun of a kind of being without article, Spanish ("zombi") or English ("zombie"). */
    static String nombreSer(EntityType<?> type, boolean es) {
        Words w = beingWords(type);
        return es ? w.unEs.replaceFirst("^(un|una) ", "") : w.oneEn.replaceFirst("^(a|an) ", "");
    }

    private static Words beingWords(EntityType<?> type) {
        String path = Registries.ENTITY_TYPE.getId(type).getPath();
        String en = type.getName().getString().toLowerCase(Locale.ROOT);
        String[] es = switch (path) {
            case "cow" -> new String[]{"una vaca", "vacas"};
            case "mooshroom" -> new String[]{"una vaca champiñón", "vacas champiñón"};
            case "pig" -> new String[]{"un cerdo", "cerdos"};
            case "sheep" -> new String[]{"una oveja", "ovejas"};
            case "chicken" -> new String[]{"una gallina", "gallinas"};
            case "horse" -> new String[]{"un caballo", "caballos"};
            case "donkey" -> new String[]{"un burro", "burros"};
            case "mule" -> new String[]{"una mula", "mulas"};
            case "rabbit" -> new String[]{"un conejo", "conejos"};
            case "wolf" -> new String[]{"un lobo", "lobos"};
            case "cat" -> new String[]{"un gato", "gatos"};
            case "ocelot" -> new String[]{"un ocelote", "ocelotes"};
            case "fox" -> new String[]{"un zorro", "zorros"};
            case "llama", "trader_llama" -> new String[]{"una llama", "llamas"};
            case "goat" -> new String[]{"una cabra", "cabras"};
            case "bee" -> new String[]{"una abeja", "abejas"};
            case "parrot" -> new String[]{"un loro", "loros"};
            case "turtle" -> new String[]{"una tortuga", "tortugas"};
            case "squid", "glow_squid" -> new String[]{"un calamar", "calamares"};
            case "cod" -> new String[]{"un bacalao", "bacalaos"};
            case "salmon" -> new String[]{"un salmón", "salmones"};
            case "tropical_fish", "pufferfish" -> new String[]{"un pez", "peces"};
            case "dolphin" -> new String[]{"un delfín", "delfines"};
            case "axolotl" -> new String[]{"un ajolote", "ajolotes"};
            case "frog" -> new String[]{"una rana", "ranas"};
            case "bat" -> new String[]{"un murciélago", "murciélagos"};
            case "polar_bear" -> new String[]{"un oso polar", "osos polares"};
            case "panda" -> new String[]{"un panda", "pandas"};
            case "camel" -> new String[]{"un camello", "camellos"};
            case "zombie" -> new String[]{"un zombi", "zombis"};
            case "husk" -> new String[]{"un zombi momificado", "zombis momificados"};
            case "drowned" -> new String[]{"un ahogado", "ahogados"};
            case "zombie_villager" -> new String[]{"un aldeano zombi", "aldeanos zombis"};
            case "skeleton" -> new String[]{"un esqueleto", "esqueletos"};
            case "stray" -> new String[]{"un esqueleto glacial", "esqueletos glaciales"};
            case "creeper" -> new String[]{"un creeper", "creepers"};
            case "spider" -> new String[]{"una araña", "arañas"};
            case "cave_spider" -> new String[]{"una araña de cueva", "arañas de cueva"};
            case "enderman" -> new String[]{"un enderman", "endermans"};
            case "witch" -> new String[]{"una bruja", "brujas"};
            case "slime" -> new String[]{"un slime", "slimes"};
            case "phantom" -> new String[]{"un phantom", "phantoms"};
            case "silverfish" -> new String[]{"un lepisma", "lepismas"};
            case "pillager" -> new String[]{"un saqueador", "saqueadores"};
            case "vindicator" -> new String[]{"un vindicador", "vindicadores"};
            case "evoker" -> new String[]{"un evocador", "evocadores"};
            case "ravager" -> new String[]{"un devastador", "devastadores"};
            default -> null;
        };
        String one = (startsVowel(en) ? "an " : "a ") + en;
        String many = en.endsWith("s") || en.endsWith("sheep") || en.endsWith("fish") || en.endsWith("cod") || en.endsWith("salmon") ? en
                : en.endsWith("y") && !en.endsWith("ey") ? en.substring(0, en.length() - 1) + "ies" : en.endsWith("wolf") ? en.substring(0, en.length() - 1) + "ves" : en + "s";
        if (es == null) {
            es = new String[]{"un " + en, many};
        }
        return Words.of(es[0], es[1], one, many);
    }

    private static boolean startsVowel(String word) {
        return !word.isEmpty() && "aeiou".indexOf(word.charAt(0)) >= 0;
    }

    // ---------------------------------------------------------------- blocks

    private static boolean chest(BlockState state) {
        return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock || state.getBlock() instanceof EnderChestBlock;
    }

    static String oreKey(BlockState state) {
        String path = Registries.BLOCK.getId(state.getBlock()).getPath();
        for (String metal : new String[]{"coal", "iron", "copper", "gold", "redstone", "lapis", "diamond", "emerald", "quartz", "debris"}) {
            if (path.contains(metal)) {
                return metal;
            }
        }
        return path;
    }

    /** es, en */
    static String[] oreName(String key) {
        return switch (key) {
            case "coal" -> new String[]{"carbón", "coal ore"};
            case "iron" -> new String[]{"mineral de hierro", "iron ore"};
            case "copper" -> new String[]{"mineral de cobre", "copper ore"};
            case "gold" -> new String[]{"mineral de oro", "gold ore"};
            case "redstone" -> new String[]{"redstone", "redstone ore"};
            case "lapis" -> new String[]{"lapislázuli", "lapis lazuli ore"};
            case "diamond" -> new String[]{"diamantes", "diamond ore"};
            case "emerald" -> new String[]{"esmeraldas", "emerald ore"};
            case "quartz" -> new String[]{"cuarzo", "quartz ore"};
            case "debris" -> new String[]{"escombros ancestrales", "ancient debris"};
            default -> new String[]{"mineral (" + key.replace('_', ' ') + ")", key.replace('_', ' ')};
        };
    }

    /** es one, es many, en one, en many, es noun of a field, by the crop most seen in it */
    private static String[] cropName(List<VisualMemory.SeenBlock> field) {
        Map<String, Integer> count = new HashMap<>();
        for (VisualMemory.SeenBlock s : field) {
            count.merge(Registries.BLOCK.getId(s.state.getBlock()).getPath(), 1, Integer::sum);
        }
        count.remove("farmland");
        String crop = count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("farmland");
        return switch (crop) {
            case "wheat" -> crop("sembrado de trigo", "sembrados de trigo", "a wheat field", "wheat fields");
            case "carrots" -> crop("sembrado de zanahorias", "sembrados de zanahorias", "a carrot field", "carrot fields");
            case "potatoes" -> crop("sembrado de papas", "sembrados de papas", "a potato field", "potato fields");
            case "beetroots" -> crop("sembrado de betarragas", "sembrados de betarragas", "a beetroot field", "beetroot fields");
            case "melon_stem", "attached_melon_stem" -> crop("sembrado de sandías", "sembrados de sandías", "a melon patch", "melon patches");
            case "pumpkin_stem", "attached_pumpkin_stem" -> crop("sembrado de zapallos", "sembrados de zapallos", "a pumpkin patch", "pumpkin patches");
            case "sweet_berry_bush" -> new String[]{"unos arbustos de bayas", "matas de bayas", "some berry bushes", "berry patches", "arbustos de bayas"};
            case "cocoa" -> new String[]{"unas vainas de cacao", "matas de cacao", "some cocoa pods", "cocoa patches", "cacao"};
            case "nether_wart" -> crop("sembrado de verrugas", "sembrados de verrugas", "a nether wart patch", "nether wart patches");
            case "farmland" -> crop("campo arado", "campos arados", "some tilled land", "tilled fields");
            default -> crop("sembrado", "sembrados", "a field", "fields");
        };
    }

    private static String[] crop(String esNoun, String esMany, String enOne, String enMany) {
        return new String[]{"un " + esNoun, esMany, enOne, enMany, esNoun};
    }

    /** es, en: what is under the feet */
    private static String[] ground(BlockState under, BlockState at) {
        if (!at.getFluidState().isEmpty() && at.getFluidState().isIn(FluidTags.WATER)) {
            return new String[]{"en el agua", "in the water"};
        }
        if (at.isOf(Blocks.SNOW) || under.isOf(Blocks.SNOW_BLOCK) || under.isOf(Blocks.POWDER_SNOW)) {
            return new String[]{"en la nieve", "on snow"};
        }
        if (under.isOf(Blocks.GRASS_BLOCK)) {
            return new String[]{"con pasto", "with grass"};
        }
        if (under.isIn(BlockTags.SAND)) {
            return new String[]{"en la arena", "on sand"};
        }
        if (under.isOf(Blocks.DIRT_PATH)) {
            return new String[]{"en un camino", "on a path"};
        }
        if (under.isOf(Blocks.FARMLAND)) {
            return new String[]{"en tierra arada", "on farmland"};
        }
        if (under.isIn(BlockTags.DIRT) || under.isOf(Blocks.GRAVEL)) {
            return new String[]{"sobre tierra", "on bare earth"};
        }
        if (Mining.isRock(under) && Obras.natural(under)) {
            return new String[]{"sobre roca", "on rock"};
        }
        if (under.isIn(BlockTags.PLANKS) || under.isIn(BlockTags.WOODEN_SLABS) || under.isIn(BlockTags.WOODEN_STAIRS)) {
            return new String[]{"sobre un piso de madera", "on a wooden floor"};
        }
        if (Obras.made(under) && !under.isAir()) {
            return new String[]{"sobre un piso hecho", "on a built floor"};
        }
        return new String[]{"", ""};
    }

    /** es, en: the lie of the land around, from the land levels its eye saw */
    private static String[] relief(MapaVisto mapa, BlockPos feet, Lugar lugar) {
        if (lugar == Lugar.BAJO_TIERRA) {
            return new String[]{"", ""};
        }
        int lower = 0, higher = 0, level = 0;
        for (MapaVisto.Celda c : mapa.cells(feet, 16)) {
            if (c.surface() == Integer.MIN_VALUE) {
                continue;
            }
            int d = c.surface() - feet.getY();
            if (d < -3) {
                lower++;
            } else if (d > 3) {
                higher++;
            } else {
                level++;
            }
        }
        int total = lower + higher + level;
        if (total < 3) {
            return new String[]{"", ""};
        }
        if (lower * 2 > total) {
            return new String[]{"en lo alto de una loma", "on high ground"};
        }
        if (higher * 2 > total) {
            return new String[]{"en un hondo", "in a hollow"};
        }
        if (level * 3 >= total * 2) {
            return new String[]{"en un llano", "on flat ground"};
        }
        return new String[]{"en una ladera", "on a slope"};
    }

    // ---------------------------------------------------------------- grouping

    private static boolean near(List<BlockPos> points, int x, int z, int r) {
        for (BlockPos p : points) {
            if (Math.abs(p.getX() - x) <= r && Math.abs(p.getZ() - z) <= r) {
                return true;
            }
        }
        return false;
    }

    private static List<List<VisualMemory.SeenBlock>> clusters(List<VisualMemory.SeenBlock> blocks, int bucket) {
        Map<Long, List<VisualMemory.SeenBlock>> buckets = new HashMap<>();
        for (VisualMemory.SeenBlock s : blocks) {
            buckets.computeIfAbsent(bucketKey(s.pos, bucket), k -> new ArrayList<>()).add(s);
        }
        List<List<VisualMemory.SeenBlock>> out = new ArrayList<>();
        for (List<Long> group : joinBuckets(buckets.keySet())) {
            List<VisualMemory.SeenBlock> all = new ArrayList<>();
            for (long key : group) {
                all.addAll(buckets.get(key));
            }
            out.add(all);
        }
        return out;
    }

    private static List<List<BlockPos>> clusterPositions(List<BlockPos> points, int bucket) {
        Map<Long, List<BlockPos>> buckets = new HashMap<>();
        for (BlockPos p : points) {
            buckets.computeIfAbsent(bucketKey(p, bucket), k -> new ArrayList<>()).add(p);
        }
        List<List<BlockPos>> out = new ArrayList<>();
        for (List<Long> group : joinBuckets(buckets.keySet())) {
            List<BlockPos> all = new ArrayList<>();
            for (long key : group) {
                all.addAll(buckets.get(key));
            }
            out.add(all);
        }
        return out;
    }

    private static long bucketKey(BlockPos pos, int bucket) {
        return BlockPos.asLong(Math.floorDiv(pos.getX(), bucket), Math.floorDiv(pos.getY(), bucket), Math.floorDiv(pos.getZ(), bucket));
    }

    /** Buckets touching each other (26 neighbours) joined into groups. */
    private static List<List<Long>> joinBuckets(Set<Long> keys) {
        Set<Long> left = new HashSet<>(keys);
        List<List<Long>> groups = new ArrayList<>();
        for (Long start : keys) {
            if (!left.remove(start)) {
                continue;
            }
            List<Long> group = new ArrayList<>();
            List<Long> open = new ArrayList<>();
            open.add(start);
            while (!open.isEmpty()) {
                long key = open.remove(open.size() - 1);
                group.add(key);
                int x = BlockPos.unpackLongX(key);
                int y = BlockPos.unpackLongY(key);
                int z = BlockPos.unpackLongZ(key);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long next = BlockPos.asLong(x + dx, y + dy, z + dz);
                            if (left.remove(next)) {
                                open.add(next);
                            }
                        }
                    }
                }
            }
            groups.add(group);
        }
        return groups;
    }

    // ---------------------------------------------------------------- words

    /** Where a point lies from the villager, by where its body faces: ahead, right, left, behind, and up or down. */
    static String direction(Vec3d origin, float facing, BlockPos pos, boolean es) {
        Vec3d c = Vec3d.ofBottomCenter(pos);
        double dx = c.x - origin.x;
        double dz = c.z - origin.z;
        double dy = pos.getY() - Math.floor(origin.y);
        double flat = Math.sqrt(dx * dx + dz * dz);
        String vertical = dy > 4 ? (es ? "arriba" : "above") : dy < -4 ? (es ? "abajo" : "below") : null;
        if (flat < 2.0) {
            if (vertical != null) {
                return es ? vertical : vertical + " you";
            }
            return es ? "aquí mismo" : "right here";
        }
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float rel = MathHelper.wrapDegrees(yaw - facing);
        String side;
        if (Math.abs(rel) <= 45.0F) {
            side = es ? "delante" : "ahead";
        } else if (rel > 45.0F && rel <= 135.0F) {
            side = es ? "a la derecha" : "to your right";
        } else if (rel < -45.0F && rel >= -135.0F) {
            side = es ? "a la izquierda" : "to your left";
        } else {
            side = es ? "detrás" : "behind you";
        }
        return vertical == null ? side : side + (es ? " y " : " and ") + vertical;
    }

    private String phrase(Item item, boolean es) {
        Cosa c = item.cosa;
        String what = item.words.say(c.cuantos(), es);
        if (ARBOL.equals(c.tipo()) && "bosque".equals(c.nombre())) {
            what = es ? "un bosque" : "a forest";
        }
        String dir = es ? c.direccion() : direction(origin, facing, c.donde(), false);
        long d = Math.round(c.distancia());
        if (d <= 2) {
            return what + (es ? " aquí al lado" : " right beside you");
        }
        if (d < 15) {
            return what + (es ? " a " + d + " bloques " : " " + d + " blocks ") + dir;
        }
        return what + " " + dir + (es ? ", a unos " + d : ", about " + d + " blocks away");
    }

    private static String join(List<String> parts, boolean es) {
        if (parts.isEmpty()) {
            return "";
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        String last = parts.get(parts.size() - 1);
        String and = es ? (last.startsWith("i") || last.startsWith("hi") ? " e " : " y ") : " and ";
        return String.join(", ", parts.subList(0, parts.size() - 1)) + and + last;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String place(boolean es) {
        StringBuilder sb = new StringBuilder();
        switch (lugar) {
            case AFUERA -> {
                if (profundidad > 4) {
                    sb.append(es ? "Estoy afuera, pero en un hoyo, a unos " + profundidad + " bloques bajo el suelo"
                            : "I'm outside, but down in a pit, about " + profundidad + " blocks below the land");
                } else {
                    sb.append(es ? "Estoy afuera" : "I'm outside");
                    String relief = es ? reliefEs : reliefEn;
                    String ground = es ? groundEs : groundEn;
                    if (!relief.isEmpty()) {
                        sb.append(", ").append(relief);
                        if (!ground.isEmpty()) {
                            sb.append(' ').append(ground);
                        }
                    } else if (!ground.isEmpty()) {
                        // "con pasto" needs a place before it
                        sb.append(", ").append(ground.startsWith("con ") ? "sobre el pasto" : ground.startsWith("with ") ? "on the grass" : ground);
                    }
                }
            }
            case BAJO_TECHO -> sb.append(es ? "Estoy bajo techo" : "I'm under a roof");
            case BAJO_TIERRA -> {
                sb.append(es ? "Estoy bajo tierra" : "I'm underground");
                if (profundidad > 2) {
                    sb.append(es ? ", a unos " + profundidad + " bloques de la superficie" : ", about " + profundidad + " blocks below the surface");
                }
            }
        }
        sb.append('.');
        if (lugar != Lugar.BAJO_TIERRA && (raining || thundering)) {
            sb.append(es ? (thundering ? " Hay tormenta." : " Está lloviendo.") : (thundering ? " There's a storm." : " It's raining."));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- the public face

    public Lugar lugar() {
        return lugar;
    }

    public int profundidad() {
        return profundidad;
    }

    /** Everything it makes out, most relevant first (danger and nearness). */
    public List<Cosa> cosas() {
        return cosas;
    }

    /** The nearest thing of a kind, or null. */
    @Nullable
    public Cosa nearest(String tipo) {
        Cosa best = null;
        for (Cosa c : cosas) {
            if (c.tipo().equals(tipo) && (best == null || c.distancia() < best.distancia())) {
                best = c;
            }
        }
        return best;
    }

    /** Two or three short sentences in the villager's voice, Spanish or English, for its own answers. */
    public String describe(boolean es) {
        String place = place(es);
        String now = ahora == null ? "" : ahora.decir(es);
        if (!now.isEmpty()) {
            now = " " + now;
        }
        if (items.isEmpty()) {
            return place + (es ? " No veo gran cosa por aquí." : " I don't see much around here.") + now;
        }
        List<String> first = new ArrayList<>();
        List<String> more = new ArrayList<>();
        for (int i = 0; i < Math.min(SAY, items.size()); i++) {
            (i < 3 ? first : more).add(phrase(items.get(i), es));
        }
        StringBuilder sb = new StringBuilder(place);
        sb.append(es ? " Veo " : " I see ").append(join(first, es)).append('.');
        if (!more.isEmpty()) {
            sb.append(es ? " También " : " Also ").append(join(more, es)).append('.');
        }
        return sb.append(now).toString();
    }

    /** About one kind only ("¿hay árboles?"): where the nearest is and how many, or that it saw none. */
    public String describe(String tipo, boolean es) {
        List<Item> of = new ArrayList<>();
        for (Item item : items) {
            if (item.cosa.tipo().equals(tipo)) {
                of.add(item);
            }
        }
        if (of.isEmpty()) {
            return none(tipo, es);
        }
        of.sort(Comparator.comparingDouble(i -> i.cosa.distancia()));
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(3, of.size()); i++) {
            parts.add(phrase(of.get(i), es));
        }
        String said = (es ? "Veo " : "I see ") + join(parts, es) + ".";
        Item first = of.get(0);
        if (ARBOL.equals(tipo) && "bosque".equals(first.cosa.nombre())) {
            said = es ? "Veo un bosque, unos " + first.cosa.cuantos() + " árboles; el más cercano está a " + Math.round(first.cosa.distancia()) + " bloques " + first.cosa.direccion() + "."
                    : "I see a forest, some " + first.cosa.cuantos() + " trees; the nearest is " + Math.round(first.cosa.distancia()) + " blocks " + direction(origin, facing, first.cosa.donde(), false) + ".";
        }
        if (of.size() > 3) {
            said += es ? " Y algo más por ahí." : " And a few more around.";
        }
        return said;
    }

    private static String none(String tipo, boolean es) {
        return switch (tipo) {
            case ARBOL -> es ? "No veo árboles por aquí." : "I don't see any trees around here.";
            case AGUA -> es ? "No veo agua por aquí." : "I don't see any water around here.";
            case LAVA -> es ? "No veo lava." : "I don't see any lava.";
            case CUEVA -> es ? "No veo cuevas por aquí." : "I don't see any caves around here.";
            case CONSTRUCCION -> es ? "No veo construcciones por aquí." : "I don't see any buildings around here.";
            case CULTIVO -> es ? "No veo cultivos por aquí." : "I don't see any fields around here.";
            case ANIMAL -> es ? "No veo animales por aquí." : "I don't see any animals around here.";
            case MONSTRUO -> es ? "No veo monstruos." : "I don't see any monsters.";
            case PERSONA -> es ? "No veo a nadie por aquí." : "I don't see anyone around here.";
            case MINERAL -> es ? "No veo minerales por aquí." : "I don't see any ores around here.";
            case COFRE -> es ? "No veo cofres por aquí." : "I don't see any chests around here.";
            case MONTANA -> es ? "No veo montañas desde aquí." : "I don't see any mountains from here.";
            case ESCALERA -> es ? "No veo ninguna escalera desde aquí." : "I don't see any staircase from here.";
            case TUNEL -> es ? "No estoy en ningún túnel." : "I'm not in a tunnel.";
            case CAMINO -> es ? "No veo caminos por aquí." : "I don't see any paths around here.";
            default -> es ? "No veo nada de eso." : "I don't see anything like that.";
        };
    }

    /** A plain English account for a language model: place, light, weather, and every thing with direction and distance. */
    public String forModel() {
        StringBuilder sb = new StringBuilder();
        sb.append("Place: ");
        switch (lugar) {
            case AFUERA -> sb.append(profundidad > 4 ? "outside under the open sky, but down in a pit about " + profundidad + " blocks below the land"
                    : "outside under the open sky, at the level of the land");
            case BAJO_TECHO -> sb.append("under a roof at the level of the land (inside a building, under a tree or an overhang)");
            case BAJO_TIERRA -> sb.append("underground in the rock, about " + profundidad + " blocks below the land");
        }
        sb.append(".\n");
        if (!reliefEn.isEmpty() || !groundEn.isEmpty()) {
            sb.append("Ground: ").append((reliefEn + " " + groundEn).trim()).append(".\n");
        }
        int hours = (int) ((timeOfDay / 1000 + 6) % 24);
        int minutes = (int) ((timeOfDay % 1000) * 60 / 1000);
        String period = hours >= 5 && hours < 7 ? "dawn" : hours < 12 && hours >= 7 ? "morning" : hours >= 12 && hours < 17 ? "afternoon"
                : hours >= 17 && hours < 19 ? "dusk" : "night";
        sb.append(String.format(Locale.ROOT, "Time: %02d:%02d (%s).%n", hours, minutes, period));
        sb.append("Weather: ").append(thundering ? "thunderstorm" : raining ? "rain" : "clear");
        if (lugar == Lugar.BAJO_TIERRA) {
            sb.append(" (it cannot see the sky from here)");
        }
        sb.append(".\n");
        if (ahora == null) {
            sb.append("Light where it stands: ").append(light).append("/15 (")
                    .append(light >= 12 ? "bright" : light >= 7 ? "dim" : light >= 3 ? "dark" : "pitch dark").append(").\n");
        }
        sb.append("Biome: ").append(biome).append(".\n");
        if (ahora != null) {
            // the moment: light and darkness, what it hears and does not see, the structures it recognises, the dangers
            String now = ahora.forModel();
            if (!now.isBlank()) {
                sb.append(now).append('\n');
            }
        }
        if (items.isEmpty()) {
            sb.append("It sees nothing of note.\n");
        } else {
            sb.append("It sees:\n");
            for (Item item : items.subList(0, Math.min(16, items.size()))) {
                sb.append("- ").append(phrase(item, false));
                long ago = item.cosa.hace() / 20;
                sb.append(ago <= 2 ? " (in view now)" : " (seen " + ago + " s ago)").append('\n');
            }
        }
        String[][] absent = {{ARBOL, "No trees in sight."}, {AGUA, "No water in sight."}, {PERSONA, "No people in sight."},
                {ANIMAL, "No animals in sight."}, {MONSTRUO, "No monsters in sight."}, {CUEVA, "No cave openings in sight."}};
        for (String[] a : absent) {
            if (nearest(a[0]) == null) {
                sb.append(a[1]).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
