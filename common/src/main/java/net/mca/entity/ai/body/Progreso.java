package net.mca.entity.ai.body;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.WorkTools;
import net.mca.entity.ai.sense.Obras;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.mca.util.InventoryUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ai.FuzzyTargeting;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Getting your own gear the way a player starts a world: punch a tree, turn logs into planks and sticks,
 * put a crafting table down, make a wooden pickaxe, and from there climb the ladder with what mining brings
 * home: stone tools from cobblestone, a furnace to smelt raw iron, iron and diamond tools, torches from coal,
 * a sword to go with the pickaxe. Recipes cost exactly what vanilla's cost, tools that need a table are only
 * made with a real table within reach, and smelting happens in a real furnace placed in the world. Tables and
 * furnaces it put down itself are picked back up, as a player would.
 */
public final class Progreso {
    private static final int CRAFT_TICKS = 12;
    private static final int LOGS_TO_START = 5;
    private static final int WOOD_SEARCH = 48;
    private static final int GIVE_UP_EXPLORING = 2400;

    private enum Mat {
        LOG(s -> s.isIn(ItemTags.LOGS)),
        PLANKS(s -> s.isIn(ItemTags.PLANKS)),
        STICK(s -> s.isOf(Items.STICK)),
        STONE(s -> s.isIn(ItemTags.STONE_TOOL_MATERIALS)),
        IRON(s -> s.isOf(Items.IRON_INGOT)),
        DIAMOND(s -> s.isOf(Items.DIAMOND)),
        COAL(s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL)),
        TABLE(s -> s.isOf(Items.CRAFTING_TABLE)),
        FURNACE(s -> s.isOf(Items.FURNACE));

        final Predicate<ItemStack> match;

        Mat(Predicate<ItemStack> match) {
            this.match = match;
        }
    }

    /** One vanilla recipe: what it costs, what it gives, and whether it needs the 3x3 grid of a table. */
    private record Recipe(Item output, int count, boolean table, Map<Mat, Integer> cost) {
    }

    private static final Recipe STICKS = new Recipe(Items.STICK, 4, false, Map.of(Mat.PLANKS, 2));
    private static final Recipe CRAFTING_TABLE = new Recipe(Items.CRAFTING_TABLE, 1, false, Map.of(Mat.PLANKS, 4));
    private static final Recipe TORCHES = new Recipe(Items.TORCH, 4, false, Map.of(Mat.COAL, 1, Mat.STICK, 1));
    private static final Recipe FURNACE = new Recipe(Items.FURNACE, 1, true, Map.of(Mat.STONE, 8));
    private static final Recipe WOODEN_PICKAXE = new Recipe(Items.WOODEN_PICKAXE, 1, true, Map.of(Mat.PLANKS, 3, Mat.STICK, 2));
    private static final Recipe STONE_PICKAXE = new Recipe(Items.STONE_PICKAXE, 1, true, Map.of(Mat.STONE, 3, Mat.STICK, 2));
    private static final Recipe IRON_PICKAXE = new Recipe(Items.IRON_PICKAXE, 1, true, Map.of(Mat.IRON, 3, Mat.STICK, 2));
    private static final Recipe DIAMOND_PICKAXE = new Recipe(Items.DIAMOND_PICKAXE, 1, true, Map.of(Mat.DIAMOND, 3, Mat.STICK, 2));
    private static final Recipe WOODEN_SWORD = new Recipe(Items.WOODEN_SWORD, 1, true, Map.of(Mat.PLANKS, 2, Mat.STICK, 1));
    private static final Recipe STONE_SWORD = new Recipe(Items.STONE_SWORD, 1, true, Map.of(Mat.STONE, 2, Mat.STICK, 1));
    private static final Recipe IRON_SWORD = new Recipe(Items.IRON_SWORD, 1, true, Map.of(Mat.IRON, 2, Mat.STICK, 1));
    private static final Recipe DIAMOND_SWORD = new Recipe(Items.DIAMOND_SWORD, 1, true, Map.of(Mat.DIAMOND, 2, Mat.STICK, 1));
    private static final Recipe CHEST = new Recipe(Items.CHEST, 1, true, Map.of(Mat.PLANKS, 8));
    /** How far it may wander off while its iron cooks before it goes back for it. */
    private static final int FURNACE_LEASH = 20;

    private enum Step {IDLE, WOOD, TABLE, SMELT, COLLECT, PACK_UP}

    private final Hands.Breaker breaker = new Hands.Breaker();
    private final Map<Long, Long> ignored = new HashMap<>();
    private Step step = Step.IDLE;
    @Nullable
    private Recipe goal;
    @Nullable
    private BlockPos log;
    private int logTicks;
    private int exploreTicks;
    @Nullable
    private BlockPos table;
    private boolean ownTable;
    @Nullable
    private BlockPos furnace;
    private long smeltUntil;
    private int craftDelay;
    private int nextCheck;
    private boolean gaveUp;
    private boolean warnedNoWood;
    /** Raw iron is in its furnace and it is off doing other things meanwhile. */
    private boolean cooking;
    /** When collecting, take everything and the furnace too: it is leaving. */
    private boolean packFurnace;
    private int collectTicks;
    private boolean wantChest;

    /** Asks for a chest to be made (from 8 planks at a table) next time it gears up. */
    public void wantChest() {
        wantChest = true;
        nextCheck = 0;
    }

    public boolean cooking() {
        return cooking;
    }

    /** It is going away: fetch whatever is in the furnace and pick the furnace up first. */
    public void leaving() {
        if (cooking && furnace != null) {
            packFurnace = true;
            step = Step.COLLECT;
            collectTicks = 0;
        }
    }

    public boolean idle() {
        return step == Step.IDLE && !cooking;
    }

    /** True once it has looked everywhere it could walk and found no tree for its first pickaxe. */
    public boolean gaveUp() {
        return gaveUp;
    }

    // ------------------------------------------------------------------ inventory

    private static int count(SimpleInventory inv, Mat mat) {
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && mat.match.test(s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static int count(SimpleInventory inv, Item item) {
        return inv.count(item);
    }

    private static void take(SimpleInventory inv, Mat mat, int n) {
        for (int i = 0; i < inv.size() && n > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && mat.match.test(s)) {
                int used = Math.min(n, s.getCount());
                s.decrement(used);
                n -= used;
            }
        }
    }

    private static void give(VillagerEntityMCA villager, ItemStack stack) {
        ItemStack leftover = villager.getInventory().addStack(stack);
        if (!leftover.isEmpty()) {
            villager.dropStack(leftover);
        }
    }

    /** The planks a log saws into: oak log, stripped oak, oak wood all give oak planks; stems give their planks. */
    public static Item planksFor(Item log) {
        Identifier id = Registries.ITEM.getId(log);
        String path = id.getPath().replace("stripped_", "");
        for (String suffix : new String[]{"_log", "_wood", "_stem", "_hyphae"}) {
            if (path.endsWith(suffix)) {
                path = path.substring(0, path.length() - suffix.length()) + "_planks";
                break;
            }
        }
        Item planks = Registries.ITEM.get(new Identifier(id.getNamespace(), path));
        return planks == Items.AIR ? Items.OAK_PLANKS : planks;
    }

    /** 0 without a pickaxe, then 1 wood or gold, 2 stone, 3 iron, 4 diamond, 5 netherite. */
    public static int pickTier(ItemStack stack) {
        if (!WorkTools.isPickaxe(stack)) {
            return 0;
        }
        return stack.getItem() instanceof ToolItem tool ? tool.getMaterial().getMiningLevel() + 1 : 2;
    }

    public static int swordTier(ItemStack stack) {
        return stack.getItem() instanceof SwordItem sword ? sword.getMaterial().getMiningLevel() + 1 : 0;
    }

    private static int best(SimpleInventory inv, java.util.function.ToIntFunction<ItemStack> tier) {
        int best = 0;
        for (int i = 0; i < inv.size(); i++) {
            best = Math.max(best, tier.applyAsInt(inv.getStack(i)));
        }
        return best;
    }

    /** Puts the best pickaxe it owns in the main hand. False when it has none. */
    public static boolean holdBestPick(VillagerEntityMCA villager) {
        SimpleInventory inv = villager.getInventory();
        ItemStack bestStack = ItemStack.EMPTY;
        int bestTier = pickTier(villager.getMainHandStack());
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            int tier = pickTier(s);
            if (tier > bestTier) {
                bestTier = tier;
                bestStack = s;
            }
        }
        if (bestStack.isEmpty()) {
            return bestTier > 0 || InventoryUtils.holdMain(villager, WorkTools::isPickaxe);
        }
        ItemStack chosen = bestStack;
        return InventoryUtils.holdMain(villager, s -> s == chosen);
    }

    // ------------------------------------------------------------------ planning

    /**
     * Whether the mats for {@code recipe} are there or can be made right now from logs, planks and sticks it
     * carries. With {@code withWood} a missing log counts as obtainable (it will go and chop).
     */
    private static int shortLogs(SimpleInventory inv, Recipe recipe, boolean needsTable) {
        int planks = count(inv, Mat.PLANKS);
        int sticks = count(inv, Mat.STICK);
        int needPlanks = recipe.cost().getOrDefault(Mat.PLANKS, 0);
        int needSticks = recipe.cost().getOrDefault(Mat.STICK, 0);
        if (needsTable && count(inv, Mat.TABLE) == 0) {
            needPlanks += 4;
        }
        if (sticks < needSticks) {
            needPlanks += 2 * (int) Math.ceil((needSticks - sticks) / 4.0);
        }
        int missingPlanks = Math.max(0, needPlanks - planks);
        int logs = count(inv, Mat.LOG);
        return Math.max(0, (int) Math.ceil(missingPlanks / 4.0) - logs);
    }

    private static boolean hasNonWood(SimpleInventory inv, Recipe recipe) {
        for (Map.Entry<Mat, Integer> e : recipe.cost().entrySet()) {
            if (e.getKey() == Mat.PLANKS || e.getKey() == Mat.STICK) {
                continue;
            }
            if (count(inv, e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** The next thing worth making, best first. Null when its gear is as good as its bag allows. */
    @Nullable
    private Recipe nextGoal(ServerWorld world, VillagerEntityMCA villager) {
        SimpleInventory inv = villager.getInventory();
        int pick = Math.max(pickTier(villager.getMainHandStack()), best(inv, Progreso::pickTier));
        int sword = Math.max(swordTier(villager.getMainHandStack()), best(inv, Progreso::swordTier));
        List<Recipe> wanted = new ArrayList<>();
        if (wantChest && count(inv, Items.CHEST) > 0) {
            wantChest = false;
        }
        if (wantChest && pick > 0) {
            wanted.add(CHEST);
        }
        if (pick == 0) {
            wanted.add(WOODEN_PICKAXE);
        }
        if (pick < 4 && count(inv, Mat.DIAMOND) >= 3) {
            wanted.add(DIAMOND_PICKAXE);
        }
        if (pick < 3 && count(inv, Mat.IRON) >= 3) {
            wanted.add(IRON_PICKAXE);
        }
        if (pick < 2 && count(inv, Mat.STONE) >= 3) {
            wanted.add(STONE_PICKAXE);
        }
        if (pick >= 4 && sword < 4 && count(inv, Mat.DIAMOND) >= 2) {
            wanted.add(DIAMOND_SWORD);
        }
        if (pick >= 3 && sword < 3 && count(inv, Mat.IRON) >= 2) {
            wanted.add(IRON_SWORD);
        }
        if (pick >= 2 && sword < 2 && count(inv, Mat.STONE) >= 2 + 8) {
            wanted.add(STONE_SWORD);
        }
        if (pick >= 1 && sword < 1 && count(inv, Mat.PLANKS) + 4 * count(inv, Mat.LOG) >= 2 + 2 + 4) {
            wanted.add(WOODEN_SWORD);
        }
        // coal for the furnace comes first: one lump smelts eight raw iron
        int coalForIron = (int) Math.ceil(count(inv, Items.RAW_IRON) / 8.0);
        if (count(inv, Mat.COAL) > coalForIron && count(inv, Items.TORCH) < 16) {
            wanted.add(TORCHES);
        }
        for (Recipe recipe : wanted) {
            boolean needsTable = recipe.table() && !tableInReach(world, villager);
            if (!hasNonWood(inv, recipe)) {
                continue;
            }
            int logs = shortLogs(inv, recipe, needsTable);
            if (logs == 0 || recipe == WOODEN_PICKAXE || nearestLog(world, villager, Retina.memory(villager), world.getTime()) != null) {
                return recipe;
            }
        }
        if (!cooking && pick < 3 && count(inv, Mat.IRON) < 3 && count(inv, Items.RAW_IRON) + count(inv, Mat.IRON) >= 3 && fuelFor(inv, 1) != null
                && (count(inv, Mat.FURNACE) > 0 || count(inv, Mat.STONE) >= 8)) {
            return FURNACE; // stands for "smelt": a furnace is needed first
        }
        return null;
    }

    // ------------------------------------------------------------------ the loop

    /**
     * One tick of gearing up. True while it is busy with it (chopping, crafting, smelting, packing up), so
     * the caller leaves mining for later.
     */
    public boolean tick(ServerWorld world, VillagerEntityMCA villager, BiConsumer<String, Object[]> say) {
        VisualMemory memory = Retina.memory(villager);
        long now = world.getTime();
        if (step == Step.IDLE && cooking) {
            BlockEntity be = furnace == null ? null : world.getBlockEntity(furnace);
            if (!(be instanceof AbstractFurnaceBlockEntity oven)) {
                cooking = false;
                furnace = null;
            } else if (oven.getStack(2).getCount() >= 3 || oven.getStack(0).isEmpty()
                    || !villager.getBlockPos().isWithinDistance(furnace, FURNACE_LEASH)) {
                // the first ingots are out, or it went too far: back to the furnace
                step = Step.COLLECT;
                collectTicks = 0;
            }
        }
        if (step == Step.IDLE) {
            if (--nextCheck > 0) {
                return false;
            }
            nextCheck = 40;
            goal = nextGoal(world, villager);
            if (goal == null) {
                return false;
            }
            SimpleInventory inv = villager.getInventory();
            if (goal == FURNACE && (count(inv, Items.RAW_IRON) > 0 && (count(inv, Mat.FURNACE) > 0 || count(inv, Mat.STONE) >= 8))) {
                step = count(inv, Mat.FURNACE) > 0 ? Step.SMELT : Step.TABLE;
            } else if (shortLogs(inv, goal, goal.table() && !tableInReach(world, villager)) > 0) {
                step = Step.WOOD;
            } else {
                step = goal.table() ? Step.TABLE : Step.IDLE;
                if (!goal.table()) {
                    craft(world, villager, goal, say);
                    return true;
                }
            }
        }
        switch (step) {
            case WOOD -> wood(world, villager, memory, now, say);
            case TABLE -> atTable(world, villager, memory, now, say);
            case SMELT -> smelt(world, villager, memory, now, say);
            case COLLECT -> collect(world, villager, memory, now, say);
            case PACK_UP -> packUp(world, villager);
            default -> {
            }
        }
        return step != Step.IDLE;
    }

    // ------------------------------------------------------------------ wood

    private void wood(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory, long now, BiConsumer<String, Object[]> say) {
        SimpleInventory inv = villager.getInventory();
        if (goal == null) {
            step = Step.IDLE;
            return;
        }
        boolean needsTable = goal.table() && !tableInReach(world, villager);
        int missing = shortLogs(inv, goal, needsTable);
        // for the first pickaxe it takes a few spare logs, for the sticks and tables to come
        int spare = goal == WOODEN_PICKAXE ? LOGS_TO_START : 0;
        if (log == null || !world.getBlockState(log).isIn(BlockTags.LOGS) || ignored.getOrDefault(log.asLong(), 0L) > now) {
            log = nearestLog(world, villager, memory, now);
            logTicks = 0;
        }
        boolean enough = missing == 0 && (count(inv, Mat.LOG) >= spare || log == null);
        if (log == null && missing > 0 && goal != WOODEN_PICKAXE) {
            // no tree in sight for an upgrade: carry on mining, look again later
            step = Step.IDLE;
            nextCheck = 600;
            return;
        }
        if (enough) {
            breaker.reset(world, villager);
            villager.getNavigation().stop();
            step = goal.table() ? Step.TABLE : Step.IDLE;
            if (!goal.table()) {
                craft(world, villager, goal, say);
            }
            return;
        }
        if ((villager.age % 10) == 0) {
            Hands.collect(world, villager, villager.getBlockPos(), 2.5);
        }
        if (log == null) {
            explore(world, villager, memory, now, say);
            return;
        }
        exploreTicks = 0;
        BlockState state = world.getBlockState(log);
        if (Retina.visibleFace(world, villager, log, Retina.BLOCK_REACH) != null) {
            villager.getNavigation().stop();
            InventoryUtils.holdMain(villager, s -> s.getItem() instanceof AxeItem);
            memory.doing("talando " + state.getBlock().getName().getString(), now);
            int r = breaker.tick(world, villager, log);
            if (r == Hands.Breaker.DONE) {
                Hands.collect(world, villager, log, 3.0);
                log = null;
            } else if (r == Hands.Breaker.FAILED) {
                ignored.put(log.asLong(), now + 1200);
                log = null;
            }
            return;
        }
        memory.doing("yendo a un árbol", now);
        if (++logTicks > 300) {
            ignored.put(log.asLong(), now + 1200);
            log = null;
            return;
        }
        if ((logTicks % 20) == 1 || villager.getNavigation().isIdle()) {
            villager.getNavigation().startMovingTo(log.getX() + 0.5, log.getY(), log.getZ() + 0.5, 0.65);
        }
    }

    @Nullable
    private BlockPos nearestLog(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory, long now) {
        BlockPos feet = villager.getBlockPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (VisualMemory.SeenBlock seen : memory.blocks(s -> s.state.isIn(BlockTags.LOGS))) {
            if (ignored.getOrDefault(seen.pos.asLong(), 0L) > now || !world.getBlockState(seen.pos).isIn(BlockTags.LOGS)) {
                continue;
            }
            // a tree the world grew; the log walls and beams of a house are not firewood
            if (!Obras.tree(world, seen.pos) || Obras.inVillageBuilding(world, seen.pos)) {
                ignored.put(seen.pos.asLong(), now + 600);
                continue;
            }
            double distance = Math.sqrt(seen.pos.getSquaredDistance(feet));
            if (distance > WOOD_SEARCH) {
                continue;
            }
            // the lowest logs of a trunk first: they are the ones a player can reach from the ground
            double score = distance + Math.max(0, seen.pos.getY() - feet.getY()) * 0.5;
            if (score < bestScore) {
                bestScore = score;
                best = seen.pos;
            }
        }
        return best;
    }

    /** No tree in memory: walk somewhere new and look around, as a player turns the camera. */
    private void explore(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory, long now, BiConsumer<String, Object[]> say) {
        memory.doing("buscando árboles", now);
        float yaw = villager.getHeadYaw() + 9.0F;
        Vec3d look = Vec3d.fromPolar(-5.0F + 10.0F * MathHelper.sin(exploreTicks * 0.05F), yaw).multiply(8);
        Hands.aim(villager, villager.getEyePos().add(look));
        if (++exploreTicks % 80 == 1 || villager.getNavigation().isIdle() && exploreTicks % 20 == 0) {
            Vec3d target = FuzzyTargeting.find(villager, 24, 6);
            if (target != null) {
                villager.getNavigation().startMovingTo(target.x, target.y, target.z, 0.65);
            }
        }
        if (exploreTicks > GIVE_UP_EXPLORING) {
            exploreTicks = 0;
            if (goal == WOODEN_PICKAXE) {
                gaveUp = true;
            }
            if (!warnedNoWood) {
                warnedNoWood = true;
                say.accept("chore.mining.nowood", new Object[0]);
            }
            step = Step.IDLE;
            nextCheck = 600;
        }
    }

    // ------------------------------------------------------------------ crafting

    private boolean tableInReach(ServerWorld world, VillagerEntityMCA villager) {
        if (table != null && world.getBlockState(table).isOf(Blocks.CRAFTING_TABLE)
                && Retina.visibleFace(world, villager, table, Retina.BLOCK_REACH) != null) {
            return true;
        }
        for (VisualMemory.SeenBlock seen : Retina.memory(villager).blocks(s -> s.state.isOf(Blocks.CRAFTING_TABLE))) {
            if (seen.pos.isWithinDistance(villager.getEyePos(), Retina.BLOCK_REACH + 1) && world.getBlockState(seen.pos).isOf(Blocks.CRAFTING_TABLE)
                    && Retina.visibleFace(world, villager, seen.pos, Retina.BLOCK_REACH) != null) {
                table = seen.pos;
                ownTable = false;
                return true;
            }
        }
        return false;
    }

    /** Turns logs into planks and planks into sticks until {@code recipe}'s wood is covered. */
    private static void prepareWood(VillagerEntityMCA villager, Recipe recipe, boolean tableToo) {
        SimpleInventory inv = villager.getInventory();
        int needPlanks = recipe.cost().getOrDefault(Mat.PLANKS, 0) + (tableToo ? 4 : 0);
        int needSticks = recipe.cost().getOrDefault(Mat.STICK, 0);
        int sticksToMake = Math.max(0, (int) Math.ceil((needSticks - count(inv, Mat.STICK)) / 4.0));
        needPlanks += 2 * sticksToMake;
        while (count(inv, Mat.PLANKS) < needPlanks && count(inv, Mat.LOG) > 0) {
            int slot = Hands.findSlot(inv, Mat.LOG.match);
            Item planks = planksFor(inv.getStack(slot).getItem());
            inv.getStack(slot).decrement(1);
            give(villager, new ItemStack(planks, 4));
        }
        for (int i = 0; i < sticksToMake && count(inv, Mat.PLANKS) >= 2; i++) {
            take(inv, Mat.PLANKS, 2);
            give(villager, new ItemStack(Items.STICK, 4));
        }
    }

    private static boolean affordable(SimpleInventory inv, Recipe recipe) {
        for (Map.Entry<Mat, Integer> e : recipe.cost().entrySet()) {
            if (count(inv, e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static boolean craft(ServerWorld world, VillagerEntityMCA villager, Recipe recipe, BiConsumer<String, Object[]> say) {
        prepareWood(villager, recipe, false);
        SimpleInventory inv = villager.getInventory();
        if (!affordable(inv, recipe)) {
            return false;
        }
        recipe.cost().forEach((mat, n) -> take(inv, mat, n));
        ItemStack made = new ItemStack(recipe.output(), recipe.count());
        give(villager, made.copy());
        villager.swingHand(Hand.MAIN_HAND);
        Retina.memory(villager).doing("fabricó " + made.getName().getString(), world.getTime());
        if (recipe == TORCHES) {
            say.accept("chore.mining.torches", new Object[]{made.getCount()});
        } else if (recipe.output() != Items.STICK) {
            say.accept("chore.mining.crafted", new Object[]{made.getName()});
        }
        return true;
    }

    private void atTable(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory, long now, BiConsumer<String, Object[]> say) {
        villager.getNavigation().stop();
        if (goal == null) {
            step = Step.IDLE;
            return;
        }
        if (!tableInReach(world, villager)) {
            SimpleInventory inv = villager.getInventory();
            if (count(inv, Mat.TABLE) == 0) {
                prepareWood(villager, CRAFTING_TABLE, false);
                if (!craft(world, villager, CRAFTING_TABLE, (k, p) -> {
                })) {
                    step = Step.WOOD;
                    return;
                }
            }
            memory.doing("poniendo una mesa de crafteo", now);
            BlockPos placed = placeBeside(world, villager, Mat.TABLE);
            if (placed == null) {
                // nowhere to set it down here: try again after a few steps
                step = Step.IDLE;
                nextCheck = 100;
                return;
            }
            table = placed;
            ownTable = true;
            craftDelay = CRAFT_TICKS;
            return;
        }
        Hands.aim(villager, Vec3d.ofCenter(table));
        if (--craftDelay > 0) {
            return;
        }
        craftDelay = CRAFT_TICKS;
        memory.doing("trabajando en la mesa de crafteo", now);
        world.playSound(null, table, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 0.5F, 1.0F);
        if (goal == FURNACE) {
            if (count(villager.getInventory(), Mat.FURNACE) == 0 && !craft(world, villager, FURNACE, say)) {
                finishCrafting();
                return;
            }
            // more to make while the table is out?
            Recipe next = nextGoal(world, villager);
            if (next != null && next != FURNACE && next.table() && affordableWithWood(villager.getInventory(), next)) {
                goal = next;
                return;
            }
            step = Step.SMELT;
            return;
        }
        prepareWood(villager, goal, false);
        if (!craft(world, villager, goal, say)) {
            finishCrafting();
            return;
        }
        Recipe next = nextGoal(world, villager);
        if (next != null && next.table() && (next == FURNACE || affordableWithWood(villager.getInventory(), next))) {
            goal = next;
            return;
        }
        if (next != null && !next.table() && affordableWithWood(villager.getInventory(), next)) {
            craft(world, villager, next, say);
        }
        finishCrafting();
    }

    private static boolean affordableWithWood(SimpleInventory inv, Recipe recipe) {
        return hasNonWood(inv, recipe) && shortLogs(inv, recipe, false) == 0;
    }

    private void finishCrafting() {
        step = ownTable && table != null ? Step.PACK_UP : Step.IDLE;
        nextCheck = 20;
    }

    @Nullable
    private static BlockPos placeBeside(ServerWorld world, VillagerEntityMCA villager, Mat what) {
        return placeBeside(world, villager, what.match);
    }

    /** Sets a carried block (table, furnace, chest) down on the floor next to it, against the top of the block below. */
    @Nullable
    public static BlockPos placeBeside(ServerWorld world, VillagerEntityMCA villager, Predicate<ItemStack> what) {
        SimpleInventory inv = villager.getInventory();
        int slot = Hands.findSlot(inv, what);
        if (slot < 0) {
            return null;
        }
        ItemStack stack = inv.getStack(slot);
        BlockPos feet = villager.getBlockPos();
        Direction facing = villager.getHorizontalFacing();
        List<Direction> order = new ArrayList<>(List.of(facing, facing.rotateYClockwise(), facing.rotateYCounterclockwise(), facing.getOpposite()));
        for (Direction dir : order) {
            BlockPos cell = feet.offset(dir);
            BlockState here = world.getBlockState(cell);
            if (!here.isReplaceable() || !here.getFluidState().isEmpty() || !Mining.standable(world, cell.down())) {
                continue;
            }
            if (!world.getOtherEntities(null, new net.minecraft.util.math.Box(cell)).isEmpty()) {
                continue;
            }
            if (Hands.place(world, villager, cell, stack)) {
                return cell;
            }
        }
        return null;
    }

    /** Breaks its own table or furnace back into the bag. */
    private void packUp(ServerWorld world, VillagerEntityMCA villager) {
        BlockPos target = furnace != null && !cooking ? furnace : table;
        if (target == null || world.getBlockState(target).isAir()) {
            if (target == furnace && furnace != null) {
                furnace = null;
            } else {
                table = null;
                ownTable = false;
            }
            step = furnace != null && !cooking || ownTable && table != null ? Step.PACK_UP : Step.IDLE;
            return;
        }
        if (target.equals(furnace)) {
            holdBestPick(villager);
        } else {
            InventoryUtils.holdMain(villager, s -> s.getItem() instanceof AxeItem);
        }
        Retina.memory(villager).doing("recogiendo " + world.getBlockState(target).getBlock().getName().getString(), world.getTime());
        int r = breaker.tick(world, villager, target);
        if (r == Hands.Breaker.DONE) {
            Hands.collect(world, villager, target, 2.5);
        } else if (r == Hands.Breaker.FAILED) {
            // out of reach now; leave it where it stands
            if (target.equals(furnace) && !cooking) {
                furnace = null;
            } else {
                table = null;
                ownTable = false;
            }
        }
    }

    // ------------------------------------------------------------------ smelting

    @Nullable
    private static Mat fuelFor(SimpleInventory inv, int items) {
        if (count(inv, Mat.COAL) * 8 >= items) {
            return Mat.COAL;
        }
        if (count(inv, Mat.PLANKS) * 3 >= items * 2) {
            return Mat.PLANKS;
        }
        if (count(inv, Mat.LOG) * 3 >= items * 2) {
            return Mat.LOG;
        }
        return count(inv, Mat.COAL) > 0 ? Mat.COAL : count(inv, Mat.PLANKS) > 0 ? Mat.PLANKS : count(inv, Mat.LOG) > 0 ? Mat.LOG : null;
    }

    /** Puts a furnace down, loads it with the raw iron and fuel from the bag, and goes on with its day. */
    private void smelt(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory, long now, BiConsumer<String, Object[]> say) {
        villager.getNavigation().stop();
        SimpleInventory inv = villager.getInventory();
        if (furnace == null || !world.getBlockState(furnace).isOf(Blocks.FURNACE)) {
            memory.doing("poniendo un horno", now);
            furnace = placeBeside(world, villager, Mat.FURNACE);
            if (furnace == null) {
                step = ownTable && table != null ? Step.PACK_UP : Step.IDLE;
                nextCheck = 200;
                return;
            }
        }
        BlockEntity be = world.getBlockEntity(furnace);
        if (!(be instanceof AbstractFurnaceBlockEntity oven)) {
            step = Step.PACK_UP;
            return;
        }
        Hands.aim(villager, Vec3d.ofCenter(furnace));
        if (!load(oven, inv)) {
            step = Step.PACK_UP;
            return;
        }
        world.playSound(null, furnace, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.5F, 1.0F);
        memory.doing("fundiendo " + oven.getStack(0).getCount() + " de hierro crudo; sigue minando mientras", now);
        say.accept("chore.mining.cooking", new Object[]{oven.getStack(0).getCount()});
        cooking = true;
        packFurnace = false;
        // the table goes back in the bag; the furnace stays lit while it mines nearby
        step = ownTable && table != null ? Step.PACK_UP : Step.IDLE;
        nextCheck = 20;
    }

    /** Raw iron from the bag into the input, fuel for it into the fuel slot. False when there is nothing to smelt or burn. */
    private static boolean load(AbstractFurnaceBlockEntity oven, SimpleInventory inv) {
        ItemStack input = oven.getStack(0);
        int rawSlot = Hands.findSlot(inv, s -> s.isOf(Items.RAW_IRON));
        if (rawSlot >= 0 && (input.isEmpty() || input.isOf(Items.RAW_IRON) && input.getCount() < 64)) {
            ItemStack raw = inv.getStack(rawSlot);
            int add = Math.min(raw.getCount(), 64 - input.getCount());
            if (input.isEmpty()) {
                oven.setStack(0, raw.split(add));
            } else {
                raw.decrement(add);
                input.increment(add);
            }
        }
        int toSmelt = oven.getStack(0).getCount();
        if (toSmelt == 0) {
            return false;
        }
        ItemStack fuelNow = oven.getStack(1);
        // fuel already in there and burning covers this many items
        int covered = fuelNow.isEmpty() ? 0 : fuelNow.isOf(Items.COAL) || fuelNow.isOf(Items.CHARCOAL) ? fuelNow.getCount() * 8 : fuelNow.getCount() * 3 / 2;
        if (covered < toSmelt) {
            Mat fuel = fuelFor(inv, toSmelt - covered);
            if (fuel == null && fuelNow.isEmpty()) {
                // nothing to burn: give the iron back
                giveBack(inv, oven, 0);
                return false;
            }
            if (fuel != null) {
                int need = toSmelt - covered;
                int items = fuel == Mat.COAL ? (int) Math.ceil(need / 8.0) : (int) Math.ceil(need / 1.5);
                int slot = Hands.findSlot(inv, fuel.match);
                ItemStack fuelStack = inv.getStack(slot);
                if (fuelNow.isEmpty()) {
                    oven.setStack(1, fuelStack.split(Math.min(items, fuelStack.getCount())));
                } else if (ItemStack.canCombine(fuelNow, fuelStack)) {
                    int add = Math.min(Math.min(items, fuelStack.getCount()), fuelNow.getMaxCount() - fuelNow.getCount());
                    fuelStack.decrement(add);
                    fuelNow.increment(add);
                }
            }
        }
        oven.markDirty();
        return true;
    }

    private static void giveBack(SimpleInventory inv, AbstractFurnaceBlockEntity oven, int slot) {
        ItemStack stack = oven.getStack(slot);
        if (!stack.isEmpty()) {
            ItemStack left = inv.addStack(stack.copy());
            oven.setStack(slot, left);
        }
    }

    /**
     * Back at the furnace: the ingots come out and go to the table at once. If it brought more raw iron, that
     * goes in to cook while it works; when nothing is left cooking (or it is leaving) the furnace comes along.
     */
    private void collect(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory, long now, BiConsumer<String, Object[]> say) {
        BlockEntity be = furnace == null ? null : world.getBlockEntity(furnace);
        if (!(be instanceof AbstractFurnaceBlockEntity oven)) {
            cooking = false;
            furnace = null;
            step = Step.IDLE;
            return;
        }
        if (Retina.visibleFace(world, villager, furnace, Retina.BLOCK_REACH) == null) {
            memory.doing("volviendo al horno", now);
            if (++collectTicks > 900) {
                // cannot get back to it: it stays where it is, cooking for whoever passes by
                cooking = false;
                furnace = null;
                step = Step.IDLE;
                return;
            }
            if ((collectTicks % 20) == 1 || villager.getNavigation().isIdle()) {
                villager.getNavigation().startMovingTo(furnace.getX() + 0.5, furnace.getY(), furnace.getZ() + 0.5, 0.7);
            }
            return;
        }
        villager.getNavigation().stop();
        Hands.aim(villager, Vec3d.ofCenter(furnace));
        SimpleInventory inv = villager.getInventory();
        int ingots = oven.getStack(2).getCount();
        giveBack(inv, oven, 2);
        oven.markDirty();
        world.playSound(null, furnace, SoundEvents.BLOCK_STONE_HIT, SoundCategory.BLOCKS, 0.5F, 1.0F);
        if (ingots > 0) {
            say.accept("chore.mining.smelted", new Object[]{ingots, new ItemStack(Items.IRON_INGOT).getName()});
        }
        boolean stillCooking = !packFurnace && load(oven, inv);
        if (!stillCooking) {
            giveBack(inv, oven, 0);
            giveBack(inv, oven, 1);
            oven.markDirty();
            cooking = false;
            packFurnace = false;
        }
        memory.doing(stillCooking ? "sacó " + ingots + " lingotes; el resto sigue en el horno" : "sacó " + ingots + " lingotes y recoge el horno", now);
        // the ingots go straight to the table
        Recipe next = nextGoal(world, villager);
        if (next != null && next != FURNACE && next.table() && affordableWithWood(inv, next)) {
            goal = next;
            step = Step.TABLE;
            return;
        }
        step = cooking ? Step.IDLE : Step.PACK_UP;
        nextCheck = 20;
    }
}
