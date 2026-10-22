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
import net.mca.entity.ai.relationship.AgeState;
import net.mca.entity.ai.sense.Entorno;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

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
        SURFACE_WORK, RETURN, REST
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
    /** A saved mine is taken up again only when this close and this recent. */
    private static final int RESUME_DISTANCE = 64;
    private static final long RESUME_AGE = 72000;
    private static final int SAVE_TICKS = 1200;
    /** Ticks without any progress before the watchdog counts a round. */
    private static final int STUCK_TICKS = 600;
    /** The same, standing on the land with no ore in view and no step cut. */
    private static final int SURFACE_STUCK_TICKS = 300;
    /** A spot found no good to start a mine is not tried again for this long. */
    private static final long SITE_FORGET = 6000;
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
    private final Progreso progreso = new Progreso();
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
    private Mina mina = new Mina();
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
    private long foodKnownAt = -1000;
    private boolean foodKnownCache;

    private int surfaceTicks;
    private int restTicks;

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
        super.run(world, villager, time);
        if (tooYoung(villager)) {
            abandonJobWithMessage("chore.mining.tooyoung");
            return;
        }
        if (!started) {
            started = true;
            init(world, villager);
        }
        Retina.lookAll(world, villager);
    }

    /** The first start of this task: picks up the mine it was working in, when it is still close and recent. */
    private void init(ServerWorld world, VillagerEntityMCA villager) {
        tunnelDir = villager.getHorizontalFacing().getHorizontal();
        base = villager.getBlockPos().toImmutable();
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

    /** Body, orders, danger, decisions, then whatever the phase is about. */
    private void work(ServerWorld world, VillagerEntityMCA villager, boolean look) {
        long now = world.getTime();
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
        if (phase == Phase.SEEK && !midStep) {
            if (gearUp(world, villager)) {
                return;
            }
            if (!equipPick(villager)) {
                if (progreso.gaveUp()) {
                    abandonJobWithMessage("chore.mining.nowood");
                }
                return;
            }
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
        }
    }

    // ------------------------------------------------------------------ what the checks and the chat may ask

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
                + ", camino " + mina.size() + (fallback ? " (escalera propia, modo " + climbMode + ")" : "");
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

    /** The step machine this miner moves with (tests look at it). */
    public Motor motor() {
        return motor;
    }

    /** To the one who sent it, wherever they are on the server (another dimension too); silent when offline. */
    private void say(String key, Object... params) {
        tellAssigner(key, params);
    }

    private void saveMine(ServerWorld world, VillagerEntityMCA villager) {
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
            eatBest(world, villager);
        }
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
        if (Mining.onSurface(world, feet) && Mining.openGround(world, feet)) {
            // the mine starts where the sky ends: the door on open ground, where the way up counts as out, the open
            // top of the staircase already part of the way in
            mina.outside(world, villager);
            return;
        }
        if (mina.walked(world, villager)) {
            levelCells++;
        }
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
        tidyBag(world, villager, false);
        // down in the rock by its own sense of place: a house roof or a tree over its head is not a mine
        boolean underground = Entorno.bajoTierra(world, villager);
        boolean noFood = foodPoints(inv) == 0;
        boolean weak = villager.getHealth() < villager.getMaxHealth() * 0.4F && food < HEAL_ABOVE;
        if (noFood && now >= nextFoodTrip && (food <= leaveAt(world, feet) || weak)) {
            say("chore.mining.hungry");
            leave(world, villager, Trip.FOOD);
            return true;
        }
        if (now >= nextBagCheck && bagFull(villager)) {
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
        climbFromIdx = mina.nearLow(world, feet);
        BlockPos target = homePos(world, villager);
        homeDir = Direction.getFacing(target.getX() - villager.getX(), 0, target.getZ() - villager.getZ()).getHorizontal();
        climbMode = preferredClimbMode(world, villager, feet);
        climbFails = 0;
        saveMine(world, villager);
        resetWatch();
        if (!Entorno.bajoTierra(world, villager) && (Mining.onSurface(world, feet) || Mining.nearTheSky(world, feet, 8))) {
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
        // it still rises over its head
        boolean open = Mining.openGround(world, feet);
        boolean out = open && Mining.onSurface(world, feet) || !camping && (open && feet.getY() >= target.getY() - 1
                || feet.getY() >= target.getY() && feet.isWithinDistance(target, 24));
        // never off its feet: leaving in mid-jump drops the block it was about to stand on
        if (out && villager.isOnGround() && !motor.midStep(villager)) {
            surfaced(world, villager);
            return;
        }
        BlockPos goal = null;
        if (!fallback) {
            int i = mina.nearLow(world, feet);
            if (i > 0) {
                goal = mina.crumb(Math.max(0, i - 1 - skip));
                memory.doing("subiendo por su camino (" + i + " pasos)", now);
            } else if (i == 0 && mina.entrance() != null && !feet.equals(mina.crumb(0))) {
                // one step from the door: out through it, not a new staircase beside it
                goal = mina.crumb(0);
                memory.doing("saliendo por la boca de su mina", now);
            } else {
                // off the trail, or at its first crumb underground: a way up of its own
                startFallback(world, villager, feet, i);
            }
        }
        if (goal == null) {
            if (plan.isEmpty()) {
                plan.add(climbGoal(villager, feet));
            }
            goal = plan.peekFirst();
            memory.doing("subiendo a la superficie para volver a casa", now);
        }
        Motor.Result result = motor.step(world, villager, goal, true, true);
        if (result == Motor.Result.ARRIVED) {
            skip = 0;
            trailFails = 0;
            if (fallback) {
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
            }
        } else if (result == Motor.Result.BLOCKED) {
            motor.reset(world, villager);
            if (!fallback) {
                // that crumb is shut: aim a few further back, then give the trail up
                if (++trailFails >= 3) {
                    startFallback(world, villager, feet, mina.nearLow(world, feet));
                } else {
                    skip += 3;
                }
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

    private void startFallback(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, int fromIdx) {
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
        }
        fallback = false;
        climb.clear();
        saveMine(world, villager);
        homeTicks = 0;
        lostTicks = 0;
        resetWatch();
        BlockPos target = homePos(world, villager);
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
        if (!digging) {
            if (ledgeFails < 3 && dy >= 1 && dy <= 3 && dx * dx + dz * dz < 64) {
                // home is up a ledge it cannot walk: climb to it the way it climbs out of a mine
                villager.getNavigation().stop();
                plan.add(target);
            } else if (villager.getNavigation().isIdle() && homeTicks > 100 && dx * dx + dz * dz < 64 * 64
                    && !villager.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.7)) {
                // no path on foot at all (not even part of the way): make one, the way it got out, one goal until it
                // gets there or gives it up
                BlockPos goal = feet.offset(Direction.getFacing(dx, 0, dz), 2);
                plan.add(Mining.outdoors(world, goal) ? goal : goal.up());
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
            if (!foraged && !world.isNight() && foodKnown(world, villager)) {
                say("chore.mining.forage");
                startForage();
                return;
            }
            if (food <= GO_HOME_BELOW) {
                // nothing to eat at home either: it stays
                phase = Phase.SEEK;
                abandonJobWithMessage("chore.mining.nofood");
                return;
            }
        }
        if (trip == Trip.CALLED) {
            phase = Phase.REST;
            restTicks = 0;
            progreso.wantKit();
            say("chore.mining.waiting");
            return;
        }
        beginReturn(world, villager);
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
        if (mina.known()) {
            phase = Phase.RETURN;
            say("chore.mining.back");
            MCA.LOGGER.info("[Tropeles] {} vuelve a su mina por su camino ({} pasos)", name(), mina.size());
        } else {
            phase = Phase.SEEK;
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
        if (++returnTicks > 9600 || !mina.known()) {
            // too long on the way: it starts a new mine where it stands
            mina.clear();
            phase = Phase.SEEK;
            return;
        }
        int i = mina.nearHigh(world, feet);
        if (i < 0) {
            BlockPos door = mina.entrance();
            if (door == null || !feet.isWithinDistance(door, HOME_REACH + 16)) {
                // (home may be as far as HOME_REACH from the mine: a trail is only forgotten beyond that)
                mina.clear();
                phase = Phase.SEEK;
                return;
            }
            memory.doing(String.format("volviendo a su mina, a %.0f bloques", Math.sqrt(feet.getSquaredDistance(door))), now);
            if ((returnTicks % 40) == 1 || villager.getNavigation().isIdle()) {
                villager.getNavigation().startMovingTo(door.getX() + 0.5, door.getY(), door.getZ() + 0.5, 0.7);
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
        BlockPos goal = mina.crumb(i + 1);
        memory.doing("bajando por su camino a la mina (" + (mina.size() - 1 - i) + " pasos)", now);
        Motor.Result result = motor.step(world, villager, goal, true, true);
        if (result == Motor.Result.ARRIVED) {
            returnFails = 0;
        } else if (result == Motor.Result.BLOCKED) {
            motor.reset(world, villager);
            if (++returnFails > 4) {
                // the old way down is shut: it digs on from here
                phase = Phase.SEEK;
            }
        }
    }

    /** Called out of the mine: it waits at home, doing its gear, until told to go back down (or a while passes). */
    private void rest(ServerWorld world, VillagerEntityMCA villager) {
        if (gearUp(world, villager)) {
            return;
        }
        BlockPos target = homePos(world, villager);
        Retina.memory(villager).doing("esperando en casa", world.getTime());
        if (++restTicks > REST_TICKS) {
            beginReturn(world, villager);
            return;
        }
        if (villager.getBlockPos().getSquaredDistance(target) > 16 && ((restTicks % 40) == 1 || villager.getNavigation().isIdle())) {
            villager.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
        }
    }

    /** Up for wood: a pickaxe (and a kit) made outside, then back down. */
    private void surfaceWork(ServerWorld world, VillagerEntityMCA villager) {
        if (gearUp(world, villager)) {
            return;
        }
        if (progreso.gaveUp()) {
            abandonJobWithMessage("chore.mining.nowood");
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
        if (++noFoodTicks > LOOK_FOR_FOOD) {
            finishForage(world, villager);
            return;
        }
        lookAround(world, villager, memory, now);
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
            phase = Phase.SEEK;
            abandonJobWithMessage("chore.mining.nofood");
            return;
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
                phase = Phase.SEEK;
                abandonJobWithMessage("chore.mining.lost");
                return true;
            }
        }
        if (phase == Phase.HOME_UP) {
            if (!fallback && stuckRounds == 1) {
                skip += 4;
            } else if (!fallback) {
                startFallback(world, villager, villager.getBlockPos(), mina.nearLow(world, villager.getBlockPos()));
            } else {
                climbMode = (climbMode + 1) % 5;
                if (stuckRounds >= 3) {
                    callOutStuck(world);
                }
                if (stuckRounds >= 4) {
                    cryForHelp(world);
                }
            }
        } else if (phase == Phase.RETURN) {
            if (stuckRounds >= 2) {
                // the trail does not lead there any more: it digs on from where it is
                phase = Phase.SEEK;
            }
        } else {
            villager.getNavigation().stop();
            if (stuckRounds >= 3) {
                // no way home from here: this is the camp now
                camping = true;
                homeTarget = villager.getBlockPos().toImmutable();
                enterHomeStore();
            } else if (stuckRounds == 2) {
                BlockPos target = homePos(world, villager);
                BlockPos feet = villager.getBlockPos();
                BlockPos goal = feet.offset(Direction.getFacing(target.getX() - feet.getX(), 0, target.getZ() - feet.getZ()), 2);
                plan.add(Mining.outdoors(world, goal) ? goal : goal.up());
            }
        }
        return true;
    }

    /** Bigger is closer to where the phase is going. */
    private double progress(ServerWorld world, VillagerEntityMCA villager) {
        BlockPos feet = villager.getBlockPos();
        if (phase == Phase.HOME_UP) {
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
     * While digging: nothing moved and nothing new in the bag for half a minute. Drop the plan, forget that ore,
     * turn; the third time in a row, head up and home and start over from there.
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
        if (stuckRounds >= 3) {
            stuckRounds = 0;
            leave(world, villager, Trip.BAG);
        }
        return true;
    }

    /** Making or upgrading its own tools comes before digging; true while that keeps it busy. */
    private boolean gearUp(ServerWorld world, VillagerEntityMCA villager) {
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
        Hands.place(world, villager, feet, villager.getInventory().getStack(slot));
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
        if (threat == null) {
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
            backOffTicks = 0;
            long now = world.getTime();
            if (now - lastFightCall > 600) {
                lastFightCall = now;
                say("chore.mining.fight", threat.getName());
            }
            MCA.LOGGER.info("[Tropeles] {} le hace frente a {}", name(), threat.getName().getString());
            return true;
        }
        beginDanger(world, threat);
        return true;
    }

    /** Armed, grown, healthy, and only one of them in sight; never a creeper. */
    private boolean canFight(ServerWorld world, VillagerEntityMCA villager, MobEntity threat) {
        if (threat instanceof CreeperEntity || villager.getHealth() < villager.getMaxHealth() * 0.5F
                || villager.getAgeState().ordinal() < AgeState.TEEN.ordinal()) {
            return false;
        }
        if (!CombatMind.hasMelee(villager) && !(CombatMind.hasRanged(villager) && Archery.hasAmmo(villager))) {
            return false;
        }
        int seen = 0;
        for (MobEntity mob : world.getNonSpectatingEntities(MobEntity.class, new Box(villager.getBlockPos()).expand(12))) {
            if (Threats.isThreat(mob, villager, null) && Retina.canSee(world, villager, mob) && ++seen > 1) {
                return false;
            }
        }
        return true;
    }

    private void fight(ServerWorld world, VillagerEntityMCA villager) {
        LivingEntity target = CombatMind.pickTarget(world, villager, null, 12);
        if (target == null) {
            // nothing left to fight: what it dropped (nobody eats rotten flesh), and back to what it was doing
            CombatMind.disengage(villager);
            Hands.collect(world, villager, villager.getBlockPos(), 2.0, stack -> !stack.isOf(Items.ROTTEN_FLESH));
            afterTrouble(world, villager);
            return;
        }
        dangerFrom = target.getBlockPos().toImmutable();
        boolean hurt = villager.getHealth() < villager.getMaxHealth() * 0.4F;
        if (target instanceof CreeperEntity || hurt || ++fightTicks > 1200) {
            CombatMind.disengage(villager);
            if (target instanceof MobEntity mob) {
                beginDanger(world, mob);
            } else {
                afterTrouble(world, villager);
            }
            return;
        }
        if (CombatMind.fight(world, villager, null, target)) {
            backOffTicks = 0;
        } else if (++backOffTicks > 60 && target instanceof MobEntity mob) {
            // it keeps backing off: hide instead
            CombatMind.disengage(villager);
            beginDanger(world, mob);
        }
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
