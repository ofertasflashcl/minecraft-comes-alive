package net.mca.entity.ai.mente;

import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.body.CombatMind;
import net.mca.entity.ai.body.Hands;
import net.mca.entity.ai.body.Motor;
import net.mca.entity.ai.body.Postura;
import net.mca.entity.ai.brain.tasks.FollowTask;
import net.mca.entity.ai.brain.tasks.chore.ProspectingTask;
import net.mca.entity.ai.sense.Entorno;
import net.mca.entity.ai.sense.Mirada;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The will of a connected villager: what it has made up its mind to do, the way a player decides between their own
 * plans and a friend's call. {@link ProspectingTask} asks it first on every tick; while the will holds the body the
 * task's own life (gear, wood, the mine, food, home, the chest) waits where it was, and picks up again from the same
 * place when the will lets go, its mine trail and its plans kept.
 * <ul>
 * <li>{@link Modo#LIBRE}: its own life, the task drives.</li>
 * <li>{@link Modo#SIGUIENDO}: at a player's side like a companion ({@link FollowTask#guard}: it follows, digs or
 * builds its way to them, fights what threatens them), eyes working, eating from its bag.</li>
 * <li>{@link Modo#ALERTA}: "¡cuidado!", "¡para!": it stops dead, turns to the player and asks what is going on; in
 * the rock, or with a monster seen close by lately, it crouches and keeps its voice down. It defends itself from
 * whatever comes within reach and goes back to what it was doing when told it was nothing, or after
 * {@value #ALERT_SILENCE} ticks of silence.</li>
 * <li>{@link Modo#ESPERANDO}: "quédate aquí": it waits on the spot, looks around and at the player, defends itself.</li>
 * </ul>
 * The mode is kept in the villager (a relog keeps it), except the alert, which falls back to the mode under it. The
 * chat changes it ({@link net.mca.entity.ai.chatAI.TriggerCommandInfos}); every change is logged. What it thinks
 * ({@link #pensando()}) is told to the language models and shown by {@code /mca-ojo}. Server thread only.
 */
public final class Voluntad {
    public enum Modo {
        LIBRE("libre", "free, living your own life (your own plans: gear, wood, mining, food, home)"),
        SIGUIENDO("siguiendo", "following $player as a companion and guarding them"),
        ALERTA("alerta", "on alert: you stopped dead and wait to hear what is going on"),
        ESPERANDO("esperando", "waiting right here as you were asked");

        private final String palabra;
        private final String english;

        Modo(String palabra, String english) {
            this.palabra = palabra;
            this.english = english;
        }

        public String palabra() {
            return palabra;
        }

        public String english() {
            return english;
        }
    }

    /** Ticks of silence after which an alert is over: it carries on, and says so. */
    public static final int ALERT_SILENCE = 800;
    /** Ticks without the player it follows or waits for before it goes back to its own life. */
    public static final int LOST_PLAYER = 6000;
    /** A villager whose will ran this recently counts as connected (the checks run the task without a job). */
    public static final int CONNECTED_GRACE = 40;
    /** How close a monster must come before an alert or a wait turns into a fight. */
    public static final double DEFEND_ALERT = 3.5;
    public static final double DEFEND_WAIT = 6.0;
    /** A monster seen this close lately is a reason to crouch on an alert. */
    public static final double MONSTER_NEAR = 16.0;
    public static final int MONSTER_RECENT = 400;
    private static final String CROUCH = "cuidado";

    private static final Map<VillagerEntityMCA, Voluntad> ALL = new WeakHashMap<>();

    private Modo modo = Modo.LIBRE;
    /** The mode an alert interrupted, and its player and spot: what it goes back to. */
    private Modo debajo = Modo.LIBRE;
    @Nullable
    private UUID debajoQuien;
    @Nullable
    private BlockPos debajoEspera;
    @Nullable
    private UUID quien;
    private WeakReference<PlayerEntity> quienRef = new WeakReference<>(null);
    private String quienNombre = "";
    @Nullable
    private BlockPos espera;
    private long desde;
    /** The last time its player said anything to it. */
    private long oido;
    private long ultimoTick = Long.MIN_VALUE / 2;
    /** Since when the player it is with has been nowhere to be found, or -1. */
    private long sinJugador = -1;
    /** The will held the body on its last tick (the task let go of it). */
    private boolean tiene;
    /** It asked {@link Postura} for the crouch, for caution. */
    private boolean agachado;
    /** Why it crouched, in its words. */
    private String porQue = "";
    private boolean es = true;
    private boolean esConocido;
    private String pensando = "";
    @Nullable
    private LivingEntity enemigo;
    private long nextSweep;
    private long lookUntil;
    private boolean loggedError;
    private final FollowTask follow = new FollowTask();
    private final Mirada mirada = new Mirada();
    final Iniciativa iniciativa = new Iniciativa();
    private final Atasco atasco = new Atasco();

    private Voluntad() {
    }

    /** The will of this villager, as saved in it. */
    public static Voluntad of(VillagerEntityMCA villager) {
        return ALL.computeIfAbsent(villager, v -> {
            Voluntad w = new Voluntad();
            w.load(v);
            return w;
        });
    }

    /** Connected: its job is to live on its own ({@link Chore#PROSPECT}), or its will ran a moment ago. */
    public static boolean conectado(VillagerEntityMCA villager) {
        if (villager.getVillagerBrain().getCurrentJob() == Chore.PROSPECT) {
            return true;
        }
        Voluntad w = ALL.get(villager);
        return w != null && villager.getWorld().getTime() - w.ultimoTick <= CONNECTED_GRACE;
    }

    /** The connection ended (the job was dropped or changed): back to nothing, standing up, nothing saved. */
    public static void olvidar(VillagerEntityMCA villager) {
        Voluntad w = ALL.remove(villager);
        if (w != null) {
            w.leave(villager, w.modo, Modo.LIBRE);
        }
        if (!villager.getVillagerBrain().getWill().isEmpty()) {
            villager.getVillagerBrain().setWill("");
        }
    }

    // ------------------------------------------------------------------ what the chat may ask

    public Modo modo() {
        return modo;
    }

    /** The mode it goes back to after an alert (its own mode otherwise). */
    public Modo debajo() {
        return modo == Modo.ALERTA ? debajo : modo;
    }

    /** Its player right now (following, waiting for, alerted by), or null. */
    @Nullable
    public UUID quien() {
        return quien;
    }

    public boolean agachado() {
        return agachado;
    }

    /** Its sense of getting nowhere ({@link Atasco}): the task asks it every tick of its own life. */
    public Atasco atasco() {
        return atasco;
    }

    /**
     * It tells the player it is with (or the one who connected it) what it is doing and why, when it changes its plans
     * for a reason: "No me acuerdo de otra salida, cavo una escalera nueva." At most one such line every few seconds;
     * every one is also a memory ({@link Recuerdos}). Server thread; never throws.
     */
    public static void contar(VillagerEntityMCA villager, String es, String en) {
        try {
            if (!(villager.getWorld() instanceof ServerWorld world)) {
                return;
            }
            Voluntad w = of(villager);
            String line = w.spanish() ? es : en;
            Recuerdos.anotar(villager, "plan", es, villager.getBlockPos());
            w.iniciativa.contar(world, villager, w, line, world.getTime());
        } catch (RuntimeException e) {
            MCA.LOGGER.warn("[Tropeles] {} no pudo contar lo que hace: {}", villager.getName().getString(), e.toString());
        }
    }

    /** Its current mode, goal and why, in a short line in Spanish. */
    public String pensando() {
        return pensando;
    }

    /** For /mca-ojo and the log: "siguiendo — sigo a Benja (a 3 bloques)". */
    public static String resumen(VillagerEntityMCA villager) {
        if (!conectado(villager)) {
            return "no está conectado";
        }
        Voluntad w = of(villager);
        return w.modo.palabra() + (w.modo == Modo.ALERTA ? " (luego " + w.debajo.palabra() + ")" : "") + " — " + w.pensando;
    }

    /** For a language model: its mode and what it is thinking. */
    public static String forModel(VillagerEntityMCA villager, String player) {
        Voluntad w = of(villager);
        String mode = w.modo.english().replace("$player", w.quienNombre.isEmpty() ? player : w.quienNombre);
        if (w.modo == Modo.ALERTA) {
            mode += "; afterwards you go back to " + (w.debajo == Modo.LIBRE ? "your own life" : w.debajo.english().replace("$player", w.quienNombre));
        }
        return mode + ". Your thoughts right now (in Spanish): " + w.pensando;
    }

    /** Someone said something to it (server thread): an alert is still being talked about, its language noted. */
    public static void oyo(VillagerEntityMCA villager, PlayerEntity player, boolean spanish) {
        Voluntad w = of(villager);
        w.es = spanish;
        w.esConocido = true;
        if (w.quien == null || w.quien.equals(player.getUuid())) {
            w.oido = villager.getWorld().getTime();
        }
    }

    /** The language it last heard, or the game's when it heard none. */
    public boolean spanish() {
        return esConocido ? es : !(MCA.language != null && MCA.language.startsWith("en"));
    }

    // ------------------------------------------------------------------ orders from the chat, each with its account

    /** "Sígueme": at the player's side, still connected; its job and its mine wait. */
    public static String seguir(VillagerEntityMCA villager, PlayerEntity player, boolean spanish) {
        Voluntad w = of(villager);
        oyo(villager, player, spanish);
        w.change(villager, Modo.SIGUIENDO, player, null, player.getName().getString() + " me pidió que lo siga");
        return "You are following " + player.getName().getString() + " now, at their side, fighting whatever threatens them. You are still "
                + "living your own life: your mine, your chest and your plans wait until you are told to go back to them.";
    }

    /** "Quédate aquí", "espérame": it waits on this spot. */
    public static String esperar(VillagerEntityMCA villager, PlayerEntity player, boolean spanish) {
        Voluntad w = of(villager);
        oyo(villager, player, spanish);
        w.change(villager, Modo.ESPERANDO, player, villager.getBlockPos().toImmutable(), player.getName().getString() + " me pidió que lo espere aquí");
        return "You stay right here and wait for " + player.getName().getString() + ", looking around and keeping yourself safe, until they call you "
                + "or send you back to your own things.";
    }

    /** "Haz lo tuyo": back to its own life, where it left it. */
    public static String libre(VillagerEntityMCA villager, @Nullable PlayerEntity player, boolean spanish) {
        Voluntad w = of(villager);
        if (player != null) {
            oyo(villager, player, spanish);
        }
        w.change(villager, Modo.LIBRE, null, null, player == null ? "vuelvo a lo mío" : player.getName().getString() + " me dijo que vuelva a lo mío");
        return "You went back to your own life, right where you left it: your own plans, your gear, your mine.";
    }

    /**
     * "¡Cuidado!", "¡para!": it stops at once, turns to the player, crouches when it is in the rock or saw a monster
     * close by lately, and waits for an explanation.
     */
    public static String alerta(VillagerEntityMCA villager, PlayerEntity player, boolean spanish) {
        Voluntad w = of(villager);
        oyo(villager, player, spanish);
        String name = player.getName().getString();
        w.change(villager, Modo.ALERTA, player, null, name + " me avisó que tenga cuidado");
        w.oido = villager.getWorld().getTime();
        Motor.standStill(villager);
        villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        w.face(villager, player);
        w.judgeCaution(villager);
        w.think(villager);
        String crouch = w.agachado ? ", crouched and keeping your voice down because " + (w.porQue.startsWith("bajo") ? "you are underground" : "you saw a monster nearby") : "";
        return "You stopped dead at once and turned to face " + name + crouch + ". You stay still and ask " + name
                + " what is happening, in a short question.";
    }

    /** "Tranquilo, sigue", "falsa alarma": the alert is over, back to the mode it interrupted. Null when not on alert. */
    @Nullable
    public static String seguirComoAntes(VillagerEntityMCA villager, @Nullable PlayerEntity player, boolean spanish) {
        Voluntad w = of(villager);
        if (w.modo != Modo.ALERTA) {
            return null;
        }
        if (player != null) {
            oyo(villager, player, spanish);
        }
        Modo back = w.resume(villager, player == null ? "pasó la alerta" : player.getName().getString() + " me dijo que no era nada");
        return "It was nothing: you stand up and carry on " + switch (back) {
            case SIGUIENDO -> "following " + w.quienNombre;
            case ESPERANDO -> "waiting where you were";
            default -> "with your own plans";
        } + ".";
    }

    /** The question it asks when stopped by an alert, in its own words: quiet when it crouches. */
    public static String pregunta(VillagerEntityMCA villager, boolean spanish) {
        Voluntad w = ALL.get(villager);
        boolean quiet = w != null && w.agachado;
        String[] lines = spanish
                ? quiet ? new String[]{"Shh... ¿qué pasa?", "¿Qué viste?", "¿Hay algo por ahí?", "¿Qué fue eso?"}
                : new String[]{"¿Qué pasa?", "¿Qué viste?", "¿Hay algo?", "¿Qué ocurre?"}
                : quiet ? new String[]{"Shh... what is it?", "What did you see?", "Is something there?"}
                : new String[]{"What's up?", "What did you see?", "Is something there?", "What is it?"};
        return lines[villager.getRandom().nextInt(lines.length)];
    }

    /** What it says on carrying on after an alert. */
    public static String sigoLinea(VillagerEntityMCA villager, boolean spanish) {
        Modo m = of(villager).modo;
        return switch (m) {
            case SIGUIENDO -> spanish ? "Vale, sigo contigo." : "Okay, I'm right behind you.";
            case ESPERANDO -> spanish ? "Bueno, sigo esperando aquí." : "Alright, I'll keep waiting here.";
            default -> spanish ? "Bueno, sigo con lo mío." : "Alright, back to what I was doing.";
        };
    }

    // ------------------------------------------------------------------ the body

    /**
     * One tick, called by {@link ProspectingTask} before anything else, never in the middle of one of its jumps. True
     * when the will has the body this tick (the task does only its metabolism then); false in its own life. The first
     * tick it takes the body the task drops its half-made step ({@link ProspectingTask#soltar}); the first tick it
     * gives it back the task plans again from where it stands ({@link ProspectingTask#retomar}).
     */
    public boolean manda(ServerWorld world, VillagerEntityMCA villager, @Nullable ProspectingTask task) {
        long now = world.getTime();
        ultimoTick = now;
        boolean drive = modo != Modo.LIBRE;
        if (drive && !tiene) {
            tiene = true;
            if (task != null) {
                task.soltar(world, villager);
            }
        } else if (!drive && tiene) {
            tiene = false;
            if (task != null) {
                task.retomar(world, villager);
            }
        }
        try {
            switch (modo) {
                case SIGUIENDO -> {
                    followTick(world, villager);
                    followStuck(world, villager);
                }
                case ALERTA -> alertTick(world, villager);
                case ESPERANDO -> waitTick(world, villager);
                default -> {
                }
            }
            if ((villager.age % 20) == 0) {
                think(villager);
            }
            iniciativa.tick(world, villager, this, task);
        } catch (RuntimeException e) {
            // the will must never stop the task's own life
            if (!loggedError) {
                loggedError = true;
                MCA.LOGGER.error("[Tropeles] la voluntad de {} falló", villager.getName().getString(), e);
            }
        }
        return drive;
    }

    private void followTick(ServerWorld world, VillagerEntityMCA villager) {
        PlayerEntity player = player(world);
        if (player == null) {
            missing(world, villager);
            return;
        }
        sinJugador = -1;
        follow.guard(world, villager, player);
    }

    /**
     * Following and getting nowhere ({@link Atasco}): beside the player, or with the player gone, it is where it should
     * be; otherwise the companion's steps start over, and in the end it tells the player where it is stuck.
     */
    private void followStuck(ServerWorld world, VillagerEntityMCA villager) {
        PlayerEntity player = player(world);
        boolean near = player == null || villager.squaredDistanceTo(player) < 5.0 * 5.0 || villager.isSleeping() || CombatMind.busy(villager);
        Atasco.Remedio r = atasco.tick(world, villager, near, pensando);
        switch (r) {
            case NINGUNO -> {
            }
            case AYUDA -> {
                BlockPos at = villager.getBlockPos();
                contar(villager, "No puedo llegar hasta ti, estoy atascado en " + at.getX() + " " + at.getY() + " " + at.getZ() + ". ¿Me ayudas?",
                        "I can't get to you, I'm stuck at " + at.getX() + " " + at.getY() + " " + at.getZ() + ". Can you help me?");
            }
            default -> {
                // the companion's half-made step and its idea of where the player stood go: it plans its way to them again
                follow.release(world, villager);
                villager.getNavigation().stop();
            }
        }
    }

    private void alertTick(ServerWorld world, VillagerEntityMCA villager) {
        long now = world.getTime();
        PlayerEntity player = player(world);
        villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        if (defend(world, villager, player, DEFEND_ALERT)) {
            return;
        }
        Motor.standStill(villager);
        if (player != null) {
            sinJugador = -1;
            face(villager, player);
        } else if (sinJugador < 0) {
            sinJugador = now;
        }
        if ((villager.age & 1) == 0) {
            Retina.look(world, villager);
        }
        if (agachado) {
            // a crouch reason lasts a few seconds unless asked for again: asked for every tick it still holds
            crouch(villager, true);
        } else if ((villager.age % 20) == 0) {
            judgeCaution(villager);
        }
        if (sinJugador >= 0 && now - sinJugador > 200) {
            resume(villager, "se fue quien me avisó");
            return;
        }
        if (now - oido > ALERT_SILENCE) {
            resume(villager, "nadie me dijo nada en un rato");
            if (player != null) {
                iniciativa.decir(villager, player, sigoLinea(villager, spanish()), now);
            }
        }
    }

    private void waitTick(ServerWorld world, VillagerEntityMCA villager) {
        long now = world.getTime();
        PlayerEntity player = player(world);
        if (defend(world, villager, player, DEFEND_WAIT)) {
            return;
        }
        if (espera != null && villager.getBlockPos().getSquaredDistance(espera) > 2.5 * 2.5) {
            // pushed off its spot, or it stepped off it in a fight: back to where it was asked to wait
            if ((villager.age % 20) == 0) {
                villager.getNavigation().startMovingTo(espera.getX() + 0.5, espera.getY(), espera.getZ() + 0.5, 0.8);
            }
            return;
        }
        Motor.standStill(villager);
        villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        if (mirada.sweeping()) {
            mirada.tick(world, villager);
            return;
        }
        if (now >= nextSweep) {
            // a look all around now and then, the way someone waiting keeps an eye out
            nextSweep = now + 300 + villager.getRandom().nextInt(200);
            mirada.start(villager);
            return;
        }
        if (player != null && villager.squaredDistanceTo(player) < 24 * 24 && (now < lookUntil || villager.getRandom().nextInt(80) == 0)) {
            if (now >= lookUntil) {
                lookUntil = now + 40;
            }
            face(villager, player);
        }
        if ((villager.age & 1) == 0) {
            Retina.look(world, villager);
        }
    }

    /** The player it was with is gone (another world, offline): it stands where it is, and after a long while goes back to its life. */
    private void missing(ServerWorld world, VillagerEntityMCA villager) {
        long now = world.getTime();
        if (sinJugador < 0) {
            sinJugador = now;
        }
        Motor.standStill(villager);
        if ((villager.age & 1) == 0) {
            Retina.look(world, villager);
        }
        if (now - sinJugador > LOST_PLAYER) {
            change(villager, Modo.LIBRE, null, null, quienNombre + " se fue hace rato");
        }
    }

    /** A monster within {@code radius} (or whatever hurts it) is fought. True while fighting. */
    private boolean defend(ServerWorld world, VillagerEntityMCA villager, @Nullable PlayerEntity player, double radius) {
        if (enemigo != null && (!enemigo.isAlive() || enemigo.isRemoved() || enemigo.getWorld() != world
                || enemigo.squaredDistanceTo(villager) > (radius + 4) * (radius + 4))) {
            enemigo = null;
            CombatMind.disengage(villager);
        }
        if (enemigo == null && (villager.age & 3) == 0) {
            LivingEntity threat = CombatMind.pickTarget(world, villager, null, radius);
            if (threat != null && threat.isAlive()) {
                enemigo = threat;
                MCA.LOGGER.info("[Tropeles] {} se defiende de {} ({})", villager.getName().getString(), threat.getName().getString(), modo.palabra());
            }
        }
        if (enemigo == null) {
            return false;
        }
        CombatMind.fight(world, villager, player, enemigo);
        return true;
    }

    /** Head and body toward the player's eyes, like turning round to someone who spoke. */
    private void face(VillagerEntityMCA villager, PlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Hands.aim(villager, eye);
        double dx = eye.x - villager.getX();
        double dz = eye.z - villager.getZ();
        if (dx * dx + dz * dz > 0.09) {
            float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
            villager.setHeadYaw(yaw);
            villager.setBodyYaw(yaw);
            villager.setYaw(yaw);
        }
    }

    /** In the rock, or a monster seen close by lately: it crouches. */
    private void judgeCaution(VillagerEntityMCA villager) {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return;
        }
        String why = null;
        if (Entorno.bajoTierra(world, villager)) {
            why = "bajo tierra";
        } else {
            String monster = monsterSeen(world, villager);
            if (monster != null) {
                why = "vi " + monster + " cerca";
            }
        }
        if (why != null && !agachado) {
            porQue = why;
            crouch(villager, true);
            agachado = true;
            MCA.LOGGER.info("[Tropeles] {} se agacha con cuidado ({})", villager.getName().getString(), why);
        }
    }

    /** The name of a monster its eyes saw within {@value #MONSTER_NEAR} blocks lately, or null. */
    @Nullable
    static String monsterSeen(ServerWorld world, VillagerEntityMCA villager) {
        long now = world.getTime();
        for (VisualMemory.SeenEntity seen : Retina.memory(villager).entities(now, MONSTER_RECENT)) {
            if (seen.type.getSpawnGroup() == SpawnGroup.MONSTER && seen.pos != null
                    && seen.pos.squaredDistanceTo(villager.getPos()) < MONSTER_NEAR * MONSTER_NEAR) {
                return seen.type.getName().getString();
            }
        }
        return null;
    }

    /** {@link Postura}, never letting a body that cannot crouch yet stop the will. */
    private void crouch(VillagerEntityMCA villager, boolean on) {
        try {
            Postura.agachar(villager, CROUCH, on);
        } catch (RuntimeException e) {
            if (!loggedError) {
                loggedError = true;
                MCA.LOGGER.warn("[Tropeles] {} no pudo {}: {}", villager.getName().getString(), on ? "agacharse" : "pararse", e.toString());
            }
        }
    }

    /** The player of this mode, when it is in this world, alive and playing. */
    @Nullable
    PlayerEntity player(ServerWorld world) {
        if (quien == null) {
            return null;
        }
        PlayerEntity player = world.getServer() == null ? null : world.getServer().getPlayerManager().getPlayer(quien);
        if (player == null) {
            // not a logged-in player (the checks' stand-in): the one it was told by, while it exists
            player = quienRef.get();
            if (player != null && player.isRemoved()) {
                player = null;
            }
        }
        if (player == null || player.getWorld() != world || !player.isAlive() || player.isSpectator()) {
            return null;
        }
        quienRef = new WeakReference<>(player);
        quienNombre = player.getName().getString();
        return player;
    }

    // ------------------------------------------------------------------ changing its mind

    private void change(VillagerEntityMCA villager, Modo to, @Nullable PlayerEntity player, @Nullable BlockPos spot, String why) {
        Modo from = modo;
        if (to == Modo.ALERTA && from != Modo.ALERTA) {
            debajo = from;
            debajoQuien = quien;
            debajoEspera = espera;
        }
        leave(villager, from, to);
        modo = to;
        if (player != null) {
            quien = player.getUuid();
            quienRef = new WeakReference<>(player);
            quienNombre = player.getName().getString();
        } else if (to == Modo.LIBRE) {
            quien = null;
            quienRef = new WeakReference<>(null);
            quienNombre = "";
        }
        espera = to == Modo.ESPERANDO ? spot : to == Modo.ALERTA ? espera : null;
        desde = villager.getWorld().getTime();
        sinJugador = -1;
        enemigo = null;
        if (to != Modo.ALERTA && from == Modo.ALERTA) {
            debajoQuien = null;
            debajoEspera = null;
        }
        save(villager);
        think(villager);
        MCA.LOGGER.info("[Tropeles] {} pasa de {} a {}{}: {}", villager.getName().getString(), from.palabra(), to.palabra(),
                quienNombre.isEmpty() || to == Modo.LIBRE ? "" : " (" + quienNombre + ")", why);
    }

    /** Lets go of what the mode it leaves held: the crouch, the companion's steps, the sweep. */
    private void leave(VillagerEntityMCA villager, Modo from, Modo to) {
        if (from == Modo.ALERTA && to != Modo.ALERTA && agachado) {
            agachado = false;
            porQue = "";
            crouch(villager, false);
        }
        if (from == Modo.SIGUIENDO && to != Modo.SIGUIENDO && villager.getWorld() instanceof ServerWorld world) {
            follow.release(world, villager);
        }
        if (from == Modo.ESPERANDO || to == Modo.ALERTA) {
            mirada.stop();
        }
        if (from == Modo.ALERTA || from == Modo.ESPERANDO) {
            if (enemigo != null) {
                CombatMind.disengage(villager);
            }
        }
    }

    /** Back to the mode an alert interrupted, with its player and spot. */
    private Modo resume(VillagerEntityMCA villager, String why) {
        Modo back = debajo;
        UUID who = debajoQuien;
        BlockPos spot = debajoEspera;
        PlayerEntity player = null;
        if (who != null && who.equals(quien) && villager.getWorld() instanceof ServerWorld world) {
            player = player(world);
        } else if (who != null && villager.getServer() != null) {
            player = villager.getServer().getPlayerManager().getPlayer(who);
        }
        change(villager, back, player, spot, why);
        if (back != Modo.LIBRE && player == null && who != null) {
            // the one it was with is not here right now: it keeps to them, and waits for them to show up
            quien = who;
            quienRef = new WeakReference<>(null);
        }
        return back;
    }

    private void save(VillagerEntityMCA villager) {
        Modo kept = modo == Modo.ALERTA ? debajo : modo;
        UUID who = modo == Modo.ALERTA ? debajoQuien : quien;
        BlockPos spot = modo == Modo.ALERTA ? debajoEspera : espera;
        String text = switch (kept) {
            case SIGUIENDO -> who == null ? "" : "SIGUIENDO " + who;
            case ESPERANDO -> spot == null ? "" : "ESPERANDO " + spot.getX() + " " + spot.getY() + " " + spot.getZ() + (who == null ? "" : " " + who);
            default -> "";
        };
        if (!text.equals(villager.getVillagerBrain().getWill())) {
            villager.getVillagerBrain().setWill(text);
        }
    }

    private void load(VillagerEntityMCA villager) {
        String text = villager.getVillagerBrain().getWill();
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            String[] parts = text.trim().split(" ");
            if (parts[0].equals("SIGUIENDO") && parts.length >= 2) {
                modo = Modo.SIGUIENDO;
                quien = UUID.fromString(parts[1]);
            } else if (parts[0].equals("ESPERANDO") && parts.length >= 4) {
                modo = Modo.ESPERANDO;
                espera = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                quien = parts.length >= 5 ? UUID.fromString(parts[4]) : null;
            }
            if (modo != Modo.LIBRE) {
                MCA.LOGGER.info("[Tropeles] {} recuerda que estaba {}", villager.getName().getString(), modo.palabra());
            }
        } catch (RuntimeException e) {
            modo = Modo.LIBRE;
            quien = null;
            espera = null;
        }
    }

    /** The "pensando" line: the mode, the goal, and why. */
    private void think(VillagerEntityMCA villager) {
        long now = villager.getWorld().getTime();
        String doing = Retina.memory(villager).doing();
        String recent = doing != null && !doing.isBlank() && now - Retina.memory(villager).doingTick() < 200 ? doing : "";
        String who = quienNombre.isEmpty() ? "alguien" : quienNombre;
        PlayerEntity player = villager.getWorld() instanceof ServerWorld world ? player(world) : null;
        String far = player == null ? "" : " (a " + Math.round(villager.distanceTo(player)) + " bloques)";
        pensando = switch (modo) {
            case LIBRE -> "vivo lo mío" + (recent.isEmpty() ? "" : ": " + recent);
            case SIGUIENDO -> player == null ? "espero a que " + who + " vuelva, no lo veo por ningún lado"
                    : "sigo a " + who + far + " y lo cuido" + (enemigo != null || CombatMind.busy(villager) ? "; peleo para protegerlo" : "")
                    + (recent.isEmpty() || recent.startsWith("siguiendo") ? "" : "; " + recent);
            case ALERTA -> enemigo != null ? "me defiendo de " + enemigo.getName().getString() + " mientras espero a ver qué pasa"
                    : "me detuve porque " + who + " me avisó; quieto mirándolo" + far + (agachado ? ", agachado porque " + porQue : "")
                    + ", espero a que me diga qué pasa (luego " + (debajo == Modo.LIBRE ? "vuelvo a lo mío" : debajo.palabra()) + ")";
            case ESPERANDO -> enemigo != null ? "me defiendo de " + enemigo.getName().getString() + " mientras espero"
                    : "espero aquí a " + who + " porque me lo pidió; miro alrededor y me cuido";
        };
    }
}
