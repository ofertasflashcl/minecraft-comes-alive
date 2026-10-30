package net.mca.entity.ai.brain.tasks.chore;

import com.google.common.collect.ImmutableMap;
import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.DistressCalls;
import net.mca.entity.ai.Threats;
import net.mca.entity.ai.WorkTools;
import net.mca.entity.ai.body.Agua;
import net.mca.entity.ai.body.Archery;
import net.mca.entity.ai.body.Cofres;
import net.mca.entity.ai.body.CombatMind;
import net.mca.entity.ai.body.Explorar;
import net.mca.entity.ai.body.Hands;
import net.mca.entity.ai.body.Mina;
import net.mca.entity.ai.body.Mining;
import net.mca.entity.ai.body.Motor;
import net.mca.entity.ai.body.Progreso;
import net.mca.entity.ai.mente.Atasco;
import net.mca.entity.ai.mente.Recuerdos;
import net.mca.entity.ai.mente.Voluntad;
import net.mca.entity.ai.relationship.AgeState;
import net.mca.entity.ai.sense.Entorno;
import net.mca.entity.ai.sense.Lugar;
import net.mca.entity.ai.sense.MapaVisto;
import net.mca.entity.ai.sense.Obras;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.mca.server.world.data.VillageManager;
import net.mca.util.InventoryUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.FuzzyTargeting;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.RabbitEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.FoodComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Mining by sight, starting from nothing, the way a player plays a survival world. Without a pickaxe it gets its
 * own (chop, planks, table, pickaxe) and keeps upgrading from what it digs up: stone, iron in a furnace, diamond,
 * a sword, torches ({@link Progreso}). The miner only goes for ore its eye has actually seen (the exact block, as
 * Jade would name it) and only hits it once the crosshair can land on it within a player's reach. To get there it
 * walks, tunnels, digs stairs, bridges gaps and pillars up, all through {@link Motor}. With nothing in view it
 * goes down a staircase to the layer where what it looks for is most common (by its pickaxe and what it was asked
 * for) and branch mines, so every wall it opens passes in front of its eyes.
 * <p>
 * It has a body to look after. Its food bar drops while it works and it eats from its bag; it mends when well
 * fed. Every cell it stood on solidly on the way down is a breadcrumb ({@link Mina}): with a full bag, hungry with
 * nothing to eat, out of pickaxes, or called out from the chat, it climbs back up its own trail, walks home, stores
 * its haul, eats or takes food, and walks back down the same trail to its work face. Lost underground it cuts a
 * staircase up toward home (a pillar only in an open shaft), and that staircase becomes the new way in. With no
 * food at home it hunts the wild animals it sees and cooks the meat. A single monster is fought when it is armed
 * and healthy; otherwise it walls itself in, calls out its coordinates, or retreats into the rock and seals the way
 * behind it, and only comes out when it has neither seen nor heard anything for a while.
 */
public class ProspectingTask extends AbstractChoreTask {
    private enum Phase {
        SEEK, FIGHT, SHELTER_DIG, SHELTER_ENTER, SHELTER_SEAL, ESCAPE, RECOVER, HOME_UP, HOME_WALK, HOME_STORE, FORAGE,
        SURFACE_WORK, RETURN, REST, SLEEP
    }

    /** Why it is on its way up: a full bag, hunger, no pickaxe left, or someone called it out of the mine. */
    private enum Trip {BAG, FOOD, PICK, CALLED}

    /** Food points like a player's bar: one goes every minute of work, food in the bag brings them back. */
    private static final int FOOD_MAX = 20;
    private static final int FOOD_TICKS = 1200;
    private static final int EAT_BELOW = 14;
    private static final int GO_HOME_BELOW = 6;
    /** Well fed it mends like a player: a health point every few seconds, paid for in food. */
    private static final int HEAL_ABOVE = 18;
    private static final int HEAL_TICKS = 80;

    /** Below this the deepslate lava lakes start. */
    public static final int DEEPSLATE_DEPTH = -50;
    private static final int ORE_SEARCH_RADIUS = 32;
    /** New cells walked on one layer without a single ore before the branch mine moves to another layer. */
    private static final int BARREN_CELLS = 96;
    private static final int LEVEL_SHIFT_MAX = 15;
    /** A bed or a chest of its own this far away (across) still counts as home; beyond that it camps by the mine. */
    private static final int HOME_REACH = 128;
    /**
     * A saved mine is taken up again only when this close and this recent: as far as home may be from it (home is up
     * to {@link #HOME_REACH} away, the walk back from there must not forget it) and for days of game time, so a
     * night's sleep, a trip home or a restart never makes it start another one.
     */
    private static final int RESUME_DISTANCE = HOME_REACH + 32;
    private static final long RESUME_AGE = 24000L * 20;
    private static final int SAVE_TICKS = 1200;
    /** Ticks without any progress before the watchdog counts a round: ten seconds, then it thinks again. */
    private static final int STUCK_TICKS = 200;
    /** The same, standing on the land with no ore in view and no step cut: seven seconds. */
    private static final int SURFACE_STUCK_TICKS = 140;
    /** Building blocks it keeps when it throws its junk away: pillars, bridges, a furnace. */
    private static final int JUNK_KEEP = 32;
    /** A field chest of its own this close (and this far up or down) is walked back to before another is made. */
    private static final int CAMPO_REUSE = 32;
    /** Ticks a field chest may take (making it, setting it down, filling it) before it gives up and goes home. */
    private static final int CAMPO_TICKS = 1600;
    /** A spot found no good to start a mine is not tried again for this long. */
    private static final long SITE_FORGET = 6000;
    /**
     * Ways it knows that it tries (and walks) before it cuts a staircase of its own: each one found shut or getting it
     * nowhere counts once. A staircase that is still standing is found again every time.
     */
    private static final int VIA_TRIES = 6;
    /** Rounds of no progress on the way home, counted in ticks, before it says it is lost. */
    private static final int LOST_TICKS = 12000;
    private static final int REST_TICKS = 6000;
    private static final int FORAGE_TICKS = 4800;
    /** Ticks it looks around for something to hunt before it gives up. */
    private static final int LOOK_FOR_FOOD = 1200;
    private static final int PREY_TICKS = 400;

    /** What it does not eat as everyday food: poisonous, teleporting, or too precious to waste on a snack. */
    private static final Set<String> NOT_FOOD = Set.of("rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish", "chorus_fruit",
            "suspicious_stew", "golden_apple", "enchanted_golden_apple", "golden_carrot");

    /** The feet's height for branch mining each ore in the Overworld: where it is most common and lava is not. */
    private static final Map<String, Integer> ORE_DEPTH = Map.of("diamond", -53, "redstone", -53, "gold", -16, "lapis", 0,
            "iron", 16, "copper", 48, "coal", 95, "emerald", 80);
    /** The pickaxe tier each ore needs to drop anything (1 wood, 2 stone, 3 iron). */
    private static final Map<String, Integer> ORE_TIER = Map.of("diamond", 3, "redstone", 3, "gold", 3, "emerald", 3,
            "iron", 2, "lapis", 2, "copper", 2, "coal", 1);
    private static final TagKey<Biome> MOUNTAINS = TagKey.of(RegistryKeys.BIOME, new Identifier("minecraft", "is_mountain"));

    private final Motor motor = new Motor();
    /** Not final: after it gave up finding wood it starts its gear over, connected, a while later. */
    private Progreso progreso = new Progreso();
    private boolean wasGearing;
    private final Deque<BlockPos> plan = new ArrayDeque<>();
    private final Map<Long, Long> ignored = new LinkedHashMap<>();
    private Phase phase = Phase.SEEK;
    /** Where it goes back to once a fight or a shelter is over. */
    private Phase resume = Phase.SEEK;
    private Trip trip = Trip.BAG;
    /** The brain starts this same task again every 12000 ticks: only the first start sets the mine up. */
    private boolean started;
    /** Run by the in-game checks: nothing is kept with the world. */
    private boolean practicing;
    /** The checks set its mine ({@link #setMina}): the first start keeps it. */
    private boolean minaDada;
    private Mina mina = new Mina();
    /** The cells of its trail's own columns (see {@link Mina#column}), whatever trail it has now. */
    private final java.util.function.Predicate<BlockPos> trailCells = cell -> mina.column(cell);
    @Nullable
    private BlockPos ore;
    private int oreFails;
    private int chooseTicks = 10;
    private int tunnelDir;
    private boolean branchLeft;
    private int levelCells;
    private boolean warnedTier;
    private boolean warnedEmerald;

    // danger
    @Nullable
    private BlockPos pocket;
    @Nullable
    private BlockPos dangerFrom;
    @Nullable
    private BlockPos escapeGoal;
    private int escapeTicks;
    private int escapes;
    private int shelterTicks;
    /** The blocks it walled itself in with: taken back once it is safe. */
    private final List<BlockPos> sealed = new ArrayList<>();
    private int calmTicks;
    private long lastCry;
    private int fightTicks;
    private int backOffTicks;
    private long lastFightCall = -1200;
    private long lastOreAnnounce;
    private boolean announcedAny;

    // the body
    private int food = FOOD_MAX;
    private int foodTicks;
    private int healTicks;
    private int heals;
    private int decideTicks = 19;
    private long nextBagCheck;
    private long nextFoodTrip;
    private long nextProvision;
    private long nextSave;

    // home
    @Nullable
    private BlockPos base;
    /** Set by the in-game checks: home is there, whatever the villager's bed says. */
    @Nullable
    private BlockPos home;
    @Nullable
    private BlockPos homeTarget;
    /** No bed and no chest of its own near: home is a camp by the mine. */
    private boolean camping;
    /** The camp it last told about. */
    @Nullable
    private BlockPos campSaid;
    private int homeTicks;
    private int lostTicks;
    private int homeDir;
    @Nullable
    private BlockPos homeChest;
    @Nullable
    private BlockPos foodChest;
    private boolean stored;
    private int storedCount;
    private int chestsMade;
    /** Ticks spent at home with a chest in the bag that is still to be set down. */
    private int placeTicks;
    private int ledgeFails;
    private boolean foraged;

    // a chest wherever it is when the bag fills away from home, like its tables and furnaces; one for junk, left behind
    private enum Campo {NADA, BASURA, GUARDAR}

    private Campo campo = Campo.NADA;
    /** The chest it is filling right now. */
    @Nullable
    private BlockPos campoCofre;
    private int campoTicks;
    private int campoWalk;
    private long nextCampo;
    /** The chest it threw its junk into this trip: used again while it is close, never claimed, never named. */
    @Nullable
    private BlockPos basura;
    /** The cell of the tunnel wall it is cutting out to set its chest into, out of the way. */
    @Nullable
    private BlockPos nicho;
    private int nichoFails;
    private final Hands.Breaker nichoBreaker = new Hands.Breaker();
    /** Field chests found full (or out of reach) this trip. */
    private final Set<Long> campoLleno = new HashSet<>();
    /** The breaking progress seen last tick: still rising means it is digging, not stuck. */
    private float lastBreak;
    private boolean breakingNow;

    // the climb out: its trail first, a staircase of its own when the trail is gone
    private boolean fallback;
    private int climbFromIdx = -1;
    private final List<BlockPos> climb = new ArrayList<>();
    private int skip;
    private int trailFails;
    /** Which way the next leg of the climb out aims: 0 straight up, 1 toward home, 2 and 3 to its sides, 4 away. */
    private int climbMode;
    /** Legs of the climb out given up in a row. */
    private int climbFails;
    /** World time it last called out that it cannot find a way up. */
    private long lastStuckCall = -1200;

    // a way it remembers walking ({@link Recuerdos}): up and out, or back down to its work face, before any digging
    private final Deque<BlockPos> via = new ArrayDeque<>();
    /** Walking a remembered way right now. */
    private boolean viaMemoria;
    /** Where that way goes: null for out at the land's level, else the cell it heads for (its work face). */
    @Nullable
    private BlockPos viaDestino;
    /** Remembered ways found shut this trip. */
    private int viaFails;
    /** Ends of remembered ways it got to and that were not out after all: not tried again this trip. */
    private final Set<Long> viaUsadas = new HashSet<>();
    /** The phase a step aside or a dig-out of the anti-stuck layer was started in. */
    @Nullable
    private Phase nudgePhase;
    /** The way it walks now as one list, start included: the body's no-dig walker ({@link Motor#walkPath}) wants the same list every tick. */
    private List<BlockPos> viaLista = List.of();
    /** The no-dig walker gave that way up: it goes on cell by cell with the step machine, still never digging. */
    private boolean viaPaso;
    /** What the way it walks is: its trail, its torches, its steps, or a way it worked out over what it saw. For the log. */
    private String viaDe = "";
    /** Its own trail (the crumbs of its mine) was already tried as the way out this trip. */
    private boolean trailTried;
    /**
     * Up its own trail backwards, reopening only the cells of the trail's own columns where something shut them (gravel,
     * its own table or furnace, a missing ceiling cell for the jump), before any new staircase: never one beside it.
     */
    private boolean trailDig;
    /** That was started once this trip already (a second time it is its own new staircase). */
    private boolean trailDigTried;
    /** The crumb it last stood on while going up its trail that way, and the steps that failed since. */
    private int trailDigIdx;
    private int trailDigFails;
    /** It said it cannot find the way home this trip (it keeps trying, connected). */
    private boolean lostSaid;
    /** A way on foot ({@link #aPie}) looked for and not found: from where, to where and when, so it is not looked for every tick. */
    @Nullable
    private BlockPos pieNadaDe;
    @Nullable
    private BlockPos pieNadaA;
    private long pieNadaAt = Long.MIN_VALUE / 2;
    /** What it looked for (and why each failed) before it cut a staircase of its own this trip: said in the log when it digs. */
    private final List<String> intentos = new ArrayList<>();

    // the night: like a player it sleeps when it is home in the dark, then goes on
    /** The bed it is going to (its head), or null. */
    @Nullable
    private BlockPos cama;
    private int camaTicks;
    /** It lay down this night: once it is up again the night is over for it. */
    private boolean slept;
    /** It went to bed while waiting at home (called home): it waits on in the morning. */
    private boolean sleepFromRest;
    /** Times it lay down in a bed (for the checks). */
    private int timesSlept;
    /** Not before this to look for a bed again (none free, or it could not lie down). */
    private long nextSleepTry;
    /** For the checks: night or day whatever the clock says (null: the clock). */
    @Nullable
    private Boolean nocheForzada;
    /** It gave up finding wood for a pickaxe: it rests, connected, until then and starts its gear over. */
    private long nowoodUntil;
    /** Not before this to walk back to its old mine on its own after a walk back that got nowhere. */
    private long nextMineReturn;

    // back down
    private int returnTicks;
    private int returnFails;

    // food from the land
    private int forageTicks;
    private int noFoodTicks;
    @Nullable
    private AnimalEntity prey;
    private int preyTicks;
    private final Map<Integer, Long> spared = new HashMap<>();
    @Nullable
    private BlockPos lootAt;
    private int lootTicks;
    /** Looking around for food, or for a spot to start a mine: where it looked already and which way it went. */
    private final Explorar explorar = new Explorar();
    /** The forage it is on is for provisions before going down, not because it is hungry. */
    private boolean provisioning;
    private long lastHuntSay = -1200;
    /** When it last asked a player for something to eat, starving with nothing in the bag. */
    private long lastFoodAsk = -2400;
    /** A ripe sweet berry bush it saw while looking for food, and the ticks spent getting to it. */
    @Nullable
    private BlockPos berries;
    private int berryTicks;
    private long foodKnownAt = -1000;
    private boolean foodKnownCache;

    private int surfaceTicks;
    private int restTicks;
    /** Digging toward an enemy it cannot walk to: ticks so far, the tick it last got closer, failed steps. */
    private int digTicks;
    /**
     * The enemy it is digging through to. Its face to the block it breaks, the enemy can drop out of its view for a
     * moment; a player still knows it is there behind that block, so the dig goes on.
     */
    @Nullable
    private LivingEntity digTarget;
    /** The enemy of this fight and when it last had it (seen or not): knocked back out of sight, it is still there. */
    @Nullable
    private LivingEntity fightTarget;
    private long fightTargetTick;
    private int digGain;
    private int digFails;
    private double digClosest;

    // where the mine starts: natural ground, not a floor, a wall or a path
    /** The spot it walks to before it cuts the first step, or null. */
    @Nullable
    private BlockPos site;
    private int siteTicks;
    /** When it may skip judging the spot it stands on again (it was fine a moment ago). */
    private long siteCheckAt;
    /** The watchdog saw it stand still on the land without digging: find another spot whatever this one looks like. */
    private boolean forceSite;
    /** Exploring for a spot: the eye showed no natural land away from buildings. */
    private boolean siteExploring;
    private final Map<Long, Long> badSites = new HashMap<>();

    // watchdog
    @Nullable
    private Phase watchPhase;
    private double bestProgress = Double.NEGATIVE_INFINITY;
    @Nullable
    private BlockPos stuckAt;
    private int stuckItems = -1;
    private int stuckTicks;
    private int stuckRounds;

    /** The ore each miner was asked to look for first ("busca diamantes"). */
    private static final Map<VillagerEntityMCA, String> WANTED = new WeakHashMap<>();
    /** Chat orders waiting for the running task: true = climb out to eat, then come back down. */
    private static final Map<VillagerEntityMCA, Boolean> CALLED = new WeakHashMap<>();
    /** "Vuelve a la mina" to one that was called out and is waiting at home. */
    private static final Map<VillagerEntityMCA, Boolean> BACK = new WeakHashMap<>();

    private static final Map<String, String> ORE_WORDS = Map.ofEntries(
            Map.entry("diamante", "diamond"), Map.entry("diamantes", "diamond"), Map.entry("diamonds", "diamond"),
            Map.entry("hierro", "iron"), Map.entry("oro", "gold"), Map.entry("carbon", "coal"), Map.entry("cobre", "copper"),
            Map.entry("lapislazuli", "lapis"), Map.entry("esmeralda", "emerald"), Map.entry("esmeraldas", "emerald"),
            Map.entry("emeralds", "emerald"), Map.entry("cuarzo", "quartz"), Map.entry("netherita", "ancient_debris"),
            Map.entry("netherite", "ancient_debris"), Map.entry("escombros", "ancient_debris"), Map.entry("debris", "ancient_debris")
    );

    /** Asks {@code villager} to go for this ore first; empty or unknown clears the wish. */
    public static void want(VillagerEntityMCA villager, @Nullable String ore) {
        String word = ore == null ? "" : java.text.Normalizer.normalize(ore, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT).trim();
        for (String part : word.split("[^a-z_]+")) {
            String key = ORE_WORDS.getOrDefault(part, part);
            if (List.of("diamond", "iron", "gold", "coal", "copper", "redstone", "lapis", "emerald", "quartz", "ancient_debris").contains(key)) {
                WANTED.put(villager, key);
                return;
            }
        }
        WANTED.remove(villager);
    }

    /** The ore it was asked to look for first, or null. */
    @Nullable
    public static String wanted(VillagerEntityMCA villager) {
        return WANTED.get(villager);
    }

    /**
     * "Inas, sube" from the chat (server thread): stop digging, climb out like a player and go home; with
     * {@code hungry}, climb out to eat and then go back down. Only recorded here: the running task picks it up on
     * its next tick, after a fight or a shelter if it is in one. Never throws, also when no task runs.
     */
    public static void callHome(VillagerEntityMCA villager, boolean hungry) {
        CALLED.merge(villager, hungry, Boolean::logicalOr);
    }

    /** The miners at work right now, so the chat can ask one how its trip is going. */
    private static final Map<VillagerEntityMCA, ProspectingTask> RUNNING = new WeakHashMap<>();
    /**
     * The one task object of each villager, kept across brain resets: MCA builds a new brain (and new task objects)
     * when the job is given, the move state changes, a profession is taken or lost at a job site near home, and so on.
     * A new object would forget the trip it was on and the mine it was working in; the same object goes on with them.
     */
    private static final Map<java.util.UUID, java.lang.ref.WeakReference<ProspectingTask>> OF_VILLAGER = new HashMap<>();

    /** The mining task of this villager: the one it already has (in its old brain), or a new one. For the brain's task lists. */
    public static ProspectingTask of(VillagerEntityMCA villager) {
        java.lang.ref.WeakReference<ProspectingTask> ref = OF_VILLAGER.get(villager.getUuid());
        ProspectingTask task = ref == null ? null : ref.get();
        if (task == null) {
            if (OF_VILLAGER.size() > 512) {
                OF_VILLAGER.values().removeIf(r -> r.get() == null);
            }
            task = new ProspectingTask();
            OF_VILLAGER.put(villager.getUuid(), new java.lang.ref.WeakReference<>(task));
        }
        return task;
    }
    /** A trip home it decided on itself after someone suggested it: true = hungry. */
    private static final Map<VillagerEntityMCA, Boolean> DECIDED = new WeakHashMap<>();
    /** When someone last suggested going back, and how many times in a row, to tell a passing remark from insisting. */
    private static final Map<VillagerEntityMCA, long[]> SUGGESTED = new WeakHashMap<>();

    /**
     * What it makes of "sería mejor volver": its reasons to go and to stay, in its own words, and whether it goes.
     * {@code volver} false means it carries on; {@code hambre} that the trip is to eat first.
     */
    public record Juicio(boolean volver, boolean hambre, boolean minando, String es, String en) {
    }

    /**
     * Someone suggests heading back (server thread). It weighs it like a player would: a full bag, hunger, wounds,
     * a pickaxe about to break, monsters around, the night; against room in the bag, food, an ore right in front of
     * it. Asked again soon after, it gives in more easily, and the third time it goes. When it decides to go, the
     * running task climbs out along its own trail, walks home, makes a chest if it has none, stores its haul and
     * comes back down. Not mining: only the night, wounds or being far from home send it home.
     */
    public static Juicio juzgarVuelta(VillagerEntityMCA villager) {
        long now = villager.getWorld().getTime();
        long[] asked = SUGGESTED.computeIfAbsent(villager, v -> new long[]{-100000L, 0L});
        asked[1] = now - asked[0] < 2400 ? asked[1] + 1 : 1;
        asked[0] = now;
        int times = (int) asked[1];
        ProspectingTask task = RUNNING.get(villager);
        boolean mining = task != null && villager.getVillagerBrain().getCurrentJob() == Chore.PROSPECT;
        List<String> goEs = new ArrayList<>();
        List<String> goEn = new ArrayList<>();
        List<String> stayEs = new ArrayList<>();
        List<String> stayEn = new ArrayList<>();
        int score = 0;
        boolean strong = false;
        boolean hungry = false;
        float health = villager.getHealth() / villager.getMaxHealth();
        if (health < 0.5F) {
            goEs.add("estoy herido");
            goEn.add("I'm hurt");
            score += 2;
            strong = true;
        }
        boolean monsters = false;
        for (VisualMemory.SeenEntity seen : Retina.memory(villager).entities(now, 200)) {
            if (seen.type.getSpawnGroup() == net.minecraft.entity.SpawnGroup.MONSTER && seen.distance < 16) {
                monsters = true;
                break;
            }
        }
        if (monsters && health < 0.8F) {
            goEs.add("hay monstruos cerca");
            goEn.add("there are monsters close by");
            score++;
        }
        if (mining) {
            SimpleInventory inv = villager.getInventory();
            int free = freeSlots(inv);
            int carried = foodPoints(inv);
            if (free <= 3) {
                goEs.add(free == 0 ? "tengo la mochila llena" : "tengo la mochila casi llena");
                goEn.add(free == 0 ? "my bag is full" : "my bag is nearly full");
                score += 2;
                strong = true;
            } else if (free <= 8) {
                goEs.add("ya llevo harto en la mochila");
                goEn.add("I'm carrying quite a lot");
                score++;
            } else {
                stayEs.add("todavía tengo espacio en la mochila");
                stayEn.add("I still have room in my bag");
                score--;
            }
            if (task.food <= GO_HOME_BELOW + 2 && carried < 4) {
                goEs.add("tengo hambre y no me queda comida");
                goEn.add("I'm hungry and out of food");
                score += 2;
                strong = true;
                hungry = true;
            } else if (carried >= 8 || task.food >= 15) {
                stayEs.add("tengo comida para rato");
                stayEn.add("I have food for a while");
                score--;
            } else if (carried < 4) {
                goEs.add("me queda poca comida");
                goEn.add("I'm running low on food");
                score++;
                hungry = true;
            }
            int picks = Progreso.countPicks(villager);
            if (picks == 0 || picks == 1 && Progreso.worn(Progreso.bestPickStack(villager)) && !Progreso.canCraftPick(villager)) {
                goEs.add("se me está acabando el pico");
                goEn.add("my pickaxe is about to break");
                score += 2;
                strong = true;
            }
            if (task.ore != null) {
                stayEs.add("tengo un mineral justo aquí");
                stayEn.add("there's an ore right here");
                score--;
            }
        } else {
            if (villager.getWorld().isNight()) {
                goEs.add("ya es de noche");
                goEn.add("it's night already");
                score += 2;
            }
            var home = villager.getResidency().getHome();
            if (home.isPresent() && home.get().getDimension() == villager.getWorld().getRegistryKey()
                    && !home.get().getPos().isWithinDistance(villager.getBlockPos(), 64)) {
                goEs.add("estoy lejos de casa");
                goEn.add("I'm far from home");
                score++;
            }
        }
        boolean go = strong || score >= 2 || times >= 2 && score >= 1 || times >= 3;
        if (go && mining) {
            DECIDED.put(villager, hungry);
        }
        String es;
        String en;
        if (go) {
            String why = goEs.isEmpty() ? (times >= 3 ? "ya que insistes" : "tienes razón") : joinEs(goEs);
            String whyEn = goEn.isEmpty() ? (times >= 3 ? "since you insist" : "you're right") : joinEn(goEn);
            es = mining ? "Sí, mejor vuelvo: " + why + ". Subo por mi camino, guardo todo en mi cofre y después sigo."
                    : "Sí, mejor me voy a casa: " + why + ".";
            en = mining ? "Yes, I'd better head back: " + whyEn + ". I'll climb out the way I came, store everything in my chest and then carry on."
                    : "Yes, I'd better go home: " + whyEn + ".";
        } else {
            String why = stayEs.isEmpty() ? "estoy bien por ahora" : joinEs(stayEs);
            String whyEn = stayEn.isEmpty() ? "I'm fine for now" : joinEn(stayEn);
            es = "Todavía no: " + why + (goEs.isEmpty() ? "" : ", aunque " + joinEs(goEs)) + ". Si insistes, vuelvo.";
            en = "Not yet: " + whyEn + (goEn.isEmpty() ? "" : ", although " + joinEn(goEn)) + ". If you insist, I'll head back.";
        }
        MCA.LOGGER.info("[Tropeles] {} piensa si volver (vez {}, puntaje {}): {}", villager.getName().getString(), times, score, es);
        return new Juicio(go, hungry, mining, es, en);
    }

    private static String joinEs(List<String> parts) {
        return parts.size() == 1 ? parts.get(0) : String.join(", ", parts.subList(0, parts.size() - 1)) + " y " + parts.get(parts.size() - 1);
    }

    private static String joinEn(List<String> parts) {
        return parts.size() == 1 ? parts.get(0) : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /** "Vuelve a la mina" (server thread): a miner waiting at home after being called out goes back down. */
    public static void backToWork(VillagerEntityMCA villager) {
        BACK.put(villager, Boolean.TRUE);
    }

    public ProspectingTask() {
        super(ImmutableMap.of(
                MemoryModuleType.LOOK_TARGET, MemoryModuleState.REGISTERED,
                MemoryModuleType.WALK_TARGET, MemoryModuleState.REGISTERED
        ), 12000);
    }

    @Override
    protected boolean allowsWorkWhileAfraid() {
        return true;
    }

    @Override
    protected boolean shouldRun(ServerWorld world, VillagerEntityMCA villager) {
        return villager.getVillagerBrain().getCurrentJob() == Chore.PROSPECT && super.shouldRun(world, villager);
    }

    @Override
    protected boolean shouldKeepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        return shouldRun(world, villager);
    }

    @Override
    protected void run(ServerWorld world, VillagerEntityMCA villager, long time) {
        VillagerEntityMCA before = this.villager;
        super.run(world, villager, time);
        if (before != null && before != villager) {
            // the same villager loaded again as a new body: the half-made step belonged to the old one
            motor.reset(world, villager);
            plan.clear();
        }
        if (tooYoung(villager)) {
            abandonJobWithMessage("chore.mining.tooyoung");
            return;
        }
        if (!started) {
            started = true;
            init(world, villager);
        }
        RUNNING.put(villager, this);
        Retina.lookAll(world, villager);
    }

    /** The first start of this task: picks up the mine it was working in, when it is still close and recent. */
    private void init(ServerWorld world, VillagerEntityMCA villager) {
        tunnelDir = villager.getHorizontalFacing().getHorizontal();
        base = villager.getBlockPos().toImmutable();
        if (minaDada) {
            return; // the checks gave it its mine
        }
        mina = new Mina();
        if (practicing) {
            return;
        }
        Mina saved = Mina.load(world, villager.getUuid());
        if (saved == null) {
            return;
        }
        if (!resumable(world, villager, saved)) {
            Mina.forget(world, villager.getUuid());
            return;
        }
        mina = saved;
        tunnelDir = saved.dir & 3;
        branchLeft = saved.left;
        MCA.LOGGER.info("[Tropeles] {} retoma su mina: {} pasos de camino guardados", villager.getName().getString(), saved.size());
        if (saved.known() && Entorno.enLaSuperficie(world, villager)) {
            beginReturn(world, villager);
        }
    }

    private static boolean resumable(ServerWorld world, VillagerEntityMCA villager, Mina saved) {
        long age = world.getTime() - saved.savedAt;
        BlockPos face = saved.last();
        if (face == null || age < 0 || age > RESUME_AGE) {
            return false;
        }
        BlockPos door = saved.entrance();
        return horizontalSq(face, villager) <= RESUME_DISTANCE * RESUME_DISTANCE
                || door != null && horizontalSq(door, villager) <= RESUME_DISTANCE * RESUME_DISTANCE;
    }

    private static double horizontalSq(BlockPos pos, LivingEntity entity) {
        double dx = pos.getX() + 0.5 - entity.getX();
        double dz = pos.getZ() + 0.5 - entity.getZ();
        return dx * dx + dz * dz;
    }

    @Override
    protected void finishRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        saveMine(world, villager);
        if (villager.isAlive() && villager.getVillagerBrain().getCurrentJob() == Chore.PROSPECT) {
            // still its job (the brain starts this task again): a jump in progress lands where it was going, any
            // other step is planned again from where it stands when the task picks up
            if (!motor.midStep(villager)) {
                motor.reset(world, villager);
            }
            return;
        }
        motor.reset(world, villager);
        plan.clear();
        // orders from the chat for the job that ended do not carry over to the next one
        CALLED.remove(villager);
        BACK.remove(villager);
        if (phase == Phase.FIGHT || phase == Phase.FORAGE) {
            CombatMind.disengage(villager);
        }
        // the job is over: the next one starts from the mine it left (when near), not from this trip's half-way state
        started = false;
        phase = Phase.SEEK;
        resume = Phase.SEEK;
        trip = Trip.BAG;
        ore = null;
        prey = null;
        homeTarget = null;
        camping = false;
        fallback = false;
        climb.clear();
        sealed.clear();
        site = null;
        siteExploring = false;
        forceSite = false;
        provisioning = false;
        campo = Campo.NADA;
        campoCofre = null;
        nicho = null;
        explorar.reset();
        resetWatch();
        ItemStack stack = villager.getMainHandStack();
        if (WorkTools.isPickaxe(stack)) {
            villager.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    @Override
    protected void keepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        super.keepRunning(world, villager, time);
        if (this.villager == null) {
            this.villager = villager;
        }
        if (villager.getVillagerBrain().getCurrentJob() != Chore.PROSPECT) {
            return;
        }
        if (!started) {
            started = true;
            init(world, villager);
        }
        work(world, villager, (villager.age & 1) == 0);
    }

    /** One tick of the mining loop on its own, without a job assigner; used by the in-game checks. */
    public void practice(ServerWorld world, VillagerEntityMCA villager) {
        this.villager = villager;
        if (!started) {
            started = true;
            practicing = true;
            init(world, villager);
        }
        work(world, villager, true);
    }

    /**
     * Its will first ({@link Voluntad}): following someone, stopped by a warning or waiting, the will has the body and
     * only the body's own needs go on here; in its own life, body, orders, danger, decisions, then whatever the
     * phase is about. Never in the middle of a jump: the step lands first.
     */
    private void work(ServerWorld world, VillagerEntityMCA villager, boolean look) {
        if (!Voluntad.conectado(villager) && net.mca.entity.ai.mente.Interrupcion.pausado(villager) && !motor.midStep(villager)) {
            // stopped with "¡para!": the hands and legs wait with it, the mine and the plan stay as they were
            Motor.standStill(villager);
            return;
        }
        long now = world.getTime();
        // every step it takes is remembered, whoever drives the body
        remember(world, villager);
        Atasco atasco = Voluntad.of(villager).atasco();
        if (!motor.midStep(villager) && Voluntad.of(villager).manda(world, villager, this)) {
            metabolism(world, villager);
            return;
        }
        if (look) {
            Retina.look(world, villager);
        }
        metabolism(world, villager);
        // mid-jump nothing changes course: the step lands first (a reset now would drop the block under its feet)
        boolean midStep = motor.midStep(villager);
        if (!midStep) {
            orders(world, villager);
        }
        recordTrail(world, villager);
        if (now >= nextSave) {
            nextSave = now + SAVE_TICKS;
            saveMine(world, villager);
        }
        if (!midStep && threat(world, villager)) {
            return;
        }
        if (!midStep && (phase == Phase.SEEK || phase == Phase.SURFACE_WORK) && ++decideTicks >= 20) {
            decideTicks = 0;
            if (decide(world, villager)) {
                return;
            }
        }
        // getting nowhere for a while, whatever it is doing: the next remedy (a step aside under way drives the body)
        if ((atasco.meta() != null || !midStep) && unstick(world, villager, atasco)) {
            return;
        }
        if (phase == Phase.SEEK && !midStep) {
            if (gearUp(world, villager)) {
                return;
            }
            if (!equipPick(villager)) {
                if (progreso.gaveUp()) {
                    noWood(world, villager);
                }
                return;
            }
        }
        if (phase == Phase.SEEK && !midStep && campo != Campo.NADA && campoTick(world, villager)) {
            return;
        }
        if (!midStep && watchdog(world, villager)) {
            return;
        }
        switch (phase) {
            case SEEK -> seek(world, villager);
            case FIGHT -> fight(world, villager);
            case SHELTER_DIG -> shelterDig(world, villager);
            case SHELTER_ENTER -> shelterEnter(world, villager);
            case SHELTER_SEAL -> shelterSeal(world, villager);
            case ESCAPE -> escape(world, villager);
            case RECOVER -> recover(world, villager);
            case HOME_UP, HOME_WALK, HOME_STORE -> homeTrip(world, villager);
            case FORAGE -> forage(world, villager);
            case SURFACE_WORK -> surfaceWork(world, villager);
            case RETURN -> returnToMine(world, villager);
            case REST -> rest(world, villager);
            case SLEEP -> sleep(world, villager);
        }
    }

    // ------------------------------------------------------------------ what the checks and the chat may ask

    /**
     * The will takes the body (following, an alert, waiting): the half-made step and the plan are dropped, a hunt is
     * called off. Everything else (the phase, the ore, the trail, the trip home) stays as it is for later.
     */
    public void soltar(ServerWorld world, VillagerEntityMCA villager) {
        Voluntad.of(villager).atasco().reset();
        motor.reset(world, villager);
        plan.clear();
        villager.getNavigation().stop();
        if (phase == Phase.FORAGE || phase == Phase.FIGHT) {
            CombatMind.disengage(villager);
            prey = null;
        }
        MCA.LOGGER.info("[Tropeles] {} deja lo suyo en pausa ({})", name(), debug());
    }

    /** The will gives the body back: it goes on from where it stands, as if it had just looked up from its work. */
    public void retomar(ServerWorld world, VillagerEntityMCA villager) {
        Voluntad.of(villager).atasco().reset();
        motor.reset(world, villager);
        plan.clear();
        resetWatch();
        decideTicks = 19;
        MCA.LOGGER.info("[Tropeles] {} vuelve a lo suyo: {}", name(), debug());
    }

    /** Food points it carries (everyday food only). */
    public static int comidaEnMochila(VillagerEntityMCA villager) {
        return foodPoints(villager.getInventory());
    }

    /** Empty slots left in its bag. */
    public static int espacioLibre(VillagerEntityMCA villager) {
        return freeSlots(villager.getInventory());
    }

    /** For the in-game checks: how full its food bar is, and where home is. */
    public void setFood(int points) {
        food = points;
    }

    public int food() {
        return food;
    }

    /** Digging, or on its way back down to its work face. */
    public boolean atWork() {
        return phase == Phase.SEEK || phase == Phase.RETURN;
    }

    /** For the in-game checks: the ore it is going for, and its phase. */
    public String debug() {
        return phase + (phase == Phase.HOME_UP || phase == Phase.HOME_WALK || phase == Phase.HOME_STORE ? " (" + trip + ")" : "")
                + (ore == null ? "" : " hacia " + ore.toShortString()) + (plan.isEmpty() ? "" : " plan " + plan.peekFirst().toShortString())
                + ", camino " + mina.size() + (fallback ? " (escalera propia, modo " + climbMode + ")" : "")
                + (viaMemoria ? " (por " + viaDe + ", " + via.size() + " pasos" + (viaPaso ? ", paso a paso" : "") + ")" : "");
    }

    /** For the checks: the phase it is in ("HOME_WALK"), and whether it is walking a way it remembers. */
    public String fase() {
        return phase.name();
    }

    public boolean porMemoria() {
        return viaMemoria;
    }

    /** Raw iron in its furnace right now. */
    public boolean cooking() {
        return progreso.cooking();
    }

    /** Not chopping, crafting, smelting or packing up. */
    public boolean gearIdle() {
        return progreso.idle();
    }

    public void setHome(BlockPos pos) {
        home = pos;
    }

    /** For the checks: the mine it has (its trail from the door to the work face), as if it had dug it. */
    public void setMina(Mina saved) {
        mina = saved;
        minaDada = true;
        tunnelDir = saved.dir & 3;
        branchLeft = saved.left;
    }

    /** For the checks: night (true) or day (false) whatever the clock says; null goes back to the clock. */
    public void setNoche(@Nullable Boolean night) {
        nocheForzada = night;
    }

    /** The door of the mine it works in, or null. */
    @Nullable
    public BlockPos minaEntrada() {
        return mina.entrance();
    }

    /** Its work face (the deepest cell of its trail), or null. */
    @Nullable
    public BlockPos frente() {
        return mina.last();
    }

    public int minaPasos() {
        return mina.size();
    }

    /** For the checks: its trail as it stands, door first, work face last. */
    public List<BlockPos> minaCeldas() {
        List<BlockPos> cells = new ArrayList<>(mina.size());
        for (int i = 0; i < mina.size(); i++) {
            cells.add(mina.crumb(i));
        }
        return cells;
    }

    /** Times it lay down in a bed. */
    public int vecesDormido() {
        return timesSlept;
    }

    /** What the way it walks now is ("sus antorchas", "su camino de la mina"...), empty when it walks none. */
    public String viaDe() {
        return viaMemoria ? viaDe : "";
    }

    /** The step machine this miner moves with (tests look at it). */
    public Motor motor() {
        return motor;
    }

    /** To the one who sent it, wherever they are on the server (another dimension too); silent when offline. */
    private void say(String key, Object... params) {
        tellAssigner(key, params);
        if (villager != null) {
            Recuerdos.anotar(villager, kind(key), Recuerdos.frase(key, params), villager.getBlockPos());
        }
    }

    /** The kind of memory one of its chore lines is. */
    private static String kind(String key) {
        return switch (key) {
            case "chore.mining.crafted", "chore.mining.smelted", "chore.mining.torches", "chore.mining.cooking" -> "oficio";
            case "chore.mining.orenotify", "chore.mining.hunted" -> "hallazgo";
            case "chore.mining.fight", "chore.mining.help", "chore.mining.shelter", "chore.mining.escape", "chore.mining.safe" -> "pelea";
            case "chore.mining.stored", "chore.mining.nochest" -> "cofre";
            case "chore.mining.stuck" -> "atasco";
            default -> "viaje";
        };
    }

    private void saveMine(ServerWorld world, VillagerEntityMCA villager) {
        if (mina.size() > 0) {
            // the places of its mine are memories too, also in the checks
            Recuerdos r = Recuerdos.of(world, villager);
            if (mina.entrance() != null) {
                r.sitio("mina", mina.entrance(), world.getTime());
            }
            if (mina.last() != null) {
                r.sitio("frente de trabajo", mina.last(), world.getTime());
            }
        }
        if (practicing || mina.size() == 0) {
            return;
        }
        mina.dir = tunnelDir;
        mina.left = branchLeft;
        mina.save(world, villager.getUuid());
    }

    private String name() {
        return villager == null ? "?" : villager.getName().getString();
    }

    // ------------------------------------------------------------------ orders from the chat

    private void orders(ServerWorld world, VillagerEntityMCA villager) {
        Boolean called = CALLED.remove(villager);
        if (called != null) {
            boolean hungry = called;
            switch (phase) {
                case SEEK, SURFACE_WORK, RETURN -> {
                    MCA.LOGGER.info("[Tropeles] {} sube de la mina por orden del chat{}", name(), hungry ? " a comer" : "");
                    leave(world, villager, hungry ? Trip.FOOD : Trip.CALLED);
                }
                case HOME_UP, HOME_WALK, HOME_STORE -> {
                    // already on its way: what it does at home changes
                    if (hungry || trip != Trip.FOOD) {
                        trip = hungry ? Trip.FOOD : Trip.CALLED;
                    }
                }
                case FORAGE -> {
                    if (!hungry) {
                        trip = Trip.CALLED;
                        CombatMind.disengage(villager);
                        prey = null;
                        enterHomeWalk();
                    }
                }
                case REST -> {
                    if (hungry) {
                        trip = Trip.FOOD;
                        enterHomeStore();
                    }
                }
                default -> CALLED.merge(villager, hungry, Boolean::logicalOr); // after the fight or the shelter
            }
        }
        Boolean decided = DECIDED.remove(villager);
        if (decided != null) {
            switch (phase) {
                case SEEK, SURFACE_WORK, RETURN -> {
                    MCA.LOGGER.info("[Tropeles] {} decide volver a casa a guardar sus cosas{}", name(), decided ? " y comer" : "");
                    leave(world, villager, decided ? Trip.FOOD : Trip.BAG);
                }
                case HOME_UP, HOME_WALK, HOME_STORE, REST, FORAGE -> {
                    // already on its way, or home
                }
                default -> DECIDED.merge(villager, decided, Boolean::logicalOr); // after the fight or the shelter
            }
        }
        if (BACK.remove(villager) != null && phase == Phase.REST) {
            MCA.LOGGER.info("[Tropeles] {} vuelve a la mina por orden del chat", name());
            beginReturn(world, villager);
        }
    }

    // ------------------------------------------------------------------ the body

    /** The food bar drops while it works; well fed it mends; low, or hurt, it eats from its bag. */
    private void metabolism(ServerWorld world, VillagerEntityMCA villager) {
        if (++foodTicks >= FOOD_TICKS) {
            foodTicks = 0;
            food = Math.max(0, food - 1);
        }
        float max = villager.getMaxHealth();
        float health = villager.getHealth();
        if (health < max && food >= HEAL_ABOVE) {
            if (++healTicks >= HEAL_TICKS) {
                healTicks = 0;
                villager.heal(1.0F);
                if (++heals % 2 == 0) {
                    food--;
                }
            }
        } else {
            healTicks = 0;
        }
        if ((villager.age % 20) == 0 && phase != Phase.FIGHT && (food <= EAT_BELOW || health < max * 0.5F && food < HEAL_ABOVE)) {
            if (!eatBest(world, villager) && food < GO_HOME_BELOW) {
                askForFood(world, villager);
            }
        }
    }

    /**
     * Starving with nothing in the bag: never quiet about it. A player within earshot is asked, every two minutes at
     * most, like a hungry friend would.
     */
    private void askForFood(ServerWorld world, VillagerEntityMCA villager) {
        long now = world.getTime();
        if (now - lastFoodAsk < 2400) {
            return;
        }
        net.minecraft.entity.player.PlayerEntity near = world.getClosestPlayer(villager, 32.0);
        if (near == null || near.isSpectator()) {
            return;
        }
        lastFoodAsk = now;
        MCA.LOGGER.info("[Tropeles] {} tiene hambre (comida {}) y nada que comer: le pide comida a {}", name(), food, near.getName().getString());
        Voluntad.contar(villager, "Tengo hambre, ¿me das algo de comer?", "I'm hungry, could you give me something to eat?",
                "comida " + food + " de " + FOOD_MAX + " y nada para comer en la mochila");
    }

    /** Eats the most filling everyday food it carries (cooked before raw). False when it has nothing to eat. */
    private boolean eatBest(ServerWorld world, VillagerEntityMCA villager) {
        SimpleInventory inv = villager.getInventory();
        int slot = -1;
        int best = Integer.MIN_VALUE;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!edible(stack)) {
                continue;
            }
            int score = mealScore(stack);
            if (score > best) {
                best = score;
                slot = i;
            }
        }
        if (slot < 0) {
            return false;
        }
        ItemStack meal = inv.getStack(slot);
        FoodComponent component = meal.getItem().getFoodComponent();
        int points = component == null ? 1 : Math.max(1, component.getHunger());
        // the name before the bite: the last one leaves an empty stack behind
        Text name = meal.getName();
        food = Math.min(FOOD_MAX, food + points);
        villager.eatFood(world, meal.split(1));
        inv.markDirty();
        Retina.memory(villager).doing("comiendo " + name.getString(), world.getTime());
        return true;
    }

    private static int mealScore(ItemStack stack) {
        FoodComponent component = stack.getItem().getFoodComponent();
        if (component == null) {
            return 0;
        }
        return component.getHunger() * 10 + (int) (component.getSaturationModifier() * 10) - (Progreso.rawFood(stack) ? 15 : 0);
    }

    /** Everyday food: it fills, it does nothing strange to the body, and it is not a treasure. */
    static boolean edible(ItemStack stack) {
        if (stack.isEmpty() || !stack.isFood()) {
            return false;
        }
        FoodComponent component = stack.getItem().getFoodComponent();
        if (component == null || component.getHunger() <= 0 || !component.getStatusEffects().isEmpty()) {
            return false;
        }
        return !NOT_FOOD.contains(Registries.ITEM.getId(stack.getItem()).getPath());
    }

    /** Food points in the bag: what it can still eat before it has to go and find more. */
    private static int foodPoints(SimpleInventory inv) {
        int points = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (edible(stack)) {
                FoodComponent component = stack.getItem().getFoodComponent();
                points += (component == null ? 1 : component.getHunger()) * stack.getCount();
            }
        }
        return points;
    }

    private static int rawCount(SimpleInventory inv) {
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (Progreso.rawFood(stack)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private static int freeSlots(SimpleInventory inv) {
        int free = 0;
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private static boolean bagFull(VillagerEntityMCA villager) {
        return freeSlots(villager.getInventory()) == 0;
    }

    // ------------------------------------------------------------------ the trail

    /** Every cell it stands on solidly while digging becomes a crumb; the climb out of its own is kept apart. */
    private void recordTrail(ServerWorld world, VillagerEntityMCA villager) {
        if (motor.midStep(villager) || !villager.isOnGround()) {
            return;
        }
        BlockPos feet = villager.getBlockPos();
        if (phase == Phase.HOME_UP) {
            if (fallback && Mining.standable(world, feet.down()) && Mining.passable(world, feet) && Mining.passable(world, feet.up())
                    && (climb.isEmpty() || !climb.get(climb.size() - 1).equals(feet))) {
                climb.add(feet.toImmutable());
                if (climb.size() > Mina.MAX) {
                    climb.remove(0);
                }
            }
            return;
        }
        if (phase != Phase.SEEK) {
            return;
        }
        if (Mining.onSurface(world, feet) && Mining.openGround(world, feet) || roofOut(world, villager, feet)) {
            if (oldMine(world, feet)) {
                // up on the land with a mine of its own to go back to: that mine is not thrown away for a new one
                return;
            }
            if (keepMine(world, feet)) {
                // at its own door or beside its trail (a trip back that stopped there, a look round): the trail stays.
                // Digging on from the end of it out onto open ground (a ravine) carries the trail on
                int i = mina.nearHigh(world, feet);
                if (i >= mina.size() - 2 && mina.walked(world, villager)) {
                    levelCells++;
                }
                return;
            }
            // the mine starts where the sky ends: the door on open ground, where the way up counts as out, the open
            // top of the staircase already part of the way in; under the eaves of a house at the land's level too
            if (mina.outside(world, villager)) {
                Recuerdos.of(world, villager).sitio("mina", feet, world.getTime());
            }
            return;
        }
        if (mina.walked(world, villager)) {
            levelCells++;
            markTrail(world, villager);
        }
    }

    /**
     * Its marks along the trail as it goes in: the mouth of the mine at its first step under the sky's end, and the
     * top and the foot of every flight of steps (where the trail turns from level to sloping or back).
     */
    private void markTrail(ServerWorld world, VillagerEntityMCA villager) {
        int n = mina.size();
        if (n == 2 && mina.entrance() != null) {
            Recuerdos.marcar(villager, Recuerdos.ENTRADA, mina.entrance());
        }
        if (n < 3) {
            return;
        }
        BlockPos c0 = mina.crumb(n - 3);
        BlockPos c1 = mina.crumb(n - 2);
        BlockPos c2 = mina.crumb(n - 1);
        if ((c0.getY() == c1.getY()) != (c1.getY() == c2.getY())) {
            Recuerdos.marcar(villager, Recuerdos.ESCALERA, c1);
        }
    }

    /**
     * It has a mine of its own it can still walk into (its door and its work face open, the door within reach) and
     * it is not on its trail now: up on the land that mine is where it goes back to, not a new one.
     */
    private boolean oldMine(ServerWorld world, BlockPos feet) {
        if (!mina.known() || world.getTime() < nextMineReturn || mina.nearHigh(world, feet) >= 0) {
            return false;
        }
        BlockPos door = mina.entrance();
        return door != null && horizontalSq(door, villager) <= (double) (HOME_REACH + 16) * (HOME_REACH + 16) && mina.usable(world);
    }

    /**
     * On its own trail, some steps short of its work face, with that face still open: the way down is walked, not dug
     * again beside. Not while a trip back just failed ({@link #nextMineReturn}).
     */
    private boolean shortOfFace(ServerWorld world, BlockPos feet, long now) {
        if (now < nextMineReturn || mina.size() < 4 || !mina.usable(world)) {
            return false;
        }
        int i = mina.nearHigh(world, feet);
        return i >= 0 && i < mina.size() - 4;
    }

    /**
     * A mine of its own it can still walk into, the door within reach: up on the land, at its door or on the open top of
     * its staircase, that trail is never thrown away for a new one started where it stands.
     */
    private boolean keepMine(ServerWorld world, BlockPos feet) {
        BlockPos door = mina.entrance();
        return mina.known() && door != null && horizontalSq(door, villager) <= (double) (HOME_REACH + 16) * (HOME_REACH + 16) && mina.usable(world);
    }

    /** Every cell its feet stand on solidly goes into its memories ({@link Recuerdos}). Never stops the task. */
    private void remember(ServerWorld world, VillagerEntityMCA villager) {
        try {
            Recuerdos.of(world, villager).paso(world, villager);
        } catch (RuntimeException e) {
            if ((villager.age % 1200) == 0) {
                MCA.LOGGER.warn("[Tropeles] {} no pudo recordar su paso: {}", name(), e.toString());
            }
        }
    }

    /**
     * Out, though there is a roof over its head: under the eaves of a house, a porch or a hut with open sides at the
     * land's level ({@link Entorno}: not down in the rock; the shelter of a building with the open sky beside its head,
     * {@link Entorno#refugio}, or at most {@value Recuerdos#LAND_DEPTH} blocks below the land with something built, or
     * a trunk, over its head rather than the rock). The open sky is not over the feet,
     * so the shape of the ground alone ({@link Mining#openGround}) would still call it a mine. Natural overhangs and
     * cave mouths keep the old test.
     */
    private static boolean roofOut(ServerWorld world, VillagerEntityMCA villager, BlockPos feet) {
        if (Mining.outdoors(world, feet) || Entorno.lugar(world, villager) == Lugar.BAJO_TIERRA) {
            return false;
        }
        if (Entorno.refugio(world, villager)) {
            return true; // a built roof with the open sky a few steps to the side at its head: an eave, a porch
        }
        if (Entorno.profundidad(world, villager) > Recuerdos.LAND_DEPTH) {
            return false;
        }
        BlockPos.Mutable cell = feet.up(2).mutableCopy();
        for (int k = 0; k < 8; k++, cell.move(Direction.UP)) {
            BlockState state = world.getBlockState(cell);
            if (state.getCollisionShape(world, cell).isEmpty()) {
                continue;
            }
            return Obras.made(state) || state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES);
        }
        return false;
    }

    /** Where to aim a dig-out leg toward home: on its own level when there is room or a built roof over it, else a step up. */
    private static BlockPos legGoal(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, BlockPos goal) {
        if (Mining.outdoors(world, goal) || roofOut(world, villager, feet)) {
            // under the eaves the way home is along the ground: never a staircase up into the roof
            return goal;
        }
        return goal.up();
    }

    // ------------------------------------------------------------------ deciding

    /**
     * What a player checks now and then while digging: food for the way up, room in the bag, a pickaxe that will
     * last, and before going down, a kit and something to eat. True when it set off somewhere this tick.
     */
    private boolean decide(ServerWorld world, VillagerEntityMCA villager) {
        long now = world.getTime();
        BlockPos feet = villager.getBlockPos();
        SimpleInventory inv = villager.getInventory();
        if (phase == Phase.SEEK && campo == Campo.NADA && now >= nextCampo && !progreso.wantsChest() && campoStart(world, villager)) {
            return true;
        }
        if (campo == Campo.NADA) {
            tidyBag(world, villager, false);
        }
        // down in the rock by its own sense of place: a house roof or a tree over its head is not a mine
        boolean underground = Entorno.bajoTierra(world, villager);
        boolean noFood = foodPoints(inv) == 0;
        boolean weak = villager.getHealth() < villager.getMaxHealth() * 0.4F && food < HEAL_ABOVE;
        if (noFood && now >= nextFoodTrip && (food <= leaveAt(world, feet) || weak)) {
            say("chore.mining.hungry");
            leave(world, villager, Trip.FOOD);
            return true;
        }
        if (now >= nextBagCheck && campo == Campo.NADA && bagFull(villager)) {
            tidyBag(world, villager, true);
            if (bagFull(villager)) {
                say("chore.mining.home");
                leave(world, villager, Trip.BAG);
                return true;
            }
        }
        if (phase != Phase.SEEK) {
            return false;
        }
        int picks = Progreso.countPicks(villager);
        if (underground && picks == 0 && progreso.needsSurface() && !Progreso.canCraftPick(villager)) {
            say("chore.mining.nopick2");
            leave(world, villager, Trip.PICK);
            return true;
        }
        if (underground && picks == 1 && Progreso.worn(Progreso.bestPickStack(villager)) && !Progreso.canCraftPick(villager)
                && !Progreso.hasKit(villager)) {
            // the last pickaxe is about to go and no wood for the next one: up while it still digs. With the wood in
            // the bag only stone is missing, and that it digs right here (going up would only send it back down)
            say("chore.mining.picklow");
            leave(world, villager, Trip.PICK);
            return true;
        }
        if (!underground && picks > 0 && ore == null && now >= nextProvision && Progreso.bestPickTier(villager) >= 2) {
            // about to go down with a real pickaxe (a wooden one only digs the stone for the next): packed like a
            // player, wood for the next pickaxe and something to eat
            nextProvision = now + 6000;
            if (!Progreso.hasKit(villager)) {
                progreso.wantKit();
            }
            if (foodPoints(inv) < 6 && !world.isNight() && foodKnown(world, villager)) {
                say("chore.mining.provisions");
                MCA.LOGGER.info("[Tropeles] {} junta provisiones antes de bajar ({}, {} puntos de comida en la mochila)", name(),
                        Entorno.lugar(world, villager).palabra(), foodPoints(inv));
                trip = Trip.FOOD;
                startForage();
                provisioning = true;
                return true;
            }
        }
        return false;
    }

    /** The food bar at which it heads up: the deeper (or farther along its trail) it is, the sooner. */
    private int leaveAt(ServerWorld world, BlockPos feet) {
        int crumbs = Math.max(0, mina.nearLow(world, feet));
        int depth = Math.max(0, world.getSeaLevel() - feet.getY());
        int ticks = Math.max(crumbs * 12, depth * 60);
        return Math.min(EAT_BELOW, GO_HOME_BELOW + (ticks + FOOD_TICKS - 1) / FOOD_TICKS);
    }

    /**
     * Throws out loose rock the way a player cleans the hotbar with Q: the two fullest stacks of building blocks
     * stay (cobblestone first, for pillars, bridges and walls), every other stack of natural rock and soil is
     * dropped and left on the ground. Only when the bag is getting full, or {@code hard}. Tools, food, ores, fuel,
     * wood and torches are never thrown away.
     */
    private void tidyBag(ServerWorld world, VillagerEntityMCA villager, boolean hard) {
        if (Hands.buildingBlockCount(villager) < 16) {
            Hands.unrefuse(villager); // running short: everything it digs is welcome again
        }
        SimpleInventory inv = villager.getInventory();
        if (!hard && freeSlots(inv) >= 3) {
            return;
        }
        int keepA = -1;
        int keepB = -1;
        int scoreA = -1;
        int scoreB = -1;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!Hands.isBuildingBlock(stack)) {
                continue;
            }
            int score = stack.getCount() + (cobble(stack) ? 64 : 0);
            if (score > scoreA) {
                keepB = keepA;
                scoreB = scoreA;
                keepA = i;
                scoreA = score;
            } else if (score > scoreB) {
                keepB = i;
                scoreB = score;
            }
        }
        for (int i = 0; i < inv.size(); i++) {
            if (i != keepA && i != keepB && rubble(inv.getStack(i))) {
                Hands.toss(world, villager, i, 6000);
            }
        }
    }

    private static boolean cobble(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && (item.getBlock() == Blocks.COBBLESTONE || item.getBlock() == Blocks.COBBLED_DEEPSLATE);
    }

    /** Natural rock or soil that came out of the tunnel: nobody keeps a whole bag of it. */
    private static boolean rubble(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
            return false;
        }
        if (cobble(stack)) {
            return true; // what stone and deepslate drop: the bulk of every bag
        }
        BlockState state = item.getBlock().getDefaultState();
        return Mining.isRock(state) && !Mining.isOre(state) && !state.isIn(BlockTags.LOGS) && !state.isOf(Blocks.AMETHYST_BLOCK);
    }

    // ------------------------------------------------------------------ leaving the mine

    /** Up and out, whatever for: back along its trail (or a staircase of its own), then home. */
    private void leave(ServerWorld world, VillagerEntityMCA villager, Trip why) {
        trip = why;
        BlockPos feet = villager.getBlockPos();
        if (campo != Campo.NADA) {
            endCampo(world, villager, world.getTime() + 1200);
        }
        progreso.leaving();
        tidyBag(world, villager, false);
        if (!motor.midStep(villager)) {
            motor.reset(world, villager);
        }
        plan.clear();
        ore = null;
        homeTicks = 0;
        lostTicks = 0;
        homeChest = null;
        foodChest = null;
        homeTarget = null;
        stored = false;
        storedCount = 0;
        chestsMade = 0;
        ledgeFails = 0;
        foraged = false;
        fallback = false;
        climb.clear();
        skip = 0;
        trailFails = 0;
        endVia();
        viaFails = 0;
        viaUsadas.clear();
        trailTried = false;
        trailDig = false;
        trailDigTried = false;
        trailDigFails = 0;
        lostSaid = false;
        intentos.clear();
        climbFromIdx = mina.nearLow(world, feet);
        BlockPos target = homePos(world, villager);
        homeDir = Direction.getFacing(target.getX() - villager.getX(), 0, target.getZ() - villager.getZ()).getHorizontal();
        climbMode = preferredClimbMode(world, villager, feet);
        climbFails = 0;
        saveMine(world, villager);
        resetWatch();
        Recuerdos.sitio(villager, camping ? "campamento" : "casa", target);
        Recuerdos.anotar(villager, "viaje", "me fui " + switch (why) {
            case BAG -> "a casa a guardar lo que llevaba";
            case FOOD -> "a buscar comida";
            case PICK -> "a la superficie a buscar madera para un pico";
            case CALLED -> "a casa porque me llamaron";
        } + " desde " + feet.getX() + " " + feet.getY() + " " + feet.getZ() + " (" + Entorno.lugar(world, villager).palabra() + ")", feet);
        // on its own staircase below the door (the open top of it, a few steps under the land) it is still in its mine:
        // up the staircase by its feet, not a walk home by the path finder that gives up and digs a way of its own
        boolean onStair = mina.entrance() != null && !feet.equals(mina.entrance()) && mina.nearLow(world, feet) > 0;
        if (!Entorno.bajoTierra(world, villager) && !onStair && (Mining.onSurface(world, feet) || Mining.nearTheSky(world, feet, 8))) {
            // not in a mine at all: out on the land, under a roof, at the open top of its staircase. Nothing to climb
            // out of; it sets off from here like a player who just turns around and walks
            MCA.LOGGER.info("[Tropeles] {} se va ({}) desde {}, {}: no está en una mina, camina directo a casa {}{}", name(), why,
                    feet.toShortString(), Entorno.lugar(world, villager).palabra(), target.toShortString(), camping ? " (acampa)" : "");
            surfaced(world, villager);
            return;
        }
        phase = Phase.HOME_UP;
        MCA.LOGGER.info("[Tropeles] {} sale de la mina ({}): {} pasos de camino, comida {}, casa {}{}", name(), why, mina.size(), food,
                target.toShortString(), camping ? " (acampa)" : "");
    }

    /**
     * Home for this trip: its bed when it is close enough to walk to, otherwise its nearest own chest, otherwise a
     * camp by the mine's door (or wherever it comes out, when it does not know the door).
     */
    private BlockPos homePos(ServerWorld world, VillagerEntityMCA villager) {
        if (home != null) {
            camping = false;
            return home;
        }
        if (homeTarget != null) {
            return homeTarget;
        }
        BlockPos feet = villager.getBlockPos();
        camping = false;
        var bed = villager.getResidency().getHome();
        if (bed.isPresent() && bed.get().getDimension() == world.getRegistryKey()
                && horizontalSq(bed.get().getPos(), villager) <= (double) HOME_REACH * HOME_REACH) {
            homeTarget = bed.get().getPos().toImmutable();
            return homeTarget;
        }
        List<BlockPos> chests = Cofres.own(world, villager, feet);
        if (!chests.isEmpty() && horizontalSq(chests.get(0), villager) <= (double) HOME_REACH * HOME_REACH) {
            homeTarget = chests.get(0).toImmutable();
            return homeTarget;
        }
        camping = true;
        BlockPos door = mina.entrance();
        homeTarget = door != null ? door : base != null ? base : feet.toImmutable();
        return homeTarget;
    }

    /**
     * Going home: up to the surface along its own trail (or any way it can), then walking, then the chest: what it
     * does not need goes in, food comes out.
     */
    private void homeTrip(ServerWorld world, VillagerEntityMCA villager) {
        VisualMemory memory = Retina.memory(villager);
        long now = world.getTime();
        // first its iron: back to the furnace, out with everything, furnace in the bag
        if (!progreso.idle()) {
            if (phase != Phase.HOME_STORE) {
                memory.doing("recogiendo su horno antes de irse", now);
                progreso.leaving();
            }
            gearUp(world, villager);
            return;
        }
        BlockPos target = homePos(world, villager);
        homeTicks++;
        BlockPos feet = villager.getBlockPos();
        if (phase == Phase.HOME_UP) {
            equipPick(villager);
            climbUp(world, villager, target, feet, memory, now);
            return;
        }
        if (phase == Phase.HOME_WALK) {
            walkHome(world, villager, target, feet, memory, now);
            return;
        }
        store(world, villager, target, memory, now);
    }

    private void climbUp(ServerWorld world, VillagerEntityMCA villager, BlockPos target, BlockPos feet, VisualMemory memory, long now) {
        // out means on open ground: sky over the open top of its own staircase is not out yet while the land beside
        // it still rises over its head. Under the eaves of a house at the land's level it is out as well
        boolean open = Mining.openGround(world, feet);
        boolean eaves = !open && roofOut(world, villager, feet);
        // the door of its own mine is out by what it was when it went in there (the last cell under the open sky)
        boolean door = mina.entrance() != null && feet.equals(mina.entrance());
        boolean out = open && Mining.onSurface(world, feet) || eaves || door || !camping && (open && feet.getY() >= target.getY() - 1
                || feet.getY() >= target.getY() && feet.isWithinDistance(target, 24));
        // never off its feet: leaving in mid-jump drops the block it was about to stand on
        if (out && villager.isOnGround() && !motor.midStep(villager)) {
            if (eaves) {
                MCA.LOGGER.info("[Tropeles] {} está afuera aunque tenga techo encima ({}, {} bloques bajo la tierra) en {}", name(),
                        Entorno.lugar(world, villager).palabra(), Entorno.profundidad(world, villager), feet.toShortString());
                Voluntad.contar(villager, "Ya salí: esto es el alero de una casa, no la mina. Me voy a casa.",
                        "I'm out: this is just a roof over a house's porch, not the mine. Heading home.");
            }
            surfaced(world, villager);
            return;
        }
        if (trailDig) {
            trailDigStep(world, villager, feet, memory, now);
            return;
        }
        BlockPos goal = null;
        if (viaMemoria) {
            goal = nextVia(feet);
            if (goal == null) {
                // the end of the way it remembered, and not out after all: that end is no way out this trip
                viaUsadas.add(feet.asLong());
                endVia();
                if (!wayOut(world, villager, feet, mina.nearLow(world, feet))) {
                    return;
                }
                goal = nextVia(feet);
            }
            if (goal != null) {
                memory.doing("subiendo por " + viaDe + " (" + via.size() + " pasos)", now);
            }
        }
        if (goal == null && !fallback && !viaMemoria) {
            // the way it came, walked and never dug: its trail, its torches, the steps it remembers, any way over what
            // it saw; a staircase of its own only when there is none of those
            if (wayOut(world, villager, feet, mina.nearLow(world, feet))) {
                goal = nextVia(feet);
            }
        }
        if (trailDig) {
            return; // up its own trail from the next tick on: nothing dug toward a staircase of its own meanwhile
        }
        if (goal == null) {
            if (plan.isEmpty()) {
                plan.add(climbGoal(villager, feet));
            }
            goal = plan.peekFirst();
            memory.doing("subiendo a la superficie para volver a casa", now);
        }
        boolean remembered = viaMemoria;
        // a remembered way is walked, never dug: what it walked once has room for it still (checked by touch)
        Motor.Result result = remembered ? walkVia(world, villager, goal) : motor.step(world, villager, goal, true, true);
        if (result == Motor.Result.ARRIVED) {
            skip = 0;
            trailFails = 0;
            if (remembered) {
                if (goal.equals(via.peekFirst())) {
                    via.pollFirst();
                }
            } else if (fallback) {
                plan.clear();
                climbFails = 0;
                BlockPos here = villager.getBlockPos();
                climbMode = preferredClimbMode(world, villager, here);
                int hx = target.getX() - feet.getX();
                int hz = target.getZ() - feet.getZ();
                // in the open top of its staircase the land is a step or two further on the way it is going: turning
                // back toward home there would set blocks over the steps it just cut, its way back down
                boolean cutTop = Mining.outdoors(world, here) && !Mining.openGround(world, here);
                if (hx * hx + hz * hz > 4 && !cutTop) {
                    // keep climbing toward home, not past it
                    homeDir = Direction.getFacing(hx, 0, hz).getHorizontal();
                }
                // its new steps may have come out onto ground it walked before: from there the remembered way, when it
                // leads higher up than where it stands (never back down its own new steps), or its staircase on foot
                if (wayByMemory(world, villager, here, null, true) || salidaAPie(world, villager, here)) {
                    fallback = false;
                    MCA.LOGGER.info("[Tropeles] {} salió a un sitio por donde ya pasó en {}: sigue por ahí ({} pasos)", name(), here.toShortString(), via.size());
                    Voluntad.contar(villager, "Ya reconozco esto, sigo por donde pasé antes.", "I know this place, I'll go on the way I came before.");
                }
            }
        } else if (result == Motor.Result.BLOCKED) {
            motor.reset(world, villager);
            if (remembered) {
                viaBlocked(world, villager, feet, goal, now);
            } else if (!fallback) {
                // that crumb is shut: aim a few further back, then give the trail up
                if (++trailFails >= 3) {
                    wayOut(world, villager, feet, mina.nearLow(world, feet));
                } else {
                    skip += 3;
                }
            } else if (salidaAPie(world, villager, villager.getBlockPos())) {
                // the leg it was cutting is shut, but its own staircase is there to walk after all
                fallback = false;
                plan.clear();
            } else {
                plan.clear();
                // the next way round, always in the same order: never a coin toss
                climbMode = (climbMode + 1) % 5;
                if (++climbFails >= 10) {
                    // boxed in for a while: it calls out where it is, and keeps trying
                    callOutStuck(world);
                }
            }
        }
    }

    /**
     * The way out when its trail does not take it: first a way over cells it remembers walking (this trip or any
     * earlier one, its own old staircases among them) to a cell that was out at the land's level; only when it
     * remembers none, a staircase of its own. True when it walks a remembered way now.
     */
    private boolean wayOut(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, int fromIdx) {
        if (viaFails < VIA_TRIES && buscarVia(world, villager, feet, null)) {
            BlockPos end = via.peekLast();
            Recuerdos r = Recuerdos.of(world, villager);
            int nuevos = 0;
            for (BlockPos cell : via) {
                if (!r.recuerda(cell)) {
                    nuevos++;
                }
            }
            MCA.LOGGER.info("[Tropeles] {} recuerda por dónde salir ({}): {} pasos ({} de ellos al lado de lo que pisó o que solo vio), de {} a {}", name(),
                    viaDe, via.size(), nuevos, feet.toShortString(), end == null ? "?" : end.toShortString());
            Voluntad.contar(villager, "Subo por donde ya pasé, no hace falta cavar otra escalera.",
                    "I'll go up the way I came, no need to dig another staircase.");
            Recuerdos.anotar(villager, "camino", "subí por " + viaDe + ", " + via.size() + " pasos de " + feet.getX() + " " + feet.getY() + " "
                    + feet.getZ() + (end == null ? "" : " a " + end.getX() + " " + end.getY() + " " + end.getZ()), feet);
            return true;
        }
        startFallback(world, villager, feet, fromIdx);
        return false;
    }

    /**
     * A way over remembered cells from the feet: out at the land's level ({@code to} null), or to {@code to}. Sets it
     * as the way to walk. True when there is one.
     */
    private boolean wayByMemory(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, @Nullable BlockPos to) {
        return wayByMemory(world, villager, feet, to, false);
    }

    /** As above; with {@code higher}, only a way that ends higher up than the feet counts. */
    private boolean wayByMemory(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, @Nullable BlockPos to, boolean higher) {
        List<BlockPos> way;
        try {
            Recuerdos r = Recuerdos.of(world, villager);
            // the way out may be a block or two to the side of the steps it remembers, or a staircase it only saw
            way = to == null ? r.caminoAfuera(world, feet, viaUsadas, Retina.memory(villager)) : r.caminoHacia(world, feet, to, 1);
        } catch (RuntimeException e) {
            MCA.LOGGER.warn("[Tropeles] {} no pudo pensar en un camino recordado: {}", name(), e.toString());
            way = List.of();
        }
        if (way.isEmpty() || higher && way.get(way.size() - 1).getY() <= feet.getY()) {
            return false;
        }
        setVia(world, villager, feet, way, to, "por donde ya pasó");
        return true;
    }

    /** That way is the one it walks now (never digging), to {@code to} or out when null. */
    private void setVia(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, List<BlockPos> way, @Nullable BlockPos to, String de) {
        via.clear();
        via.addAll(way);
        viaMemoria = true;
        trailDig = false;
        viaDestino = to;
        viaDe = de;
        viaPaso = false;
        List<BlockPos> lista = new ArrayList<>(way.size() + 1);
        lista.add(feet.toImmutable());
        lista.addAll(way);
        viaLista = lista;
        plan.clear();
        if (!motor.midStep(villager)) {
            motor.reset(world, villager);
        }
        // its measure of getting somewhere is the way left now
        bestProgress = Double.NEGATIVE_INFINITY;
        stuckTicks = 0;
    }

    /**
     * Looks for a way it can walk without digging or placing anything, in the order a player would think of them: out
     * ({@code to} null) its own trail backwards, then torch to torch ({@link Recuerdos#caminoPorMarcas}), then the steps
     * it remembers ({@link Recuerdos#caminoAfuera}), then any way over cells it walked or saw ({@link Recuerdos#caminoA});
     * to {@code to} the steps it remembers, then any such way. True when it walks one now.
     */
    private boolean buscarVia(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, @Nullable BlockPos to) {
        List<BlockPos> way = List.of();
        String de = "";
        try {
            Recuerdos r = Recuerdos.of(world, villager);
            VisualMemory vista = Retina.memory(villager);
            // first the way its feet can take right now over what it knows, never digging: up its own staircase to the
            // door of this mine (out), or to where it goes. Walked as found, one axis at a time, every cell checked now
            BlockPos door = mina.entrance();
            BlockPos meta = to != null ? to : door != null && door.isWithinDistance(feet, RESUME_DISTANCE) ? door : null;
            if (meta != null) {
                List<BlockPos> pie = aPie(world, villager, feet, meta, to != null ? 1.0 : 0.5);
                if (pie != null && pie.size() >= 2) {
                    way = pie.subList(1, pie.size());
                    de = to != null ? "un camino que conoce, a pie" : "su escalera, a pie";
                } else {
                    intentos.add((to != null ? "a pie hasta " : "a pie hasta la boca de su mina ") + meta.toShortString() + ": "
                            + (pie == null ? "no hay paso sin cavar" : "ya está ahí"));
                }
            } else if (to == null) {
                intentos.add("a pie: no sabe dónde está la boca de su mina");
            }
            if (to == null && way.isEmpty()) {
                if (!trailTried) {
                    trailTried = true;
                    way = trailWay(world, feet);
                    de = "su camino de la mina";
                }
                if (way.isEmpty()) {
                    intentos.add("su rastro de la mina: " + (mina.entrance() == null ? "no conoce la boca" : mina.nearLow(world, feet) < 0
                            ? "no está sobre él" : "cerrado o ya probado"));
                    way = r.caminoPorMarcas(world, feet, viaUsadas, vista);
                    de = "sus antorchas";
                }
                if (way.isEmpty()) {
                    intentos.add("antorcha a antorcha: " + r.marcas(Recuerdos.ANTORCHA) + " antorchas, ninguna cadena hasta afuera");
                    way = r.caminoAfuera(world, feet, viaUsadas, vista);
                    de = "por donde ya pasó";
                }
                if (way.isEmpty()) {
                    intentos.add("por donde ya pasó: ningún paso suyo lleva afuera");
                    BlockPos boca = r.sitio("mina");
                    if (boca != null && !boca.equals(door) && boca.isWithinDistance(feet, RESUME_DISTANCE)) {
                        // the mouth of a mine it remembers (an older one, its trail forgotten): on foot up to it
                        List<BlockPos> pie = aPie(world, villager, feet, boca, 0.5);
                        if (pie != null && pie.size() >= 2) {
                            way = pie.subList(1, pie.size());
                            de = "la boca de su mina, a pie";
                        } else {
                            intentos.add("a pie hasta la boca que recuerda " + boca.toShortString() + ": no hay paso sin cavar");
                        }
                    }
                }
                if (way.isEmpty()) {
                    int y = feet.getY();
                    BlockPos hacia = mina.entrance() != null ? mina.entrance() : r.sitio("mina");
                    way = r.caminoA(world, feet, (pos, p) -> p.fuera() && pos.getY() >= y && !viaUsadas.contains(pos.asLong()), hacia, vista,
                            pos -> pos.getY() >= y + 2 && !viaUsadas.contains(pos.asLong()) && Mining.onSurface(world, pos) && Mining.openGround(world, pos),
                            Recuerdos.FAR_NODES);
                    de = "un camino sin cavar";
                    if (way.isEmpty()) {
                        intentos.add("un camino largo sin cavar: nada en " + Recuerdos.FAR_NODES + " celdas");
                    }
                }
            } else if (way.isEmpty()) {
                way = r.caminoHacia(world, feet, to, 1);
                de = "por donde ya pasó";
                if (way.isEmpty()) {
                    way = r.caminoA(world, feet, (pos, p) -> pos.isWithinDistance(to, 1.5), to, vista, pos -> pos.isWithinDistance(to, 1.5),
                            Recuerdos.FAR_NODES);
                    de = "un camino sin cavar";
                }
            }
        } catch (RuntimeException e) {
            MCA.LOGGER.warn("[Tropeles] {} no pudo pensar en un camino sin cavar: {}", name(), e.toString());
            way = List.of();
        }
        if (way.isEmpty()) {
            return false;
        }
        setVia(world, villager, feet, way, to, de);
        return true;
    }

    /** Its own trail from where it stands back to the door of its mine, every crumb still open; empty when it is not on it. */
    private List<BlockPos> trailWay(ServerWorld world, BlockPos feet) {
        int i = mina.nearLow(world, feet);
        if (i < 0 || mina.entrance() == null) {
            return List.of();
        }
        List<BlockPos> way = new ArrayList<>();
        for (int k = feet.equals(mina.crumb(i)) ? i - 1 : i; k >= 0; k--) {
            if (!mina.open(world, k)) {
                return List.of(); // something fell in or was built there: another way
            }
            way.add(mina.crumb(k));
        }
        return way;
    }

    /** Its staircase out on foot (to the door of its mine), as the way it walks now. True when there is one. */
    private boolean salidaAPie(ServerWorld world, VillagerEntityMCA villager, BlockPos feet) {
        BlockPos door = salida(world, villager);
        // a staircase its legs failed on over and over is not tried for ever: its own steps then
        return door != null && !door.equals(feet) && viaFails < VIA_TRIES + 3 && pieA(world, villager, feet, door, 0.5, null, "su escalera, a pie");
    }

    /** Where out is for a way on foot: the door of its mine, else the mouth of a mine it remembers. Null when it knows neither. */
    @Nullable
    private BlockPos salida(ServerWorld world, VillagerEntityMCA villager) {
        BlockPos door = mina.entrance();
        if (door == null) {
            door = Recuerdos.of(world, villager).sitio("mina");
        }
        return door != null && door.isWithinDistance(villager.getBlockPos(), RESUME_DISTANCE) ? door : null;
    }

    /**
     * A way on foot from the feet to {@code goal} (within {@code within} blocks) over what is there now, never digging or
     * placing ({@link Motor#findWalk}: level, up a one-high step with room for the head, off a drop of at most three, a
     * ladder), through the cells it knows: cells of its mine, cells it walked, the cells touching those, and cells whose
     * floor its eye saw. The feet first, then every cell, each next to the one before on one axis; null when there is no
     * such way. This is a player who knows the staircase is there and walks it, instead of cutting another one beside it.
     */
    @Nullable
    private List<BlockPos> aPie(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, BlockPos goal, double within) {
        long now = world.getTime();
        if (feet.equals(pieNadaDe) && goal.equals(pieNadaA) && now - pieNadaAt < 100) {
            return null; // the same question a moment ago from the same cell: the same answer
        }
        Recuerdos r = Recuerdos.of(world, villager);
        VisualMemory vista = Retina.memory(villager);
        Map<Long, Boolean> cache = new HashMap<>();
        Predicate<BlockPos> conocida = c -> cache.computeIfAbsent(c.asLong(), k -> conoce(r, vista, c));
        int nodes = (int) Math.min(16000, 3000 + 60 * Math.sqrt(feet.getSquaredDistance(goal)));
        List<BlockPos> way;
        try {
            way = Motor.findWalk(world, feet, goal, within, nodes, conocida);
        } catch (RuntimeException e) {
            MCA.LOGGER.warn("[Tropeles] {} no pudo pensar en un camino a pie: {}", name(), e.toString());
            way = null;
        }
        if (way == null) {
            pieNadaDe = feet.toImmutable();
            pieNadaA = goal.toImmutable();
            pieNadaAt = now;
            MCA.LOGGER.info("[Tropeles] {} no ve paso a pie de {} a {} ({} celdas pensadas)", name(), feet.toShortString(), goal.toShortString(), nodes);
        }
        return way;
    }

    /** A cell it knows: of its mine, walked, touching one of those, or with a floor its eye saw. */
    private boolean conoce(Recuerdos r, VisualMemory vista, BlockPos c) {
        if (mina.has(c) || r.recuerda(c)) {
            return true;
        }
        BlockState floor = vista.stateAt(c.down());
        if (floor != null && !floor.isAir()) {
            return true;
        }
        for (Direction d : Direction.values()) {
            BlockPos n = c.offset(d);
            if (mina.has(n) || r.recuerda(n)) {
                return true;
            }
        }
        return false;
    }

    /** That way on foot ({@link #aPie}) is the one it walks now, to {@code to} (null: out). True when there is one. */
    private boolean pieA(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, BlockPos goal, double within, @Nullable BlockPos to, String de) {
        List<BlockPos> way = aPie(world, villager, feet, goal, within);
        if (way == null || way.size() < 2) {
            return false;
        }
        setVia(world, villager, feet, way.subList(1, way.size()), to, de);
        MCA.LOGGER.info("[Tropeles] {} va por {} ({}): {} pasos sin cavar de {} a {}", name(), de, phase, way.size() - 1, feet.toShortString(),
                goal.toShortString());
        return true;
    }

    /**
     * One tick along the way it walks, never breaking or placing anything: the body's walker over the whole way, and
     * when that gives up, the step machine cell by cell (still no digging, no blocks) before the way counts as shut.
     */
    private Motor.Result walkVia(ServerWorld world, VillagerEntityMCA villager, BlockPos goal) {
        if (!viaPaso) {
            Motor.Result r = motor.walkPath(world, villager, viaLista);
            if (r == Motor.Result.BLOCKED) {
                viaPaso = true;
                if (!motor.midStep(villager)) {
                    motor.reset(world, villager);
                }
                MCA.LOGGER.info("[Tropeles] {} no sigue {} de corrido en {}: paso a paso, sin cavar", name(), viaDe, villager.getBlockPos().toShortString());
                return Motor.Result.RUNNING;
            }
            if (r == Motor.Result.ARRIVED) {
                via.clear();
            }
            return r;
        }
        return motor.step(world, villager, goal, false, false);
    }

    /** The next cell of the remembered way: cells it already stands on or passed are dropped. Null at its end. */
    @Nullable
    private BlockPos nextVia(BlockPos feet) {
        int k = 0;
        int at = -1;
        for (BlockPos cell : via) {
            if (cell.equals(feet)) {
                at = k;
            }
            k++;
        }
        for (int j = 0; j <= at; j++) {
            via.pollFirst();
        }
        return via.peekFirst();
    }

    private void endVia() {
        via.clear();
        viaMemoria = false;
        viaDestino = null;
        viaLista = List.of();
        viaPaso = false;
    }

    /** A cell of the remembered way is shut now: skipped for a while, and another remembered way, or none. */
    private void viaBlocked(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, BlockPos cell, long now) {
        // a cell that is still there to stand in (the legs got nowhere for a moment, someone stood in the way) is not
        // shut: the next look finds the same staircase again, walked afresh from where it stands
        boolean shut = !Motor.standCell(world, cell);
        if (shut) {
            Recuerdos.of(world, villager).cerrar(cell, now + 6000);
        }
        BlockPos to = viaDestino;
        String was = viaDe;
        endVia();
        MCA.LOGGER.info("[Tropeles] {} no sigue por {}: {} {} (vez {} de {})", name(), was, cell.toShortString(), shut ? "está cerrado" : "sigue abierto, no avanzó",
                viaFails + 1, VIA_TRIES);
        intentos.add(was + ": " + (shut ? "cerrado en " : "no avanzó en ") + cell.toShortString());
        if (++viaFails < VIA_TRIES && buscarVia(world, villager, feet, to)) {
            return;
        }
        if (phase == Phase.HOME_UP) {
            Voluntad.contar(villager, "Por donde vine está tapado. Cavo una escalera nueva hacia arriba.",
                    "The way I came is blocked. I'll dig a new staircase up.",
                    intentos.isEmpty() ? null : "probé: " + String.join("; ", intentos));
            startFallback(world, villager, feet, mina.nearLow(world, feet));
        }
    }

    private void startFallback(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, int fromIdx) {
        if (!fallback && !trailDig && !trailDigTried && phase == Phase.HOME_UP && startTrailDig(world, villager, feet)) {
            return; // its own trail, opened where it is shut, before a staircase of its own
        }
        trailDig = false;
        if (!fallback) {
            MCA.LOGGER.info("[Tropeles] {} no recuerda otra salida desde {}: cava su propia escalera (probó: {})", name(), feet.toShortString(),
                    intentos.isEmpty() ? "nada" : String.join("; ", intentos));
            Voluntad.contar(villager, "No me acuerdo de otra salida desde aquí, así que cavo una escalera hacia arriba.",
                    "I don't remember another way out from here, so I'll dig a staircase up.",
                    intentos.isEmpty() ? null : "probé: " + String.join("; ", intentos));
        }
        endVia();
        fallback = true;
        climbFromIdx = fromIdx;
        climb.clear();
        plan.clear();
        climbMode = preferredClimbMode(world, villager, feet);
        climbFails = 0;
        // height is the measure now, not the trail; the rounds already lost still count
        bestProgress = Double.NEGATIVE_INFINITY;
        stuckTicks = 0;
    }

    /**
     * No way out on foot, but it stands on (or by) its own trail: up that, backwards, crumb by crumb, digging only what
     * shut the trail's own cells (the ceiling cell a jump up the next step needs, gravel that came down, its own table
     * left on a step), like a player who clears his own staircase instead of cutting a second one beside it. True when
     * it goes that way now.
     */
    private boolean startTrailDig(ServerWorld world, VillagerEntityMCA villager, BlockPos feet) {
        trailDigTried = true;
        if (mina.entrance() == null || mina.size() < 2) {
            return false;
        }
        int at = mina.nearLowAny(feet);
        if (at <= 0) {
            return false;
        }
        BlockPos shut = null;
        for (int k = at; k >= 0 && shut == null; k--) {
            BlockPos c = mina.crumb(k);
            if (!mina.open(world, k) || k > 0 && !Mining.passable(world, c.up(2)) && mina.crumb(k - 1).getY() > c.getY()) {
                shut = c;
            }
        }
        endVia();
        plan.clear();
        if (!motor.midStep(villager)) {
            motor.reset(world, villager);
        }
        trailDig = true;
        trailDigIdx = at;
        trailDigFails = 0;
        bestProgress = Double.NEGATIVE_INFINITY;
        stuckTicks = 0;
        String probo = intentos.isEmpty() ? "nada" : String.join("; ", intentos);
        MCA.LOGGER.info("[Tropeles] {} sube por su propio camino desde {} ({} pasos hasta la boca {}), abriendo solo sus celdas{}: no cava otra escalera (probó: {})",
                name(), feet.toShortString(), at, mina.entrance().toShortString(),
                shut == null ? "" : " (cerrado en " + shut.toShortString() + ")", probo);
        Voluntad.contar(villager, "Subo por mi propio camino; donde se tapó lo abro, no hace falta otra escalera.",
                "I'll go up my own way; where it's blocked I'll clear it, no need for another staircase.",
                "no había paso a pie sin cavar (" + probo + ")" + (shut == null ? "" : "; mi camino estaba tapado en " + shut.toShortString()));
        return true;
    }

    /** One tick up its own trail that way ({@link #startTrailDig}). */
    private void trailDigStep(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, VisualMemory memory, long now) {
        if (!motor.midStep(villager)) {
            int here = mina.indexOf(feet);
            if (here >= 0 && here < trailDigIdx) {
                trailDigIdx = here;
            } else if (here < 0 && mina.nearLowAny(feet) < 0 && !feet.isWithinDistance(mina.crumb(Math.min(trailDigIdx, mina.size() - 1)), 2.5)) {
                // pushed or fallen off its trail: the usual ways out from where it is now
                MCA.LOGGER.info("[Tropeles] {} se salió de su camino en {}: busca otra forma de subir", name(), feet.toShortString());
                trailDig = false;
                wayOut(world, villager, feet, mina.nearLow(world, feet));
                return;
            }
        }
        int idx = Math.min(trailDigIdx, mina.size() - 1);
        int next = feet.equals(mina.crumb(idx)) ? idx - 1 : idx;
        if (next < 0) {
            trailDig = false; // at the door of its mine: out
            return;
        }
        BlockPos goal = mina.crumb(next);
        memory.doing("subiendo por su propio camino, abriéndolo donde se tapó (" + next + " pasos)", now);
        Motor.Result result = motor.stepWithin(world, villager, goal, true, trailCells);
        if (result == Motor.Result.ARRIVED) {
            trailDigIdx = next;
            trailDigFails = 0;
        } else if (result == Motor.Result.BLOCKED) {
            motor.reset(world, villager);
            if (++trailDigFails > 4) {
                MCA.LOGGER.info("[Tropeles] {} no puede abrir su camino en {} (hacia {}): cava su propia escalera", name(), feet.toShortString(), goal.toShortString());
                intentos.add("su propio camino, abriéndolo: no pasa de " + feet.toShortString());
                trailDig = false;
                startFallback(world, villager, feet, mina.nearLow(world, feet));
            }
        }
    }

    /** Where the next leg of the climb out aims: three up, and three toward home, to one side of it, or away. */
    private BlockPos climbGoal(VillagerEntityMCA villager, BlockPos feet) {
        VisualMemory memory = Retina.memory(villager);
        for (int k = 0; k < 5; k++) {
            if (climbMode == 0) {
                if (!Mining.lavaSeen(memory, feet.up(3)) && !Mining.lavaSeen(memory, feet.up(4))) {
                    return feet.up(3);
                }
            } else {
                Direction toward = Direction.fromHorizontal(homeDir);
                Direction way;
                if (climbMode == 1) {
                    way = toward;
                } else if (climbMode == 2) {
                    way = toward.rotateYClockwise();
                } else if (climbMode == 3) {
                    way = toward.rotateYCounterclockwise();
                } else {
                    way = toward.getOpposite();
                }
                BlockPos first = feet.offset(way).up();
                // the eye saw lava or water that way: the next way round
                if (!Mining.lavaSeen(memory, first) && !Mining.lavaSeen(memory, first.up()) && !Agua.seenNear(memory, first.up())) {
                    return feet.offset(way, 3).up(3);
                }
            }
            climbMode = (climbMode + 1) % 5;
        }
        return feet.up(3);
    }

    /**
     * Straight up on its own blocks in an open shaft; anywhere else a staircase toward home, which it can walk back
     * down later (a pillar fills the cells it stood on).
     */
    private static int preferredClimbMode(ServerWorld world, VillagerEntityMCA villager, BlockPos feet) {
        return openShaft(world, feet) && Hands.buildingBlockCount(villager) >= 4 ? 0 : 1;
    }

    /**
     * A one-wide shaft going up from here: walls all round at head height and room above for a while (water in it
     * too: the Motor swims up and spams blocks under its feet, and still cuts stairs when the column is not safe).
     */
    private static boolean openShaft(ServerWorld world, BlockPos feet) {
        BlockPos head = feet.up();
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            if (Mining.passable(world, head.offset(dir))) {
                return false;
            }
        }
        int open = 0;
        while (open < 8 && Mining.passable(world, feet.up(open + 1))) {
            open++;
        }
        return open >= 4;
    }

    /** Boxed in on the way up: it says where it is (once a minute at most) so someone can come and get it out. */
    private void callOutStuck(ServerWorld world) {
        long now = world.getTime();
        if (now - lastStuckCall < 1200) {
            return;
        }
        lastStuckCall = now;
        BlockPos pos = villager.getBlockPos();
        say("chore.mining.stuck", pos.getX(), pos.getY(), pos.getZ());
        getAssigningPlayer().ifPresent(assigner -> DistressCalls.raise(world, villager, assigner.getUuid()));
    }

    /** Out in the open: the way it climbed becomes the way in, then home, the furnace, the woods or a hunt. */
    private void surfaced(ServerWorld world, VillagerEntityMCA villager) {
        motor.reset(world, villager);
        plan.clear();
        BlockPos feet = villager.getBlockPos();
        if (fallback && !climb.isEmpty() && mina.rebase(world, climb, climbFromIdx)) {
            MCA.LOGGER.info("[Tropeles] {} guarda la escalera por la que salió como camino a su mina ({} pasos)", name(), mina.size());
            // the foot and the head of the staircase it cut are marks on its way, like its torches
            Recuerdos.marcar(villager, Recuerdos.ESCALERA, climb.get(0));
            Recuerdos.marcar(villager, Recuerdos.ENTRADA, mina.crumb(0));
        }
        fallback = false;
        climb.clear();
        endVia();
        saveMine(world, villager);
        homeTicks = 0;
        lostTicks = 0;
        resetWatch();
        BlockPos target = homePos(world, villager);
        Recuerdos.anotar(villager, "viaje", "salí a la superficie en " + feet.getX() + " " + feet.getY() + " " + feet.getZ() + " ("
                + Entorno.lugar(world, villager).palabra() + ")", feet);
        if (camping) {
            if (mina.entrance() == null) {
                homeTarget = feet.toImmutable();
                target = homeTarget;
            }
            if (!target.equals(campSaid)) {
                // said once for each camp, not on every trip up
                campSaid = target.toImmutable();
                say("chore.mining.camp", target.getX(), target.getY(), target.getZ());
            }
        }
        MCA.LOGGER.info("[Tropeles] {} salió a la superficie en {} ({})", name(), feet.toShortString(), trip);
        switch (trip) {
            case PICK -> {
                surfaceTicks = 0;
                progreso.wantKit();
                phase = Phase.SURFACE_WORK;
            }
            case FOOD -> {
                if (!camping) {
                    enterHomeWalk(); // its chest is where the food is
                } else if (!world.isNight() && foodKnown(world, villager)) {
                    say("chore.mining.forage");
                    startForage();
                } else {
                    finishHome(world, villager, 0);
                }
            }
            default -> enterHomeWalk();
        }
    }

    private void enterHomeWalk() {
        phase = Phase.HOME_WALK;
        homeTicks = 0;
        plan.clear();
        resetWatch();
    }

    private void enterHomeStore() {
        phase = Phase.HOME_STORE;
        homeTicks = 0;
        stored = false;
        storedCount = 0;
        placeTicks = 0;
        homeChest = null;
        foodChest = null;
        resetWatch();
    }

    private void walkHome(ServerWorld world, VillagerEntityMCA villager, BlockPos target, BlockPos feet, VisualMemory memory, long now) {
        double dx = target.getX() + 0.5 - villager.getX();
        double dz = target.getZ() + 0.5 - villager.getZ();
        int dy = target.getY() - feet.getY();
        // a dig-out leg under way is finished before the path finder gets the legs back
        boolean digging = motor.busy() || !plan.isEmpty();
        // home is at most a hop up: still down in a hole or a cut under it is not there yet (the ledge climb below)
        if (!digging && dx * dx + dz * dz <= 16 && dy <= 1 && dy >= -3 && villager.isOnGround()) {
            villager.getNavigation().stop();
            enterHomeStore();
            return;
        }
        memory.doing(String.format("volviendo a casa, a %.0f bloques", Math.sqrt(dx * dx + dz * dz)), now);
        if (viaMemoria && plan.isEmpty()) {
            // a way on foot it worked out (up its own steps, round a ledge): walked, never dug
            BlockPos next = nextVia(feet);
            if (next == null) {
                endVia();
            } else {
                villager.getNavigation().stop();
                Motor.Result r = walkVia(world, villager, next);
                if (r == Motor.Result.ARRIVED && next.equals(via.peekFirst())) {
                    via.pollFirst();
                } else if (r == Motor.Result.BLOCKED) {
                    motor.reset(world, villager);
                    viaBlocked(world, villager, feet, next, now);
                }
                return;
            }
        }
        if (!digging) {
            if (ledgeFails < 3 && dy >= 1 && dy <= 3 && dx * dx + dz * dz < 64) {
                // home is up a ledge it cannot walk: a way up it knows on foot first, else climb to it the way it climbs
                // out of a mine
                villager.getNavigation().stop();
                if (!pieA(world, villager, feet, target, 1.5, target, "un camino a pie hasta casa")) {
                    MCA.LOGGER.info("[Tropeles] {} cava un escalón hasta su casa en {}: no hay paso a pie desde {}", name(), target.toShortString(), feet.toShortString());
                    plan.add(target);
                }
            } else if (villager.getNavigation().isIdle() && homeTicks > 100 && dx * dx + dz * dz < 64 * 64
                    && !villager.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.7)) {
                // no path on foot at all for the path finder: a way on foot over what it knows, else make one, the way it
                // got out, one goal until it gets there or gives it up
                if (!pieA(world, villager, feet, target, 2.0, target, "un camino a pie hasta casa")) {
                    BlockPos goal = feet.offset(Direction.getFacing(dx, 0, dz), 2);
                    MCA.LOGGER.info("[Tropeles] {} cava hacia su casa desde {}: ni el buscador de caminos ni sus recuerdos tienen paso a pie", name(),
                            feet.toShortString());
                    plan.add(legGoal(world, villager, feet, goal));
                }
            } else if ((homeTicks % 40) == 1 || villager.getNavigation().isIdle()) {
                villager.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.7);
            }
        }
        if (!plan.isEmpty()) {
            BlockPos goal = plan.peekFirst();
            Motor.Result result = motor.step(world, villager, goal, true, true);
            if (result != Motor.Result.RUNNING) {
                plan.clear();
                if (result == Motor.Result.BLOCKED) {
                    motor.reset(world, villager);
                    if (goal.equals(target)) {
                        ledgeFails++;
                    }
                }
            }
        }
    }

    /** The first stack it would put away (what it does not keep on it), or empty. */
    private static ItemStack firstToStore(VillagerEntityMCA villager) {
        int[] buildingStacks = {0};
        SimpleInventory inv = villager.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && !keep(stack, buildingStacks)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** At home: its haul into a chest with room (one it puts down or makes when none has), then food for the way. */
    private void store(ServerWorld world, VillagerEntityMCA villager, BlockPos target, VisualMemory memory, long now) {
        if (!stored) {
            ItemStack first = firstToStore(villager);
            if (first.isEmpty()) {
                stored = true;
                homeChest = null;
                return;
            }
            if (homeChest == null || !(world.getBlockState(homeChest).getBlock() instanceof ChestBlock)) {
                homeChest = null;
                for (BlockPos pos : Cofres.own(world, villager, target)) {
                    if (pos.isWithinDistance(target, Cofres.HOME_RANGE) && Cofres.roomFor(world, pos, first)) {
                        homeChest = pos;
                        break;
                    }
                }
            }
            if (homeChest == null) {
                // no chest of its own with room here: one from the bag, or make one
                if (chestsMade < 2 && villager.getInventory().count(Items.CHEST) > 0) {
                    memory.doing("poniendo su cofre", now);
                    placeTicks++;
                    double hx = target.getX() + 0.5 - villager.getX();
                    double hz = target.getZ() + 0.5 - villager.getZ();
                    if (hx * hx + hz * hz > 64 && placeTicks <= 400) {
                        // it went off for the wood to make it: the chest goes down at home, not by the tree
                        if ((placeTicks % 20) == 1 || villager.getNavigation().isIdle()) {
                            villager.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
                        }
                        return;
                    }
                    // never in the way into its mine: not on a cell of its trail, nor over one
                    BlockPos placed = Progreso.placeBeside(world, villager, Cofres::isChestItem,
                            cell -> mina.has(cell) || mina.has(cell.down()) || mina.has(cell.down(2)));
                    if (placed != null) {
                        Cofres.claim(world, villager, placed);
                        homeChest = placed;
                        chestsMade++;
                        Recuerdos.sitio(villager, "cofre", placed);
                        Recuerdos.marcar(villager, "cofre", placed);
                        Recuerdos.anotar(villager, "cofre", "puse mi cofre en " + placed.getX() + " " + placed.getY() + " " + placed.getZ(), placed);
                        return;
                    }
                    if (placeTicks > 1000) {
                        // nowhere to set it down around here: it keeps its things on it this time
                        MCA.LOGGER.info("[Tropeles] {} no encuentra dónde poner su cofre en {}", name(), villager.getBlockPos().toShortString());
                        stored = true;
                    } else if ((placeTicks % 60) == 1) {
                        // a cramped spot: a few steps to one side and try again
                        Vec3d aside = FuzzyTargeting.find(villager, 4, 2);
                        if (aside != null) {
                            villager.getNavigation().startMovingTo(aside.x, aside.y, aside.z, 0.6);
                        }
                    }
                    return;
                }
                if (chestsMade < 2) {
                    progreso.wantChest();
                    if (gearUp(world, villager)) {
                        return;
                    }
                }
                if (chestsMade >= 2 || homeTicks > 2400
                        || villager.getInventory().count(Items.CHEST) == 0 && !Progreso.canMakeChest(villager.getInventory()) && progreso.idle() && homeTicks > 200) {
                    say("chore.mining.nochest");
                    stored = true;
                }
                return;
            }
            if (Retina.visibleFace(world, villager, homeChest, Retina.BLOCK_REACH) == null) {
                memory.doing("yendo a su cofre", now);
                if ((homeTicks % 20) == 1 || villager.getNavigation().isIdle()) {
                    villager.getNavigation().startMovingTo(homeChest.getX() + 0.5, homeChest.getY(), homeChest.getZ() + 0.5, 0.6);
                }
                if (homeTicks > 1200) {
                    homeChest = null;
                    stored = true;
                }
                return;
            }
            villager.getNavigation().stop();
            int[] buildingStacks = {0};
            int moved = Cofres.store(world, villager, homeChest, stack -> keep(stack, buildingStacks));
            storedCount += moved;
            Recuerdos.sitio(villager, "cofre", homeChest);
            if (moved > 0) {
                Recuerdos.anotar(villager, "cofre", "guardé " + moved + " cosas en mi cofre en " + homeChest.getX() + " " + homeChest.getY() + " "
                        + homeChest.getZ(), homeChest);
            }
            memory.doing("guardó " + moved + " cosas en su cofre", now);
            tidyBag(world, villager, true);
            ItemStack left = firstToStore(villager);
            if (!left.isEmpty() && !Cofres.roomFor(world, homeChest, left)) {
                // that chest is full: the next one, or a new one beside it
                homeChest = null;
                homeTicks = 0;
                return;
            }
            stored = true;
            homeChest = null;
            homeTicks = 0;
            return;
        }
        // food for the way down
        if (foodPoints(villager.getInventory()) < 12) {
            if (foodChest == null || !(world.getBlockState(foodChest).getBlock() instanceof ChestBlock)) {
                foodChest = Cofres.withFood(world, villager, target, Cofres.HOME_RANGE, ProspectingTask::edible);
                if (foodChest == null) {
                    finishHome(world, villager, storedCount);
                    return;
                }
            }
            if (Retina.visibleFace(world, villager, foodChest, Retina.BLOCK_REACH) == null) {
                memory.doing("yendo a buscar comida a su cofre", now);
                if ((homeTicks % 20) == 1 || villager.getNavigation().isIdle()) {
                    villager.getNavigation().startMovingTo(foodChest.getX() + 0.5, foodChest.getY(), foodChest.getZ() + 0.5, 0.6);
                }
                if (homeTicks > 1200) {
                    finishHome(world, villager, storedCount);
                }
                return;
            }
            villager.getNavigation().stop();
            int taken = Cofres.take(world, villager, foodChest, ProspectingTask::edible, 8);
            memory.doing("sacó " + taken + " de comida de su cofre", now);
            if (taken > 0) {
                Recuerdos.anotar(villager, "cofre", "saqué " + taken + " de comida de mi cofre en " + foodChest.getX() + " " + foodChest.getY() + " "
                        + foodChest.getZ(), foodChest);
            }
        }
        finishHome(world, villager, storedCount);
    }

    private void finishHome(ServerWorld world, VillagerEntityMCA villager, int storedHere) {
        progreso.forgetChest();
        if (storedHere > 0) {
            say("chore.mining.stored", storedHere);
        }
        // eat what it found before going back down
        for (int k = 0; k < 8 && food < FOOD_MAX - 2 && eatBest(world, villager); k++) {
            // one bite after the other, like a player holding the button
        }
        if (!motor.midStep(villager)) {
            motor.reset(world, villager);
        }
        plan.clear();
        homeChest = null;
        foodChest = null;
        stored = false;
        storedCount = 0;
        long now = world.getTime();
        nextBagCheck = now + 2400;
        boolean noFood = foodPoints(villager.getInventory()) == 0;
        if (trip == Trip.FOOD && noFood && food < EAT_BELOW) {
            nextFoodTrip = now + 6000;
            // starving and ripe berries in sight right here: those too (never instead of a night's sleep, never a blind
            // search that wanders back into its mine); otherwise only food it knows of, as before
            if (!foraged && !world.isNight() && (foodKnown(world, villager)
                    || food <= GO_HOME_BELOW && !wantsSleep(world, villager) && Mining.onSurface(world, villager.getBlockPos())
                    && seenBerries(villager, Retina.memory(villager)) != null)) {
                say("chore.mining.forage");
                startForage();
                return;
            }
            if (food <= GO_HOME_BELOW) {
                // nothing to eat at home either: a player does not log off for that. It stays connected, goes on (a
                // night's sleep first when it is dark) and looks for food again in a while
                noFoodAtHome(world, villager);
            }
        }
        if (trip == Trip.CALLED) {
            phase = Phase.REST;
            restTicks = 0;
            progreso.wantKit();
            say("chore.mining.waiting");
            return;
        }
        if (wantsSleep(world, villager)) {
            startSleep(world, villager);
            return;
        }
        beginReturn(world, villager);
    }

    /** No food in the bag nor at home: it says so and goes on, connected; the next food trip waits a while. */
    private void noFoodAtHome(ServerWorld world, VillagerEntityMCA villager) {
        nextFoodTrip = world.getTime() + 6000;
        foraged = true;
        MCA.LOGGER.info("[Tropeles] {} no tiene nada que comer ni en casa (comida {}): sigue conectado y vuelve a lo suyo", name(), food);
        Recuerdos.anotar(villager, "viaje", "no había comida ni en casa; seguí con lo mío", villager.getBlockPos());
        Voluntad.contar(villager, "En casa tampoco hay comida. Sigo con lo mío y busco algo de comer más tarde.",
                "There's no food at home either. I'll carry on and look for something to eat later.");
    }

    /** What a miner keeps on it: tools, weapons, arrows, light, food, crafting stuff, fuel, iron to smelt and some blocks. */
    private static boolean keep(ItemStack stack, int[] buildingStacks) {
        if (stack.getItem() instanceof net.minecraft.item.ToolItem || stack.getItem() instanceof net.minecraft.item.RangedWeaponItem
                || stack.getItem() instanceof net.minecraft.item.ArmorItem || stack.getItem() instanceof net.minecraft.item.ArrowItem
                || stack.isOf(Items.TORCH) || edible(stack) || Progreso.rawFood(stack) || stack.isOf(Items.CRAFTING_TABLE) || stack.isOf(Items.FURNACE)
                || stack.isOf(Items.CHEST) || stack.isOf(Items.STICK) || stack.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)
                || stack.isIn(net.minecraft.registry.tag.ItemTags.LOGS) || stack.isOf(Items.COAL) || stack.isOf(Items.CHARCOAL)
                || stack.isOf(Items.RAW_IRON) || stack.isOf(Items.IRON_INGOT) || stack.isOf(Items.DIAMOND)) {
            return true;
        }
        if (Hands.isBuildingBlock(stack) && buildingStacks[0] < 2) {
            buildingStacks[0]++;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ a chest wherever it is

    /**
     * The bag is (nearly) full away from home: like a player it sets a chest down right there instead of walking all
     * the way back. Junk first ({@link #junkPlan}: loose rock and soil beyond {@value #JUNK_KEEP} building blocks,
     * rotten flesh and such) into a chest it leaves behind and forgets; with the bag still full of its haul, into a
     * field chest of its own, remembered by name ({@link Recuerdos#COFRE_CAMPO}) and filled again the next time it is
     * close. It needs a chest in the bag, or the wood to make one (8 planks and a table, at the table it sets down like
     * for a pickaxe). True when it starts on that now.
     */
    private boolean campoStart(ServerWorld world, VillagerEntityMCA villager) {
        SimpleInventory inv = villager.getInventory();
        int free = freeSlots(inv);
        if (free >= 3) {
            return false;
        }
        boolean chest = inv.count(Items.CHEST) > 0;
        int junk = junkCount(inv);
        boolean canMake = chestWood(inv, 0) && (free >= 2 || junk > 0);
        // a chest only for junk is not worth the wood for its next pickaxe: that much more wood, or the junk goes on the ground
        if (junk >= 64 && (chest || chestWood(inv, 6) && (free >= 2 || junk > 0) || basuraCerca(world, villager) != null)) {
            startCampo(world, villager, Campo.BASURA);
            return true;
        }
        ItemStack first = firstToStore(villager);
        if (free <= 2 && !first.isEmpty()) {
            BlockPos target = homePos(world, villager);
            if (!camping && target.isWithinDistance(villager.getBlockPos(), Cofres.HOME_RANGE)) {
                return false; // home is right here: its chest at home
            }
            if (chest || canMake || campoCerca(world, villager, first) != null) {
                startCampo(world, villager, Campo.GUARDAR);
                return true;
            }
        }
        return false;
    }

    private void startCampo(ServerWorld world, VillagerEntityMCA villager, Campo kind) {
        campo = kind;
        campoCofre = null;
        campoTicks = 0;
        campoWalk = 0;
        nicho = null;
        nichoFails = 0;
        nichoBreaker.reset(world, villager);
        if (!motor.midStep(villager)) {
            motor.reset(world, villager);
        }
        plan.clear();
        villager.getNavigation().stop();
        resetWatch();
        SimpleInventory inv = villager.getInventory();
        boolean known = kind == Campo.BASURA ? basuraCerca(world, villager) != null : campoCerca(world, villager, firstToStore(villager)) != null;
        if (!known && inv.count(Items.CHEST) == 0 && chestWood(inv, kind == Campo.BASURA ? 6 : 0) && freeSlots(inv) >= 2) {
            // the wood is for the chest from now on: nothing else gets it first
            progreso.wantChest();
        }
        BlockPos feet = villager.getBlockPos();
        if (kind == Campo.BASURA) {
            MCA.LOGGER.info("[Tropeles] {} tiene la mochila llena de cosas que no le sirven ({} de basura) en {}: las deja en un cofre ahí mismo",
                    name(), junkCount(villager.getInventory()), feet.toShortString());
            Voluntad.contar(villager, "Tengo la mochila llena de piedras y cosas que no me sirven. Las dejo en un cofre aquí mismo.",
                    "My bag is full of rocks and junk I don't need. I'll leave them in a chest right here.");
        } else {
            MCA.LOGGER.info("[Tropeles] {} tiene la mochila llena lejos de casa en {} ({}): guarda lo suyo en un cofre de campo", name(),
                    feet.toShortString(), Entorno.lugar(world, villager).palabra());
            Voluntad.contar(villager, "Tengo la mochila llena y mi casa está lejos. Guardo lo mío en un cofre aquí y sigo trabajando.",
                    "My bag is full and home is far. I'll store my things in a chest here and keep working.");
        }
    }

    private void endCampo(ServerWorld world, VillagerEntityMCA villager, long until) {
        campo = Campo.NADA;
        campoCofre = null;
        nicho = null;
        nichoBreaker.reset(world, villager);
        nextCampo = until;
        progreso.forgetChest();
        resetWatch();
    }

    /** One tick of setting a chest down where it is and filling it. True while that has the body. */
    private boolean campoTick(ServerWorld world, VillagerEntityMCA villager) {
        long now = world.getTime();
        SimpleInventory inv = villager.getInventory();
        VisualMemory memory = Retina.memory(villager);
        BlockPos feet = villager.getBlockPos();
        boolean basuraNow = campo == Campo.BASURA;
        if (++campoTicks > CAMPO_TICKS) {
            MCA.LOGGER.info("[Tropeles] {} no logró dejar sus cosas en un cofre en {} ticks: sigue como antes", name(), CAMPO_TICKS);
            endCampo(world, villager, now + 2400);
            return false;
        }
        if (campoCofre != null && !(world.getBlockState(campoCofre).getBlock() instanceof ChestBlock)) {
            campoCofre = null;
        }
        if (campoCofre == null) {
            campoCofre = basuraNow ? basuraCerca(world, villager) : campoCerca(world, villager, firstToStore(villager));
            campoWalk = 0;
            if (campoCofre != null && !basuraNow) {
                MCA.LOGGER.info("[Tropeles] {} vuelve a su {} en {}", name(), Recuerdos.of(world, villager).nombreCofreDeCampo(campoCofre), campoCofre.toShortString());
            }
        }
        if (campoCofre == null) {
            if (inv.count(Items.CHEST) == 0) {
                if (!progreso.wantsChest()) {
                    if (!chestWood(inv, basuraNow ? 6 : 0) || freeSlots(inv) < 2 && !tossJunk(world, villager)) {
                        MCA.LOGGER.info("[Tropeles] {} no tiene cofre ni con qué hacer uno", name());
                        endCampo(world, villager, now + 2400);
                        return false;
                    }
                    // the table and the chest are made the way it makes a pickaxe (gearUp, at the top of the dig)
                    progreso.wantChest();
                } else if (progreso.idle() && campoTicks > 100) {
                    MCA.LOGGER.info("[Tropeles] {} no pudo hacer un cofre aquí", name());
                    endCampo(world, villager, now + 2400);
                    return false;
                }
                memory.doing(basuraNow ? "haciendo un cofre para la basura" : "haciendo un cofre para guardar lo que lleva", now);
                return true;
            }
            progreso.forgetChest();
            BlockPos placed = placeCampo(world, villager, feet, memory, now);
            if (placed == null) {
                return true; // a niche being cut, or nowhere yet: it keeps trying until its time is up
            }
            Lugar lugar = Entorno.lugar(world, villager);
            if (basuraNow) {
                basura = placed.toImmutable();
                Recuerdos.anotar(villager, "cofre", "dejé un cofre para la basura en " + placed.getX() + " " + placed.getY() + " " + placed.getZ()
                        + " (" + lugar.palabra() + ") y me olvido de él", placed);
                MCA.LOGGER.info("[Tropeles] {} pone un cofre para la basura en {} ({}): no es suyo, lo deja ahí", name(), placed.toShortString(), lugar.palabra());
            } else {
                Cofres.claim(world, villager, placed);
                Recuerdos r = Recuerdos.of(world, villager);
                String nombre = r.nombreCofreDeCampo(placed);
                r.sitio(nombre, placed, now);
                r.marcar("cofre", placed, now);
                r.anotar(now, "cofre", "puse mi " + nombre + " en " + placed.getX() + " " + placed.getY() + " " + placed.getZ() + " (" + lugar.palabra()
                        + "), lejos de casa, para vaciar la mochila", placed);
                MCA.LOGGER.info("[Tropeles] {} pone su {} en {} ({})", name(), nombre, placed.toShortString(), lugar.palabra());
            }
            campoCofre = placed.toImmutable();
            campoWalk = 0;
        }
        if (Retina.visibleFace(world, villager, campoCofre, Retina.BLOCK_REACH) == null) {
            if (++campoWalk > 400) {
                // it cannot get to that one: another, or a new one
                campoLleno.add(campoCofre.asLong());
                campoCofre = null;
                return true;
            }
            memory.doing(basuraNow ? "yendo al cofre de la basura" : "yendo a su cofre de campo", now);
            if ((campoWalk % 20) == 1 || villager.getNavigation().isIdle()) {
                villager.getNavigation().startMovingTo(campoCofre.getX() + 0.5, campoCofre.getY(), campoCofre.getZ() + 0.5, 0.6);
            }
            return true;
        }
        villager.getNavigation().stop();
        BlockPos at = campoCofre;
        int moved;
        if (basuraNow) {
            moved = Cofres.dump(world, villager, at, junkPlan(inv));
            if (moved > 0) {
                Recuerdos.anotar(villager, "cofre", "tiré " + moved + " cosas que no me sirven en el cofre de la basura en " + at.getX() + " " + at.getY()
                        + " " + at.getZ(), at);
            }
            memory.doing("dejó " + moved + " cosas que no le sirven en un cofre", now);
            MCA.LOGGER.info("[Tropeles] {} deja {} cosas que no le sirven en el cofre de {}; le quedan {} bloques para construir", name(), moved,
                    at.toShortString(), Hands.buildingBlockCount(villager));
        } else {
            int[] buildingStacks = {0};
            moved = Cofres.store(world, villager, at, stack -> keep(stack, buildingStacks));
            Recuerdos r = Recuerdos.of(world, villager);
            String nombre = r.nombreCofreDeCampo(at);
            r.sitio(nombre, at, now);
            if (moved > 0) {
                r.anotar(now, "cofre", "guardé " + moved + " cosas en mi " + nombre + " en " + at.getX() + " " + at.getY() + " " + at.getZ(), at);
            }
            memory.doing("guardó " + moved + " cosas en su " + nombre, now);
            MCA.LOGGER.info("[Tropeles] {} guarda {} cosas en su {} en {}", name(), moved, nombre, at.toShortString());
            ItemStack left = firstToStore(villager);
            if (!left.isEmpty() && freeSlots(inv) <= 2 && !Cofres.roomFor(world, at, left)) {
                // that one is full: the next one it knows, or a new one beside it, or home when it cannot make one
                campoLleno.add(at.asLong());
                campoCofre = null;
                if (inv.count(Items.CHEST) == 0 && !chestWood(inv, 0) && campoCerca(world, villager, left) == null) {
                    endCampo(world, villager, now + 2400);
                    return false;
                }
                return true;
            }
        }
        endCampo(world, villager, now + (moved > 0 ? 200 : 2400));
        return false;
    }

    /**
     * Where the chest goes: in a tunnel, into a niche it cuts in the side wall, so it never shuts the way; in the open,
     * on the floor beside it, never on its trail, in a one-wide passage or at the end of one. Null while the niche is
     * being cut or when there is nowhere.
     */
    @Nullable
    private BlockPos placeCampo(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, VisualMemory memory, long now) {
        SimpleInventory inv = villager.getInventory();
        if (nicho == null && nichoFails < 2) {
            // the walls to the sides of the way its tunnel runs (never the face it digs on, nor the way back)
            Direction ahead = Direction.fromHorizontal(tunnelDir & 3);
            Direction facing = villager.getHorizontalFacing();
            Direction[] sides = facing.getAxis() == ahead.getAxis()
                    ? new Direction[]{ahead.rotateYClockwise(), ahead.rotateYCounterclockwise()}
                    : new Direction[]{facing, facing.getOpposite()};
            for (Direction side : sides) {
                BlockPos cell = feet.offset(side);
                BlockState state = world.getBlockState(cell);
                if (Mining.isRock(state) && !Mining.isOre(state) && !mina.has(cell) && Mining.standable(world, cell.down()) && !Mining.heat(world, cell)
                        && world.getFluidState(cell.up()).isEmpty() && !Mining.passable(world, cell.up()) && Obras.mayBreak(world, villager, cell)) {
                    nicho = cell.toImmutable();
                    break;
                }
            }
        }
        if (nicho == null || nichoFails >= 2) {
            return Progreso.placeBeside(world, villager, Cofres::isChestItem,
                    cell -> mina.has(cell) || mina.has(cell.down()) || mina.has(cell.down(2)) || narrow(world, cell));
        }
        if (!Mining.passable(world, nicho)) {
            int r = nichoBreaker.tick(world, villager, nicho);
            if (r == Hands.Breaker.RUNNING) {
                memory.doing("cavando un hueco en la pared para su cofre", now);
                return null;
            }
            if (r == Hands.Breaker.FAILED && !Mining.passable(world, nicho)) {
                nichoFails++;
                nicho = null;
                return null;
            }
        }
        BlockPos cell = nicho;
        nicho = null;
        int slot = Hands.findSlot(inv, Cofres::isChestItem);
        if (slot >= 0 && Hands.place(world, villager, cell, inv.getStack(slot))) {
            return cell;
        }
        nichoFails++;
        return null;
    }

    /** A cell of a one-wide passage (walls on both sides, open ahead and behind) or the dead end of one: a chest there shuts the way. */
    private static boolean narrow(ServerWorld world, BlockPos cell) {
        boolean n = Mining.passable(world, cell.north());
        boolean s = Mining.passable(world, cell.south());
        boolean e = Mining.passable(world, cell.east());
        boolean w = Mining.passable(world, cell.west());
        int open = (n ? 1 : 0) + (s ? 1 : 0) + (e ? 1 : 0) + (w ? 1 : 0);
        return open <= 1 || n && s && !e && !w || e && w && !n && !s;
    }

    /** Its nearest field chest it remembers within {@value #CAMPO_REUSE} blocks that still stands and has room for {@code first}. */
    @Nullable
    private BlockPos campoCerca(ServerWorld world, VillagerEntityMCA villager, ItemStack first) {
        if (first.isEmpty()) {
            return null;
        }
        BlockPos feet = villager.getBlockPos();
        Recuerdos r = Recuerdos.of(world, villager);
        BlockPos best = null;
        double bestD = (double) CAMPO_REUSE * CAMPO_REUSE;
        for (Map.Entry<String, BlockPos> e : r.cofresDeCampo().entrySet()) {
            BlockPos pos = e.getValue();
            double d = pos.getSquaredDistance(feet);
            if (d > bestD || Math.abs(pos.getY() - feet.getY()) > 12 || campoLleno.contains(pos.asLong()) || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }
            if (!(world.getBlockState(pos).getBlock() instanceof ChestBlock)) {
                r.olvidarSitio(e.getKey(), world.getTime()); // it is gone: no use remembering it
                continue;
            }
            if (Cofres.roomFor(world, pos, first)) {
                best = pos;
                bestD = d;
            }
        }
        return best;
    }

    /** The junk chest of this trip, when it is still close and has room. */
    @Nullable
    private BlockPos basuraCerca(ServerWorld world, VillagerEntityMCA villager) {
        if (basura == null || !basura.isWithinDistance(villager.getBlockPos(), 12) || campoLleno.contains(basura.asLong())
                || !(world.getBlockState(basura).getBlock() instanceof ChestBlock)) {
            return null;
        }
        SimpleInventory inv = villager.getInventory();
        int[] plan = junkPlan(inv);
        for (int i = 0; i < plan.length; i++) {
            if (plan[i] > 0 && Cofres.roomFor(world, basura, inv.getStack(i))) {
                return basura;
            }
        }
        return null;
    }

    /** The wood for a chest and the table to make it on, in planks (8 and 4), and {@code spare} planks more. */
    private static boolean chestWood(SimpleInventory inv, int spare) {
        int planks = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) {
                planks += stack.getCount();
            } else if (stack.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) {
                planks += 4 * stack.getCount();
            }
        }
        return planks >= 12 + spare && Progreso.canMakeChest(inv);
    }

    /** Throws its biggest stack of junk on the ground to make room for crafting. False when it has none. */
    private boolean tossJunk(ServerWorld world, VillagerEntityMCA villager) {
        SimpleInventory inv = villager.getInventory();
        int[] plan = junkPlan(inv);
        int slot = -1;
        for (int i = 0; i < plan.length; i++) {
            if (plan[i] > 0 && plan[i] == inv.getStack(i).getCount() && (slot < 0 || plan[i] > plan[slot])) {
                slot = i;
            }
        }
        return slot >= 0 && Hands.toss(world, villager, slot, 6000);
    }

    /** What it does not need at all: not food, not worth a thing to a miner. */
    private static boolean junk(ItemStack stack) {
        return stack.isOf(Items.ROTTEN_FLESH) || stack.isOf(Items.POISONOUS_POTATO) || stack.isOf(Items.SPIDER_EYE);
    }

    /**
     * How much of each slot is junk: loose rock and soil beyond {@value #JUNK_KEEP} building blocks (cobblestone kept
     * first, the biggest stacks first: for pillars, bridges, walls and a furnace), and everything {@link #junk}.
     */
    private static int[] junkPlan(SimpleInventory inv) {
        int[] out = new int[inv.size()];
        List<Integer> rocks = new ArrayList<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (rubble(stack)) {
                rocks.add(i);
            } else if (junk(stack)) {
                out[i] = stack.getCount();
            }
        }
        rocks.sort((a, b) -> {
            boolean ca = cobble(inv.getStack(a));
            boolean cb = cobble(inv.getStack(b));
            if (ca != cb) {
                return ca ? -1 : 1;
            }
            return Integer.compare(inv.getStack(b).getCount(), inv.getStack(a).getCount());
        });
        int keepLeft = JUNK_KEEP;
        for (int i : rocks) {
            ItemStack stack = inv.getStack(i);
            int kept = Hands.isBuildingBlock(stack) ? Math.min(keepLeft, stack.getCount()) : 0;
            keepLeft -= kept;
            out[i] = stack.getCount() - kept;
        }
        return out;
    }

    private static int junkCount(SimpleInventory inv) {
        int n = 0;
        for (int k : junkPlan(inv)) {
            n += k;
        }
        return n;
    }

    // ------------------------------------------------------------------ back to work

    /** Down its own trail to the face it left, or a fresh start where it stands when it knows no trail. */
    private void beginReturn(ServerWorld world, VillagerEntityMCA villager) {
        plan.clear();
        ore = null;
        returnTicks = 0;
        returnFails = 0;
        if (!motor.midStep(villager)) {
            motor.reset(world, villager);
        }
        resetWatch();
        endVia();
        viaFails = 0;
        if (mina.known()) {
            phase = Phase.RETURN;
            say("chore.mining.back");
            MCA.LOGGER.info("[Tropeles] {} vuelve a su mina por su camino ({} pasos)", name(), mina.size());
        } else if (faceByMemory(world, villager, villager.getBlockPos())) {
            // no trail from a door, but it remembers walking from here to its work face
            phase = Phase.RETURN;
            say("chore.mining.back");
        } else {
            phase = Phase.SEEK;
        }
    }

    /** Back to its work face over cells it remembers walking (its trail lost or shut). True when it walks that way now. */
    private boolean faceByMemory(ServerWorld world, VillagerEntityMCA villager, BlockPos feet) {
        BlockPos face = mina.last();
        if (face == null) {
            face = Recuerdos.of(world, villager).sitio("frente de trabajo");
        }
        if (face == null || face.isWithinDistance(feet, 1.5) || viaFails >= 3 || !wayByMemory(world, villager, feet, face)) {
            return false;
        }
        MCA.LOGGER.info("[Tropeles] {} baja a su frente de trabajo {} por donde ya caminó ({} pasos)", name(), face.toShortString(), via.size());
        Voluntad.contar(villager, "Bajo a mi mina por donde ya pasé.", "I'll go back down to my mine the way I walked before.");
        return true;
    }

    /** One tick down a remembered way to its work face; at its end it digs on from there. */
    private void returnByMemory(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, VisualMemory memory, long now) {
        BlockPos goal = nextVia(feet);
        BlockPos face = viaDestino;
        if (goal == null || face != null && feet.isWithinDistance(face, 1.5)) {
            endVia();
            plan.clear();
            if (!motor.midStep(villager)) {
                motor.reset(world, villager);
            }
            int i = mina.nearHigh(world, feet);
            if (mina.known() && i >= 0 && i < mina.size() - 1) {
                // at the door of its mine (or on its trail): down the trail from here
                returnTicks = 0;
                returnFails = 0;
                resetWatch();
                memory.doing("en la boca de su mina", now);
                return;
            }
            if (mina.last() != null && feet.isWithinDistance(mina.last(), 2)) {
                tunnelDir = mina.dir & 3;
                branchLeft = mina.left;
            }
            phase = Phase.SEEK;
            resetWatch();
            memory.doing("de vuelta en su frente de trabajo", now);
            return;
        }
        villager.getNavigation().stop();
        memory.doing("bajando a su mina por " + viaDe + " (" + via.size() + " pasos)", now);
        Motor.Result result = walkVia(world, villager, goal);
        if (result == Motor.Result.ARRIVED) {
            if (goal.equals(via.peekFirst())) {
                via.pollFirst();
            }
        } else if (result == Motor.Result.BLOCKED) {
            motor.reset(world, villager);
            viaBlocked(world, villager, feet, goal, now);
            if (!viaMemoria && !mina.known()) {
                // no remembered way down any more and no trail of its own: it digs on from where it is (up on the
                // land, its mine waits a minute). With its trail it goes on down that, opening it where it is shut
                MCA.LOGGER.info("[Tropeles] {} no tiene camino a su frente desde {} ni rastro de su mina: cava desde aquí", name(), feet.toShortString());
                phase = Phase.SEEK;
                nextMineReturn = now + 1200;
            }
        }
    }

    private void returnToMine(ServerWorld world, VillagerEntityMCA villager) {
        if (!progreso.idle()) {
            gearUp(world, villager);
            return;
        }
        VisualMemory memory = Retina.memory(villager);
        long now = world.getTime();
        BlockPos feet = villager.getBlockPos();
        equipPick(villager);
        if (viaMemoria) {
            returnTicks++;
            returnByMemory(world, villager, feet, memory, now);
            return;
        }
        if (++returnTicks > 9600 || !mina.known()) {
            if (returnTicks <= 9600 && faceByMemory(world, villager, feet)) {
                return;
            }
            // too long on the way, or no trail: it starts a new mine where it stands. A trail it can still walk is kept,
            // and it tries that mine again in a while
            if (!mina.usable(world)) {
                mina.clear();
            }
            nextMineReturn = world.getTime() + 6000;
            phase = Phase.SEEK;
            return;
        }
        int i = mina.nearHigh(world, feet);
        if (i < 0) {
            BlockPos door = mina.entrance();
            if (door == null || !feet.isWithinDistance(door, HOME_REACH + 16)) {
                // (home may be as far as HOME_REACH from the mine: a trail is only forgotten beyond that) the way it
                // remembers walking may still lead there
                if (faceByMemory(world, villager, feet)) {
                    return;
                }
                mina.clear();
                phase = Phase.SEEK;
                return;
            }
            memory.doing(String.format("volviendo a su mina, a %.0f bloques", Math.sqrt(feet.getSquaredDistance(door))), now);
            BlockPos face = mina.last();
            if (face != null && feet.isWithinDistance(door, 12) && (returnTicks % 20) == 2
                    && pieA(world, villager, feet, face, 0.5, face, "su escalera hacia abajo, a pie")) {
                // by the mouth of its mine: down its own staircase on foot, whichever side it came from
                villager.getNavigation().stop();
                return;
            }
            if ((returnTicks % 40) == 1 || villager.getNavigation().isIdle()) {
                if (!villager.getNavigation().startMovingTo(door.getX() + 0.5, door.getY(), door.getZ() + 0.5, 0.7) || villager.getNavigation().isIdle()) {
                    // too far for one walk: a way it remembers (it walked from there to here), else a leg of the way
                    if (returnTicks > 40 && (returnTicks % 200) == 41 && buscarVia(world, villager, feet, door)) {
                        MCA.LOGGER.info("[Tropeles] {} vuelve a la boca de su mina {} por {} ({} pasos)", name(), door.toShortString(), viaDe, via.size());
                        return;
                    }
                    walkToward(world, villager, door);
                }
            }
            return;
        }
        if (i >= mina.size() - 1) {
            // at the work face again: on with the tunnel it was cutting
            tunnelDir = mina.dir & 3;
            branchLeft = mina.left;
            plan.clear();
            if (!motor.midStep(villager)) {
                motor.reset(world, villager);
            }
            phase = Phase.SEEK;
            resetWatch();
            memory.doing("de vuelta en su frente de trabajo", now);
            return;
        }
        villager.getNavigation().stop();
        BlockPos face = mina.last();
        if (!motor.midStep(villager) && pieA(world, villager, feet, face, 0.5, face, "su escalera hacia abajo, a pie")) {
            // the way down it knows, worked out over what is there now and walked one cell at a time: its own
            // staircase and tunnel, never a new one beside them
            return;
        }
        BlockPos goal = mina.crumb(i + 1);
        memory.doing("bajando por su camino a la mina (" + (mina.size() - 1 - i) + " pasos)", now);
        // its own trail is walked: digging only once it found it shut (gravel came down, something grew in) and there is
        // no way on foot to its work face at all (just above): then it opens its own trail again, nothing beside it
        boolean dig = returnFails >= 2;
        if (dig && returnFails == 2) {
            MCA.LOGGER.info("[Tropeles] {} encuentra su camino cerrado en {} y no hay paso a pie a su frente {}: abre su propio camino", name(),
                    goal.toShortString(), face.toShortString());
        }
        // opening it, only its own cells: the trail's crumbs and the room over them, never a new way beside them
        Motor.Result result = dig ? motor.stepWithin(world, villager, goal, true, trailCells) : motor.step(world, villager, goal, false, false);
        if (result == Motor.Result.ARRIVED) {
            returnFails = 0;
        } else if (result == Motor.Result.BLOCKED) {
            motor.reset(world, villager);
            if (++returnFails > 4) {
                // the old way down is shut: another way it remembers walking to the face, or it digs on from here
                if (!faceByMemory(world, villager, feet)) {
                    MCA.LOGGER.info("[Tropeles] {} no puede bajar por su camino desde {} ni abrirlo: cava desde aquí", name(), feet.toShortString());
                    phase = Phase.SEEK;
                    nextMineReturn = world.getTime() + 1200;
                }
            }
        }
    }

    /** A leg of a long walk: as far toward {@code to} as the path finder goes in one go. */
    private void walkToward(ServerWorld world, VillagerEntityMCA villager, BlockPos to) {
        double dx = to.getX() + 0.5 - villager.getX();
        double dz = to.getZ() + 0.5 - villager.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1) {
            return;
        }
        double leg = Math.min(d, 24);
        // the path finder settles the aim on the ground under (or over) it
        BlockPos aim = BlockPos.ofFloored(villager.getX() + dx / d * leg, villager.getY(), villager.getZ() + dz / d * leg);
        villager.getNavigation().startMovingTo(aim.getX() + 0.5, aim.getY(), aim.getZ() + 0.5, 0.7);
    }

    /** Called out of the mine: it waits at home, doing its gear, until told to go back down (or a while passes). */
    private void rest(ServerWorld world, VillagerEntityMCA villager) {
        if (gearUp(world, villager)) {
            return;
        }
        BlockPos target = homePos(world, villager);
        Retina.memory(villager).doing("esperando en casa", world.getTime());
        if (wantsSleep(world, villager)) {
            // waiting at home into the night: to bed like everyone else, and on waiting in the morning
            startSleep(world, villager);
            return;
        }
        if (++restTicks > REST_TICKS) {
            beginReturn(world, villager);
            return;
        }
        if (villager.getBlockPos().getSquaredDistance(target) > 16 && ((restTicks % 40) == 1 || villager.getNavigation().isIdle())) {
            villager.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
        }
    }

    /**
     * It looked everywhere it could walk and found no tree for a pickaxe: it does not log off. It waits a couple of
     * minutes (connected, in Tab, talking) and then starts its gear over and looks again, further this time.
     */
    private void noWood(ServerWorld world, VillagerEntityMCA villager) {
        long now = world.getTime();
        if (nowoodUntil == 0) {
            nowoodUntil = now + 2400;
            MCA.LOGGER.info("[Tropeles] {} no encontró madera para un pico: sigue conectado, descansa y vuelve a buscar", name());
            Voluntad.contar(villager, "No encuentro árboles por aquí. Descanso un poco y vuelvo a buscar.",
                    "I can't find any trees around here. I'll rest a bit and look again.");
            Motor.standStill(villager);
            return;
        }
        Retina.memory(villager).doing("descansando antes de volver a buscar árboles", now);
        if (now >= nowoodUntil) {
            nowoodUntil = 0;
            progreso = new Progreso();
            explorar.reset();
            resetWatch();
        }
    }

    // ------------------------------------------------------------------ the night

    /** Night for it: the clock (or the checks' say-so). */
    private boolean night(ServerWorld world) {
        return nocheForzada != null ? nocheForzada : world.isNight();
    }

    /** At home in the dark with a bed to go to: it sleeps, the way a player does before going on. */
    private boolean wantsSleep(ServerWorld world, VillagerEntityMCA villager) {
        if (!night(world) || world.getTime() < nextSleepTry || tooYoung(villager)) {
            return false;
        }
        cama = findBed(world, villager);
        if (cama == null) {
            nextSleepTry = world.getTime() + 1200;
            return false;
        }
        return true;
    }

    /** Its own bed in the village when it is near, else any free bed close by (its head cell), or null. */
    @Nullable
    private BlockPos findBed(ServerWorld world, VillagerEntityMCA villager) {
        BlockPos feet = villager.getBlockPos();
        var home = villager.getResidency().getHome();
        if (home.isPresent() && home.get().getDimension() == world.getRegistryKey() && home.get().getPos().isWithinDistance(feet, 48)) {
            BlockPos head = bedHead(world, home.get().getPos());
            if (head != null && !world.getBlockState(head).get(net.minecraft.block.BedBlock.OCCUPIED)) {
                return head;
            }
        }
        return net.mca.entity.ai.sense.Dormir.camaLibre(world, feet, 24);
    }

    /** The head cell of the bed at {@code pos} (either half), or null when there is no bed there. */
    @Nullable
    private static BlockPos bedHead(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof net.minecraft.block.BedBlock)) {
            return null;
        }
        if (state.get(net.minecraft.block.BedBlock.PART) == net.minecraft.block.enums.BedPart.HEAD) {
            return pos.toImmutable();
        }
        BlockPos head = pos.offset(state.get(net.minecraft.block.HorizontalFacingBlock.FACING));
        return world.getBlockState(head).getBlock() instanceof net.minecraft.block.BedBlock ? head : null;
    }

    private void startSleep(ServerWorld world, VillagerEntityMCA villager) {
        sleepFromRest = phase == Phase.REST;
        phase = Phase.SLEEP;
        camaTicks = 0;
        slept = false;
        plan.clear();
        if (!motor.midStep(villager)) {
            motor.reset(world, villager);
        }
        resetWatch();
        MCA.LOGGER.info("[Tropeles] {} se va a dormir a la cama de {} (sigue conectado)", name(), cama == null ? "?" : cama.toShortString());
        Recuerdos.anotar(villager, "viaje", "me fui a dormir", cama);
        Voluntad.contar(villager, "Ya es de noche. Me voy a dormir un rato y sigo mañana.", "It's night. I'll get some sleep and carry on tomorrow.");
    }

    /**
     * To bed and asleep ({@link net.mca.entity.ai.sense.Dormir}: counted like a player's sleep for the night skip),
     * still connected; up again in the morning (or when the night is skipped, or something woke it), it goes on with
     * what it was doing: back to its mine, or waiting at home when it was called home.
     */
    private void sleep(ServerWorld world, VillagerEntityMCA villager) {
        long now = world.getTime();
        VisualMemory memory = Retina.memory(villager);
        if (net.mca.entity.ai.sense.Dormir.dormido(villager)) {
            slept = true;
            Motor.standStill(villager);
            memory.doing("durmiendo", now);
            return;
        }
        if (slept || !night(world)) {
            if (slept) {
                Recuerdos.anotar(villager, "viaje", "me desperté", villager.getBlockPos());
                Voluntad.contar(villager, "Buenos días. Sigo con lo mío.", "Good morning. Back to it.");
            }
            wakeUpAndGoOn(world, villager);
            return;
        }
        if (cama == null || bedHead(world, cama) == null || world.getBlockState(cama).get(net.minecraft.block.BedBlock.OCCUPIED)) {
            cama = findBed(world, villager);
            if (cama == null) {
                nextSleepTry = now + 1200;
                wakeUpAndGoOn(world, villager);
                return;
            }
        }
        if (++camaTicks > 1200) {
            // could not get to it or lie down: not tonight, it goes on
            MCA.LOGGER.info("[Tropeles] {} no pudo acostarse en {}: sigue con lo suyo", name(), cama.toShortString());
            nextSleepTry = now + 2400;
            wakeUpAndGoOn(world, villager);
            return;
        }
        double d2 = villager.squaredDistanceTo(Vec3d.ofBottomCenter(cama));
        memory.doing("yendo a dormir", now);
        if (d2 <= 2.0 * 2.0) {
            villager.getNavigation().stop();
            // the checks' night lies down in daylight too (the clock stays at noon for them)
            boolean lay = Boolean.TRUE.equals(nocheForzada) ? net.mca.entity.ai.sense.Dormir.acostarPrueba(world, villager, cama)
                    : net.mca.entity.ai.sense.Dormir.acostar(world, villager, cama);
            if (lay) {
                slept = true;
                timesSlept++;
                MCA.LOGGER.info("[Tropeles] {} se acostó en {}", name(), cama.toShortString());
            } else if (camaTicks > 200) {
                // by the bed and it will not have it (day after all, or a monster near): not tonight
                nextSleepTry = now + 2400;
                MCA.LOGGER.info("[Tropeles] {} no puede acostarse ahora en {}: sigue con lo suyo", name(), cama.toShortString());
                wakeUpAndGoOn(world, villager);
            }
            return;
        }
        if ((camaTicks % 20) == 1 || villager.getNavigation().isIdle()) {
            villager.getNavigation().startMovingTo(cama.getX() + 0.5, cama.getY(), cama.getZ() + 0.5, 0.6);
        }
    }

    private void wakeUpAndGoOn(ServerWorld world, VillagerEntityMCA villager) {
        if (net.mca.entity.ai.sense.Dormir.dormido(villager)) {
            net.mca.entity.ai.sense.Dormir.levantar(world, villager);
        }
        slept = false;
        cama = null;
        camaTicks = 0;
        villager.getNavigation().stop();
        if (sleepFromRest) {
            sleepFromRest = false;
            phase = Phase.REST;
            resetWatch();
            return;
        }
        beginReturn(world, villager);
    }

    /** Up for wood: a pickaxe (and a kit) made outside, then back down. */
    private void surfaceWork(ServerWorld world, VillagerEntityMCA villager) {
        if (gearUp(world, villager)) {
            return;
        }
        if (progreso.gaveUp()) {
            noWood(world, villager);
            return;
        }
        surfaceTicks++;
        boolean picked = Progreso.countPicks(villager) > 0;
        if (picked && (Progreso.hasKit(villager) || surfaceTicks > 1200) || surfaceTicks > 6000) {
            equipPick(villager);
            beginReturn(world, villager);
        }
    }

    // ------------------------------------------------------------------ food from the land

    private void startForage() {
        phase = Phase.FORAGE;
        forageTicks = 0;
        noFoodTicks = 0;
        prey = null;
        preyTicks = 0;
        lootTicks = 0;
        lootAt = null;
        explorar.reset();
        provisioning = false;
        foraged = true;
        resetWatch();
        MCA.LOGGER.info("[Tropeles] {} sale a buscar comida (comida {})", name(), food);
    }

    /**
     * No food in the bag or at home: it hunts the wild animals it sees (never a pen, a village's, a named, leashed,
     * tamed or young one, nor the last of its kind around), picks up what they drop and cooks the meat when it can.
     */
    private void forage(ServerWorld world, VillagerEntityMCA villager) {
        VisualMemory memory = Retina.memory(villager);
        long now = world.getTime();
        SimpleInventory inv = villager.getInventory();
        if (gearUp(world, villager)) {
            return; // the meat in the furnace, a table, a furnace of its own
        }
        int carried = foodPoints(inv);
        if (carried >= 12 || food >= FOOD_MAX - 2 && carried >= 6 || ++forageTicks > FORAGE_TICKS) {
            finishForage(world, villager);
            return;
        }
        if (rawCount(inv) >= 2) {
            progreso.wantCook();
        }
        if (lootTicks > 0) {
            lootTicks--;
            if (lootAt != null) {
                if (villager.getBlockPos().isWithinDistance(lootAt, 2.0)) {
                    Hands.collect(world, villager, villager.getBlockPos(), 2.0);
                    lootTicks = Math.min(lootTicks, 5);
                } else if ((lootTicks % 20) == 19 || villager.getNavigation().isIdle()) {
                    villager.getNavigation().startMovingTo(lootAt.getX() + 0.5, lootAt.getY(), lootAt.getZ() + 0.5, 0.7);
                }
            }
            return;
        }
        if (prey != null) {
            if (prey.isDead()) {
                lootAt = prey.getBlockPos().toImmutable();
                lootTicks = 80;
                if (now - lastHuntSay > 600) {
                    lastHuntSay = now;
                    say("chore.mining.hunted", prey.getName());
                }
                CombatMind.disengage(villager);
                prey = null;
                return;
            }
            if (prey.isRemoved() || ++preyTicks > PREY_TICKS || prey.squaredDistanceTo(villager) > 32 * 32) {
                // it got away: that one is left alone for a while
                spared.put(prey.getId(), now + 2400);
                CombatMind.disengage(villager);
                prey = null;
                return;
            }
            memory.doing("cazando " + prey.getName().getString(), now);
            CombatMind.fight(world, villager, null, prey);
            return;
        }
        if (world.isNight()) {
            // nothing hunts well in the dark: home, or back to the mine with what it has
            finishForage(world, villager);
            return;
        }
        if ((villager.age % 10) == 0) {
            prey = findPrey(world, villager, memory, now);
            preyTicks = 0;
            if (prey != null) {
                noFoodTicks = 0;
                return;
            }
        }
        if (!camping && (villager.age % 20) == 5) {
            BlockPos chest = Cofres.withFood(world, villager, villager.getBlockPos(), HOME_REACH, ProspectingTask::edible);
            if (chest != null) {
                // it remembers food in one of its chests after all
                homeTarget = chest.toImmutable();
                trip = Trip.FOOD;
                enterHomeWalk();
                return;
            }
        }
        if (pickBerries(world, villager, memory, now)) {
            noFoodTicks = 0;
            return;
        }
        if (++noFoodTicks > LOOK_FOR_FOOD) {
            finishForage(world, villager);
            return;
        }
        lookAround(world, villager, memory, now);
    }

    /**
     * Ripe sweet berries it saw near it: walked up to and picked by hand (a player's right click: one or two berries,
     * one more from a full bush, the bush left to grow again), then picked up. True while busy with them.
     */
    private boolean pickBerries(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory, long now) {
        if (berries == null && (villager.age % 20) == 7) {
            berries = seenBerries(villager, memory);
            berryTicks = 0;
        }
        if (berries == null) {
            return false;
        }
        BlockState bush = world.getBlockState(berries);
        if (!bush.isOf(Blocks.SWEET_BERRY_BUSH) || bush.get(net.minecraft.block.SweetBerryBushBlock.AGE) < 2 || ++berryTicks > 400) {
            berries = null;
            return false;
        }
        double reach = villager.getEyePos().distanceTo(Vec3d.ofCenter(berries));
        if (reach > 3.0 || Retina.visibleFace(world, villager, berries, Retina.BLOCK_REACH) == null) {
            if ((berryTicks % 20) == 1 || villager.getNavigation().isIdle()) {
                villager.getNavigation().startMovingTo(berries.getX() + 0.5, berries.getY(), berries.getZ() + 0.5, 0.6);
            }
            memory.doing("yendo a unas bayas", now);
            return true;
        }
        villager.getNavigation().stop();
        villager.getLookControl().lookAt(Vec3d.ofCenter(berries));
        int age = bush.get(net.minecraft.block.SweetBerryBushBlock.AGE);
        int count = 1 + world.random.nextInt(2) + (age == 3 ? 1 : 0);
        net.minecraft.block.Block.dropStack(world, berries, new ItemStack(Items.SWEET_BERRIES, count));
        world.setBlockState(berries, bush.with(net.minecraft.block.SweetBerryBushBlock.AGE, 1), net.minecraft.block.Block.NOTIFY_LISTENERS);
        villager.swingHand(Hand.MAIN_HAND);
        MCA.LOGGER.info("[Tropeles] {} recoge {} bayas en {} (comida {})", name(), count, berries.toShortString(), food);
        memory.doing("recogiendo bayas", now);
        // they pop out toward it, as for a player: into the bag, without walking into the thorns
        Hands.collect(world, villager, berries, 1.0);
        berries = null;
        return true;
    }

    /** The nearest ripe sweet berry bush its eye saw within 16 blocks, or null. */
    @Nullable
    private static BlockPos seenBerries(VillagerEntityMCA villager, VisualMemory memory) {
        BlockPos feet = villager.getBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(feet.add(-16, -4, -16), feet.add(16, 4, 16))) {
            BlockState seen = memory.stateAt(pos);
            if (seen == null || !seen.isOf(Blocks.SWEET_BERRY_BUSH) || seen.get(net.minecraft.block.SweetBerryBushBlock.AGE) < 2) {
                continue;
            }
            double d = pos.getSquaredDistance(feet);
            if (d < bestD) {
                bestD = d;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    /**
     * No animal in sight: explore like a player, a look all around, then the way it knows least about (see
     * {@link Explorar}). Once it has seen most of the land around and nothing to hunt, the search is over.
     */
    private void lookAround(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory, long now) {
        memory.doing(explorar.sweeping() ? "mirando alrededor buscando algo para comer" : "buscando algo para comer", now);
        if (!explorar.tick(world, villager, Explorar.Busca.COMIDA)) {
            noFoodTicks = LOOK_FOR_FOOD; // nothing to hunt anywhere it can see: done looking
        }
    }

    private void finishForage(ServerWorld world, VillagerEntityMCA villager) {
        CombatMind.disengage(villager);
        prey = null;
        for (int k = 0; k < 8 && food < FOOD_MAX - 2 && eatBest(world, villager); k++) {
            // eats what it got before going back
        }
        int carried = foodPoints(villager.getInventory());
        MCA.LOGGER.info("[Tropeles] {} termina de buscar comida: lleva {} puntos, comida {}", name(), carried, food);
        if (provisioning) {
            provisioning = false;
            if (carried < 6) {
                // nothing out there to take along: it goes down with what it has, and does not set off again for a day
                nextProvision = world.getTime() + 24000;
                MCA.LOGGER.info("[Tropeles] {} no encontró provisiones: baja a minar con lo que tiene", name());
            }
        }
        if (carried == 0 && food <= GO_HOME_BELOW) {
            // nothing out there either: connected all the same, it goes on and tries again later
            noFoodAtHome(world, villager);
        }
        if (trip == Trip.CALLED) {
            enterHomeWalk();
            return;
        }
        beginReturn(world, villager);
    }

    /** The nearest animal in sight it may hunt, or null. */
    @Nullable
    private AnimalEntity findPrey(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory, long now) {
        if (spared.size() > 64) {
            spared.values().removeIf(until -> until < now);
        }
        List<AnimalEntity> near = world.getEntitiesByClass(AnimalEntity.class, villager.getBoundingBox().expand(24, 8, 24),
                a -> a.isAlive() && huntable(a));
        AnimalEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (AnimalEntity animal : near) {
            Long until = spared.get(animal.getId());
            if (until != null && until > now || !Retina.canSee(world, villager, animal)) {
                continue;
            }
            // never the last of its kind around: at least two of them in sight or fresh in mind
            int same = 0;
            for (AnimalEntity other : near) {
                if (other.getType() == animal.getType() && (other == animal || memory.entity(other.getId()) != null)) {
                    same++;
                }
            }
            if (same < 2 || penned(world, animal.getBlockPos()) || VillageManager.get(world).findNearestVillage(animal.getBlockPos(), 16).isPresent()) {
                continue;
            }
            double distance = animal.squaredDistanceTo(villager);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = animal;
            }
        }
        return best;
    }

    /** Wild food on four legs (or two): grown, free and nobody's. */
    private static boolean huntable(AnimalEntity animal) {
        if (!(animal instanceof CowEntity || animal instanceof PigEntity || animal instanceof SheepEntity
                || animal instanceof ChickenEntity || animal instanceof RabbitEntity)) {
            return false;
        }
        return !animal.isBaby() && !(animal instanceof TameableEntity) && !animal.isLeashed() && !animal.hasCustomName() && !animal.isInLove();
    }

    /** Fences or gates around it: somebody's farm. */
    private static boolean penned(ServerWorld world, BlockPos pos) {
        for (BlockPos cell : BlockPos.iterate(pos.add(-3, -1, -3), pos.add(3, 1, 3))) {
            BlockState state = world.getBlockState(cell);
            if (state.isIn(BlockTags.FENCES) || state.isIn(BlockTags.FENCE_GATES)) {
                return true;
            }
        }
        return false;
    }

    /** Whether it knows where food is: an animal it may hunt in sight, food in one of its chests, or raw meat to cook. */
    private boolean foodKnown(ServerWorld world, VillagerEntityMCA villager) {
        long now = world.getTime();
        if (now - foodKnownAt < 100) {
            return foodKnownCache;
        }
        foodKnownAt = now;
        foodKnownCache = rawCount(villager.getInventory()) >= 2
                || findPrey(world, villager, Retina.memory(villager), now) != null
                || Cofres.withFood(world, villager, villager.getBlockPos(), HOME_REACH, ProspectingTask::edible) != null;
        return foodKnownCache;
    }

    // ------------------------------------------------------------------ never stuck anywhere

    /**
     * The global sense of getting nowhere ({@link Atasco}) for its own life, in every phase: a quarter of a minute
     * without moving more than a block, without anything new in the bag and without breaking or placing a block, and
     * not standing still by choice, and it tries the next remedy. True while a step aside or a dig-out has the body.
     */
    private boolean unstick(ServerWorld world, VillagerEntityMCA villager, Atasco atasco) {
        long now = world.getTime();
        VisualMemory memory = Retina.memory(villager);
        BlockPos goal = atasco.meta();
        if (goal != null && (phase != nudgePhase || troubled())) {
            // a fight, an order or a new plan came up meanwhile: that comes first, the step aside is dropped
            atasco.fin(false);
            if (!motor.midStep(villager)) {
                motor.reset(world, villager);
            }
            goal = null;
        }
        if (goal != null) {
            if (!atasco.seguirMoviendo()) {
                MCA.LOGGER.info("[Tropeles] {} atascado: no llegó a {} a tiempo", name(), goal.toShortString());
                atasco.fin(true);
                if (!motor.midStep(villager)) {
                    motor.reset(world, villager);
                }
                return false;
            }
            boolean dig = atasco.cavando();
            memory.doing(dig ? "atascado: cavando para salir hacia " + goal.toShortString() : "atascado: se aparta a " + goal.toShortString(), now);
            Motor.Result result = motor.step(world, villager, goal, dig, dig);
            if (result == Motor.Result.ARRIVED) {
                atasco.fin(false);
                motor.reset(world, villager);
                plan.clear();
                resetWatch();
                MCA.LOGGER.info("[Tropeles] {} atascado: llegó a {}, sigue con lo suyo", name(), goal.toShortString());
            } else if (result == Motor.Result.BLOCKED) {
                atasco.fin(true);
                motor.reset(world, villager);
            }
            return true;
        }
        // standing still by choice: resting at home, fighting or hiding, its iron in the furnace, asleep
        // and a block being broken that is still cracking (a hard one takes a while): that is digging, not being stuck
        float breaking = Math.max(motor.breaker().progress(), nichoBreaker.progress());
        boolean digging = breaking > 0 && breaking != lastBreak;
        lastBreak = breaking;
        breakingNow = digging;
        boolean byChoice = phase == Phase.REST || troubled() || progreso.cooking() || villager.isSleeping() || digging;
        Atasco.Remedio remedy = atasco.tick(world, villager, byChoice, memory.doing() + " (" + phase + ")");
        if (remedy == Atasco.Remedio.NINGUNO) {
            return false;
        }
        BlockPos feet = villager.getBlockPos();
        Recuerdos recuerdos = Recuerdos.of(world, villager);
        recuerdos.anotar(now, "atasco", "me quedé atascado en " + feet.getX() + " " + feet.getY() + " " + feet.getZ() + " haciendo «" + memory.doing()
                + "»: " + remedy.name().toLowerCase(java.util.Locale.ROOT), feet);
        switch (remedy) {
            case REINICIAR -> {
                if (!motor.midStep(villager)) {
                    motor.reset(world, villager);
                }
                plan.clear();
                villager.getNavigation().stop();
                resetWatch();
                decideTicks = 19;
                memory.doing("atascado: lo piensa de nuevo", now);
            }
            case SOLTAR -> {
                String what = giveUpTarget(world, villager, now);
                MCA.LOGGER.info("[Tropeles] {} atascado en {}: {}", name(), feet.toShortString(), what);
                Voluntad.contar(villager, "No avanzo así: " + what + ".", "I'm getting nowhere like this: trying something else.");
            }
            case APARTARSE -> {
                BlockPos free = Atasco.libreAlLado(world, feet, (int) (now / 20), recuerdos);
                if (free != null) {
                    MCA.LOGGER.info("[Tropeles] {} atascado en {}: se aparta a {}", name(), feet.toShortString(), free.toShortString());
                    atasco.mover(free, false);
                } else {
                    digOut(world, villager, atasco, recuerdos, feet);
                }
            }
            case CAVAR -> digOut(world, villager, atasco, recuerdos, feet);
            case AYUDA -> {
                MCA.LOGGER.info("[Tropeles] {} atascado en {}: pide ayuda en el chat", name(), feet.toShortString());
                Voluntad.contar(villager, "Estoy atascado en " + feet.getX() + " " + feet.getY() + " " + feet.getZ() + " y no logro salir. ¿Me ayudas?",
                        "I'm stuck at " + feet.getX() + " " + feet.getY() + " " + feet.getZ() + " and can't get out. Can you help me?");
                lastStuckCall = -1200;
                callOutStuck(world);
            }
            default -> {
            }
        }
        nudgePhase = phase;
        return atasco.meta() != null;
    }

    /**
     * Boxed in: digs toward the nearest open cell it remembers walking, or a step up ahead. Not when there is a way on
     * foot to where it is going (up its own staircase, down it to its work face, home): that way is walked again.
     */
    private void digOut(ServerWorld world, VillagerEntityMCA villager, Atasco atasco, Recuerdos recuerdos, BlockPos feet) {
        if (sinCavar(world, villager, feet)) {
            return;
        }
        BlockPos to = Atasco.salidaCavando(world, feet, recuerdos, villager.getHorizontalFacing());
        MCA.LOGGER.info("[Tropeles] {} atascado en {}: está encerrado, cava hacia {}", name(), feet.toShortString(), to.toShortString());
        Voluntad.contar(villager, "Estoy encerrado aquí, cavo para salir.", "I'm boxed in here, I'll dig my way out.");
        equipPick(villager);
        atasco.mover(to, true);
    }

    /**
     * Stuck on the way up, down or home with a way on foot to where it is going: that way, found afresh from where it
     * stands and walked, instead of digging out. True when it walks one now.
     */
    private boolean sinCavar(ServerWorld world, VillagerEntityMCA villager, BlockPos feet) {
        BlockPos goal;
        double within;
        BlockPos to;
        switch (phase) {
            case HOME_UP -> {
                goal = salida(world, villager);
                within = 0.5;
                to = null;
            }
            case RETURN -> {
                goal = mina.last() != null ? mina.last() : mina.entrance();
                within = 0.5;
                to = goal;
            }
            case HOME_WALK -> {
                goal = homePos(world, villager);
                within = 2.0;
                to = goal;
            }
            default -> {
                return false;
            }
        }
        if (goal == null || goal.equals(feet) || !pieA(world, villager, feet, goal, within, to, phase == Phase.HOME_UP ? "su escalera, a pie" : "un camino a pie que conoce")) {
            return false;
        }
        fallback = false;
        MCA.LOGGER.info("[Tropeles] {} atascado en {} ({}) pero hay paso a pie hasta {}: no cava, camina", name(), feet.toShortString(), phase, goal.toShortString());
        Voluntad.contar(villager, "No hace falta cavar: conozco el camino, sigo por ahí.", "No need to dig: I know the way, I'll take it.");
        return true;
    }

    /** Lets go of what it was going for, so the next try is a different one. Its own words for what it gave up. */
    private String giveUpTarget(ServerWorld world, VillagerEntityMCA villager, long now) {
        if (!motor.midStep(villager)) {
            motor.reset(world, villager);
        }
        plan.clear();
        villager.getNavigation().stop();
        resetWatch();
        if (ore != null) {
            ignore(ore, now);
            ore = null;
            return "dejo ese mineral, no llego a él";
        }
        if (site != null) {
            badSites.put(siteKey(site), now + SITE_FORGET);
            site = null;
            return "busco otro sitio para empezar mi mina";
        }
        if (prey != null) {
            spared.put(prey.getId(), now + 2400);
            CombatMind.disengage(villager);
            prey = null;
            return "dejo ese animal en paz";
        }
        if (viaMemoria && !via.isEmpty()) {
            viaBlocked(world, villager, villager.getBlockPos(), via.peekFirst(), now);
            return "por ahí no paso, busco otro camino";
        }
        switch (phase) {
            case HOME_UP -> {
                if (trailDig) {
                    trailDigFails += 2;
                } else if (fallback) {
                    climbMode = (climbMode + 1) % 5;
                } else {
                    skip += 3;
                }
                return "pruebo otra forma de subir";
            }
            case HOME_WALK -> {
                ledgeFails++;
                homeTicks = Math.max(homeTicks, 101);
                return "busco otro camino a casa";
            }
            case RETURN -> {
                returnFails += 2;
                return "pruebo otro camino a mi mina";
            }
            default -> {
            }
        }
        BlockPos waypoint = explorar.waypoint();
        if (waypoint != null) {
            explorar.fail(waypoint, now);
            return "miro por otro lado";
        }
        if (!progreso.idle()) {
            return "sigo con mis herramientas, pero desde otro lado";
        }
        tunnelDir = (tunnelDir + 1) & 3;
        return "cavo hacia otro lado";
    }

    // ------------------------------------------------------------------ the watchdog

    private void resetWatch() {
        watchPhase = null;
        bestProgress = Double.NEGATIVE_INFINITY;
        stuckTicks = 0;
        stuckRounds = 0;
        stuckAt = null;
    }

    /**
     * A player who notices they have not got anywhere for half a minute stops and tries something else. Each phase
     * has its own measure of getting somewhere; every round without it the next remedy is tried. True when it
     * changed course this tick.
     */
    private boolean watchdog(ServerWorld world, VillagerEntityMCA villager) {
        if (watchPhase != phase) {
            resetWatch();
            watchPhase = phase;
        }
        if (!progreso.idle()) {
            stuckTicks = 0;
            stuckAt = null;
            return false;
        }
        if (phase == Phase.SEEK) {
            return seekWatchdog(world, villager);
        }
        if (phase != Phase.HOME_UP && phase != Phase.HOME_WALK && phase != Phase.RETURN) {
            return false;
        }
        if ((villager.age % 20) != 0) {
            return false;
        }
        if (breakingNow) {
            // a hard block still cracking under the pickaxe: that is work, the ten seconds wait for it
            return false;
        }
        double progress = progress(world, villager);
        if (bestProgress == Double.NEGATIVE_INFINITY) {
            // a new measure (a new phase, or its own staircase instead of the trail): this is where it starts from
            bestProgress = progress;
            stuckTicks = 0;
            return false;
        }
        if (progress > bestProgress + 0.5) {
            bestProgress = progress;
            stuckTicks = 0;
            stuckRounds = 0;
            return false;
        }
        stuckTicks += 20;
        if (stuckTicks < STUCK_TICKS) {
            return false;
        }
        stuckTicks = 0;
        stuckRounds++;
        motor.reset(world, villager);
        plan.clear();
        Retina.memory(villager).doing("atascado: prueba otro camino", world.getTime());
        MCA.LOGGER.info("[Tropeles] {} no avanza en {} (vuelta {})", name(), phase, stuckRounds);
        if (phase == Phase.HOME_UP || phase == Phase.HOME_WALK) {
            lostTicks += STUCK_TICKS;
            if (lostTicks > LOST_TICKS) {
                // lost for a long while: it says so and asks for help, still connected, and keeps trying (its own
                // staircase up, or a camp where it stands on the land)
                lostTicks = 0;
                if (!lostSaid) {
                    lostSaid = true;
                    say("chore.mining.lost");
                }
                lastStuckCall = -1200;
                callOutStuck(world);
                if (phase == Phase.HOME_UP && !fallback) {
                    startFallback(world, villager, villager.getBlockPos(), mina.nearLow(world, villager.getBlockPos()));
                } else if (phase == Phase.HOME_WALK) {
                    camping = true;
                    homeTarget = villager.getBlockPos().toImmutable();
                    enterHomeStore();
                }
                return true;
            }
        }
        if (phase == Phase.HOME_UP) {
            if (viaMemoria) {
                // the remembered way does not get it anywhere: that cell is shut for now, another way or its own stairs
                BlockPos next = via.peekFirst();
                viaBlocked(world, villager, villager.getBlockPos(), next != null ? next : villager.getBlockPos(), world.getTime());
            } else if (trailDig) {
                if (stuckRounds >= 2) {
                    // its own trail does not open up from here: a staircase of its own after all
                    intentos.add("su propio camino, abriéndolo: atascado en " + villager.getBlockPos().toShortString());
                    trailDig = false;
                    startFallback(world, villager, villager.getBlockPos(), mina.nearLow(world, villager.getBlockPos()));
                } else {
                    trailDigFails++;
                }
            } else if (!fallback && stuckRounds == 1) {
                skip += 4;
            } else if (!fallback) {
                wayOut(world, villager, villager.getBlockPos(), mina.nearLow(world, villager.getBlockPos()));
            } else {
                climbMode = (climbMode + 1) % 5;
                // rounds are ten seconds now: a call after a minute, a cry for help after a minute and a half
                if (stuckRounds >= 6) {
                    callOutStuck(world);
                }
                if (stuckRounds >= 9) {
                    cryForHelp(world);
                }
            }
        } else if (phase == Phase.RETURN) {
            if (viaMemoria) {
                BlockPos next = via.peekFirst();
                viaBlocked(world, villager, villager.getBlockPos(), next != null ? next : villager.getBlockPos(), world.getTime());
                if (!viaMemoria && !mina.known()) {
                    phase = Phase.SEEK;
                }
            } else if (stuckRounds >= 2 && !sinCavar(world, villager, villager.getBlockPos())) {
                // the trail does not lead there any more and no way on foot does: it digs on from where it is
                MCA.LOGGER.info("[Tropeles] {} no avanza hacia su mina desde {} y no ve paso a pie: cava desde aquí", name(), villager.getBlockPos().toShortString());
                phase = Phase.SEEK;
                nextMineReturn = world.getTime() + 1200;
            }
        } else {
            villager.getNavigation().stop();
            if (stuckRounds >= 6) {
                // a minute without getting closer: no way home from here, this is the camp now
                camping = true;
                homeTarget = villager.getBlockPos().toImmutable();
                enterHomeStore();
            } else if (stuckRounds == 2 || stuckRounds == 4) {
                BlockPos target = homePos(world, villager);
                BlockPos feet = villager.getBlockPos();
                // a way on foot to it first (up its own steps out of a cut, round what stops the path finder): never a
                // staircase of its own beside one that is there
                if (!pieA(world, villager, feet, target, 2.0, target, "un camino a pie hasta casa")) {
                    BlockPos goal = feet.offset(Direction.getFacing(target.getX() - feet.getX(), 0, target.getZ() - feet.getZ()), 2);
                    MCA.LOGGER.info("[Tropeles] {} no avanza hacia su casa y no ve paso a pie desde {}: cava un tramo hacia {}", name(), feet.toShortString(),
                            goal.toShortString());
                    plan.add(legGoal(world, villager, feet, goal));
                }
            }
        }
        return true;
    }

    /** Bigger is closer to where the phase is going. */
    private double progress(ServerWorld world, VillagerEntityMCA villager) {
        BlockPos feet = villager.getBlockPos();
        if (viaMemoria && (phase == Phase.HOME_UP || phase == Phase.RETURN)) {
            return -via.size();
        }
        if (phase == Phase.HOME_UP) {
            if (trailDig) {
                return -trailDigIdx;
            }
            if (!fallback) {
                int i = mina.nearLow(world, feet);
                return i < 0 ? -Mina.MAX - 1 : -i;
            }
            return feet.getY();
        }
        if (phase == Phase.RETURN) {
            int i = mina.nearHigh(world, feet);
            if (i >= 0) {
                return i;
            }
            BlockPos door = mina.entrance();
            return door == null ? 0 : -Math.sqrt(feet.getSquaredDistance(door)) - Mina.MAX;
        }
        BlockPos target = homePos(world, villager);
        return -Math.sqrt(horizontalSq(target, villager));
    }

    /**
     * While digging: nothing moved and nothing new in the bag for ten seconds (seven up on the land). Drop the plan,
     * forget that ore, turn; after a minute of that, head up and home and start over from there.
     */
    private boolean seekWatchdog(ServerWorld world, VillagerEntityMCA villager) {
        if ((villager.age % 20) != 0) {
            return false;
        }
        BlockPos feet = villager.getBlockPos();
        int items = 0;
        for (int i = 0; i < villager.getInventory().size(); i++) {
            items += villager.getInventory().getStack(i).getCount();
        }
        if (stuckAt == null || !feet.isWithinDistance(stuckAt, 1.5) || items != stuckItems) {
            if (stuckAt != null && !feet.isWithinDistance(stuckAt, 3)) {
                stuckRounds = 0;
            }
            stuckAt = feet;
            stuckItems = items;
            stuckTicks = 0;
            return false;
        }
        stuckTicks += 20;
        // standing about up on the land with nothing in view is noticed sooner: there is no rock face to work at
        boolean onLand = ore == null && Entorno.enLaSuperficie(world, villager);
        if (stuckTicks < (onLand ? SURFACE_STUCK_TICKS : STUCK_TICKS)) {
            return false;
        }
        stuckTicks = 0;
        stuckRounds++;
        motor.reset(world, villager);
        plan.clear();
        if (onLand) {
            // it never got the first step cut here: another spot, another way, never a climb out of a mine it is not in
            MCA.LOGGER.info("[Tropeles] {} lleva {} s quieto en {} ({}) sin cavar, haciendo «{}»: busca otro sitio para empezar su mina (vuelta {})",
                    name(), SURFACE_STUCK_TICKS / 20, feet.toShortString(), Entorno.lugar(world, villager).palabra(), Retina.memory(villager).doing(), stuckRounds);
            badSites.put(siteKey(feet), world.getTime() + SITE_FORGET);
            forceSite = true;
            siteCheckAt = 0;
            site = null;
            tunnelDir = (tunnelDir + (stuckRounds % 2 == 1 ? 1 : 3)) & 3;
            Retina.memory(villager).doing("atascado: busca otro sitio para cavar", world.getTime());
            return true;
        }
        if (ore != null) {
            ignore(ore, world.getTime());
            ore = null;
        }
        tunnelDir = (tunnelDir + (stuckRounds % 2 == 1 ? 1 : 3)) & 3;
        Retina.memory(villager).doing("atascado: cambia de rumbo", world.getTime());
        if (stuckRounds >= 6) {
            stuckRounds = 0;
            leave(world, villager, Trip.BAG);
        }
        return true;
    }

    /** Making or upgrading its own tools comes before digging; true while that keeps it busy. */
    private boolean gearUp(ServerWorld world, VillagerEntityMCA villager) {
        // its table and furnace off its own staircase when there is any other spot: a furnace left burning on the step
        // behind shuts the way up (and a table set there and picked up again looked like a dig-and-fill loop)
        progreso.keepClear(trailCells);
        boolean busy = progreso.tick(world, villager, this::say);
        if (busy && !wasGearing && !motor.midStep(villager)) {
            // two step machines must never drive one body: the task's stands down while Progreso works
            motor.reset(world, villager);
            plan.clear();
        }
        wasGearing = busy;
        return busy;
    }

    // ------------------------------------------------------------------ mining

    private void seek(ServerWorld world, VillagerEntityMCA villager) {
        VisualMemory memory = Retina.memory(villager);
        long now = world.getTime();
        if (ore == null && plan.isEmpty() && !motor.busy() && !Entorno.bajoTierra(world, villager) && oldMine(world, villager.getBlockPos())) {
            // up on the land, its own mine still there: back to it and on from its work face, never a new one beside it
            MCA.LOGGER.info("[Tropeles] {} está en la superficie con su mina en {}: vuelve a ella ({} pasos)", name(),
                    mina.entrance() == null ? "?" : mina.entrance().toShortString(), mina.size());
            beginReturn(world, villager);
            return;
        }
        if (ore == null && plan.isEmpty() && !motor.busy() && shortOfFace(world, villager.getBlockPos(), now)) {
            // standing on its own staircase or tunnel short of its work face (a trip back that stopped half way, a fight,
            // its tools): down to the face and on from its end, never a new staircase from the middle of the old one
            MCA.LOGGER.info("[Tropeles] {} está en su propia mina en {}, a {} pasos de su frente {}: baja a él en vez de cavar otra escalera", name(),
                    villager.getBlockPos().toShortString(), mina.size() - 1 - mina.nearHigh(world, villager.getBlockPos()),
                    mina.last() == null ? "?" : mina.last().toShortString());
            beginReturn(world, villager);
            return;
        }
        maybePlaceTorch(world, villager);

        if (ore != null && !stillOre(memory, ore)) {
            ore = null;
        }
        if (ore == null && ++chooseTicks >= 10) {
            // a look over the ores it remembers twice a second, not every tick
            chooseTicks = 0;
            ore = chooseOre(world, villager, memory);
            oreFails = 0;
            if (ore != null) {
                plan.clear();
            }
        }
        if (ore != null) {
            BlockPos feet = villager.getBlockPos();
            boolean underFeet = ore.equals(feet.down());
            // only on its feet and between steps: mid-jump the step goes on (and puts its block down), and the ore
            // waits until it has landed, so the ore and the step never take the one Breaker from each other
            boolean footed = Motor.settled(villager) && !motor.midStep(villager);
            if (!underFeet && footed && Retina.visibleFace(world, villager, ore, Retina.BLOCK_REACH) != null) {
                BlockState live = world.getBlockState(ore);
                if (!Mining.isOre(live)) {
                    // in sight and not ore any more: somebody else had it
                    memory.forget(ore);
                    ore = null;
                    return;
                }
                if (Mining.lavaSeen(memory, ore) && Hands.buildingBlockCount(villager) < 8) {
                    // lava against it and too few blocks to plug what would pour out
                    ignore(ore, now);
                    ore = null;
                    return;
                }
                if (motor.tend(world, villager)) {
                    memory.doing(motor.doing(), now);
                    return;
                }
                String name = live.getBlock().getName().getString();
                memory.doing(String.format("picando %s a %.1f bloques", name, Math.sqrt(villager.getEyePos().squaredDistanceTo(ore.toCenterPos()))), now);
                Motor.standStill(villager);
                int r = motor.breaker().tick(world, villager, ore);
                if (r == Hands.Breaker.DONE) {
                    motor.broke(world, villager, ore);
                    Recuerdos.anotar(villager, "hallazgo", "piqué " + name + " en " + ore.getX() + " " + ore.getY() + " " + ore.getZ(), ore);
                    announceOre(world, live);
                    levelCells = 0;
                    ore = null;
                } else if (r == Hands.Breaker.FAILED) {
                    ignore(ore, now);
                    ore = null;
                }
                return;
            }
            BlockState state = memory.stateAt(ore);
            String name = state == null ? "?" : state.getBlock().getName().getString();
            memory.doing(String.format("yendo hacia %s en %d %d %d", name, ore.getX(), ore.getY(), ore.getZ()), now);
            Motor.Result result = motor.step(world, villager, ore, true, true);
            if ("tapando el agua".equals(motor.doing())) {
                memory.doing("tapando el agua camino a " + name, now);
            }
            if (result == Motor.Result.BLOCKED) {
                if (++oreFails > 3) {
                    ignore(ore, now);
                    ore = null;
                }
            }
            return;
        }

        // nothing seen: open rock in front of the eyes, from ground that is its to dig
        if (plan.isEmpty() && !motor.busy() && needsSite(world, villager, memory, now)) {
            return;
        }
        if (plan.isEmpty()) {
            if (levelCells >= BARREN_CELLS) {
                barrenLevel(world, villager);
            }
            planExploration(world, villager);
        }
        BlockPos goal = plan.peekFirst();
        if (goal == null) {
            return;
        }
        int target = targetY(world, villager);
        memory.doing(villager.getBlockY() > target + 2 ? "bajando en escalera a buscar minerales"
                : villager.getBlockY() < target - 3 ? "subiendo de capa a buscar minerales" : "minando en ramas", now);
        Motor.Result result = motor.step(world, villager, goal, true, true);
        if ("tapando el agua".equals(motor.doing())) {
            memory.doing("tapando el agua que entra al túnel", now);
        }
        if (result == Motor.Result.ARRIVED) {
            plan.pollFirst();
        } else if (result == Motor.Result.BLOCKED) {
            plan.clear();
            motor.reset(world, villager);
            tunnelDir = (tunnelDir + (villager.getRandom().nextBoolean() ? 1 : 3)) & 3;
        }
    }

    /**
     * Before the first step of a staircase, up on the land: the ground it is about to cut must be nature's, not the
     * floor, a wall or a path of a village or a player, nor the inside of a village house. When it is not, it walks
     * to open natural land its eye saw away from any building (and explores for some when it saw none), and
     * starts there. True while it is busy getting to such a spot.
     */
    private boolean needsSite(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory, long now) {
        BlockPos feet = villager.getBlockPos();
        if (site == null && !siteExploring && !forceSite
                && (now < siteCheckAt || feet.getY() <= targetY(world, villager) + 2 || Entorno.bajoTierra(world, villager)
                || Entorno.profundidad(world, villager) > 2)) {
            return false; // already in the rock, not going down, or judged fine a moment ago
        }
        if (site != null) {
            double dx = site.getX() + 0.5 - villager.getX();
            double dz = site.getZ() + 0.5 - villager.getZ();
            boolean there = dx * dx + dz * dz <= 2.25;
            boolean lost = ++siteTicks > 400;
            villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
            if (!there && !lost && villager.getNavigation().isIdle() && (siteTicks % 20) == 0) {
                // the path ran out short of it: on from here, or that spot is out of reach
                lost = !villager.getNavigation().startMovingTo(site.getX() + 0.5, site.getY(), site.getZ() + 0.5, 0.65);
            }
            if (!there && !lost) {
                memory.doing("yendo a un buen sitio para empezar su mina", now);
                return true;
            }
            villager.getNavigation().stop();
            if (!there && dx * dx + dz * dz > 9) {
                MCA.LOGGER.info("[Tropeles] {} no llega al sitio {} para su mina: busca otro", name(), site.toShortString());
                badSites.put(siteKey(site), now + SITE_FORGET);
            }
            site = null;
            // judged again below, from where it stands now
        }
        if (siteExploring && (villager.age % 10) != 0) {
            memory.doing("buscando dónde empezar su mina", now);
            if (!explorar.tick(world, villager, Explorar.Busca.SITIO)) {
                stopSiteSearch(now, "exploró todo alrededor sin ver tierra natural lejos de las casas");
                return false;
            }
            return true;
        }
        String why = forceSite ? "lleva rato parado ahí sin poder cavar" : badSpot(world, villager, feet);
        forceSite = false;
        if (why == null) {
            if (siteExploring) {
                MCA.LOGGER.info("[Tropeles] {} encontró dónde empezar su mina en {}", name(), feet.toShortString());
                siteExploring = false;
            }
            siteCheckAt = now + 100;
            return false;
        }
        badSites.put(siteKey(feet), now + SITE_FORGET);
        BlockPos next = chooseSite(world, villager, feet, now);
        if (next != null) {
            MCA.LOGGER.info("[Tropeles] {} no empieza su mina en {} ({}, {}): va a tierra natural que vio en {}", name(), feet.toShortString(), why,
                    Entorno.lugar(world, villager).palabra(), next.toShortString());
            site = next;
            siteTicks = 0;
            siteExploring = false;
            memory.doing("yendo a un buen sitio para empezar su mina", now);
            villager.getNavigation().startMovingTo(site.getX() + 0.5, site.getY(), site.getZ() + 0.5, 0.65);
            return true;
        }
        if (!siteExploring) {
            MCA.LOGGER.info("[Tropeles] {} no empieza su mina en {} ({}) y no vio tierra natural lejos de las casas: explora", name(),
                    feet.toShortString(), why);
            siteExploring = true;
            explorar.reset();
        }
        memory.doing("buscando dónde empezar su mina", now);
        if (!explorar.tick(world, villager, Explorar.Busca.SITIO)) {
            stopSiteSearch(now, "exploró todo alrededor sin ver tierra natural lejos de las casas");
            return false;
        }
        return true;
    }

    /** Nothing better anywhere it looked: it digs where it can from here, and the watchdog takes it from there. */
    private void stopSiteSearch(long now, String why) {
        siteExploring = false;
        siteCheckAt = now + SITE_FORGET;
        explorar.reset();
        MCA.LOGGER.info("[Tropeles] {} deja de buscar sitio ({}): cava donde pueda", name(), why);
    }

    /** Why the mine cannot start where it stands, or null when the ground here is its to cut. */
    @Nullable
    private String badSpot(ServerWorld world, VillagerEntityMCA villager, BlockPos feet) {
        if (Obras.inVillageBuilding(world, feet)) {
            return "está dentro de una casa del pueblo";
        }
        Obras.Kind floor = Obras.kind(world, villager, feet.down());
        if (floor == Obras.Kind.BUILDING || floor == Obras.Kind.PLAYER_BUILT || floor == Obras.Kind.CROP) {
            return "el suelo es " + floor.label;
        }
        for (int turn = 0; turn < 4; turn++) {
            Direction way = Direction.fromHorizontal((tunnelDir + turn) & 3);
            if (stairsFit(world, villager, feet, way)) {
                if (turn != 0) {
                    tunnelDir = way.getHorizontal();
                }
                return null;
            }
        }
        return "no hay tierra natural para bajar por ningún lado";
    }

    /** The first two steps down that way cut only nature (or open air): no floor, wall or path of anybody's build. */
    private static boolean stairsFit(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, Direction way) {
        for (int k = 1; k <= 2; k++) {
            BlockPos step = feet.offset(way, k).down(k);
            for (BlockPos cell : new BlockPos[]{step.up(2), step.up(), step}) {
                if (Mining.passable(world, cell)) {
                    continue;
                }
                if (!Mining.mayDig(world, cell, world.getBlockState(cell)) || !Obras.mayBreak(world, villager, cell)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Open natural land or rock its eye saw near its own level, nearest first, away from anything built and from
     * water and lava, and not a spot already found no good.
     */
    @Nullable
    private BlockPos chooseSite(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, long now) {
        badSites.values().removeIf(until -> until < now);
        MapaVisto mapa = Retina.memory(villager).mapa();
        int tries = 0;
        for (BlockPos cell : mapa.find(feet, 40, MapaVisto.LAND | MapaVisto.ROCK)) {
            BlockPos top = mapa.land(cell.getX(), cell.getZ());
            if (top == null || top.isWithinDistance(feet, 3) || Math.abs(top.getY() - feet.getY()) > 6) {
                continue;
            }
            Long until = badSites.get(siteKey(top));
            if (until != null && until > now || (mapa.flags(top.getX(), top.getZ()) & (MapaVisto.WATER | MapaVisto.LAVA | MapaVisto.BUILT)) != 0
                    || builtNear(mapa, top)) {
                continue;
            }
            if (++tries > 24) {
                break; // a look at the nearest few is enough; the village test below is not free
            }
            if (Obras.inVillageBuilding(world, top)) {
                continue;
            }
            return top;
        }
        return null;
    }

    /** Its eye saw a building within about six blocks of this spot. */
    private static boolean builtNear(MapaVisto mapa, BlockPos pos) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if ((mapa.flags(pos.getX() + dx * MapaVisto.CELL, pos.getZ() + dz * MapaVisto.CELL) & MapaVisto.BUILT) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long siteKey(BlockPos pos) {
        return ((long) (pos.getX() >> 1) << 32) ^ ((pos.getZ() >> 1) & 0xffffffffL);
    }

    /** A whole stretch of this layer and not one ore: the branch mine moves a few blocks up or down. */
    private void barrenLevel(ServerWorld world, VillagerEntityMCA villager) {
        levelCells = 0;
        int target = targetY(world, villager);
        if (Math.abs(villager.getBlockY() - target) > 3) {
            return; // still on its way to the layer
        }
        // 0, +4, -4, +8, -8, +12, -12, then back to the usual layer
        int next = mina.levelShift > 0 ? -mina.levelShift : -mina.levelShift + 4;
        mina.levelShift = Math.abs(next) > LEVEL_SHIFT_MAX ? 0 : next;
        int now = targetY(world, villager);
        if (now != target) {
            say("chore.mining.level", now);
            MCA.LOGGER.info("[Tropeles] {} no encuentra nada en la capa {}: prueba la {}", name(), target, now);
        }
    }

    /** The ore it is after now, by what it was asked for and the pickaxe it has (iron first for an iron pickaxe). */
    private String goalOre(ServerWorld world, VillagerEntityMCA villager, int tier) {
        String wish = WANTED.get(villager);
        if (wish != null && world.getRegistryKey() == World.OVERWORLD && ORE_DEPTH.containsKey(wish)) {
            if ("emerald".equals(wish) && !mountains(world, villager)) {
                if (!warnedEmerald) {
                    warnedEmerald = true;
                    say("chore.mining.noemerald");
                }
            } else if (tier < ORE_TIER.getOrDefault(wish, 1)) {
                if (!warnedTier) {
                    warnedTier = true;
                    say("chore.mining.needironpick");
                }
                return "iron";
            } else {
                return wish;
            }
        }
        return tier >= 3 ? "diamond" : "iron";
    }

    private boolean mountains(ServerWorld world, VillagerEntityMCA villager) {
        BlockPos door = mina.entrance();
        BlockPos at = door != null ? door : base != null ? base : villager.getBlockPos();
        return world.getBiome(at).isIn(MOUNTAINS);
    }

    /** The height of the feet for branch mining now: by the ore it is after, moved by barren layers, above the lava. */
    private int targetY(ServerWorld world, VillagerEntityMCA villager) {
        int bottom = world.getBottomY();
        int tier = Progreso.bestPickTier(villager);
        int y;
        if (world.getRegistryKey() == World.OVERWORLD) {
            y = ORE_DEPTH.getOrDefault(goalOre(world, villager, tier), 16);
            // coal and emerald lie high: never a layer above the ground over its head (it would build a staircase
            // into the sky to get there), always some rock between it and the open air
            int ground = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, villager.getBlockX(), villager.getBlockZ());
            y = Math.min(y, ground - 8);
        } else if (world.getRegistryKey() == World.NETHER) {
            // ancient debris lies low; quartz is everywhere, so it stays on its layer
            y = tier >= 4 ? 15 : villager.getBlockY();
        } else {
            y = villager.getBlockY();
        }
        return Math.max(bottom + 9, y + mina.levelShift);
    }

    /** Next stretch: a staircase to the layer, then a corridor with side branches on both sides every third block. */
    private void planExploration(ServerWorld world, VillagerEntityMCA villager) {
        BlockPos feet = villager.getBlockPos();
        int depth = targetY(world, villager);
        BlockPos oven = progreso.cookingAt();
        if (oven != null && !Mining.onSurface(world, oven.up())) {
            // iron in the furnace: branch-mine at its level and stay within reach of it (a furnace out on the
            // surface, meat cooking after a hunt, is fetched when it goes too far: no trenches across the land)
            depth = oven.getY();
            int dx = oven.getX() - feet.getX();
            int dz = oven.getZ() - feet.getZ();
            if (dx * dx + dz * dz > 100) {
                tunnelDir = Direction.getFacing(dx, 0, dz).getHorizontal();
            }
        }
        boolean down = feet.getY() > depth + 2;
        Direction forward = Direction.fromHorizontal(tunnelDir);
        if (wetAhead(villager, feet, forward) || lavaAhead(villager, feet, forward, down)) {
            // the eye saw water (with too few blocks to wall it off) or lava that way: another way
            for (int turn = 1; turn <= 3; turn++) {
                Direction other = Direction.fromHorizontal((tunnelDir + turn) & 3);
                if (!wetAhead(villager, feet, other) && !lavaAhead(villager, feet, other, down)) {
                    tunnelDir = other.getHorizontal();
                    forward = other;
                    break;
                }
            }
        }
        if (villager.isTouchingWater() && !Mining.outdoors(world, feet)) {
            // standing in running water: a staircase down here would only fill up behind it; first walk out to dry rock
            for (int turn = 0; turn < 4; turn++) {
                Direction dir = Direction.fromHorizontal((tunnelDir + turn) & 3);
                boolean dry = true;
                for (int k = 1; k <= 3 && dry; k++) {
                    dry = !Agua.water(world, feet.offset(dir, k)) && !Agua.water(world, feet.offset(dir, k).up());
                }
                if (dry) {
                    tunnelDir = dir.getHorizontal();
                    for (int k = 1; k <= 3; k++) {
                        plan.add(feet.offset(dir, k));
                    }
                    return;
                }
            }
        }
        if (down) {
            int steps = Math.min(4, feet.getY() - depth);
            plan.add(feet.offset(forward, steps).down(steps));
            return;
        }
        if (feet.getY() < depth - 3) {
            // deeper than what it is after now: a staircase up to the layer
            int steps = Math.min(4, depth - feet.getY());
            plan.add(feet.offset(forward, steps).up(steps));
            return;
        }
        for (int k = 1; k <= 3; k++) {
            plan.add(feet.offset(forward, k));
        }
        // a branch to each side, two blocks of rock between branches: every block passes within a block of its eyes
        BlockPos junction = feet.offset(forward, 3);
        Direction first = branchLeft ? forward.rotateYCounterclockwise() : forward.rotateYClockwise();
        branchLeft = !branchLeft;
        for (Direction side : new Direction[]{first, first.getOpposite()}) {
            if (wetAhead(villager, junction, side) || lavaAhead(villager, junction, side, false)) {
                continue;
            }
            for (int j = 1; j <= 8; j++) {
                plan.add(junction.offset(side, j));
            }
            plan.add(junction);
        }
    }

    /** The eye remembers water in the next few cells that way, and the bag is short of blocks to wall it off. */
    private boolean wetAhead(VillagerEntityMCA villager, BlockPos feet, Direction forward) {
        if (Hands.buildingBlockCount(villager) >= 16) {
            return false;
        }
        VisualMemory memory = Retina.memory(villager);
        for (int k = 1; k <= 4; k++) {
            BlockPos cell = feet.offset(forward, k);
            if (Agua.seenNear(memory, cell) || Agua.seenNear(memory, cell.down()) || Agua.seenNear(memory, cell.up())) {
                return true;
            }
        }
        return false;
    }

    /** The eye saw lava in or around the next two cells that way (going down, the cells of the staircase). */
    private boolean lavaAhead(VillagerEntityMCA villager, BlockPos feet, Direction forward, boolean down) {
        VisualMemory memory = Retina.memory(villager);
        for (int k = 1; k <= 2; k++) {
            BlockPos cell = feet.offset(forward, k).down(down ? k : 0);
            for (int dy = -1; dy <= 2; dy++) {
                if (Mining.lavaSeen(memory, cell.up(dy))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean stillOre(VisualMemory memory, BlockPos pos) {
        BlockState state = memory.stateAt(pos);
        return state != null && Mining.isOre(state);
    }

    @Nullable
    private BlockPos chooseOre(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory) {
        ItemStack tool = villager.getMainHandStack();
        long now = world.getTime();
        BlockPos feet = villager.getBlockPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int blocks = Hands.buildingBlockCount(villager);
        boolean lowOnBlocks = blocks < 6;
        SimpleInventory inv = villager.getInventory();
        boolean needsCoal = inv.count(Items.TORCH) < 4 && inv.count(Items.COAL) + inv.count(Items.CHARCOAL) == 0;
        String wish = WANTED.get(villager);
        for (VisualMemory.SeenBlock seen : memory.ores()) {
            if (!Mining.isOre(seen.state) || isIgnored(seen.pos, now) || !tool.isSuitableFor(seen.state)) {
                continue;
            }
            if (lowOnBlocks && Agua.seenNear(memory, seen.pos)) {
                continue; // ore in the water and nothing to wall it off with
            }
            if (blocks < 8 && Mining.lavaSeen(memory, seen.pos)) {
                continue; // against lava, with nothing to plug it
            }
            double distance = Math.sqrt(seen.pos.getSquaredDistance(feet));
            if (distance > ORE_SEARCH_RADIUS) {
                continue;
            }
            // up or down costs more than along: stairs and pillars are slow
            double score = distance + 0.5 * Math.abs(seen.pos.getY() - feet.getY()) - Mining.oreValue(seen.state) / 10.0;
            if (wish != null && Registries.BLOCK.getId(seen.state.getBlock()).getPath().contains(wish)) {
                score -= 25; // what it was asked for comes first
            }
            if (needsCoal && (seen.state.isOf(Blocks.COAL_ORE) || seen.state.isOf(Blocks.DEEPSLATE_COAL_ORE))) {
                score -= 12; // no torches and no coal: coal first, a player would
            }
            if (score < bestScore) {
                bestScore = score;
                best = seen.pos;
            }
        }
        return best;
    }

    private void ignore(BlockPos pos, long now) {
        ignored.put(pos.asLong(), now + 6000);
        if (ignored.size() > 512) {
            Iterator<Long> it = ignored.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    private boolean isIgnored(BlockPos pos, long now) {
        Long until = ignored.get(pos.asLong());
        if (until == null) {
            return false;
        }
        if (until < now) {
            ignored.remove(pos.asLong());
            return false;
        }
        return true;
    }

    private void maybePlaceTorch(ServerWorld world, VillagerEntityMCA villager) {
        if (motor.midStep(villager) || motor.climbingUp()) {
            return; // a torch at its feet takes the place of the next pillar block
        }
        if ((villager.age & 31) != 0) {
            return;
        }
        BlockPos feet = villager.getBlockPos();
        if (world.getLightLevel(feet) > 7) {
            return;
        }
        int slot = InventoryUtils.getFirstSlotContainingItem(villager.getInventory(), stack -> stack.isOf(Items.TORCH));
        if (slot < 0 || !world.getBlockState(feet).isAir() || !Blocks.TORCH.getDefaultState().canPlaceAt(world, feet)) {
            return;
        }
        if (Hands.place(world, villager, feet, villager.getInventory().getStack(slot))) {
            // every torch it sets is a mark on its way back, as a player finds the way out torch to torch
            Recuerdos.marcar(villager, Recuerdos.ANTORCHA, feet);
        }
    }

    private void announceOre(ServerWorld world, BlockState state) {
        int value = Mining.oreValue(state);
        long now = world.getTime();
        if (value < 30 && announcedAny) {
            return;
        }
        if (now - lastOreAnnounce < 200) {
            return;
        }
        lastOreAnnounce = now;
        announcedAny = true;
        say("chore.mining.orenotify", state.getBlock().getName());
    }

    // ------------------------------------------------------------------ danger: fight, walls, calls, retreat

    private boolean troubled() {
        return phase == Phase.FIGHT || phase == Phase.SHELTER_DIG || phase == Phase.SHELTER_ENTER || phase == Phase.SHELTER_SEAL
                || phase == Phase.ESCAPE || phase == Phase.RECOVER;
    }

    /** A monster in sight: one it can take on is fought, anything else is hidden from. True when it reacted. */
    private boolean threat(ServerWorld world, VillagerEntityMCA villager) {
        if (troubled() || (villager.age & 3) != 0) {
            return false;
        }
        MobEntity threat = Threats.nearest(world, villager.getBlockPos(), 10, villager, null);
        if (threat == null || CombatMind.ignoring(villager, threat)) {
            return false;
        }
        resume = phase == Phase.HOME_STORE ? Phase.HOME_WALK : phase;
        dangerFrom = threat.getBlockPos().toImmutable();
        escapes = 0;
        plan.clear();
        if (phase == Phase.FORAGE) {
            // the hunt is off: the fight is with the monster, not the cow it was chasing
            prey = null;
            CombatMind.disengage(villager);
        }
        if (canFight(world, villager, threat)) {
            motor.reset(world, villager);
            phase = Phase.FIGHT;
            fightTicks = 0;
            digTicks = 0;
            backOffTicks = 0;
            long now = world.getTime();
            if (now - lastFightCall > 600) {
                lastFightCall = now;
                say("chore.mining.fight", threat.getName());
            }
            MCA.LOGGER.info("[Tropeles] {} le hace frente a {}", name(), threat.getName().getString());
            Recuerdos.anotar(villager, "pelea", "le hice frente a " + threat.getName().getString() + " en " + villager.getBlockX() + " "
                    + villager.getBlockY() + " " + villager.getBlockZ(), villager.getBlockPos());
            return true;
        }
        beginDanger(world, threat);
        return true;
    }

    /**
     * Armed and grown, never a creeper. With a sword or axe it takes on what it sees (a skeleton included) unless it is
     * already badly hurt; healthy and armed it faces two at once, never more.
     */
    private boolean canFight(ServerWorld world, VillagerEntityMCA villager, MobEntity threat) {
        float health = villager.getHealth() / villager.getMaxHealth();
        if (threat instanceof CreeperEntity || health < 0.3F || villager.getAgeState().ordinal() < AgeState.TEEN.ordinal()) {
            return false;
        }
        if (!CombatMind.hasMelee(villager) && !(CombatMind.hasRanged(villager) && Archery.hasAmmo(villager))) {
            return false;
        }
        int allowed = health >= 0.6F ? 2 : 1;
        int seen = 0;
        for (MobEntity mob : world.getNonSpectatingEntities(MobEntity.class, new Box(villager.getBlockPos()).expand(12))) {
            if (Threats.isThreat(mob, villager, null) && !(mob instanceof CreeperEntity && mob != threat)
                    && Retina.canSee(world, villager, mob) && ++seen > allowed) {
                return false;
            }
        }
        return true;
    }

    private void fight(ServerWorld world, VillagerEntityMCA villager) {
        LivingEntity target = CombatMind.pickTarget(world, villager, null, 12);
        if (target == null && digTicks > 0 && digTarget != null && digTarget.isAlive() && !digTarget.isRemoved() && digTarget.getWorld() == world
                && digTarget.squaredDistanceTo(villager) < 16 * 16 && !CombatMind.ignoring(villager, digTarget)) {
            // looking at the rock it breaks it does not see the enemy behind it, but it knows where it is
            target = digTarget;
        }
        if (target == null && fightTarget != null && fightTarget.isAlive() && !fightTarget.isRemoved() && fightTarget.getWorld() == world
                && world.getTime() - fightTargetTick < 200 && fightTarget.squaredDistanceTo(villager) < 16 * 16
                && !CombatMind.ignoring(villager, fightTarget)) {
            // knocked back behind the rock or round a corner: a player knows it is still there and goes after it
            target = fightTarget;
        } else if (target != null) {
            fightTarget = target;
            fightTargetTick = world.getTime();
        }
        if (target == null) {
            fightTarget = null;
            digTicks = 0;
            digTarget = null;
            Recuerdos.anotar(villager, "pelea", "terminé la pelea, no queda nada que me ataque", villager.getBlockPos());
            // nothing left to fight: what it dropped (nobody eats rotten flesh), and back to what it was doing
            CombatMind.disengage(villager);
            Hands.collect(world, villager, villager.getBlockPos(), 2.0, stack -> !stack.isOf(Items.ROTTEN_FLESH));
            afterTrouble(world, villager);
            return;
        }
        dangerFrom = target.getBlockPos().toImmutable();
        // it only thinks of running when it is really low, and finishes off an enemy that is about to fall
        boolean almostDead = target.getHealth() <= 4.0F && villager.getHealth() >= villager.getMaxHealth() * 0.12F;
        boolean hurt = villager.getHealth() < villager.getMaxHealth() * (CombatMind.hasMelee(villager) ? 0.2F : 0.3F) && !almostDead;
        if (target instanceof CreeperEntity || hurt || ++fightTicks > 1200) {
            if (fightTicks > 1200) {
                CombatMind.giveUp(villager, target, 2400); // a minute on one enemy: it is not winning this one
            }
            digTicks = 0;
            CombatMind.disengage(villager);
            if (target instanceof MobEntity mob) {
                beginDanger(world, mob);
            } else {
                afterTrouble(world, villager);
            }
            return;
        }
        double distance = Retina.distanceToBox(villager.getEyePos(), target.getBoundingBox());
        boolean ranged = CombatMind.hasRanged(villager) && Archery.hasAmmo(villager);
        if (digTicks > 0 || !ranged && CombatMind.stuck(villager) > 40 && distance > Retina.ENTITY_REACH) {
            // its legs do not get it there: rock, a step too high, a gap. A player digs through to it, pickaxe in hand
            if (digToward(world, villager, target, distance)) {
                return;
            }
        }
        if (CombatMind.fight(world, villager, null, target)) {
            backOffTicks = 0;
        } else if (++backOffTicks > 60 && target instanceof MobEntity mob) {
            // it keeps backing off: hide instead
            CombatMind.disengage(villager);
            beginDanger(world, mob);
        }
    }

    /**
     * Digs and steps toward an enemy it cannot walk to. True while digging is what it does this tick; false when it
     * is close and in sight again (the fight goes on). Gives up on one it gets no closer to after a while, or when
     * the way is blocked for good: it leaves that one alone (and takes cover from one that shoots).
     */
    private boolean digToward(ServerWorld world, VillagerEntityMCA villager, LivingEntity target, double distance) {
        long now = world.getTime();
        boolean seen = Retina.canSee(world, villager, target);
        if (digTicks > 0 && distance <= Retina.ENTITY_REACH && seen && !motor.midStep(villager)) {
            digTicks = 0;
            motor.reset(world, villager);
            return false; // in reach: the sword again
        }
        if (digTicks == 0) {
            digClosest = distance;
            digGain = 0;
            motor.reset(world, villager);
            villager.getNavigation().stop();
            MCA.LOGGER.info("[Tropeles] {} no llega a {} caminando: cava hacia él", name(), target.getName().getString());
        }
        digTicks++;
        digTarget = target;
        if (distance < digClosest - 0.5) {
            digClosest = distance;
            digGain = digTicks;
        }
        boolean blocked = false;
        if (Progreso.countPicks(villager) > 0 && equipPick(villager)) {
            Retina.memory(villager).doing("cavando hacia " + target.getName().getString() + String.format(" a %.1f bloques", distance), now);
            Motor.Result result = motor.step(world, villager, target.getBlockPos(), true, true);
            blocked = result == Motor.Result.BLOCKED && ++digFails > 3;
            if (result != Motor.Result.BLOCKED) {
                digFails = 0;
            }
        } else {
            blocked = true;
        }
        if (blocked || digTicks - digGain > 200 || digTicks > 1200) {
            MCA.LOGGER.info("[Tropeles] {} no puede llegar a {} ({}): lo deja en paz", name(), target.getName().getString(),
                    blocked ? "camino cerrado" : "no se acerca");
            digTicks = 0;
            digFails = 0;
            if (!motor.midStep(villager)) {
                motor.reset(world, villager);
            }
            CombatMind.giveUp(villager, target, 2400);
            if (target instanceof net.minecraft.entity.mob.AbstractSkeletonEntity && target instanceof MobEntity mob && seen) {
                beginDanger(world, mob); // it keeps shooting: cover first
            } else {
                afterTrouble(world, villager);
            }
        }
        return true;
    }

    /** The danger is over: back to whatever it was doing, the pickaxe back in hand. */
    private void afterTrouble(ServerWorld world, VillagerEntityMCA villager) {
        motor.reset(world, villager);
        plan.clear();
        ore = null;
        escapes = 0;
        equipPick(villager);
        Phase back = resume;
        if (back == Phase.FIGHT || back == Phase.SHELTER_DIG || back == Phase.SHELTER_ENTER || back == Phase.SHELTER_SEAL
                || back == Phase.ESCAPE || back == Phase.RECOVER) {
            back = Phase.SEEK;
        }
        if (back == Phase.HOME_STORE) {
            back = Phase.HOME_WALK;
        }
        phase = back;
        resetWatch();
    }

    private void beginDanger(ServerWorld world, MobEntity threat) {
        dangerFrom = threat.getBlockPos().toImmutable();
        Recuerdos.anotar(villager, "pelea", "me escondí de " + threat.getName().getString() + " en " + villager.getBlockX() + " "
                + villager.getBlockY() + " " + villager.getBlockZ(), villager.getBlockPos());
        cryForHelp(world);
        motor.reset(world, villager);
        plan.clear();
        villager.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
        shelterTicks = 0;
        if (tryBeginShelter(world, threat)) {
            return;
        }
        beginEscape(world);
    }

    private boolean tryBeginShelter(ServerWorld world, MobEntity threat) {
        BlockPos feet = villager.getBlockPos();
        Direction away = awayFrom(villager, threat.getBlockPos());
        BlockPos retreat = feet.offset(away);
        pocket = retreat;
        if (Mining.passable(world, retreat) && Mining.passable(world, retreat.up()) && Mining.standable(world, retreat.down()) && !Mining.heat(world, retreat)) {
            phase = Phase.SHELTER_ENTER;
            return true;
        }
        if (Mining.mayDig(world, retreat, world.getBlockState(retreat)) && !Mining.heat(world, retreat) && !Mining.heat(world, retreat.up())) {
            phase = Phase.SHELTER_DIG;
            return true;
        }
        return false;
    }

    /**
     * No shelter to hand: away from the danger along its own trail, or two blocks into the rock away from it,
     * never down; there it walls itself in.
     */
    private void beginEscape(ServerWorld world) {
        BlockPos feet = villager.getBlockPos();
        escapeTicks = 0;
        escapes++;
        escapeGoal = null;
        int i = mina.nearLow(world, feet);
        if (i >= 0 && mina.size() > 1 && dangerFrom != null) {
            BlockPos up = mina.crumb(Math.max(0, i - 6));
            BlockPos deeper = mina.crumb(Math.min(mina.size() - 1, i + 6));
            BlockPos away = dangerFrom.getSquaredDistance(up) >= dangerFrom.getSquaredDistance(deeper) ? up : deeper;
            if (!away.equals(feet)) {
                escapeGoal = away;
            }
        }
        if (escapeGoal == null) {
            Direction away = dangerFrom != null ? awayFrom(villager, dangerFrom) : villager.getHorizontalFacing().getOpposite();
            escapeGoal = feet.offset(away, 2);
        }
        phase = Phase.ESCAPE;
    }

    private void shelterDig(ServerWorld world, VillagerEntityMCA villager) {
        if (pocket == null) {
            beginEscape(world);
            return;
        }
        for (BlockPos cell : new BlockPos[]{pocket.up(), pocket}) {
            if (!Mining.passable(world, cell)) {
                if (!Motor.settled(villager)) {
                    return; // land first
                }
                Motor.standStill(villager);
                int r = motor.breaker().tick(world, villager, cell);
                if (r == Hands.Breaker.FAILED) {
                    beginEscape(world);
                } else if (r == Hands.Breaker.DONE) {
                    Hands.collect(world, villager, cell, 2.5);
                }
                return;
            }
        }
        phase = Phase.SHELTER_ENTER;
        shelterTicks = 0;
    }

    private void shelterEnter(ServerWorld world, VillagerEntityMCA villager) {
        if (pocket == null || ++shelterTicks > 100) {
            // it cannot get in there: retreat instead
            beginEscape(world);
            return;
        }
        villager.getMoveControl().moveTo(pocket.getX() + 0.5, pocket.getY(), pocket.getZ() + 0.5, 0.8);
        if (villager.getBlockPos().equals(pocket) && villager.isOnGround()) {
            Motor.standStill(villager);
            phase = Phase.SHELTER_SEAL;
            cryForHelp(world);
            say("chore.mining.shelter");
            MCA.LOGGER.info("[Tropeles] {} se tapia en {}", name(), pocket.toShortString());
        }
    }

    /** Every open side of the cell it stands in, at the feet and at the head, and over the head. */
    private static List<BlockPos> openings(ServerWorld world, BlockPos at) {
        List<BlockPos> open = new ArrayList<>();
        for (BlockPos level : new BlockPos[]{at, at.up()}) {
            for (Direction dir : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
                BlockPos cell = level.offset(dir);
                if (Mining.passable(world, cell) && world.getBlockState(cell).isReplaceable()) {
                    open.add(cell);
                }
            }
        }
        BlockPos over = at.up(2);
        if (Mining.passable(world, over) && world.getBlockState(over).isReplaceable()) {
            open.add(over);
        }
        return open;
    }

    /** One block a tick into the holes around it, like a player boxing themselves in. */
    private void shelterSeal(ServerWorld world, VillagerEntityMCA villager) {
        keepDistressAlive(world);
        if (!Motor.settled(villager)) {
            return;
        }
        Motor.standStill(villager);
        List<BlockPos> holes = openings(world, villager.getBlockPos());
        for (BlockPos hole : holes) {
            if (placeSolid(world, hole)) {
                sealed.add(hole.toImmutable());
                return;
            }
        }
        if (!holes.isEmpty() && escapes < 2) {
            // holes it cannot close (no blocks, nothing to set them against): away from here
            beginEscape(world);
            return;
        }
        phase = Phase.RECOVER;
        calmTicks = 0;
    }

    private void escape(ServerWorld world, VillagerEntityMCA villager) {
        keepDistressAlive(world);
        villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        Motor.Result result = escapeGoal == null ? Motor.Result.BLOCKED : motor.step(world, villager, escapeGoal, true, true);
        if (result == Motor.Result.RUNNING && ++escapeTicks < 200) {
            Retina.memory(villager).doing("huyendo del peligro", world.getTime());
            return;
        }
        if (motor.midStep(villager)) {
            return; // land first
        }
        motor.reset(world, villager);
        BlockPos at = villager.getBlockPos();
        say("chore.mining.escape", at.getX(), at.getY(), at.getZ());
        MCA.LOGGER.info("[Tropeles] {} se retira a {} y se tapia", name(), at.toShortString());
        // where it got to, it walls itself in
        pocket = at;
        phase = Phase.SHELTER_SEAL;
    }

    /**
     * Waits in its shelter until it has neither seen nor heard anything for ten seconds, then takes its own blocks
     * back and goes on with what it was doing.
     */
    private void recover(ServerWorld world, VillagerEntityMCA villager) {
        MobEntity threat = Threats.nearest(world, villager.getBlockPos(), 8, villager, null);
        BlockPos heard = threat == null ? Threats.footstep(world, villager, null) : null;
        if (threat != null || heard != null) {
            calmTicks = 0;
            keepDistressAlive(world);
            if (threat != null) {
                dangerFrom = threat.getBlockPos().toImmutable();
                if (!openings(world, villager.getBlockPos()).isEmpty() && villager.squaredDistanceTo(threat) < 25) {
                    // it can get at it: wall up again, or retreat
                    beginDanger(world, threat);
                }
            }
            return;
        }
        if (++calmTicks < 200) {
            return;
        }
        if (reopen(world, villager)) {
            return;
        }
        say("chore.mining.safe");
        MCA.LOGGER.info("[Tropeles] {} sale de su refugio", name());
        afterTrouble(world, villager);
    }

    /** Breaks its own wall blocks back into the bag, last placed first. True while at it. */
    private boolean reopen(ServerWorld world, VillagerEntityMCA villager) {
        while (!sealed.isEmpty()) {
            BlockPos spot = sealed.get(sealed.size() - 1);
            if (world.getBlockState(spot).isReplaceable() || Obras.kind(world, villager, spot) != Obras.Kind.OWN
                    || Retina.visibleFace(world, villager, spot, Retina.BLOCK_REACH) == null) {
                sealed.remove(sealed.size() - 1); // gone, not its own any more, or out of reach: left as it is
                continue;
            }
            if (!Motor.settled(villager)) {
                return true;
            }
            Motor.standStill(villager);
            equipPick(villager);
            Retina.memory(villager).doing("abriendo su refugio", world.getTime());
            int r = motor.breaker().tick(world, villager, spot);
            if (r == Hands.Breaker.DONE) {
                motor.broke(world, villager, spot);
                sealed.remove(sealed.size() - 1);
            } else if (r == Hands.Breaker.FAILED) {
                sealed.remove(sealed.size() - 1);
            }
            return true;
        }
        return false;
    }

    /** A wall block from the bag, placed like a player: false when no face to set it against is in sight. */
    private boolean placeSolid(ServerWorld world, BlockPos pos) {
        if (!world.getBlockState(pos).isReplaceable() || Mining.heat(world, pos)) {
            return false;
        }
        if (!world.isInBuildLimit(pos) || !world.getWorldBorder().contains(pos)) {
            return false;
        }
        ItemStack block = Hands.buildingBlock(villager);
        if (block.isEmpty()) {
            return false;
        }
        // no face of a neighbour in sight to set it against: a player could not place it either
        return Hands.place(world, villager, pos, block);
    }

    private void cryForHelp(ServerWorld world) {
        long now = world.getTime();
        getAssigningPlayer().ifPresent(assigner -> DistressCalls.raise(world, villager, assigner.getUuid()));
        if (now - lastCry > 400) {
            lastCry = now;
            BlockPos pos = villager.getBlockPos();
            say("chore.mining.help", pos.getX(), pos.getY(), pos.getZ());
        }
    }

    private void keepDistressAlive(ServerWorld world) {
        if ((villager.age % 80) != 0) {
            return;
        }
        getAssigningPlayer().ifPresent(player -> DistressCalls.raise(world, villager, player.getUuid()));
    }

    private boolean equipPick(VillagerEntityMCA villager) {
        return Progreso.holdBestPick(villager);
    }

    private boolean tooYoung(VillagerEntityMCA villager) {
        return villager.getAgeState().ordinal() <= AgeState.TODDLER.ordinal();
    }

    private static Direction awayFrom(VillagerEntityMCA villager, BlockPos from) {
        double dx = villager.getX() - (from.getX() + 0.5);
        double dz = villager.getZ() - (from.getZ() + 0.5);
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
