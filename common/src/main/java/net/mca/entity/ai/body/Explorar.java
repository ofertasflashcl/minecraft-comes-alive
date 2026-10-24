package net.mca.entity.ai.body;

import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.sense.MapaVisto;
import net.mca.entity.ai.sense.Mirada;
import net.mca.entity.ai.sense.Obras;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Exploring the way a player does it in a new world, from nothing but what its own eyes saw ({@link MapaVisto}).
 * Looking for wood, a tree it already saw anywhere within {@value #FAR_TREES} blocks (the map its eyes fill as far as a
 * player's twelve chunks reach) comes first: it walks straight to the nearest one its legs can get to, with no look
 * round on the way, and only when it knows of none does it stop to look. A look round is four turns of the head
 * ({@link Mirada}), the view is wide. Then it picks the way it knows least about, out of
 * sixteen headings, leaning toward open land (and toward trees when it wants wood) and away from water, lava,
 * buildings and ground it already walked, and it keeps going the way it was going rather than turning back and
 * forth. It walks to a point 24 to 40 blocks out on land its eye saw (or just that way when it saw none), with its
 * head looking ahead and to the sides; there, every ten seconds on the way, and whenever the legs cannot get
 * further, it stops and looks all around again. A canopy seen far away is a tree: when it wants wood it walks
 * to the leaves, and the trunk shows up as it gets close.
 * <p>
 * It stays within a ring around where the search began and widens it when it has seen most of what is inside.
 * Only when the widest ring is mostly seen (or the search went on far too long) does it say there is nothing.
 */
public final class Explorar {
    /** What it is looking for: it changes which way looks most promising. */
    public enum Busca {
        ARBOLES("árboles"), COMIDA("comida"), SITIO("un sitio para cavar");

        public final String palabra;

        Busca(String palabra) {
            this.palabra = palabra;
        }
    }

    private static final int SECTORS = 16;
    private static final float SECTOR = 360.0F / SECTORS;
    /** The ring of each sector it weighs: closer than this it sees anyway, farther it cannot judge. */
    private static final int NEAR = 8;
    private static final int FAR = 40;
    private static final int MIN_LEG = 24;
    private static final int MAX_LEG = 40;
    /** Ticks of walking before it stops and looks around again. */
    private static final int LEG_TICKS = 200;
    private static final int START_RADIUS = 48;
    private static final int WIDEN = 32;
    private static final int MAX_RADIUS = 128;
    /** A sector this little unknown counts as seen. */
    private static final double SEEN = 0.25;
    /** Part of the ring it must have seen before it widens it (or, at the widest, gives up). */
    private static final double COVERED = 0.7;
    /** However it goes, a search this long is over. */
    private static final int MAX_TICKS = 12000;
    /** A way that did not work is left alone this long. */
    private static final long FAILED_TICKS = 6000;
    /** A pause in the calls longer than this means a new search from where it stands. */
    private static final long PAUSE = 400;
    private static final float SPEED = 0.65F;
    /** How far away a tree it saw may be and still be walked to: a player's twelve chunks of view. */
    public static final int FAR_TREES = 192;
    /** A tree seen this far above or below its feet is on other land (down off a cliff, up a mountain). */
    private static final int TREE_RISE = 16;
    /** Trees whose way it checks with its legs' path finder each time it chooses one. */
    private static final int TREE_TRIES = 4;
    /** Walking to a tree, a nearer one that shows up on the way is taken when it is this much nearer. */
    private static final int NEARER = 8;

    private final Mirada mirada = new Mirada();
    /** Its hands, for the natural leaves that close the way it walks (a hedge, a low crown): its legs' path finder takes them for a wall. */
    private final Hands.Breaker hands = new Hands.Breaker();
    /** The leaf block across the way it is clicking away, the leg waiting on it. */
    @Nullable
    private BlockPos hedge;
    /** Cells (by {@link MapaVisto#CELL} columns) of waypoints and cues that led nowhere, until when. */
    private final Map<Long, Long> failed = new HashMap<>();
    @Nullable
    private BlockPos origin;
    @Nullable
    private Busca busca;
    private int radius = START_RADIUS;
    @Nullable
    private BlockPos waypoint;
    /** The waypoint is a sighting (a canopy, a tree), not just a direction. */
    private boolean cue;
    private BlockPos legFrom = BlockPos.ORIGIN;
    private BlockPos navFrom = BlockPos.ORIGIN;
    private int legTicks;
    private int navFails;
    /** The way the last leg went (degrees, Minecraft yaw), NaN before the first. */
    private float heading = Float.NaN;
    private boolean sweeping;
    private boolean exhausted;
    private long lastTick = Long.MIN_VALUE;
    private int ticks;
    private int legs;

    /** Forget this search (not the ways that failed): the next tick starts one from where it stands. */
    public void reset() {
        origin = null;
        busca = null;
        waypoint = null;
        cue = false;
        sweeping = false;
        exhausted = false;
        heading = Float.NaN;
        radius = START_RADIUS;
        ticks = 0;
        legs = 0;
        hedge = null;
        mirada.stop();
    }

    /** It has looked all over the widest ring and found nothing. */
    public boolean exhausted() {
        return exhausted;
    }

    /** Where it is walking to now, or null while it looks around. */
    @Nullable
    public BlockPos waypoint() {
        return waypoint;
    }

    public boolean sweeping() {
        return sweeping;
    }

    /** A point it went for and could not reach, or a cue that showed nothing: not again for a while. */
    public void fail(BlockPos pos, long now) {
        failed.put(cellKey(pos.getX(), pos.getZ()), now + FAILED_TICKS);
    }

    /**
     * One tick of exploring for {@code what}. True while the search goes on, false once it has seen most of the
     * widest ring around where it began (then {@link #exhausted()}).
     */
    public boolean tick(ServerWorld world, VillagerEntityMCA villager, Busca what) {
        long now = world.getTime();
        if (origin == null || busca != what || now - lastTick > PAUSE) {
            begin(villager, what);
        }
        lastTick = now;
        if (exhausted) {
            return false;
        }
        BlockPos feet = villager.getBlockPos();
        MapaVisto mapa = Retina.memory(villager).mapa();
        if (++ticks % 10 == 0) {
            mapa.walked(feet, now);
        }
        if (ticks > MAX_TICKS) {
            giveUp(villager, "lleva demasiado rato buscando");
            return false;
        }
        if (sweeping) {
            // a steady look: it stands still while it turns its head
            Motor.standStill(villager);
            if (mirada.tick(world, villager)) {
                return true;
            }
            sweeping = false;
            plan(world, villager, mapa, feet, now);
            return !exhausted;
        }
        if (waypoint == null) {
            // a tree it already saw comes before looking round: no turning the head for what it knows is there
            if (!goToKnownTree(world, villager, mapa, feet, now, "recuerda")) {
                sweep(villager);
            }
            return true;
        }
        legTicks++;
        // its own legs, not the brain's errands: no other walk target pulls it away mid-leg
        villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        if (hedge != null) {
            // leaves across the way: standing still, by hand, the leg's clock waiting
            legTicks--;
            Motor.standStill(villager);
            int r = hands.tick(world, villager, hedge);
            if (r != Hands.Breaker.RUNNING) {
                if (r == Hands.Breaker.DONE) {
                    Hands.collect(world, villager, hedge, 1.5);
                    navFails = 0;
                    navFrom = feet;
                    villager.getNavigation().startMovingTo(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5, SPEED);
                }
                hedge = null;
            }
            return true;
        }
        double dx = waypoint.getX() + 0.5 - villager.getX();
        double dz = waypoint.getZ() + 0.5 - villager.getZ();
        double reach = cue ? 4.0 : 3.0;
        if (dx * dx + dz * dz <= reach * reach) {
            if (cue) {
                // it came to look at what it saw: whatever is there is in sight now, no need to come back to it
                fail(waypoint, now);
            }
            waypoint = null;
            sweep(villager);
            return true;
        }
        if (busca == Busca.ARBOLES && legTicks % 40 == 0) {
            // a canopy that came into view on the way: that is where the wood is, the leg can wait (walking to a tree
            // already, only one a good deal nearer)
            BlockPos seen = treeCue(world, villager, mapa, feet, now, false);
            if (seen != null && (!cue || seen.getSquaredDistance(feet) < sq(Math.sqrt(waypoint.getSquaredDistance(feet)) - NEARER))) {
                MCA.LOGGER.info("[Tropeles] {} ve copas de árboles hacia {} mientras camina: va hacia ellas", name(villager), seen.toShortString());
                go(seen, true, feet);
                return true;
            }
        }
        if (legTicks >= LEG_TICKS) {
            boolean got = !legFrom.isWithinDistance(feet, 4);
            if (cue && got) {
                // on its way to a tree it saw and getting closer: no stopping to look round, it walks on
                legTicks = 0;
                legFrom = feet;
                return true;
            }
            if (!got) {
                fail(waypoint, now); // ten seconds and it got nowhere that way
            }
            waypoint = null;
            if (!goToKnownTree(world, villager, mapa, feet, now, "sigue hacia")) {
                sweep(villager);
            }
            return true;
        }
        if (legTicks == 1 || villager.getNavigation().isIdle() && legTicks % 10 == 0) {
            // the path ran out (partial paths end short of a far point): on from here, unless it got nowhere
            boolean stuck = legTicks > 1 && navFrom.isWithinDistance(feet, 1.5);
            navFrom = feet;
            boolean started = villager.getNavigation().startMovingTo(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5, SPEED);
            BlockPos leaf = !started || stuck ? Hands.leafAhead(world, villager, Vec3d.ofBottomCenter(waypoint)) : null;
            if (leaf != null) {
                // what stops it is leaves (never a player's, never a village's): clicked away, and on it walks
                hedge = leaf;
                villager.getNavigation().stop();
                Retina.memory(villager).doing("quita las hojas que le cierran el paso", now);
                return true;
            }
            if ((!started || stuck) && ++navFails >= 2) {
                // its legs find no way there: remember that, look around and choose again
                MCA.LOGGER.info("[Tropeles] {} no encuentra camino hacia {}: mira otra vez", name(villager), waypoint.toShortString());
                fail(waypoint, now);
                waypoint = null;
                if (!goToKnownTree(world, villager, mapa, feet, now, "prueba con")) {
                    sweep(villager);
                }
                return true;
            }
        }
        lookWhileWalking(villager, dx, dz);
        return true;
    }

    private void begin(VillagerEntityMCA villager, Busca what) {
        reset();
        origin = villager.getBlockPos().toImmutable();
        busca = what;
    }

    private void sweep(VillagerEntityMCA villager) {
        sweeping = true;
        villager.getNavigation().stop();
        mirada.start(villager);
    }

    /** Head up and ahead, turning slowly left and right of the way it walks, the way a player scans the land. */
    private void lookWhileWalking(VillagerEntityMCA villager, double dx, double dz) {
        float toward = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float yaw = toward + 40.0F * MathHelper.sin(legTicks * 0.07F);
        float pitch = 4.0F + 6.0F * MathHelper.sin(legTicks * 0.11F);
        Vec3d look = Vec3d.fromPolar(pitch, yaw).multiply(8);
        Hands.aim(villager, villager.getEyePos().add(look));
    }

    // ------------------------------------------------------------------ choosing

    /** After a look around: a sighting to walk to, else the most promising way, else a wider ring, else nothing. */
    private void plan(ServerWorld world, VillagerEntityMCA villager, MapaVisto mapa, BlockPos feet, long now) {
        failed.values().removeIf(until -> until < now);
        if (busca == Busca.ARBOLES) {
            BlockPos seen = treeCue(world, villager, mapa, feet, now, true);
            if (seen != null) {
                MCA.LOGGER.info("[Tropeles] {} ve copas de árboles hacia {} (a {} bloques): va a verlas de cerca", name(villager),
                        seen.toShortString(), (int) Math.sqrt(seen.getSquaredDistance(feet)));
                go(seen, true, feet);
                return;
            }
        }
        double[] score = new double[SECTORS];
        double[] novelty = new double[SECTORS];
        int seenAround = 0;
        int best = -1;
        for (int i = 0; i < SECTORS; i++) {
            float yaw = i * SECTOR;
            novelty[i] = mapa.novelty(feet, yaw, NEAR, FAR);
            if (novelty[i] < SEEN) {
                seenAround++;
            }
            score[i] = score(mapa, feet, yaw, novelty[i], now);
            if (best < 0 || score[i] > score[best]) {
                best = i;
            }
        }
        double covered = coverage(mapa);
        if (covered >= COVERED || seenAround >= SECTORS - 2 || score[best] < -1.5) {
            if (radius >= MAX_RADIUS) {
                giveUp(villager, String.format(Locale.ROOT, "vio el %.0f%% de %d bloques a la redonda", covered * 100, radius));
                return;
            }
            radius = Math.min(MAX_RADIUS, radius + WIDEN);
            MCA.LOGGER.info("[Tropeles] {} ya vio casi todo cerca ({}% de la zona, {} rumbos vistos): busca {} más lejos, hasta {} bloques",
                    name(villager), (int) (covered * 100), seenAround, busca.palabra, radius);
            // the same choice again with the wider ring, now that the far side of it counts
            best = 0;
            for (int i = 0; i < SECTORS; i++) {
                score[i] = score(mapa, feet, i * SECTOR, novelty[i], now);
                if (score[i] > score[best]) {
                    best = i;
                }
            }
        }
        float yaw = best * SECTOR;
        heading = yaw;
        BlockPos to = waypointToward(mapa, feet, yaw, now);
        legs++;
        MCA.LOGGER.info("[Tropeles] {} busca {}: va hacia el {} ({}), novedad {}, a {} bloques, tramo {}", name(villager), busca.palabra,
                rumbo(yaw), to.toShortString(), String.format(Locale.ROOT, "%.2f", novelty[best]),
                (int) Math.sqrt(to.getSquaredDistance(feet)), legs);
        go(to, false, feet);
    }

    private void go(BlockPos to, boolean isCue, BlockPos feet) {
        waypoint = to;
        cue = isCue;
        legFrom = feet;
        navFrom = feet;
        legTicks = 0;
        navFails = 0;
    }

    private void giveUp(VillagerEntityMCA villager, String why) {
        exhausted = true;
        waypoint = null;
        sweeping = false;
        mirada.stop();
        villager.getNavigation().stop();
        MCA.LOGGER.info("[Tropeles] {} deja de buscar {}: {}", name(villager), busca == null ? "" : busca.palabra, why);
    }

    /**
     * How good a heading looks: what it does not know yet, plus land (and trees, when it wants wood) it glimpsed
     * that way, minus water, lava, buildings and its own footsteps, the ring around where it began, and turning
     * back on the way it was going.
     */
    private double score(MapaVisto mapa, BlockPos feet, float yaw, double novelty, long now) {
        int known = 0;
        int tree = 0;
        int land = 0;
        int water = 0;
        int lava = 0;
        int built = 0;
        int walked = 0;
        double sx = -MathHelper.sin(yaw * MathHelper.RADIANS_PER_DEGREE);
        double sz = MathHelper.cos(yaw * MathHelper.RADIANS_PER_DEGREE);
        for (int d = NEAR; d <= FAR; d += MapaVisto.CELL) {
            for (int off = -2; off <= 2; off++) {
                // a fan as wide as the sector
                float a = (yaw + off * SECTOR / 4) * MathHelper.RADIANS_PER_DEGREE;
                int x = feet.getX() + (int) Math.round(-MathHelper.sin(a) * d);
                int z = feet.getZ() + (int) Math.round(MathHelper.cos(a) * d);
                int f = mapa.flags(x, z);
                if (f == 0) {
                    continue;
                }
                known++;
                tree += (f & MapaVisto.TREE) != 0 ? 1 : 0;
                land += (f & (MapaVisto.LAND | MapaVisto.ROCK)) != 0 ? 1 : 0;
                water += (f & MapaVisto.WATER) != 0 ? 1 : 0;
                lava += (f & MapaVisto.LAVA) != 0 ? 1 : 0;
                built += (f & MapaVisto.BUILT) != 0 ? 1 : 0;
                walked += (f & MapaVisto.WALKED) != 0 ? 1 : 0;
            }
        }
        double s = novelty;
        if (known > 0) {
            double k = known;
            s += 0.2 * land / k - 0.5 * water / k - 1.0 * lava / k - 0.4 * walked / k;
            s -= (busca == Busca.SITIO ? 1.2 : busca == Busca.COMIDA ? 0.3 : 0.6) * built / k;
            if (busca == Busca.ARBOLES) {
                s += 0.8 * tree / k;
            }
        }
        if (!Float.isNaN(heading)) {
            // keep going the way it was going: never back and forth between two opposite sides
            float turn = Math.abs(MathHelper.wrapDegrees(yaw - heading));
            s += 0.25 * MathHelper.cos(turn * MathHelper.RADIANS_PER_DEGREE);
            if (turn > 135.0F) {
                s -= 0.3;
            }
        }
        if (origin != null) {
            double ex = feet.getX() + sx * MIN_LEG - origin.getX();
            double ez = feet.getZ() + sz * MIN_LEG - origin.getZ();
            double out = Math.sqrt(ex * ex + ez * ez) - radius;
            if (out > 0) {
                s -= 1.0 + out / 16.0;
            }
        }
        BlockPos end = new BlockPos(feet.getX() + (int) Math.round(sx * MIN_LEG), feet.getY(), feet.getZ() + (int) Math.round(sz * MIN_LEG));
        if (failedAt(end, now)) {
            s -= 0.6;
        }
        return s;
    }

    /** A point that way, 24 to 40 blocks out on land its eye saw near its own level, or just that way. */
    private BlockPos waypointToward(MapaVisto mapa, BlockPos feet, float yaw, long now) {
        double sx = -MathHelper.sin(yaw * MathHelper.RADIANS_PER_DEGREE);
        double sz = MathHelper.cos(yaw * MathHelper.RADIANS_PER_DEGREE);
        for (int d = MAX_LEG; d >= MIN_LEG; d -= MapaVisto.CELL) {
            for (int off : new int[]{0, -6, 6, -12, 12}) {
                int x = feet.getX() + (int) Math.round(sx * d - sz * off);
                int z = feet.getZ() + (int) Math.round(sz * d + sx * off);
                if (!inRing(x, z)) {
                    continue;
                }
                BlockPos land = mapa.land(x, z);
                int f = mapa.flags(x, z);
                if (land == null || (f & (MapaVisto.WATER | MapaVisto.LAVA | MapaVisto.BUILT)) != 0
                        || Math.abs(land.getY() - feet.getY()) > 8 || failedAt(land, now)) {
                    continue;
                }
                return land;
            }
        }
        // nothing seen that way: walk toward it anyway, as far as the ring and the legs allow
        for (int d = MIN_LEG; d >= 8; d -= MapaVisto.CELL) {
            int x = feet.getX() + (int) Math.round(sx * d);
            int z = feet.getZ() + (int) Math.round(sz * d);
            if (inRing(x, z) || d == 8) {
                return new BlockPos(x, feet.getY(), z);
            }
        }
        return feet;
    }

    /**
     * Something that is a tree to a person's eye: a cell where trees showed, or natural leaves it saw, the nearest
     * first. The canopy is what shows from far away; the trunk comes into view on the way.
     */
    @Nullable
    private BlockPos treeCue(ServerWorld world, VillagerEntityMCA villager, MapaVisto mapa, BlockPos feet, long now, boolean checkWay) {
        int tries = 0;
        for (BlockPos tree : mapa.trees(feet, FAR_TREES)) {
            if (failedAt(tree, now) || Math.abs(tree.getY() - feet.getY()) > TREE_RISE || tree.isWithinDistance(feet, 3)
                    || !world.isChunkLoaded(tree.getX() >> 4, tree.getZ() >> 4)) {
                continue;
            }
            if (!checkWay) {
                return tree;
            }
            if (reachable(villager, feet, tree)) {
                return tree;
            }
            fail(tree, now); // its legs find no way there: not that one for a while
            if (++tries >= TREE_TRIES) {
                break;
            }
        }
        VisualMemory memory = Retina.memory(villager);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (VisualMemory.SeenBlock seen : memory.blocks(s -> s.state.isIn(BlockTags.LEAVES) && Obras.natural(s.state))) {
            double dx = seen.pos.getX() - feet.getX();
            double dz = seen.pos.getZ() - feet.getZ();
            double distance = dx * dx + dz * dz;
            if (distance < 9 || distance >= bestDistance || failedAt(seen.pos, now) || !inRing(seen.pos.getX(), seen.pos.getZ())
                    || Math.abs(seen.pos.getY() - feet.getY()) > 16) {
                continue;
            }
            bestDistance = distance;
            best = seen.pos;
        }
        if (best == null) {
            return null;
        }
        // under the leaves, at its own level: the path finder takes it as close as the ground goes
        BlockPos ground = mapa.land(best.getX(), best.getZ());
        return ground != null && ground.getY() < best.getY() ? ground : new BlockPos(best.getX(), feet.getY(), best.getZ());
    }

    /**
     * Its legs can get to {@code tree}, or a good deal closer to it: the path finder its navigation walks by, from
     * where it stands, over loaded land (a far tree's path ends short of it, and gets on from there).
     */
    private static boolean reachable(VillagerEntityMCA villager, BlockPos feet, BlockPos tree) {
        if (!villager.isOnGround()) {
            return true; // mid-jump or swimming the path finder cannot judge: its legs will tell on the way
        }
        net.minecraft.entity.ai.pathing.Path path = villager.getNavigation().findPathTo(tree, 2);
        if (path == null || path.getLength() == 0) {
            return false;
        }
        if (path.reachesTarget()) {
            return true;
        }
        BlockPos end = path.getNodePos(path.getLength() - 1);
        double before = Math.sqrt(feet.getSquaredDistance(tree));
        double after = Math.sqrt(end.getSquaredDistance(tree));
        return after <= 6 || after < before - 12;
    }

    /**
     * Walks to the nearest tree it saw that its legs can get to, if any. True when it is on its way. The log says
     * which and how far, and {@code verb} what led to it.
     */
    private boolean goToKnownTree(ServerWorld world, VillagerEntityMCA villager, MapaVisto mapa, BlockPos feet, long now, String verb) {
        if (busca != Busca.ARBOLES) {
            return false;
        }
        BlockPos tree = treeCue(world, villager, mapa, feet, now, true);
        if (tree == null) {
            return false;
        }
        MCA.LOGGER.info("[Tropeles] {} {} un árbol que vio en {} (a {} bloques): va directo, sin mirar alrededor", name(villager), verb,
                tree.toShortString(), (int) Math.sqrt(tree.getSquaredDistance(feet)));
        sweeping = false;
        mirada.stop();
        go(tree, true, feet);
        return true;
    }

    private static double sq(double v) {
        return v <= 0 ? 0 : v * v;
    }

    /** How much of the ring around where the search began it has seen, 0 to 1. */
    private double coverage(MapaVisto mapa) {
        if (origin == null) {
            return 0;
        }
        int all = 0;
        int known = 0;
        for (int r = MapaVisto.CELL * 2; r <= radius; r += MapaVisto.CELL * 2) {
            int steps = Math.max(8, (int) (2 * Math.PI * r / (MapaVisto.CELL * 2)));
            for (int k = 0; k < steps; k++) {
                double a = 2 * Math.PI * k / steps;
                all++;
                if (mapa.known(origin.getX() + (int) Math.round(Math.cos(a) * r), origin.getZ() + (int) Math.round(Math.sin(a) * r))) {
                    known++;
                }
            }
        }
        return all == 0 ? 1 : known / (double) all;
    }

    private boolean inRing(int x, int z) {
        if (origin == null) {
            return true;
        }
        double dx = x - origin.getX();
        double dz = z - origin.getZ();
        return dx * dx + dz * dz <= (double) radius * radius;
    }

    private boolean failedAt(BlockPos pos, long now) {
        Long until = failed.get(cellKey(pos.getX(), pos.getZ()));
        return until != null && until > now;
    }

    private static long cellKey(int x, int z) {
        return ((long) Math.floorDiv(x, MapaVisto.CELL) << 32) ^ (Math.floorDiv(z, MapaVisto.CELL) & 0xffffffffL);
    }

    /** The way a heading points, in words for the log. */
    public static String rumbo(float yaw) {
        String[] names = {"sur", "suroeste", "oeste", "noroeste", "norte", "noreste", "este", "sureste"};
        int i = Math.floorMod(Math.round(MathHelper.wrapDegrees(yaw) / 45.0F), 8);
        return names[i];
    }

    private static String name(VillagerEntityMCA villager) {
        return villager.getName().getString();
    }
}
