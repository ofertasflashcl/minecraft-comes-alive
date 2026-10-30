package net.mca.entity.ai.sense;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.body.Hands;
import net.mca.entity.ai.body.Mining;
import net.mca.entity.ai.mente.Recuerdos;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
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
import java.util.WeakHashMap;

/**
 * "What I see now": the compact understanding of the moment a player has without thinking about it, remade once a
 * second ({@value #EVERY} ticks) from what the eye recorded ({@link VisualMemory}), what the ears catch and what the
 * body feels. The brain and the chat read it instead of the world.
 * <ul>
 * <li><b>Light</b> ({@link Luz}): the block light and the sky light where it stands, how much of its last frame was too
 * dark to make out, whether monsters can spawn around it (block light 0 and the light it stands in 7 or less, the
 * 1.20.1 overworld rule) and whether a player would set a torch here.</li>
 * <li><b>Beings</b> ({@link Ser}): every one it saw lately or hears now, hostile, animal, player or villager, with the
 * distance and the side ("detrás", "a la izquierda"), whether it is coming closer (its distance shrank since the last
 * second, or its seen motion points at it), and whether it was seen or only heard. Hearing ({@value #HEAR} blocks):
 * footsteps of anything that moved and is not sneaking, and within {@value #HEAR_STILL} the noises mobs make standing
 * (groans, rattles, bleats); a creeper is silent until it hisses. A heard being is placed only roughly (to two
 * blocks), the way a sound tells where, not what exactly. A connected villager glances at a hostile it hears and does
 * not see ({@link #tick}); the brain may turn to it for real ({@link #voltear}).</li>
 * <li><b>Structures</b> ({@link Estructura}): staircases (a chain of at least {@value #STAIR_STEPS} cells, each one up
 * and one over from the last, whose floors the eye saw, with room for the body and a jump; carved into the rock, or
 * its own: cells it walked or an {@link Recuerdos#ESCALERA} mark), the tunnel it stands in, a cave, a ravine, a path,
 * rails and old mineshafts.</li>
 * <li><b>Dangers</b> ({@link Peligro}): lava seen near, a drop ahead, loose gravel or sand over the head, a hostile
 * close, a creeper about to blow, darkness where monsters spawn.</li>
 * <li><b>Ores</b> ({@link Mineral}): by name, only those the eye saw.</li>
 * </ul>
 * It casts no rays of its own: the glance is {@link Retina#canSee} (at most nine rays) and only when something is
 * heard, at most once every {@value #GLANCE_COOLDOWN} ticks. Everything else reads memory, the light engine and a
 * handful of block look-ups at the feet (touch).
 */
public final class Ahora {
    /** Remade this often (ticks). */
    public static final int EVERY = 20;
    /** Seen within this many ticks is "in view now". */
    public static final int SEEN = 40;
    /** Structures, ores and dangers from what it saw this many ticks back at most. */
    public static final int MEMORY = 1200;
    /** Beings seen this many ticks back at most are still told. */
    public static final int BEINGS = 200;
    /** Footsteps are heard this far. */
    public static final double HEAR = 16.0;
    /** A mob standing still is heard this far (its idle noises). */
    public static final double HEAR_STILL = 8.0;
    /** Moved at least this much since the last second: footsteps. */
    public static final double MOVED = 0.3;
    /** Came at least this much closer since the last second: approaching. */
    public static final double APPROACH = 0.5;
    /** How far around it structures and dangers count (blocks). */
    public static final int NEAR = 24;
    /** Staircases are looked for among the floors seen this far around (blocks), at most this far up or down. */
    public static final int STAIR_RADIUS = 16;
    public static final int STAIR_DY = 10;
    /** A staircase has at least this many steps. */
    public static final int STAIR_STEPS = 3;
    /** A glance at something heard, at most this often. */
    public static final int GLANCE_COOLDOWN = 60;
    /** Lava this close is a danger. */
    public static final double LAVA_NEAR = 12.0;
    /** A drop this deep ahead is a danger. */
    public static final int DROP = 4;
    /** Light under this is dark for the eye. */
    public static final int DARK = 8;

    /** Kinds of {@link Estructura#tipo()}: the same words as {@link Escena}'s kinds. */
    public static final String ESCALERA = Escena.ESCALERA;
    public static final String TUNEL = Escena.TUNEL;
    public static final String CUEVA = Escena.CUEVA;
    public static final String BARRANCO = Escena.BARRANCO;
    public static final String CAMINO = Escena.CAMINO;
    public static final String VIAS = Escena.VIAS;

    /** Kinds of {@link Peligro#tipo()}. */
    public static final String P_LAVA = "lava";
    public static final String P_CAIDA = "caida";
    public static final String P_GRAVA = "grava";
    public static final String P_MONSTRUO = "monstruo";
    public static final String P_CREEPER = "creeper";
    public static final String P_OSCURIDAD = "oscuridad";

    public enum Clase {
        HOSTIL, ANIMAL, JUGADOR, ALDEANO
    }

    /**
     * A being it saw lately or hears now. {@code nombre}/{@code name}: the Spanish and English noun without article
     * ("zombi"), or the person's name. {@code pos}: where it was last seen, or roughly where the sound came from.
     * {@code hace}: ticks since it was last seen (0 when only heard now).
     */
    public record Ser(int id, String nombre, String name, Clase clase, Vec3d pos, double distancia, String direccion, String direction,
                      boolean visto, boolean oido, boolean acercandose, long hace) {
        public boolean hostil() {
            return clase == Clase.HOSTIL;
        }
    }

    /**
     * A structure it makes out. {@code celdas}: for a staircase its feet cells from the bottom step up; for the others
     * the cells that showed it (may be one). {@code largo}: steps of a staircase, cells otherwise. {@code propia}: a
     * staircase it made or walked itself. {@code sube}: from the nearest end of a staircase the way leads up (true) or
     * down (false).
     */
    public record Estructura(String tipo, String nombre, String name, BlockPos donde, double distancia, String direccion, String direction,
                             int largo, boolean propia, boolean sube, List<BlockPos> celdas) {
    }

    /** Something that can hurt it, with its words in Spanish and English. */
    public record Peligro(String tipo, String es, String en, @Nullable BlockPos donde, double distancia) {
    }

    /** An ore it saw, by name, the nearest of its kind, and how many of that kind it saw. */
    public record Mineral(String nombre, String name, BlockPos donde, double distancia, String direccion, int cuantos) {
    }

    /**
     * The light. {@code bloque}/{@code cielo}: the block and raw sky light where it stands; {@code nivel}: the light it
     * stands in now (the sky darkened by the hour); {@code oscuroVista}: the share of its last frame too dark to make
     * out; {@code monstruos}: monsters can spawn around it; {@code antorcha}: a player would set a torch here.
     */
    public record Luz(int bloque, int cielo, int nivel, double oscuroVista, boolean oscuro, boolean monstruos, boolean antorcha) {
        public String palabra(boolean es) {
            if (nivel >= 12) {
                return es ? "hay buena luz" : "it is bright";
            }
            if (nivel >= DARK) {
                return es ? "hay poca luz" : "it is dim";
            }
            if (nivel >= 4) {
                return es ? "está oscuro" : "it is dark";
            }
            return es ? "está muy oscuro" : "it is pitch dark";
        }
    }

    private static final class Estado {
        @Nullable
        Ahora last;
        Map<Integer, Vec3d> heardAt = new HashMap<>();
        Map<Integer, Double> distance = new HashMap<>();
        /** when each being was last heard: a sound stays in mind a couple of seconds */
        Map<Integer, Long> heardTick = new HashMap<>();
        long glance = Long.MIN_VALUE / 2;
    }

    private static final Map<LivingEntity, Estado> ESTADOS = new WeakHashMap<>();

    private final long tick;
    private final Lugar lugar;
    private final int profundidad;
    private final Luz luz;
    private final List<Ser> seres;
    private final List<Estructura> estructuras;
    private final List<Peligro> peligros;
    private final List<Mineral> minerales;

    private Ahora(long tick, Lugar lugar, int profundidad, Luz luz, List<Ser> seres, List<Estructura> estructuras, List<Peligro> peligros,
                  List<Mineral> minerales) {
        this.tick = tick;
        this.lugar = lugar;
        this.profundidad = profundidad;
        this.luz = luz;
        this.seres = Collections.unmodifiableList(seres);
        this.estructuras = Collections.unmodifiableList(estructuras);
        this.peligros = Collections.unmodifiableList(peligros);
        this.minerales = Collections.unmodifiableList(minerales);
    }

    // ---------------------------------------------------------------- getting one

    /** The moment as it understands it, remade when the last one is older than {@value #EVERY} ticks. Server thread. */
    public static Ahora of(ServerWorld world, LivingEntity viewer) {
        Estado estado = ESTADOS.computeIfAbsent(viewer, v -> new Estado());
        Ahora last = estado.last;
        if (last != null && world.getTime() - last.tick < EVERY && world.getTime() >= last.tick) {
            return last;
        }
        return update(world, viewer);
    }

    /** The last one made, or null; never makes one. */
    @Nullable
    public static Ahora get(LivingEntity viewer) {
        Estado estado = ESTADOS.get(viewer);
        return estado == null ? null : estado.last;
    }

    /** Remakes it now. Server thread. */
    public static Ahora update(ServerWorld world, LivingEntity viewer) {
        Estado estado = ESTADOS.computeIfAbsent(viewer, v -> new Estado());
        Ahora made = build(world, viewer, estado);
        estado.last = made;
        return made;
    }

    /** Forgets it (a check's stand-in when it is done). */
    public static void olvidar(LivingEntity viewer) {
        ESTADOS.remove(viewer);
    }

    /**
     * Every tick of every villager (from {@link Pantalla#tick}): once a second, for one whose eye is working (connected,
     * or it looked in the last two seconds), the moment is remade; then, when it hears a hostile it does not see within
     * {@value #HEAR_STILL} blocks, or coming closer within {@value #HEAR}, it glances that way (the head turns, the eye
     * checks, the head goes back), at most every {@value #GLANCE_COOLDOWN} ticks. A villager without AI has no reflexes.
     */
    public static void tick(ServerWorld world, VillagerEntityMCA villager) {
        if (villager.isSleeping() || !villager.isAlive()) {
            return;
        }
        long now = world.getTime();
        if (Math.floorMod(now + villager.getId(), EVERY) != 0) {
            return;
        }
        VisualMemory memory = Retina.memory(villager);
        if (!Pantalla.on(villager) && now - memory.lastLook() > 2L * EVERY) {
            return;
        }
        Ahora a = update(world, villager);
        if (villager.isAiDisabled()) {
            return;
        }
        Estado estado = ESTADOS.get(villager);
        Ser heard = a.oidoSinVer();
        if (estado != null && heard != null && now - estado.glance >= GLANCE_COOLDOWN
                && (heard.distancia() <= HEAR_STILL || heard.acercandose())) {
            estado.glance = now;
            Entity target = world.getEntityById(heard.id());
            if (target != null && ojeada(world, villager, target, false)) {
                update(world, villager);
            }
        }
    }

    /**
     * Turns the head to the nearest hostile it hears but does not see, the way a player turns at a groan behind them,
     * and looks. The head stays turned (the body's own aim takes it back later). True when it now sees it.
     */
    public static boolean voltear(ServerWorld world, LivingEntity viewer) {
        Ahora a = of(world, viewer);
        Ser heard = a.oidoSinVer();
        if (heard == null) {
            return false;
        }
        Entity target = world.getEntityById(heard.id());
        if (target == null) {
            return false;
        }
        Estado estado = ESTADOS.get(viewer);
        if (estado != null) {
            estado.glance = world.getTime();
        }
        boolean seen = ojeada(world, viewer, target, true);
        update(world, viewer);
        return seen;
    }

    /** The head toward the target, one look with {@link Retina#canSee}; back where it was unless {@code keep}. */
    static boolean ojeada(ServerWorld world, LivingEntity viewer, Entity target, boolean keep) {
        Vec3d eye = Retina.eye(viewer);
        Vec3d to = target.getBoundingBox().getCenter().subtract(eye);
        double flat = Math.sqrt(to.x * to.x + to.z * to.z);
        float yaw = (float) (MathHelper.atan2(to.z, to.x) * (180.0 / Math.PI)) - 90.0F;
        float pitch = (float) -(MathHelper.atan2(to.y, flat) * (180.0 / Math.PI));
        float headYaw = viewer.getHeadYaw();
        float oldPitch = viewer.getPitch();
        float bodyYaw = viewer.getBodyYaw();
        float oldYaw = viewer.getYaw();
        viewer.setHeadYaw(MathHelper.wrapDegrees(yaw));
        viewer.setPitch(MathHelper.clamp(pitch, -90.0F, 90.0F));
        boolean seen = false;
        try {
            seen = Retina.canSee(world, viewer, target);
            if (seen) {
                Retina.memory(viewer).seeEntity(target, Retina.distanceToBox(eye, target.getBoundingBox()), world.getTime());
            }
        } finally {
            if (!keep) {
                viewer.setHeadYaw(headYaw);
                viewer.setPitch(oldPitch);
                viewer.setBodyYaw(bodyYaw);
                viewer.setYaw(oldYaw);
            } else if (viewer instanceof MobEntity mob) {
                Hands.aim(mob, target.getBoundingBox().getCenter());
            }
        }
        return seen;
    }

    // ---------------------------------------------------------------- making it

    private static Ahora build(ServerWorld world, LivingEntity viewer, Estado estado) {
        long now = world.getTime();
        VisualMemory memory = Retina.memory(viewer);
        BlockPos feet = Mining.pies(viewer);
        Vec3d origin = viewer.getPos();
        float facing = viewer.getHeadYaw();
        Lugar lugar = Entorno.lugar(world, viewer);
        int depth = Entorno.profundidad(world, viewer);

        Luz luz = luz(world, viewer, memory, feet, lugar, now);
        List<Ser> seres = beings(world, viewer, memory, estado, origin, facing, now);

        // one pass over what the eye saw lately: floors for staircases, lava, paths, rails, webs, ravine floors
        List<VisualMemory.SeenBlock> floors = new ArrayList<>();
        VisualMemory.SeenBlock lava = null;
        double lavaD = Double.MAX_VALUE;
        List<BlockPos> paths = new ArrayList<>();
        List<BlockPos> rails = new ArrayList<>();
        int webs = 0;
        List<BlockPos> ravine = new ArrayList<>();
        int r2 = NEAR * NEAR;
        for (VisualMemory.SeenBlock s : memory.blocks(s -> s.state != null && now - s.lastSeen <= MEMORY)) {
            int dx = s.pos.getX() - feet.getX();
            int dz = s.pos.getZ() - feet.getZ();
            int dy = s.pos.getY() - feet.getY();
            if (dx * dx + dz * dz > r2 || Math.abs(dy) > NEAR) {
                continue;
            }
            BlockState state = s.state;
            if (!state.getFluidState().isEmpty() && state.getFluidState().isIn(FluidTags.LAVA)) {
                double d = origin.distanceTo(Vec3d.ofCenter(s.pos));
                if (d < lavaD) {
                    lavaD = d;
                    lava = s;
                }
                continue;
            }
            if (state.isOf(Blocks.DIRT_PATH)) {
                paths.add(s.pos);
            } else if (state.getBlock() instanceof AbstractRailBlock) {
                rails.add(s.pos);
            } else if (state.isOf(Blocks.COBWEB)) {
                webs++;
            }
            if (s.seeThrough || state.isIn(BlockTags.LEAVES) || MapaVisto.trunk(state) || !state.getFluidState().isEmpty()) {
                continue;
            }
            if (Math.abs(dy) <= STAIR_DY && dx * dx + dz * dz <= STAIR_RADIUS * STAIR_RADIUS && !state.getCollisionShape(world, s.pos).isEmpty()) {
                floors.add(s);
            }
            if (lugar == Lugar.AFUERA && s.face == Direction.UP && dy <= -8 && dx * dx + dz * dz <= 16 * 16
                    && world.getLightLevel(LightType.SKY, s.pos.up()) >= 12) {
                ravine.add(s.pos);
            }
        }

        List<Estructura> estructuras = new ArrayList<>(stairs(world, viewer, floors, origin, facing));
        here(world, viewer, memory, lugar, depth, origin, facing, feet, estructuras);
        if (paths.size() >= 3) {
            BlockPos at = nearest(origin, paths);
            estructuras.add(structure(CAMINO, "camino", "path", at, origin, facing, paths.size(), false, true, paths));
        }
        if (rails.size() >= 2) {
            BlockPos at = nearest(origin, rails);
            boolean shaft = webs > 0 || lugar == Lugar.BAJO_TIERRA;
            estructuras.add(structure(VIAS, shaft ? "mina abandonada" : "vías", shaft ? "old mineshaft" : "rails", at, origin, facing,
                    rails.size(), false, true, rails));
        }
        if (ravine.size() >= 4) {
            BlockPos at = nearest(origin, ravine);
            estructuras.add(structure(BARRANCO, "barranco", "ravine", at, origin, facing, ravine.size(), false, false, ravine));
        }
        estructuras.sort(Comparator.comparingDouble(e -> e.distancia() - (e.propia() ? 100.0 : 0.0)));

        // dangers
        List<Peligro> peligros = new ArrayList<>();
        if (lava != null && lavaD <= LAVA_NEAR) {
            String dir = Escena.direction(origin, facing, lava.pos, true);
            String dirEn = Escena.direction(origin, facing, lava.pos, false);
            long d = Math.round(lavaD);
            peligros.add(new Peligro(P_LAVA, (d <= 2 ? "¡lava aquí al lado!" : "lava a " + d + " bloques " + dir),
                    (d <= 2 ? "lava right beside it" : "lava " + d + " blocks " + dirEn), lava.pos, lavaD));
        }
        Direction ahead = Direction.fromRotation(facing);
        for (int k = 1; k <= 2; k++) {
            BlockPos c = feet.offset(ahead, k);
            if (!Mining.passable(world, c) || !Mining.passable(world, c.up())) {
                break;
            }
            if (Mining.standable(world, c.down())) {
                continue;
            }
            int fall = 1;
            while (fall < 24 && !Mining.standable(world, c.down(fall + 1)) && world.getBlockState(c.down(fall)).getFluidState().isEmpty()) {
                fall++;
            }
            if (fall >= DROP && world.getBlockState(c.down(fall)).getFluidState().isEmpty()) {
                peligros.add(new Peligro(P_CAIDA, "una caída de " + (fall >= 24 ? "muchos" : fall) + " bloques delante",
                        "a drop of " + (fall >= 24 ? "many" : fall) + " blocks ahead", c, k));
            }
            break;
        }
        Tacto.Toque roof = Tacto.techo(world, viewer, 3);
        if (roof != null && roof.state().getBlock() instanceof FallingBlock) {
            peligros.add(new Peligro(P_GRAVA, Tacto.nombre(roof.state(), true) + " suelta encima de la cabeza",
                    Tacto.nombre(roof.state(), false) + " loose over its head", roof.pos(), roof.distancia()));
        }
        for (Ser s : seres) {
            if (s.hostil() && s.distancia() <= 10 && (s.visto() || s.oido()) && s.hace() <= SEEN) {
                Entity e = world.getEntityById(s.id());
                if (e instanceof CreeperEntity creeper && creeper.getFuseSpeed() > 0) {
                    peligros.add(new Peligro(P_CREEPER, "¡un creeper a punto de explotar " + s.direccion() + "!",
                            "a creeper about to explode " + s.direction(), BlockPos.ofFloored(s.pos()), s.distancia()));
                } else {
                    String how = s.visto() ? "" : " (lo oigo)";
                    peligros.add(new Peligro(P_MONSTRUO, articulo(s) + " a " + Math.round(s.distancia()) + " bloques " + s.direccion()
                            + (s.acercandose() ? ", acercándose" : "") + how,
                            "a " + s.name() + " " + Math.round(s.distancia()) + " blocks " + s.direction() + (s.acercandose() ? ", coming closer" : "")
                                    + (s.visto() ? "" : " (heard, not seen)"), BlockPos.ofFloored(s.pos()), s.distancia()));
                }
                break;
            }
        }
        if (luz.monstruos()) {
            peligros.add(new Peligro(P_OSCURIDAD, "está tan oscuro que pueden salir monstruos", "so dark that monsters can spawn", feet, 0));
        }
        peligros.sort(Comparator.comparingDouble(Peligro::distancia));

        List<Mineral> minerales = ores(memory, origin, facing, now);
        return new Ahora(now, lugar, depth, luz, seres, estructuras, peligros, minerales);
    }

    private static Luz luz(ServerWorld world, LivingEntity viewer, VisualMemory memory, BlockPos feet, Lugar lugar, long now) {
        int block = world.getLightLevel(LightType.BLOCK, feet);
        int sky = world.getLightLevel(LightType.SKY, feet);
        int level = world.getLightLevel(feet);
        double dark = -1;
        Retina.Pixel[] frame = memory.frame();
        if (frame != null && now - memory.frameTick() <= 3L * EVERY) {
            int n = 0;
            int d = 0;
            for (Retina.Pixel p : frame) {
                if (p != null && !p.sky()) {
                    n++;
                    if (p.dark()) {
                        d++;
                    }
                }
            }
            dark = n == 0 ? 0 : d / (double) n;
        }
        if (dark < 0) {
            Pantalla screen = Pantalla.get(viewer);
            dark = screen == null ? 0 : screen.darkShare();
        }
        boolean night = Retina.nightVision(viewer);
        boolean oscuro = !night && level < DARK;
        boolean monsters = block == 0 && level <= 7;
        boolean torch = block <= 7 && level <= 7;
        return new Luz(block, sky, level, dark, oscuro, monsters, torch);
    }

    private static List<Ser> beings(ServerWorld world, LivingEntity viewer, VisualMemory memory, Estado estado, Vec3d origin, float facing, long now) {
        Map<Integer, Ser> found = new LinkedHashMap<>();
        Map<Integer, Double> distances = new HashMap<>();
        for (VisualMemory.SeenEntity seen : memory.entities(now, BEINGS)) {
            if (seen.id == viewer.getId() || seen.pos == null || seen.type == EntityType.ITEM) {
                continue;
            }
            Entity e = world.getEntityById(seen.id);
            Clase clase = clase(e, seen.type);
            if (clase == null) {
                continue;
            }
            double d = origin.distanceTo(seen.pos);
            Double before = estado.distance.get(seen.id);
            boolean closer = before != null && before - d >= APPROACH;
            if (!closer && seen.velocity != null && now - seen.lastSeen <= SEEN) {
                Vec3d toMe = origin.subtract(seen.pos);
                double len = toMe.length();
                closer = len > 0.01 && seen.velocity.dotProduct(toMe) / len > 0.03;
            }
            distances.put(seen.id, d);
            String[] names = names(e, seen.type);
            BlockPos at = BlockPos.ofFloored(seen.pos);
            found.put(seen.id, new Ser(seen.id, names[0], names[1], clase, seen.pos, d, Escena.direction(origin, facing, at, true),
                    Escena.direction(origin, facing, at, false), now - seen.lastSeen <= SEEN, false, closer, now - seen.lastSeen));
        }

        // the ears: footsteps and noises, through walls, roughly placed
        Map<Integer, Vec3d> heardAt = new HashMap<>();
        Map<Integer, Long> heardTicks = new HashMap<>();
        Box area = viewer.getBoundingBox().expand(HEAR);
        for (Entity e : world.getOtherEntities(viewer, area, e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator())) {
            Clase clase = clase(e, e.getType());
            if (clase == null) {
                continue;
            }
            double d = origin.distanceTo(e.getPos());
            if (d > HEAR) {
                continue;
            }
            Vec3d was = estado.heardAt.get(e.getId());
            heardAt.put(e.getId(), e.getPos());
            distances.putIfAbsent(e.getId(), d);
            boolean moved = was != null && was.distanceTo(e.getPos()) >= MOVED && !e.isSneaking();
            boolean noisy = !(e instanceof PlayerEntity) && !(e instanceof CreeperEntity) && d <= HEAR_STILL;
            boolean hiss = e instanceof CreeperEntity creeper && creeper.getFuseSpeed() > 0;
            Long lastHeard = estado.heardTick.get(e.getId());
            boolean still = lastHeard != null && now - lastHeard <= SEEN;
            if (!moved && !noisy && !hiss && !still) {
                continue;
            }
            if (moved || noisy || hiss) {
                heardTicks.put(e.getId(), now);
            } else {
                heardTicks.put(e.getId(), lastHeard);
            }
            Ser seen = found.get(e.getId());
            Double before = estado.distance.get(e.getId());
            boolean closer = before != null && before - d >= APPROACH;
            if (seen != null) {
                found.put(e.getId(), new Ser(seen.id(), seen.nombre(), seen.name(), seen.clase(), seen.pos(), seen.distancia(), seen.direccion(),
                        seen.direction(), seen.visto(), true, seen.acercandose() || closer, seen.hace()));
                continue;
            }
            // a sound tells the side and the distance roughly, not the exact spot
            Vec3d rough = new Vec3d(Math.round(e.getX() / 2.0) * 2.0, Math.round(e.getY() / 2.0) * 2.0, Math.round(e.getZ() / 2.0) * 2.0);
            BlockPos at = BlockPos.ofFloored(rough);
            String[] names = names(e, e.getType());
            found.put(e.getId(), new Ser(e.getId(), names[0], names[1], clase, rough, Math.round(d), Escena.direction(origin, facing, at, true),
                    Escena.direction(origin, facing, at, false), false, true, closer, 0));
        }
        estado.heardAt = heardAt;
        estado.heardTick = heardTicks;
        estado.distance = distances;
        List<Ser> list = new ArrayList<>(found.values());
        list.sort(Comparator.comparingDouble(s -> s.distancia() * (s.hostil() ? 0.5 : 1.0) + s.hace() / 40.0));
        return list;
    }

    @Nullable
    private static Clase clase(@Nullable Entity e, EntityType<?> type) {
        if (e instanceof PlayerEntity || type == EntityType.PLAYER) {
            return Clase.JUGADOR;
        }
        if (e instanceof VillagerEntityMCA || e instanceof MerchantEntity || type == EntityType.VILLAGER || type == EntityType.WANDERING_TRADER) {
            return Clase.ALDEANO;
        }
        if (e instanceof Monster || type.getSpawnGroup() == SpawnGroup.MONSTER) {
            return Clase.HOSTIL;
        }
        if (type.getSpawnGroup() == SpawnGroup.MISC) {
            return null; // stands, golems, carts, boats, arrows
        }
        return Clase.ANIMAL;
    }

    private static String[] names(@Nullable Entity e, EntityType<?> type) {
        if (e instanceof PlayerEntity || e instanceof VillagerEntityMCA) {
            String n = e.getName().getString();
            return new String[]{n, n};
        }
        if (e instanceof MerchantEntity || type == EntityType.VILLAGER) {
            return type == EntityType.WANDERING_TRADER ? new String[]{"vendedor ambulante", "wandering trader"} : new String[]{"aldeano", "villager"};
        }
        return new String[]{Escena.nombreSer(type, true), Escena.nombreSer(type, false)};
    }

    private static List<Mineral> ores(VisualMemory memory, Vec3d origin, float facing, long now) {
        Map<String, List<VisualMemory.SeenBlock>> kinds = new LinkedHashMap<>();
        for (VisualMemory.SeenBlock s : memory.ores()) {
            if (s.state != null && now - s.lastSeen <= MEMORY && origin.squaredDistanceTo(Vec3d.ofCenter(s.pos)) <= 16.0 * 16.0) {
                kinds.computeIfAbsent(Escena.oreKey(s.state), k -> new ArrayList<>()).add(s);
            }
        }
        List<Mineral> out = new ArrayList<>();
        for (Map.Entry<String, List<VisualMemory.SeenBlock>> kind : kinds.entrySet()) {
            VisualMemory.SeenBlock best = null;
            double bd = Double.MAX_VALUE;
            for (VisualMemory.SeenBlock s : kind.getValue()) {
                double d = origin.distanceTo(Vec3d.ofCenter(s.pos));
                if (d < bd) {
                    bd = d;
                    best = s;
                }
            }
            String[] name = Escena.oreName(kind.getKey());
            out.add(new Mineral(name[0], name[1], best.pos, bd, Escena.direction(origin, facing, best.pos, true), kind.getValue().size()));
        }
        out.sort(Comparator.comparingDouble(Mineral::distancia));
        return out;
    }

    // ---------------------------------------------------------------- staircases

    /**
     * Staircases among the floors the eye saw: feet cells (over a seen floor, the body's two cells free) chained one up
     * and one over at a time, each lower step with room for the jump; at least {@value #STAIR_STEPS} cells. A chain
     * under the open sky the whole way is only a hillside, unless it is its own.
     */
    private static List<Estructura> stairs(ServerWorld world, LivingEntity viewer, List<VisualMemory.SeenBlock> floors, Vec3d origin, float facing) {
        Set<Long> cells = new HashSet<>();
        for (VisualMemory.SeenBlock s : floors) {
            BlockPos f = s.pos.up();
            if (Mining.passable(world, f) && Mining.passable(world, f.up())) {
                cells.add(f.asLong());
            }
        }
        if (cells.size() < STAIR_STEPS) {
            return List.of();
        }
        Map<Long, Integer> memo = new HashMap<>();
        Set<Long> hasLower = new HashSet<>();
        for (long key : cells) {
            BlockPos f = BlockPos.fromLong(key);
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos next = f.offset(d).up();
                if (cells.contains(next.asLong()) && Mining.passable(world, f.up(2))) {
                    hasLower.add(next.asLong());
                }
            }
        }
        Recuerdos recuerdos = null;
        try {
            if (viewer instanceof VillagerEntityMCA) {
                recuerdos = Recuerdos.of(world, viewer);
            }
        } catch (RuntimeException ignored) {
            // no memories: none of them is its own
        }
        List<List<BlockPos>> chains = new ArrayList<>();
        for (long key : cells) {
            if (hasLower.contains(key)) {
                continue;
            }
            List<BlockPos> chain = new ArrayList<>();
            BlockPos f = BlockPos.fromLong(key);
            chain.add(f);
            while (chain.size() < 64) {
                BlockPos best = null;
                int bestLen = 0;
                if (Mining.passable(world, f.up(2))) {
                    for (Direction d : Direction.Type.HORIZONTAL) {
                        BlockPos next = f.offset(d).up();
                        if (cells.contains(next.asLong())) {
                            int len = length(world, cells, next, memo, 0);
                            if (len > bestLen) {
                                bestLen = len;
                                best = next;
                            }
                        }
                    }
                }
                if (best == null) {
                    break;
                }
                chain.add(best);
                f = best;
            }
            if (chain.size() >= STAIR_STEPS) {
                chains.add(chain);
            }
        }
        // own ones first, then the longest; one staircase two wide is one
        List<Object[]> scored = new ArrayList<>();
        for (List<BlockPos> chain : chains) {
            int walked = 0;
            int enclosed = 0;
            for (BlockPos c : chain) {
                if (recuerdos != null && recuerdos.recuerda(c)) {
                    walked++;
                }
                if (world.getLightLevel(LightType.SKY, c.up()) < 15) {
                    enclosed++;
                }
            }
            boolean own = walked >= 2 || (recuerdos != null && marked(recuerdos, chain));
            if (!own && enclosed * 2 < chain.size()) {
                continue; // a hillside under the sky
            }
            scored.add(new Object[]{chain, own});
        }
        scored.sort((a, b) -> {
            boolean oa = (Boolean) a[1], ob = (Boolean) b[1];
            if (oa != ob) {
                return oa ? -1 : 1;
            }
            return Integer.compare(((List<?>) b[0]).size(), ((List<?>) a[0]).size());
        });
        List<Estructura> out = new ArrayList<>();
        List<BlockPos> taken = new ArrayList<>();
        for (Object[] s : scored) {
            @SuppressWarnings("unchecked")
            List<BlockPos> chain = (List<BlockPos>) s[0];
            boolean own = (Boolean) s[1];
            boolean overlaps = false;
            for (BlockPos c : chain) {
                for (BlockPos t : taken) {
                    if (Math.abs(c.getX() - t.getX()) <= 1 && Math.abs(c.getY() - t.getY()) <= 1 && Math.abs(c.getZ() - t.getZ()) <= 1) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) {
                    break;
                }
            }
            if (overlaps) {
                continue;
            }
            taken.addAll(chain);
            int nearestIndex = 0;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < chain.size(); i++) {
                double d = origin.squaredDistanceTo(Vec3d.ofBottomCenter(chain.get(i)));
                if (d < bd) {
                    bd = d;
                    nearestIndex = i;
                }
            }
            boolean up = nearestIndex * 2 < chain.size();
            out.add(structure(ESCALERA, own ? "mi escalera" : "escalera", own ? "my staircase" : "staircase", chain.get(nearestIndex), origin, facing,
                    chain.size(), own, up, List.copyOf(chain)));
            if (out.size() >= 3) {
                break;
            }
        }
        return out;
    }

    private static int length(ServerWorld world, Set<Long> cells, BlockPos f, Map<Long, Integer> memo, int depth) {
        Integer had = memo.get(f.asLong());
        if (had != null) {
            return had;
        }
        int best = 1;
        if (depth < 64 && Mining.passable(world, f.up(2))) {
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos next = f.offset(d).up();
                if (cells.contains(next.asLong())) {
                    best = Math.max(best, 1 + length(world, cells, next, memo, depth + 1));
                }
            }
        }
        memo.put(f.asLong(), best);
        return best;
    }

    private static boolean marked(Recuerdos recuerdos, List<BlockPos> chain) {
        List<Recuerdos.Marca> marcas = recuerdos.marcas();
        for (int i = marcas.size() - 1; i >= 0 && i >= marcas.size() - 256; i--) {
            Recuerdos.Marca m = marcas.get(i);
            if (!Recuerdos.ESCALERA.equals(m.tipo())) {
                continue;
            }
            for (BlockPos c : chain) {
                if (c.getManhattanDistance(m.pos()) <= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the space it stands in

    /** The tunnel or the cave it is in, felt with the body and judged from how far its last frame reached. */
    private static void here(ServerWorld world, LivingEntity viewer, VisualMemory memory, Lugar lugar, int depth, Vec3d origin, float facing,
                             BlockPos feet, List<Estructura> out) {
        if (lugar != Lugar.BAJO_TIERRA) {
            return;
        }
        Direction ahead = Direction.fromRotation(facing);
        Map<Direction, Tacto.Toque> sides = Tacto.lados(world, viewer);
        Tacto.Toque roof = Tacto.techo(world, viewer, 2);
        boolean walls = sides.containsKey(ahead.rotateYClockwise()) && sides.containsKey(ahead.rotateYCounterclockwise())
                || sides.containsKey(ahead) && sides.containsKey(ahead.getOpposite());
        if (walls && roof != null) {
            out.add(structure(TUNEL, "túnel", "tunnel", feet, origin, facing, 1, false, false, List.of(feet)));
            return;
        }
        // a big dark space: much of the last frame reached far
        Retina.Pixel[] frame = memory.frame();
        if (frame != null && world.getTime() - memory.frameTick() <= 3L * EVERY) {
            int n = 0;
            int far = 0;
            for (Retina.Pixel p : frame) {
                if (p != null && p.hit() != null) {
                    n++;
                    if (p.distance() > 10) {
                        far++;
                    }
                }
            }
            if (n > 0 && far * 3 >= n) {
                out.add(structure(CUEVA, "cueva", "cave", feet, origin, facing, far, false, false, List.of(feet)));
            }
        }
    }

    private static Estructura structure(String tipo, String nombre, String name, BlockPos at, Vec3d origin, float facing, int largo, boolean own,
                                        boolean up, List<BlockPos> cells) {
        return new Estructura(tipo, nombre, name, at.toImmutable(), origin.distanceTo(Vec3d.ofBottomCenter(at)), Escena.direction(origin, facing, at, true),
                Escena.direction(origin, facing, at, false), largo, own, up, cells);
    }

    private static BlockPos nearest(Vec3d origin, List<BlockPos> points) {
        BlockPos best = points.get(0);
        double bd = Double.MAX_VALUE;
        for (BlockPos p : points) {
            double d = origin.squaredDistanceTo(Vec3d.ofCenter(p));
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- reading it

    public long tick() {
        return tick;
    }

    public Lugar lugar() {
        return lugar;
    }

    public int profundidad() {
        return profundidad;
    }

    public Luz luz() {
        return luz;
    }

    /** Beings, the most pressing first (hostile and near before the rest). */
    public List<Ser> seres() {
        return seres;
    }

    /** Structures, its own staircase first, then by nearness. */
    public List<Estructura> estructuras() {
        return estructuras;
    }

    /** Dangers, nearest first. */
    public List<Peligro> peligros() {
        return peligros;
    }

    /** Ores it saw lately within 16 blocks, one per kind, nearest first. */
    public List<Mineral> minerales() {
        return minerales;
    }

    /** The nearest hostile it sees or hears now, or null. */
    @Nullable
    public Ser amenaza() {
        Ser best = null;
        for (Ser s : seres) {
            if (s.hostil() && (s.oido() || s.visto()) && (best == null || s.distancia() < best.distancia())) {
                best = s;
            }
        }
        return best;
    }

    /** The nearest hostile it hears but does not see, or null. */
    @Nullable
    public Ser oidoSinVer() {
        Ser best = null;
        for (Ser s : seres) {
            if (s.hostil() && s.oido() && !s.visto() && (best == null || s.distancia() < best.distancia())) {
                best = s;
            }
        }
        return best;
    }

    /** The staircase it makes out: its own if it sees one, else the nearest; null if none. */
    @Nullable
    public Estructura escalera() {
        for (Estructura e : estructuras) {
            if (ESCALERA.equals(e.tipo())) {
                return e;
            }
        }
        return null;
    }

    /** Its own staircase in view, or null. */
    @Nullable
    public Estructura escaleraPropia() {
        Estructura e = escalera();
        return e != null && e.propia() ? e : null;
    }

    @Nullable
    public Estructura estructura(String tipo) {
        for (Estructura e : estructuras) {
            if (e.tipo().equals(tipo)) {
                return e;
            }
        }
        return null;
    }

    public boolean peligro(String tipo) {
        for (Peligro p : peligros) {
            if (p.tipo().equals(tipo)) {
                return true;
            }
        }
        return false;
    }

    /** One line for logs and the eye report: light, beings, structures, dangers, ores. */
    public String resumen(boolean es) {
        List<String> parts = new ArrayList<>();
        parts.add(luz.palabra(es) + String.format(Locale.ROOT, es ? " (luz %d, bloque %d, cielo %d, %d%% de la vista a oscuras)" : " (light %d, block %d, sky %d, %d%% of the view too dark)",
                luz.nivel(), luz.bloque(), luz.cielo(), Math.round(luz.oscuroVista() * 100)));
        for (Ser s : seres.subList(0, Math.min(5, seres.size()))) {
            parts.add(ser(s, es));
        }
        for (Estructura e : estructuras.subList(0, Math.min(4, estructuras.size()))) {
            parts.add(estructura(e, es));
        }
        for (Peligro p : peligros) {
            parts.add((es ? "peligro: " : "danger: ") + (es ? p.es() : p.en()));
        }
        for (Mineral m : minerales.subList(0, Math.min(3, minerales.size()))) {
            parts.add(es ? m.nombre() + " a " + Math.round(m.distancia()) + " bloques " + m.direccion()
                    : m.name() + " " + Math.round(m.distancia()) + " blocks away");
        }
        return String.join("; ", parts);
    }

    private static String ser(Ser s, boolean es) {
        String how = s.visto() ? (s.oido() ? (es ? "lo veo y lo oigo" : "seen and heard") : (es ? "lo veo" : "seen"))
                : s.oido() ? (es ? "lo oigo, no lo veo" : "heard, not seen") : (es ? "visto hace " + s.hace() / 20 + " s" : "seen " + s.hace() / 20 + " s ago");
        return (es ? s.nombre() + " a " + Math.round(s.distancia()) + " bloques " + s.direccion() : s.name() + " " + Math.round(s.distancia()) + " blocks " + s.direction())
                + (s.acercandose() ? (es ? ", acercándose" : ", coming closer") : "") + " (" + how + ")";
    }

    private static String estructura(Estructura e, boolean es) {
        String extra = "";
        if (ESCALERA.equals(e.tipo())) {
            extra = es ? " (" + e.largo() + " escalones, " + (e.sube() ? "sube" : "baja") + " desde aquí)"
                    : " (" + e.largo() + " steps, leading " + (e.sube() ? "up" : "down") + " from its near end)";
        }
        long d = Math.round(e.distancia());
        String where = d <= 1 ? (es ? " aquí" : " right here") : (es ? " a " + d + " bloques " + e.direccion() : " " + d + " blocks " + e.direction());
        return (es ? e.nombre() : e.name()) + extra + where;
    }

    /**
     * What is worth saying about the moment in the villager's own voice, for the chat: the light when it matters, what
     * it hears and does not see, the dangers, its own staircase. Empty when nothing stands out.
     */
    public String decir(boolean es) {
        List<String> out = new ArrayList<>();
        if (luz.oscuro()) {
            out.add(capital(luz.palabra(es)) + (luz.antorcha() ? (es ? "; aquí haría falta una antorcha" : "; a torch would help here") : "") + ".");
        }
        for (Ser s : seres) {
            if (s.oido() && !s.visto()) {
                out.add(es ? "Oigo " + articulo(s) + " " + s.direccion() + (s.acercandose() ? ", y se está acercando" : "") + "."
                        : "I hear " + (s.clase() == Clase.JUGADOR || s.clase() == Clase.ALDEANO ? s.name() : "a " + s.name()) + " " + s.direction()
                        + (s.acercandose() ? ", coming closer" : "") + ".");
                break;
            }
        }
        for (Peligro p : peligros) {
            if (!P_OSCURIDAD.equals(p.tipo()) && !(P_MONSTRUO.equals(p.tipo()) && out.size() > 1)) {
                out.add((es ? "Cuidado: " : "Careful: ") + (es ? p.es() : p.en()) + ".");
                break;
            }
        }
        Estructura stair = escaleraPropia();
        if (stair != null) {
            long d = Math.round(stair.distancia());
            out.add(es ? "Mi escalera está " + (d <= 1 ? "aquí mismo" : "a " + d + " bloques " + stair.direccion()) + "."
                    : "My staircase is " + (d <= 1 ? "right here" : d + " blocks " + stair.direction()) + ".");
        }
        return String.join(" ", out);
    }

    private static String articulo(Ser s) {
        if (s.clase() == Clase.JUGADOR || s.clase() == Clase.ALDEANO && !"aldeano".equals(s.nombre())) {
            return "a " + s.nombre();
        }
        String n = s.nombre();
        boolean fem = n.endsWith("a") && !n.endsWith("ma") || n.startsWith("araña") || n.equals("bruja");
        return (fem ? "una " : "un ") + n;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The same for a language model, plain English, one fact per line. */
    public String forModel() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Light: %d/15 where it stands (block light %d, sky light %d), %s; %d%% of its view is too dark to make out%s%s.%n",
                luz.nivel(), luz.bloque(), luz.cielo(), luz.palabra(false), Math.round(luz.oscuroVista() * 100),
                luz.monstruos() ? "; monsters can spawn here" : "", luz.antorcha() ? "; a player would place a torch here" : ""));
        List<String> heard = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Ser s : seres) {
            if (s.visto() || s.hace() > 0) {
                seen.add(ser(s, false));
            } else if (s.oido()) {
                heard.add(ser(s, false));
            }
        }
        if (!seen.isEmpty()) {
            sb.append("Beings it saw: ").append(String.join("; ", seen.subList(0, Math.min(6, seen.size())))).append(".\n");
        }
        if (!heard.isEmpty()) {
            sb.append("Heard but not seen (it would have to turn to look): ").append(String.join("; ", heard.subList(0, Math.min(4, heard.size())))).append(".\n");
        }
        if (!estructuras.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Estructura e : estructuras.subList(0, Math.min(4, estructuras.size()))) {
                parts.add(estructura(e, false));
            }
            sb.append("Structures it recognises: ").append(String.join("; ", parts)).append(".\n");
        }
        if (!peligros.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Peligro p : peligros) {
                parts.add(p.en());
            }
            sb.append("Dangers: ").append(String.join("; ", parts)).append(".\n");
        }
        return sb.toString().trim();
    }
}
