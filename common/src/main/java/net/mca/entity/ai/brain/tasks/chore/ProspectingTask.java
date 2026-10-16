package net.mca.entity.ai.brain.tasks.chore;

import com.google.common.collect.ImmutableMap;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.DistressCalls;
import net.mca.entity.ai.Threats;
import net.mca.entity.ai.WorkTools;
import net.mca.entity.ai.body.Agua;
import net.mca.entity.ai.body.Cofres;
import net.mca.entity.ai.body.Hands;
import net.mca.entity.ai.body.Mining;
import net.mca.entity.ai.body.Motor;
import net.mca.entity.ai.body.Progreso;
import net.mca.entity.ai.relationship.AgeState;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.mca.util.InventoryUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Mining by sight, starting from nothing. Without a pickaxe it gets its own (chop, planks, table, pickaxe) and
 * keeps upgrading from what it digs up: stone, iron in a furnace, diamond, a sword, torches ({@link Progreso}).
 * The miner only goes for ore its eye has actually seen (the exact block, as Jade would
 * name it) and only hits it once the crosshair can land on it within a player's reach. To get there it
 * walks, tunnels, digs stairs, bridges gaps and pillars up, all through {@link Motor}. With nothing in view it
 * goes down a staircase to diamond depth and branch mines, so every wall it opens passes in front of its eyes.
 * In danger it walls itself in, calls out its coordinates, or digs down and caps the hole (from the earlier
 * version of this chore).
 */
public class ProspectingTask extends AbstractChoreTask {
    private enum Phase {
        SEEK, SHELTER_DIG, SHELTER_ENTER, SHELTER_SEAL, ESCAPE, RECOVER, HOME_UP, HOME_WALK, HOME_STORE
    }

    /** Food points like a player's bar: one goes every minute of work, food in the bag brings them back. */
    private static final int FOOD_MAX = 20;
    private static final int FOOD_TICKS = 1200;
    private static final int EAT_BELOW = 14;
    private static final int GO_HOME_BELOW = 6;

    /** Below this the deepslate lava lakes start. */
    public static final int DEEPSLATE_DEPTH = -50;
    private static final int ORE_SEARCH_RADIUS = 32;

    private final Motor motor = new Motor();
    private final Progreso progreso = new Progreso();
    private boolean wasGearing;
    private final Deque<BlockPos> plan = new ArrayDeque<>();
    private final Map<Long, Long> ignored = new LinkedHashMap<>();
    private Phase phase = Phase.SEEK;
    private BlockPos ore;
    private int oreFails;
    private int tunnelDir;
    private boolean branchLeft;
    private BlockPos pocket;
    private BlockPos sealPos;
    private BlockPos escapeOrigin;
    private int escapeLeft;
    private int calmTicks;
    private long lastCry;
    private long lastOreAnnounce;
    private boolean announcedAny;
    private boolean warnedFull;
    private int food = FOOD_MAX;
    private int foodTicks;
    private BlockPos base;
    private BlockPos home;
    private int homeTicks;
    private int homeDir;
    private BlockPos homeChest;
    private boolean hungryTrip;
    private BlockPos stuckAt;
    private int stuckItems = -1;
    private int stuckTicks;
    private int stuckRounds;

    /** The ore each miner was asked to look for first ("busca diamantes"). */
    private static final Map<VillagerEntityMCA, String> WANTED = new java.util.WeakHashMap<>();

    private static final Map<String, String> ORE_WORDS = Map.ofEntries(
            Map.entry("diamante", "diamond"), Map.entry("diamantes", "diamond"), Map.entry("diamonds", "diamond"),
            Map.entry("hierro", "iron"), Map.entry("oro", "gold"), Map.entry("carbon", "coal"), Map.entry("cobre", "copper"),
            Map.entry("lapislazuli", "lapis"), Map.entry("esmeralda", "emerald"), Map.entry("esmeraldas", "emerald"),
            Map.entry("emeralds", "emerald"), Map.entry("cuarzo", "quartz"), Map.entry("netherita", "ancient_debris"),
            Map.entry("netherite", "ancient_debris"), Map.entry("escombros", "ancient_debris"), Map.entry("debris", "ancient_debris")
    );

    /** Asks {@code villager} to go for this ore first; empty or unknown clears the wish. */
    public static void want(VillagerEntityMCA villager, @org.jetbrains.annotations.Nullable String ore) {
        String word = ore == null ? "" : java.text.Normalizer.normalize(ore, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT).trim();
        for (String part : word.split("[^a-z_]+")) {
            String key = ORE_WORDS.getOrDefault(part, part);
            if (java.util.List.of("diamond", "iron", "gold", "coal", "copper", "redstone", "lapis", "emerald", "quartz", "ancient_debris").contains(key)) {
                WANTED.put(villager, key);
                return;
            }
        }
        WANTED.remove(villager);
    }

    /** The ore it was asked to look for first, or null. */
    @org.jetbrains.annotations.Nullable
    public static String wanted(VillagerEntityMCA villager) {
        return WANTED.get(villager);
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
        tunnelDir = villager.getHorizontalFacing().getHorizontal();
        base = villager.getBlockPos();
        Retina.lookAll(world, villager);
    }

    @Override
    protected void finishRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        motor.reset(world, villager);
        plan.clear();
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
        if ((villager.age & 1) == 0) {
            Retina.look(world, villager);
        }
        if (phase == Phase.SEEK && bagFull(villager)) {
            // a full bag goes home to the chest, then back to work
            say("chore.mining.home");
            beginHome(world, villager, false);
        }
        if (phase == Phase.SEEK && hunger(world, villager)) {
            say("chore.mining.hungry");
            beginHome(world, villager, true);
        }

        if (phase == Phase.SEEK) {
            MobEntity threat = Threats.nearest(world, villager.getBlockPos(), 10, villager, null);
            if (threat != null) {
                beginDanger(world, threat);
            }
        }

        if (phase == Phase.SEEK) {
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

        if (watchdog(world, villager)) {
            return;
        }
        switch (phase) {
            case SEEK -> seek(world, villager);
            case SHELTER_DIG -> shelterDig(world, villager);
            case SHELTER_ENTER -> shelterEnter(world, villager);
            case SHELTER_SEAL -> shelterSeal(world, villager);
            case ESCAPE -> escape(world, villager);
            case RECOVER -> recover(world, villager);
            case HOME_UP, HOME_WALK, HOME_STORE -> homeTrip(world, villager);
        }
    }

    /** One tick of the mining loop on its own, without a job assigner; used by the in-game checks. */
    public void practice(ServerWorld world, VillagerEntityMCA villager) {
        this.villager = villager;
        if (base == null) {
            base = villager.getBlockPos();
        }
        Retina.look(world, villager);
        if (phase == Phase.SEEK && (bagFull(villager) || hunger(world, villager))) {
            beginHome(world, villager, !bagFull(villager));
        }
        if (phase == Phase.HOME_UP || phase == Phase.HOME_WALK || phase == Phase.HOME_STORE) {
            homeTrip(world, villager);
            return;
        }
        if (gearUp(world, villager) || !equipPick(villager)) {
            return;
        }
        if (watchdog(world, villager)) {
            return;
        }
        seek(world, villager);
    }

    /**
     * A player who notices they have not got anywhere for half a minute stops and tries something else. Nothing
     * moved and nothing new in the bag for that long: drop the plan, forget that ore, turn; the third time in a
     * row, head up and home and start over from there. True when it changed course this tick.
     */
    private boolean watchdog(ServerWorld world, VillagerEntityMCA villager) {
        if (phase != Phase.SEEK || !progreso.idle()) {
            stuckTicks = 0;
            stuckAt = null;
            return false;
        }
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
        if (stuckTicks < 600) {
            return false;
        }
        stuckTicks = 0;
        stuckRounds++;
        motor.reset(world, villager);
        plan.clear();
        if (ore != null) {
            ignore(ore, world.getTime());
            ore = null;
        }
        tunnelDir = (tunnelDir + (villager.getRandom().nextBoolean() ? 1 : 3)) & 3;
        Retina.memory(villager).doing("atascado: cambia de rumbo", world.getTime());
        if (stuckRounds >= 3) {
            stuckRounds = 0;
            beginHome(world, villager, false);
        }
        return true;
    }

    /** For the in-game checks: how full its food bar is, and where home is. */
    public void setFood(int points) {
        food = points;
    }

    public int food() {
        return food;
    }

    public boolean atWork() {
        return phase == Phase.SEEK;
    }

    /** For the in-game checks: the ore it is going for, and its phase. */
    public String debug() {
        return phase + (ore == null ? "" : " hacia " + ore.toShortString()) + (plan.isEmpty() ? "" : " plan " + plan.peekFirst().toShortString());
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

    private void say(String key, Object... params) {
        getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, key, params));
    }

    // ------------------------------------------------------------------ food and home

    /** Works up an appetite; eats from the bag when it can. True when it is hungry with nothing left to eat. */
    private boolean hunger(ServerWorld world, VillagerEntityMCA villager) {
        if (++foodTicks >= FOOD_TICKS) {
            foodTicks = 0;
            food = Math.max(0, food - 1);
        }
        if (food < EAT_BELOW) {
            int slot = Hands.findSlot(villager.getInventory(), ProspectingTask::edible);
            if (slot >= 0) {
                ItemStack meal = villager.getInventory().getStack(slot);
                food = Math.min(FOOD_MAX, food + Math.max(1, meal.getItem().getFoodComponent().getHunger()));
                villager.eatFood(world, meal.split(1));
                Retina.memory(villager).doing("comiendo " + meal.getName().getString(), world.getTime());
            }
        }
        return food <= GO_HOME_BELOW && Hands.findSlot(villager.getInventory(), ProspectingTask::edible) < 0;
    }

    private static boolean edible(ItemStack stack) {
        return stack.isFood() && stack.getItem().getFoodComponent() != null && stack.getItem().getFoodComponent().getHunger() > 0
                && !stack.isOf(Items.ROTTEN_FLESH) && !stack.isOf(Items.SPIDER_EYE) && !stack.isOf(Items.POISONOUS_POTATO);
    }

    private BlockPos homePos(ServerWorld world, VillagerEntityMCA villager) {
        if (home != null) {
            return home;
        }
        var registered = villager.getResidency().getHome();
        if (registered.isPresent() && registered.get().getDimension() == world.getRegistryKey()) {
            return registered.get().getPos();
        }
        return base != null ? base : villager.getBlockPos();
    }

    private void beginHome(ServerWorld world, VillagerEntityMCA villager, boolean hungry) {
        hungryTrip = hungry;
        progreso.leaving();
        motor.reset(world, villager);
        plan.clear();
        ore = null;
        homeTicks = 0;
        homeChest = null;
        BlockPos target = homePos(world, villager);
        homeDir = Direction.getFacing(target.getX() - villager.getX(), 0, target.getZ() - villager.getZ()).getHorizontal();
        phase = Phase.HOME_UP;
    }

    /**
     * Going home: up to the surface any way it can (stairs dug toward home, pillars, bridges), then walking,
     * then the chest: what it does not need goes in, food comes out.
     */
    private void homeTrip(ServerWorld world, VillagerEntityMCA villager) {
        VisualMemory memory = Retina.memory(villager);
        long now = world.getTime();
        // first its iron: back to the furnace, out with everything, furnace in the bag
        if (!progreso.idle()) {
            memory.doing("recogiendo su horno antes de irse", now);
            progreso.leaving();
            gearUp(world, villager);
            return;
        }
        BlockPos target = homePos(world, villager);
        if (++homeTicks > 12000) {
            phase = Phase.SEEK;
            abandonJobWithMessage("chore.mining.lost");
            return;
        }
        BlockPos feet = villager.getBlockPos();
        equipPick(villager);
        if (phase == Phase.HOME_UP) {
            if (world.isSkyVisible(feet.up()) && feet.getY() >= target.getY() - 1 || feet.getY() >= target.getY() && feet.isWithinDistance(target, 24)) {
                motor.reset(world, villager);
                phase = Phase.HOME_WALK;
                return;
            }
            memory.doing("subiendo a la superficie para volver a casa", now);
            if (plan.isEmpty()) {
                Direction toward = Direction.fromHorizontal(homeDir);
                plan.add(feet.offset(toward, 3).up(3));
            }
            Motor.Result result = motor.step(world, villager, plan.peekFirst(), true, true);
            if (result == Motor.Result.ARRIVED) {
                plan.clear();
            } else if (result == Motor.Result.BLOCKED) {
                plan.clear();
                motor.reset(world, villager);
                homeDir = (homeDir + (villager.getRandom().nextBoolean() ? 1 : 3)) & 3;
            }
            return;
        }
        if (phase == Phase.HOME_WALK) {
            double dx = target.getX() + 0.5 - villager.getX();
            double dz = target.getZ() + 0.5 - villager.getZ();
            if (dx * dx + dz * dz < 9 && Math.abs(target.getY() - villager.getY()) < 1.5) {
                villager.getNavigation().stop();
                phase = Phase.HOME_STORE;
                homeTicks = 0;
                return;
            }
            memory.doing(String.format("volviendo a casa, a %.0f bloques", Math.sqrt(dx * dx + dz * dz)), now);
            if (target.getY() - feet.getY() >= 1 && dx * dx + dz * dz < 64) {
                // home is up a ledge it cannot walk: climb to it the way it climbs out of a mine
                villager.getNavigation().stop();
                if (motor.step(world, villager, target, true, true) == Motor.Result.BLOCKED) {
                    motor.reset(world, villager);
                    phase = Phase.HOME_UP;
                    plan.clear();
                }
                return;
            }
            if ((homeTicks % 40) == 1 || villager.getNavigation().isIdle()) {
                villager.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.7);
            }
            if (villager.getNavigation().isIdle() && homeTicks > 100) {
                // no path on foot: make one, the way it got out
                BlockPos goal = feet.offset(Direction.getFacing(dx, 0, dz), 2);
                Motor.Result result = motor.step(world, villager, world.isSkyVisible(goal) ? goal : goal.up(), true, true);
                if (result == Motor.Result.BLOCKED) {
                    motor.reset(world, villager);
                }
            }
            return;
        }
        store(world, villager, target, memory, now);
    }

    private void store(ServerWorld world, VillagerEntityMCA villager, BlockPos target, VisualMemory memory, long now) {
        if (homeChest == null || !(world.getBlockState(homeChest).getBlock() instanceof net.minecraft.block.ChestBlock)) {
            homeChest = Cofres.own(world, villager, target).stream()
                    .filter(pos -> pos.isWithinDistance(target, Cofres.HOME_RANGE)).findFirst().orElse(null);
        }
        if (homeChest == null) {
            // no chest of its own here yet: one from the bag, or make one
            if (villager.getInventory().count(Items.CHEST) > 0) {
                memory.doing("poniendo su cofre", now);
                BlockPos placed = Progreso.placeBeside(world, villager, Cofres::isChestItem);
                if (placed != null) {
                    Cofres.claim(world, villager, placed);
                    homeChest = placed;
                }
                return;
            }
            progreso.wantChest();
            if (gearUp(world, villager)) {
                return;
            }
            if (homeTicks > 2400 || villager.getInventory().count(Items.CHEST) == 0 && !Progreso.canMakeChest(villager.getInventory()) && progreso.idle() && homeTicks > 200) {
                say("chore.mining.nochest");
                homeChest = null;
                finishHome(world, villager, 0);
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
                finishHome(world, villager, 0);
            }
            return;
        }
        villager.getNavigation().stop();
        int[] buildingStacks = {0};
        int stored = Cofres.store(world, villager, homeChest, stack -> keep(stack, buildingStacks));
        Cofres.take(world, villager, homeChest, ProspectingTask::edible, 8);
        memory.doing("guardó " + stored + " cosas en su cofre", now);
        finishHome(world, villager, stored);
    }

    private void finishHome(ServerWorld world, VillagerEntityMCA villager, int stored) {
        if (stored > 0) {
            say("chore.mining.stored", stored);
        }
        // eat what it found before going back down
        while (food < FOOD_MAX - 2) {
            int slot = Hands.findSlot(villager.getInventory(), ProspectingTask::edible);
            if (slot < 0) {
                break;
            }
            ItemStack meal = villager.getInventory().getStack(slot);
            food = Math.min(FOOD_MAX, food + Math.max(1, meal.getItem().getFoodComponent().getHunger()));
            villager.eatFood(world, meal.split(1));
        }
        motor.reset(world, villager);
        plan.clear();
        phase = Phase.SEEK;
        if (hungryTrip && food <= GO_HOME_BELOW) {
            // nothing to eat at home either: it stays
            abandonJobWithMessage("chore.mining.nofood");
        }
    }

    /** What a miner keeps on it: tools, weapons, arrows, light, food, crafting stuff, fuel, iron to smelt and some blocks. */
    private static boolean keep(ItemStack stack, int[] buildingStacks) {
        if (stack.getItem() instanceof net.minecraft.item.ToolItem || stack.getItem() instanceof net.minecraft.item.RangedWeaponItem
                || stack.getItem() instanceof net.minecraft.item.ArmorItem || stack.getItem() instanceof net.minecraft.item.ArrowItem
                || stack.isOf(Items.TORCH) || edible(stack) || stack.isOf(Items.CRAFTING_TABLE) || stack.isOf(Items.FURNACE)
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

    /** Making or upgrading its own tools comes before digging; true while that keeps it busy. */
    private boolean gearUp(ServerWorld world, VillagerEntityMCA villager) {
        boolean busy = progreso.tick(world, villager, (key, params) ->
                getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, key, params)));
        if (busy && !wasGearing) {
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

        if (ore == null || !stillOre(memory, ore)) {
            ore = chooseOre(world, villager, memory);
            oreFails = 0;
        }
        if (ore != null) {
            BlockState state = memory.stateAt(ore);
            String name = state == null ? "?" : state.getBlock().getName().getString();
            BlockPos feet = villager.getBlockPos();
            boolean underFeet = ore.equals(feet.down());
            if (!underFeet && Retina.visibleFace(world, villager, ore, Retina.BLOCK_REACH) != null) {
                if (motor.tend(world, villager)) {
                    memory.doing(motor.doing(), now);
                    return;
                }
                memory.doing(String.format("picando %s a %.1f bloques", name, Math.sqrt(villager.getEyePos().squaredDistanceTo(ore.toCenterPos()))), now);
                villager.getNavigation().stop();
                int r = motor.breaker().tick(world, villager, ore);
                if (r == Hands.Breaker.DONE) {
                    motor.broke(world, villager, ore);
                    if (state != null) {
                        announceOre(world, state);
                    }
                    ore = null;
                } else if (r == Hands.Breaker.FAILED) {
                    ignore(ore, now);
                    ore = null;
                }
                return;
            }
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

        // nothing seen: open rock in front of the eyes
        if (plan.isEmpty()) {
            planExploration(world, villager);
        }
        BlockPos goal = plan.peekFirst();
        if (goal == null) {
            return;
        }
        memory.doing(villager.getY() > targetDepth(world) + 2 ? "bajando en escalera a buscar minerales" : "minando en ramas", now);
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

    private int targetDepth(ServerWorld world) {
        int bottom = world.getBottomY();
        return bottom < 0 ? DEEPSLATE_DEPTH : bottom + 12;
    }

    /** Next stretch: a staircase down to depth, then a corridor with side branches every third block. */
    private void planExploration(ServerWorld world, VillagerEntityMCA villager) {
        BlockPos feet = villager.getBlockPos();
        int depth = targetDepth(world);
        BlockPos oven = progreso.cookingAt();
        if (oven != null) {
            // iron in the furnace: branch-mine at its level and stay within reach of it
            depth = oven.getY();
            int dx = oven.getX() - feet.getX();
            int dz = oven.getZ() - feet.getZ();
            if (dx * dx + dz * dz > 100) {
                tunnelDir = Direction.getFacing(dx, 0, dz).getHorizontal();
            }
        }
        Direction forward = Direction.fromHorizontal(tunnelDir);
        if (wetAhead(villager, feet, forward)) {
            // the eye saw water that way and there are not enough blocks to wall it off: another way
            for (int turn = 1; turn <= 3; turn++) {
                Direction other = Direction.fromHorizontal((tunnelDir + turn) & 3);
                if (!wetAhead(villager, feet, other)) {
                    tunnelDir = other.getHorizontal();
                    forward = other;
                    break;
                }
            }
        }
        if (villager.isTouchingWater() && !world.isSkyVisible(feet.up())) {
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
        if (feet.getY() > depth + 2) {
            int steps = Math.min(4, feet.getY() - depth);
            plan.add(feet.offset(forward, steps).down(steps));
            return;
        }
        for (int k = 1; k <= 3; k++) {
            plan.add(feet.offset(forward, k));
        }
        BlockPos junction = feet.offset(forward, 3);
        Direction side = branchLeft ? forward.rotateYCounterclockwise() : forward.rotateYClockwise();
        branchLeft = !branchLeft;
        for (int j = 1; j <= 5; j++) {
            plan.add(junction.offset(side, j));
        }
        plan.add(junction);
    }

    /** The eye remembers water in the next few cells that way, and the bag is short of blocks to wall it off. */
    private boolean wetAhead(VillagerEntityMCA villager, BlockPos feet, Direction forward) {
        if (buildingBlocks(villager) >= 16) {
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

    private static int buildingBlocks(VillagerEntityMCA villager) {
        int count = 0;
        for (int i = 0; i < villager.getInventory().size(); i++) {
            ItemStack stack = villager.getInventory().getStack(i);
            if (Hands.isBuildingBlock(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean stillOre(VisualMemory memory, BlockPos pos) {
        BlockState state = memory.stateAt(pos);
        return state != null && Mining.isOre(state);
    }

    private BlockPos chooseOre(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory) {
        ItemStack tool = villager.getMainHandStack();
        long now = world.getTime();
        BlockPos feet = villager.getBlockPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        boolean lowOnBlocks = buildingBlocks(villager) < 6;
        boolean needsCoal = villager.getInventory().count(Items.TORCH) < 4 && villager.getInventory().count(Items.COAL) + villager.getInventory().count(Items.CHARCOAL) == 0;
        for (VisualMemory.SeenBlock seen : memory.blocks(s -> Mining.isOre(s.state))) {
            if (isIgnored(seen.pos, now) || !tool.isSuitableFor(seen.state)) {
                continue;
            }
            if (lowOnBlocks && Agua.seenNear(memory, seen.pos)) {
                continue; // ore in the water and nothing to wall it off with
            }
            double distance = Math.sqrt(seen.pos.getSquaredDistance(feet));
            if (distance > ORE_SEARCH_RADIUS) {
                continue;
            }
            double score = distance - Mining.oreValue(seen.state) / 10.0;
            String wish = WANTED.get(villager);
            if (wish != null && net.minecraft.registry.Registries.BLOCK.getId(seen.state.getBlock()).getPath().contains(wish)) {
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

    private static boolean bagFull(VillagerEntityMCA villager) {
        for (int i = 0; i < villager.getInventory().size(); i++) {
            if (villager.getInventory().getStack(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void maybePlaceTorch(ServerWorld world, VillagerEntityMCA villager) {
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
        getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, "chore.mining.orenotify", state.getBlock().getName()));
    }

    // ------------------------------------------------------------------ danger (walls, calls, escape)

    private void beginDanger(ServerWorld world, MobEntity threat) {
        cryForHelp(world);
        motor.reset(world, villager);
        plan.clear();
        villager.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
        if (tryBeginShelter(world, threat)) {
            return;
        }
        beginEscape();
    }

    private boolean tryBeginShelter(ServerWorld world, MobEntity threat) {
        BlockPos feet = villager.getBlockPos();
        Direction away = awayFrom(villager, threat);
        BlockPos retreat = feet.offset(away);
        pocket = retreat;
        sealPos = feet;
        if (Mining.passable(world, retreat) && Mining.passable(world, retreat.up()) && !Mining.heat(world, retreat)) {
            phase = Phase.SHELTER_ENTER;
            return true;
        }
        if (Mining.mayDig(world, retreat, world.getBlockState(retreat)) && !Mining.heat(world, retreat)) {
            phase = Phase.SHELTER_DIG;
            return true;
        }
        return false;
    }

    private void beginEscape() {
        escapeOrigin = villager.getBlockPos();
        escapeLeft = 5;
        phase = Phase.ESCAPE;
    }

    private void shelterDig(ServerWorld world, VillagerEntityMCA villager) {
        if (pocket == null) {
            beginEscape();
            return;
        }
        for (BlockPos cell : new BlockPos[]{pocket.up(), pocket}) {
            if (!Mining.passable(world, cell)) {
                int r = motor.breaker().tick(world, villager, cell);
                if (r == Hands.Breaker.FAILED) {
                    beginEscape();
                } else if (r == Hands.Breaker.DONE) {
                    Hands.collect(world, villager, cell, 2.5);
                }
                return;
            }
        }
        phase = Phase.SHELTER_ENTER;
    }

    private void shelterEnter(ServerWorld world, VillagerEntityMCA villager) {
        if (pocket == null) {
            beginEscape();
            return;
        }
        villager.getMoveControl().moveTo(pocket.getX() + 0.5, pocket.getY(), pocket.getZ() + 0.5, 0.8);
        if (villager.getBlockPos().equals(pocket)) {
            phase = Phase.SHELTER_SEAL;
            cryForHelp(world);
            getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, "chore.mining.shelter"));
        }
    }

    private void shelterSeal(ServerWorld world, VillagerEntityMCA villager) {
        keepDistressAlive(world);
        if (sealPos == null) {
            phase = Phase.RECOVER;
            calmTicks = 0;
            return;
        }
        for (BlockPos spot : new BlockPos[]{sealPos, sealPos.up()}) {
            if (Mining.passable(world, spot)) {
                if (!placeSolid(world, spot)) {
                    beginEscape();
                }
                return;
            }
        }
        phase = Phase.RECOVER;
        calmTicks = 0;
    }

    private void escape(ServerWorld world, VillagerEntityMCA villager) {
        keepDistressAlive(world);
        villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        if (escapeLeft > 0) {
            BlockPos below = villager.getBlockPos().down();
            if (Mining.heat(world, below) || !Mining.mayDig(world, below, world.getBlockState(below))) {
                escapeLeft = 0;
                return;
            }
            int r = motor.breaker().tick(world, villager, below);
            if (r == Hands.Breaker.DONE) {
                Hands.collect(world, villager, below, 2.5);
                escapeLeft--;
            } else if (r == Hands.Breaker.FAILED) {
                escapeLeft = 0;
            }
            return;
        }
        if (escapeOrigin != null && Mining.passable(world, escapeOrigin) && villager.getBlockPos().getY() < escapeOrigin.getY()) {
            placeSolid(world, escapeOrigin.getY() - villager.getBlockPos().getY() > 2 ? villager.getBlockPos().up(2) : escapeOrigin);
        }
        getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, "chore.mining.escape",
                villager.getBlockPos().getX(), villager.getBlockPos().getY(), villager.getBlockPos().getZ()));
        phase = Phase.RECOVER;
        calmTicks = 0;
    }

    private void recover(ServerWorld world, VillagerEntityMCA villager) {
        MobEntity threat = Threats.nearest(world, villager.getBlockPos(), 8, villager, null);
        if (threat != null) {
            calmTicks = 0;
            keepDistressAlive(world);
            if (sealPos != null && Mining.passable(world, sealPos)) {
                beginDanger(world, threat);
            }
            return;
        }
        if (++calmTicks > 80) {
            getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, "chore.mining.safe"));
            phase = Phase.SEEK;
            ore = null;
            calmTicks = 0;
        }
    }

    /** A wall block from the bag, placed like a player; if the face is out of sight in the rush, it is set directly. */
    private boolean placeSolid(ServerWorld world, BlockPos pos) {
        if (!world.getBlockState(pos).isReplaceable() || Mining.heat(world, pos)) {
            return !world.getBlockState(pos).isReplaceable();
        }
        if (!world.isInBuildLimit(pos) || !world.getWorldBorder().contains(pos)) {
            return false;
        }
        ItemStack block = Hands.buildingBlock(villager);
        if (block.isEmpty()) {
            return false;
        }
        if (Hands.place(world, villager, pos, block)) {
            return true;
        }
        if (!world.getOtherEntities(null, new net.minecraft.util.math.Box(pos)).isEmpty()) {
            return false;
        }
        world.setBlockState(pos, ((net.minecraft.item.BlockItem) block.getItem()).getBlock().getDefaultState(), 3);
        block.decrement(1);
        return true;
    }

    private void cryForHelp(ServerWorld world) {
        long now = world.getTime();
        Optional<PlayerEntity> player = getAssigningPlayer();
        player.ifPresent(assigner -> {
            DistressCalls.raise(world, villager, assigner.getUuid());
            if (now - lastCry > 400) {
                lastCry = now;
                BlockPos pos = villager.getBlockPos();
                villager.sendChatMessage(assigner, "chore.mining.help", pos.getX(), pos.getY(), pos.getZ());
            }
        });
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

    private static Direction awayFrom(VillagerEntityMCA villager, MobEntity threat) {
        double dx = villager.getX() - threat.getX();
        double dz = villager.getZ() - threat.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
