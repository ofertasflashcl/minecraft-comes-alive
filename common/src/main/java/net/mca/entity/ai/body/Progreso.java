package net.mca.entity.ai.body;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.WorkTools;
import net.mca.entity.ai.sense.Obras;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.mca.util.InventoryUtils;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.FuzzyTargeting;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Getting your own gear the way a player starts a world: punch a tree, turn logs into planks and sticks,
 * put a crafting table down, make a wooden pickaxe, and from there climb the ladder with what mining brings
 * home: stone tools from cobblestone, a furnace to smelt raw iron, iron and diamond tools, torches from coal,
 * a sword to go with the pickaxe, a spare pickaxe before the first one wears out, and meat cooked in the same
 * furnace. Recipes cost exactly what vanilla's cost, tools that need a table are only made with a real table
 * within reach, and smelting happens in a real furnace placed in the world. Tables and furnaces it put down
 * itself are picked back up, as a player would.
 * <p>
 * Wood only grows outside: underground with nothing to make a pickaxe from it asks to be taken up
 * ({@link #needsSurface()}) instead of wandering the tunnels looking for trees. A tree is felled whole, from the
 * bottom log up, standing in the cut trunk once the lowest logs are gone and building a block up when the top is
 * out of reach; then it gathers what fell and takes its own blocks back.
 */
public final class Progreso {
    private static final int CRAFT_TICKS = 12;
    private static final int LOGS_TO_START = 5;
    private static final int WOOD_SEARCH = 48;
    private static final int GIVE_UP_EXPLORING = 2400;
    /** A tree it can no longer get anywhere with after this long is left as it is. */
    private static final int TREE_TICKS = 3000;
    /** How far from the stump it looks for the logs that fell and the logs of the same trunk. */
    private static final int TREE_REACH = 6;

    /** Raw food a furnace turns into a meal, by registry name (the same in every version). */
    private static final Set<String> RAW_FOOD = Set.of("beef", "porkchop", "chicken", "mutton", "rabbit", "cod", "salmon", "potato");

    private enum Mat {
        LOG(s -> s.isIn(ItemTags.LOGS)),
        PLANKS(s -> s.isIn(ItemTags.PLANKS)),
        STICK(s -> s.isOf(Items.STICK)),
        STONE(s -> s.isIn(ItemTags.STONE_TOOL_MATERIALS)),
        IRON(s -> s.isOf(Items.IRON_INGOT)),
        DIAMOND(s -> s.isOf(Items.DIAMOND)),
        COAL(s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL)),
        TABLE(s -> s.isOf(Items.CRAFTING_TABLE)),
        FURNACE(s -> s.isOf(Items.FURNACE)),
        RAW(Progreso::rawFood);

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
    /** Stands for "cook the raw food it carries": a furnace is needed first, like {@link #FURNACE} for iron. */
    private static final Recipe COOK = new Recipe(Items.FURNACE, 1, true, Map.of(Mat.STONE, 8));
    /** Stands for "take some wood along before going down": logs for a table and sticks for a spare pickaxe. */
    private static final Recipe KIT = new Recipe(Items.STICK, 0, false, Map.of(Mat.PLANKS, 8));
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

    /** The tree it is felling: one trunk, bottom log first, then what fell, then its own blocks back. */
    private static final class Tree {
        /** The stump cell: where the lowest log of the trunk stood. */
        final BlockPos base;
        int ticks;
        int side;
        int leaves;
        int walk;
        int cut;
        int stuck;
        int sweep;
        @Nullable
        BlockPos next;
        int nextAt;
        /** Blocks it stood on to reach the top logs, lowest first. */
        final List<BlockPos> pillar = new ArrayList<>();
        @Nullable
        BlockPos rising;
        /** No log of this trunk left that it knows of: gather what fell and come down. */
        boolean felled;

        Tree(BlockPos base) {
            this.base = base;
        }
    }

    private final Hands.Breaker breaker = new Hands.Breaker();
    private final Map<Long, Long> ignored = new HashMap<>();
    private Step step = Step.IDLE;
    @Nullable
    private Recipe goal;
    @Nullable
    private Tree tree;
    private int dropTicks;
    private int exploreTicks;
    @Nullable
    private BlockPos table;
    private boolean ownTable;
    @Nullable
    private BlockPos furnace;
    /** Where it stood when it lit the furnace: the way back to it. */
    private BlockPos furnaceStand;
    private final Motor motor = new Motor();
    private int craftDelay;
    private int nextCheck;
    private boolean gaveUp;
    private boolean warnedNoWood;
    /** Something is in its furnace and it is off doing other things meanwhile. */
    private boolean cooking;
    /** When collecting, take everything and the furnace too: it is leaving. */
    private boolean packFurnace;
    private int collectTicks;
    /** Ticks it walks back to its furnace on a path before trying its own steps again. */
    private int motorRest;
    private int packTicks;
    private int tableTicks;
    private boolean wantChest;
    private boolean wantKit;
    private boolean wantCook;
    /** No pickaxe, nothing to make one from, and no tree down here: the way on is up to the surface. */
    private boolean needsSurface;

    /** Asks for a chest to be made (from 8 planks at a table) next time it gears up. */
    public void wantChest() {
        if (!wantChest) {
            wantChest = true;
            nextCheck = 0;
        }
    }

    /** The trip home is over: the chest no longer comes before everything else. */
    public void forgetChest() {
        wantChest = false;
    }

    public boolean wantsChest() {
        return wantChest;
    }

    /** About to go down: take wood for a spare pickaxe and a table along, like a player packing up. */
    public void wantKit() {
        if (!wantKit) {
            wantKit = true;
            nextCheck = 0;
        }
    }

    public boolean wantsKit() {
        return wantKit;
    }

    /** It carries raw food: cook it next time it gears up, even a single piece. */
    public void wantCook() {
        if (!wantCook) {
            wantCook = true;
            nextCheck = 0;
        }
    }

    /** Underground with no pickaxe and nothing to make one: it has to go up for wood. */
    public boolean needsSurface() {
        return needsSurface;
    }

    /** The furnace it left cooking, or null. */
    @org.jetbrains.annotations.Nullable
    public BlockPos cookingAt() {
        return cooking ? furnace : null;
    }

    public boolean cooking() {
        return cooking;
    }

    /** It is going away: fetch whatever is in the furnace and pick the furnace up first. */
    public void leaving() {
        // the caller may say so every tick on its way out: only the first time starts the walk back to the furnace,
        // so the give-up timer of that walk keeps running
        if (cooking && furnace != null && !(packFurnace && step == Step.COLLECT)) {
            packFurnace = true;
            step = Step.COLLECT;
            collectTicks = 0;
            motorRest = 0;
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

    /** Raw meat or fish (or a potato) that a furnace turns into a proper meal. */
    public static boolean rawFood(ItemStack stack) {
        return !stack.isEmpty() && stack.isFood() && RAW_FOOD.contains(Registries.ITEM.getId(stack.getItem()).getPath());
    }

    /** Anything it would put in a furnace: raw iron for ingots, raw food for a meal. */
    private static boolean cookable(ItemStack stack) {
        return stack.isOf(Items.RAW_IRON) || rawFood(stack);
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

    /** The best pickaxe tier it owns, in the hand or the bag (0 for none). */
    public static int bestPickTier(VillagerEntityMCA villager) {
        return Math.max(pickTier(villager.getMainHandStack()), best(villager.getInventory(), Progreso::pickTier));
    }

    /** Whether the main hand holds a stack of its own that is not one of the bag's slots. */
    private static boolean handApart(VillagerEntityMCA villager) {
        ItemStack held = villager.getMainHandStack();
        if (held.isEmpty()) {
            return false;
        }
        SimpleInventory inv = villager.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i) == held) {
                return false;
            }
        }
        return true;
    }

    /** How many pickaxes it carries, the one in its hand included. */
    public static int countPicks(VillagerEntityMCA villager) {
        SimpleInventory inv = villager.getInventory();
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            if (WorkTools.isPickaxe(inv.getStack(i))) {
                n++;
            }
        }
        if (handApart(villager) && WorkTools.isPickaxe(villager.getMainHandStack())) {
            n++;
        }
        return n;
    }

    /** Its best pickaxe (the one it digs with), or empty. */
    public static ItemStack bestPickStack(VillagerEntityMCA villager) {
        ItemStack bestStack = WorkTools.isPickaxe(villager.getMainHandStack()) ? villager.getMainHandStack() : ItemStack.EMPTY;
        int bestTier = pickTier(bestStack);
        SimpleInventory inv = villager.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            int tier = pickTier(s);
            if (tier > bestTier) {
                bestTier = tier;
                bestStack = s;
            }
        }
        return bestStack;
    }

    /** A pickaxe that is close to breaking: a player watching the bar makes the next one now. */
    public static boolean worn(ItemStack pick) {
        return pick.isDamageable() && pick.getMaxDamage() - pick.getDamage() < Math.max(24, pick.getMaxDamage() / 10);
    }

    /** Whether it could make a stone pickaxe (or better) right now with what it carries, table included. */
    public static boolean canCraftPick(VillagerEntityMCA villager) {
        SimpleInventory inv = villager.getInventory();
        boolean needsTable = count(inv, Mat.TABLE) == 0;
        for (Recipe recipe : new Recipe[]{DIAMOND_PICKAXE, IRON_PICKAXE, STONE_PICKAXE}) {
            if (hasNonWood(inv, recipe) && shortLogs(inv, recipe, needsTable) == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ready to go down: a spare pickaxe, or one pickaxe and the wood for a table and sticks to make the next one
     * from the cobblestone it will dig.
     */
    public static boolean hasKit(VillagerEntityMCA villager) {
        SimpleInventory inv = villager.getInventory();
        int picks = countPicks(villager);
        int wood = count(inv, Mat.PLANKS) + 4 * count(inv, Mat.LOG) + count(inv, Mat.STICK) / 2 + 4 * count(inv, Mat.TABLE);
        return picks >= 2 || picks >= 1 && wood >= 6;
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

    /** Smelting stands-in: the furnace has to be there (or made) before anything goes in. */
    private static boolean smelting(@Nullable Recipe recipe) {
        return recipe == FURNACE || recipe == COOK;
    }

    /**
     * Wood within reach of its legs: out in the open (where trees grow), or a tree it saw close by. Underground a
     * tree remembered from the surface does not count: the rock is in between.
     */
    public boolean woodHere(ServerWorld world, VillagerEntityMCA villager) {
        BlockPos feet = villager.getBlockPos();
        if (Mining.outdoors(world, feet)) {
            return true;
        }
        BlockPos log = nearestLog(world, villager, Retina.memory(villager), world.getTime());
        return log != null && log.isWithinDistance(feet, 16) && Math.abs(log.getY() - feet.getY()) <= 6;
    }

    /** The next thing worth making, best first. Null when its gear is as good as its bag allows. */
    @Nullable
    private Recipe nextGoal(ServerWorld world, VillagerEntityMCA villager) {
        SimpleInventory inv = villager.getInventory();
        int pick = bestPickTier(villager);
        int sword = Math.max(swordTier(villager.getMainHandStack()), best(inv, Progreso::swordTier));
        needsSurface = false;
        List<Recipe> wanted = new ArrayList<>();
        if (wantChest && count(inv, Items.CHEST) > 0) {
            wantChest = false;
        }
        if (wantChest && pick > 0) {
            // at home with no chest: the wood is for the chest, nothing else gets it first
            boolean needsTable = !tableInReach(world, villager);
            return hasNonWood(inv, CHEST) && (shortLogs(inv, CHEST, needsTable) == 0
                    || woodHere(world, villager) && nearestLog(world, villager, Retina.memory(villager), world.getTime()) != null) ? CHEST : null;
        }
        if (wantKit) {
            // the kit comes after the chest: the chest's wood is not for spare sticks
            if (pick == 0 || hasKit(villager)) {
                wantKit = false;
            } else if (woodHere(world, villager) && nearestLog(world, villager, Retina.memory(villager), world.getTime()) != null) {
                return KIT;
            } else {
                wantKit = false; // no tree around: it goes down with what it has
            }
        }
        if (pick == 0) {
            // the best pickaxe it can make from what it carries; wood only when there is nothing better
            if (count(inv, Mat.DIAMOND) >= 3) {
                wanted.add(DIAMOND_PICKAXE);
            }
            if (count(inv, Mat.IRON) >= 3) {
                wanted.add(IRON_PICKAXE);
            }
            if (count(inv, Mat.STONE) >= 3) {
                wanted.add(STONE_PICKAXE);
            }
            wanted.add(WOODEN_PICKAXE);
        }
        if (pick < 4 && pick > 0 && count(inv, Mat.DIAMOND) >= 3) {
            wanted.add(DIAMOND_PICKAXE);
        }
        if (pick < 3 && pick > 0 && count(inv, Mat.IRON) >= 3) {
            wanted.add(IRON_PICKAXE);
        }
        if (pick < 2 && pick > 0 && count(inv, Mat.STONE) >= 3) {
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
        if (pick > 0 && countPicks(villager) < 2) {
            // a second pickaxe before the first one breaks deep down; cobblestone is always at hand, iron and
            // diamond only replace a worn pickaxe of their own kind (otherwise they go to an upgrade)
            boolean worn = worn(bestPickStack(villager));
            if (worn && pick >= 4 && count(inv, Mat.DIAMOND) >= 3) {
                wanted.add(DIAMOND_PICKAXE);
            } else if (worn && pick >= 3 && count(inv, Mat.IRON) >= 3) {
                wanted.add(IRON_PICKAXE);
            }
            // eight stones stay for a furnace
            if (count(inv, Mat.STONE) >= (worn ? 3 : 11)) {
                wanted.add(STONE_PICKAXE);
            }
        }
        for (Recipe recipe : wanted) {
            boolean needsTable = recipe.table() && !tableInReach(world, villager);
            if (!hasNonWood(inv, recipe) || robsIronPick(world, inv, recipe, pick)) {
                continue;
            }
            int logs = shortLogs(inv, recipe, needsTable);
            if (logs == 0) {
                return recipe;
            }
            boolean here = woodHere(world, villager);
            if (here && nearestLog(world, villager, Retina.memory(villager), world.getTime()) != null) {
                return recipe;
            }
            if (recipe == WOODEN_PICKAXE) {
                if (here) {
                    return recipe; // out in the open with no tree in sight: it goes looking for one
                }
                // underground with no pickaxe and nothing to make one: the wood is up there
                needsSurface = true;
                return null;
            }
        }
        // the wood the next tool and its table need is not firewood
        int keep = woodToKeep(inv);
        if (!cooking && pick < 3 && count(inv, Mat.IRON) < 3 && count(inv, Items.RAW_IRON) + count(inv, Mat.IRON) >= 3 && fuelFor(inv, 1, keep) != null
                && (count(inv, Mat.FURNACE) > 0 || count(inv, Mat.STONE) >= 8)) {
            return FURNACE; // stands for "smelt": a furnace is needed first
        }
        int raw = count(inv, Mat.RAW);
        if (!cooking && raw > 0 && (raw >= 2 || wantCook) && fuelFor(inv, raw, keep) != null
                && (count(inv, Mat.FURNACE) > 0 || count(inv, Mat.STONE) >= 8)) {
            return COOK; // raw meat on it: into the furnace, like a player who hunted
        }
        wantCook = false;
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
            } else if (enoughOut(villager, oven) || oven.getStack(0).isEmpty() || wentOut(oven)
                    || tooFar(villager, furnace)) {
                // the ingots for a tool are out, the fire went out with some left, or it went too far: back to the furnace
                step = Step.COLLECT;
                collectTicks = 0;
                motorRest = 0;
                motor.reset(world, villager);
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
            boolean canSmelt = count(inv, Mat.FURNACE) > 0 || count(inv, Mat.STONE) >= 8;
            if (goal == KIT) {
                if (shortLogs(inv, KIT, false) > 0) {
                    step = Step.WOOD;
                } else {
                    wantKit = false;
                    return false;
                }
            } else if (goal == FURNACE && count(inv, Items.RAW_IRON) > 0 && canSmelt || goal == COOK && count(inv, Mat.RAW) > 0 && canSmelt) {
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
        // a tree it started is felled to the top and what fell is picked up: no half-cut trunks left floating
        if (tree != null) {
            if (fell(world, villager, memory, now)) {
                return;
            }
            tree = null;
        }
        boolean needsTable = goal.table() && !tableInReach(world, villager);
        int missing = shortLogs(inv, goal, needsTable);
        // for the first pickaxe it takes a few spare logs, for the sticks and tables to come
        int spare = goal == WOODEN_PICKAXE ? LOGS_TO_START : 0;
        BlockPos log = nearestLog(world, villager, memory, now);
        boolean enough = missing == 0 && (count(inv, Mat.LOG) >= spare || log == null);
        if (enough) {
            breaker.reset(world, villager);
            villager.getNavigation().stop();
            if (goal == KIT) {
                wantKit = false;
                step = Step.IDLE;
                nextCheck = 20;
                return;
            }
            step = goal.table() ? Step.TABLE : Step.IDLE;
            if (!goal.table()) {
                craft(world, villager, goal, say);
            }
            return;
        }
        if (log == null && missing > 0 && goal != WOODEN_PICKAXE) {
            // no tree in sight for an upgrade: carry on mining, look again later
            step = Step.IDLE;
            nextCheck = 600;
            if (goal == KIT) {
                wantKit = false;
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
        tree = new Tree(stump(world, log));
        fell(world, villager, memory, now);
    }

    /** Where the trunk of {@code log} stands on the ground: down through its logs, and down a cut foot. */
    private static BlockPos stump(ServerWorld world, BlockPos log) {
        BlockPos base = log;
        int k = 0;
        while (k++ < 32 && world.getBlockState(base.down()).isIn(BlockTags.LOGS)) {
            base = base.down();
        }
        k = 0;
        while (k++ < 8 && Mining.passable(world, base.down()) && !Mining.standable(world, base.down())) {
            base = base.down(); // its foot was already cut: the stump cell is on the ground
        }
        return base.toImmutable();
    }

    /**
     * One tick of felling the current tree. True while the tree still keeps it busy: a log to cut, the leaves in
     * the way, a block to stand on for the top, what fell to pick up, its own blocks to take back.
     */
    private boolean fell(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory, long now) {
        Tree t = tree;
        if (t == null) {
            return false;
        }
        BlockPos feet = villager.getBlockPos();
        if (motor.midStep(villager)) {
            // up a block of its own in the trunk: the jump finishes before anything else
            motor.step(world, villager, t.rising != null ? t.rising.up() : feet, false, true);
            return true;
        }
        if (t.rising != null) {
            if (!world.getBlockState(t.rising).isReplaceable()) {
                t.pillar.add(t.rising);
            }
            t.rising = null;
        }
        if (dropTicks > 0) {
            // the log is falling apart into items: they land around its feet
            if (--dropTicks == 0) {
                Hands.collect(world, villager, villager.getBlockPos(), 1.5);
            }
            return true;
        }
        if (++t.ticks > TREE_TICKS) {
            t.felled = true;
        }
        if (!t.felled) {
            if (t.next == null || --t.nextAt <= 0 || !world.getBlockState(t.next).isIn(BlockTags.LOGS)) {
                t.next = nextLog(world, memory, t, now);
                t.nextAt = 20;
            }
            if (t.next == null) {
                t.felled = true;
                dropTicks = 10;
                return true;
            }
            chop(world, villager, memory, now, t, t.next, feet);
            return true;
        }
        return gather(world, villager, memory, now, t, feet);
    }

    private void chop(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory, long now, Tree t, BlockPos target, BlockPos feet) {
        boolean footed = Motor.settled(villager);
        if (footed && Retina.visibleFace(world, villager, target, Retina.BLOCK_REACH) != null) {
            Motor.standStill(villager);
            InventoryUtils.holdMain(villager, s -> s.getItem() instanceof AxeItem);
            memory.doing("talando " + world.getBlockState(target).getBlock().getName().getString(), now);
            int r = breaker.tick(world, villager, target);
            if (r == Hands.Breaker.DONE) {
                t.cut++;
                t.next = null;
                t.walk = 0;
                t.side = 0;
                // look into the gap it opened: the next log of the trunk shows there
                Retina.peek(world, villager, target);
                dropTicks = 8;
            } else if (r == Hands.Breaker.FAILED) {
                t.side++;
            }
            return;
        }
        BlockPos leaf = footed ? leafInTheWay(world, villager, target) : null;
        if (leaf != null && t.leaves < 24) {
            Motor.standStill(villager);
            memory.doing("quitando hojas para llegar al tronco", now);
            int r = breaker.tick(world, villager, leaf);
            if (r == Hands.Breaker.DONE) {
                t.leaves++;
                Retina.peek(world, villager, leaf);
            } else if (r == Hands.Breaker.FAILED) {
                t.side++;
            }
            return;
        }
        boolean inTrunk = feet.getX() == t.base.getX() && feet.getZ() == t.base.getZ();
        BlockPos stand = standFor(world, villager, t, target, feet);
        if (stand == null || t.side > 8) {
            ignored.put(target.asLong(), now + 2400);
            t.next = null;
            t.side = 0;
            if (++t.stuck > 3) {
                t.felled = true;
            }
            return;
        }
        if (feet.equals(stand) && footed) {
            if (inTrunk && target.getY() - feet.getY() >= 5) {
                // out of reach even from the stump: a block under its feet, like a player, never a log
                if (Hands.buildingBlock(villager).isEmpty() || t.pillar.size() >= 8) {
                    ignored.put(target.asLong(), now + 2400);
                    t.next = null;
                    t.felled = true;
                    return;
                }
                memory.doing("pone un bloque bajo los pies para alcanzar el tronco", now);
                t.rising = feet.toImmutable();
                Motor.Result r = motor.step(world, villager, feet.up(), false, true);
                if (r == Motor.Result.BLOCKED) {
                    t.rising = null;
                    motor.reset(world, villager);
                    ignored.put(target.asLong(), now + 2400);
                    t.next = null;
                    t.felled = true;
                }
                return;
            }
            // standing there and still blind to it: go round
            t.side++;
            return;
        }
        memory.doing("yendo al árbol", now);
        if (++t.walk > 200) {
            t.side++;
            t.walk = 0;
            motor.reset(world, villager);
            return;
        }
        if (feet.isWithinDistance(stand, 2.5)) {
            villager.getNavigation().stop();
            if (motor.step(world, villager, stand, false, true) == Motor.Result.BLOCKED) {
                motor.reset(world, villager);
                t.side++;
            }
        } else if (t.walk % 20 == 1 || villager.getNavigation().isIdle()) {
            villager.getNavigation().startMovingTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.65);
        }
    }

    /** Down from its own blocks, then the logs that fell are picked up walking over them. True while at it. */
    private boolean gather(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory, long now, Tree t, BlockPos feet) {
        if (!t.pillar.isEmpty()) {
            BlockPos top = t.pillar.get(t.pillar.size() - 1);
            if (!world.getBlockState(top).isReplaceable() && Obras.kind(world, villager, top) == Obras.Kind.OWN
                    && Motor.settled(villager) && Retina.visibleFace(world, villager, top, Retina.BLOCK_REACH) != null) {
                // its own block comes back into the bag; standing on it, it just drops one block
                Motor.standStill(villager);
                holdBestPick(villager);
                memory.doing("recogiendo el bloque que puso", now);
                int r = breaker.tick(world, villager, top);
                if (r == Hands.Breaker.DONE) {
                    t.pillar.remove(t.pillar.size() - 1);
                    dropTicks = 6;
                } else if (r == Hands.Breaker.FAILED) {
                    t.pillar.clear();
                }
                return true;
            }
            t.pillar.remove(t.pillar.size() - 1); // gone already, or out of reach: left where it is
            return true;
        }
        if (++t.sweep > 240) {
            return false;
        }
        ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class, new Box(t.base).expand(TREE_REACH, 8, TREE_REACH),
                e -> e.isAlive() && !e.getStack().isEmpty() && !Hands.refused(villager, e.getStack(), now))) {
            double d = item.squaredDistanceTo(villager);
            if (d < best) {
                best = d;
                nearest = item;
            }
        }
        if (nearest == null) {
            return false;
        }
        memory.doing("juntando la leña que cayó", now);
        if (best < 2.0) {
            Hands.collect(world, villager, villager.getBlockPos(), 1.5);
            return true;
        }
        if (t.sweep % 20 == 1 || villager.getNavigation().isIdle()) {
            villager.getNavigation().startMovingTo(nearest.getX(), nearest.getY(), nearest.getZ(), 0.65);
        }
        return true;
    }

    /** The lowest log of this trunk it knows of: the column of the stump and the logs touching it. */
    @Nullable
    private BlockPos nextLog(ServerWorld world, VisualMemory memory, Tree t, long now) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (VisualMemory.SeenBlock seen : memory.blocks(s -> s.state.isIn(BlockTags.LOGS))) {
            BlockPos pos = seen.pos;
            int dx = pos.getX() - t.base.getX();
            int dz = pos.getZ() - t.base.getZ();
            int dy = pos.getY() - t.base.getY();
            if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || dy < 0 || dy > 30) {
                continue;
            }
            if (ignored.getOrDefault(pos.asLong(), 0L) > now || !world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                continue;
            }
            // the trunk is still a tree: a cabin's corner logs never are
            if (!Obras.tree(world, pos)) {
                continue;
            }
            double score = dy * 4 + Math.abs(dx) + Math.abs(dz);
            if (score < bestScore) {
                bestScore = score;
                best = pos;
            }
        }
        return best;
    }

    /**
     * Where to stand to cut {@code log}: where it stands now if that is in the cut trunk below it, the stump cell
     * once the lowest two logs are gone (every log above is in sight straight up from there), else beside the
     * trunk, going round it side by side.
     */
    @Nullable
    private static BlockPos standFor(ServerWorld world, VillagerEntityMCA villager, Tree t, BlockPos log, BlockPos feet) {
        if (feet.getX() == t.base.getX() && feet.getZ() == t.base.getZ() && feet.getY() >= t.base.getY() && feet.getY() < log.getY() - 1
                && fits(world, feet)) {
            return feet;
        }
        if (log.getY() > t.base.getY() + 1 && fits(world, t.base)) {
            return t.base;
        }
        Direction[] sides = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int k = 0; k < 4; k++) {
            BlockPos side = t.base.offset(sides[(t.side + k) & 3]);
            for (BlockPos cell : new BlockPos[]{side, side.down(), side.up()}) {
                if (fits(world, cell)) {
                    return cell;
                }
            }
        }
        return null;
    }

    /** Room for the body with a floor under it, and not on a field. */
    private static boolean fits(ServerWorld world, BlockPos cell) {
        return Mining.passable(world, cell) && Mining.passable(world, cell.up()) && Mining.standable(world, cell.down())
                && !world.getBlockState(cell).isIn(BlockTags.LEAVES) && !Obras.cropAt(world, cell.down());
    }

    /** Natural leaves between its eye and the log, within reach: what a player clicks away to get at the trunk. */
    @Nullable
    private static BlockPos leafInTheWay(ServerWorld world, VillagerEntityMCA villager, BlockPos log) {
        Vec3d eye = villager.getEyePos();
        BlockHitResult hit = world.raycast(new RaycastContext(eye, Vec3d.ofCenter(log), RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, villager));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(log)) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = world.getBlockState(pos);
        if (!state.isIn(BlockTags.LEAVES) || !Obras.natural(state) || eye.distanceTo(hit.getPos()) > Retina.BLOCK_REACH) {
            return null;
        }
        return pos.toImmutable();
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
            double distance = Math.sqrt(seen.pos.getSquaredDistance(feet));
            if (distance > WOOD_SEARCH) {
                continue;
            }
            // a tree the world grew; the log walls and beams of a house are not firewood
            if (!Obras.tree(world, seen.pos) || Obras.inVillageBuilding(world, seen.pos)) {
                ignored.put(seen.pos.asLong(), now + 600);
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

    /** No tree in memory: walk somewhere new and look around, as a player turns the camera. Only out in the open. */
    private void explore(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory, long now, BiConsumer<String, Object[]> say) {
        if (!Mining.onSurface(world, villager.getBlockPos())) {
            // no trees grow in the rock: the miner goes up for them, it does not wander the tunnels
            exploreTicks = 0;
            step = Step.IDLE;
            nextCheck = 600;
            if (goal == WOODEN_PICKAXE) {
                needsSurface = true;
            }
            return;
        }
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
        if (recipe == KIT || recipe == COOK) {
            return false; // stand-ins, nothing to craft
        }
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
        if (goal == null) {
            step = Step.IDLE;
            return;
        }
        if (!tableInReach(world, villager)) {
            SimpleInventory inv = villager.getInventory();
            if (ownTable && table != null && world.getBlockState(table).isOf(Blocks.CRAFTING_TABLE)
                    && villager.getPos().distanceTo(Vec3d.ofCenter(table)) < 10 && ++tableTicks < 300) {
                // its own table is just over there: walk back to it instead of making another
                memory.doing("volviendo a su mesa de crafteo", now);
                if ((tableTicks % 20) == 1 || villager.getNavigation().isIdle()) {
                    villager.getNavigation().startMovingTo(table.getX() + 0.5, table.getY(), table.getZ() + 0.5, 0.6);
                }
                return;
            }
            if (tableTicks >= 300) {
                // no way back to it: it stays there
                table = null;
                ownTable = false;
            }
            tableTicks = 0;
            villager.getNavigation().stop();
            if (count(inv, Mat.TABLE) == 0) {
                if (!smelting(goal) && shortLogs(inv, goal, true) > 0) {
                    // a table now would leave too little wood for what it is for
                    step = Step.WOOD;
                    return;
                }
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
                // nowhere to set it down here: try again after a few steps (a furnace that is done comes along first)
                step = furnace != null && !cooking ? Step.PACK_UP : Step.IDLE;
                nextCheck = 100;
                return;
            }
            table = placed;
            ownTable = true;
            craftDelay = CRAFT_TICKS;
            return;
        }
        tableTicks = 0;
        villager.getNavigation().stop();
        Hands.aim(villager, Vec3d.ofCenter(table));
        if (--craftDelay > 0) {
            return;
        }
        craftDelay = CRAFT_TICKS;
        memory.doing("trabajando en la mesa de crafteo", now);
        world.playSound(null, table, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 0.5F, 1.0F);
        if (smelting(goal)) {
            if (count(villager.getInventory(), Mat.FURNACE) == 0 && !craft(world, villager, FURNACE, say)) {
                finishCrafting();
                return;
            }
            // more to make while the table is out?
            Recipe next = nextGoal(world, villager);
            if (next != null && !smelting(next) && next.table() && affordableWithWood(villager.getInventory(), next)) {
                Recipe smelt = goal;
                goal = next;
                prepareWood(villager, goal, false);
                craft(world, villager, goal, say);
                goal = smelt;
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
        if (next != null && next.table() && (smelting(next) || affordableWithWood(villager.getInventory(), next))) {
            goal = next;
            return;
        }
        if (next != null && !next.table() && next != KIT && affordableWithWood(villager.getInventory(), next)) {
            craft(world, villager, next, say);
        }
        finishCrafting();
    }

    /** Whether it has (or can saw from its logs) the planks for a chest, and for the table to make it on. */
    public static boolean canMakeChest(SimpleInventory inv) {
        return hasNonWood(inv, CHEST) && shortLogs(inv, CHEST, true) == 0;
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
        return placeBeside(world, villager, what, cell -> false);
    }

    /** The same, never into a cell {@code keepClear} wants left open (the way into its mine, say). */
    @Nullable
    public static BlockPos placeBeside(ServerWorld world, VillagerEntityMCA villager, Predicate<ItemStack> what, Predicate<BlockPos> keepClear) {
        SimpleInventory inv = villager.getInventory();
        int slot = Hands.findSlot(inv, what);
        if (slot < 0) {
            return null;
        }
        ItemStack stack = inv.getStack(slot);
        BlockPos feet = villager.getBlockPos();
        Direction facing = villager.getHorizontalFacing();
        List<Direction> order = new ArrayList<>(List.of(facing, facing.rotateYClockwise(), facing.rotateYCounterclockwise(), facing.getOpposite()));
        List<BlockPos> cells = new ArrayList<>();
        for (Direction dir : order) {
            cells.add(feet.offset(dir));
        }
        // nothing free right beside it (a narrow shaft, a ditch): anywhere within arm's reach, a step up or down
        for (int dy : new int[]{1, 0, -1}) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos cell = feet.add(dx, dy, dz);
                    if ((dx != 0 || dz != 0) && !cells.contains(cell)) {
                        cells.add(cell);
                    }
                }
            }
        }
        for (BlockPos cell : cells) {
            if (net.mca.entity.ai.sense.Obras.cropAt(world, cell) || net.mca.entity.ai.sense.Obras.cropAt(world, cell.down())) {
                continue; // never on a field
            }
            if (keepClear.test(cell)) {
                continue;
            }
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
            packTicks = 0;
            Hands.collect(world, villager, target, 2.5);
        } else if (r == Hands.Breaker.FAILED) {
            // out of reach: a player walks back for a table or a furnace that is still close
            if (villager.getPos().distanceTo(Vec3d.ofCenter(target)) < 12 && ++packTicks < 300) {
                if ((packTicks % 20) == 1 || villager.getNavigation().isIdle()) {
                    villager.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
                }
                return;
            }
            packTicks = 0;
            // too far now; leave it where it stands
            if (target.equals(furnace) && !cooking) {
                furnace = null;
            } else {
                table = null;
                ownTable = false;
            }
        }
    }

    // ------------------------------------------------------------------ smelting

    /**
     * Planks it keeps out of the fire: the sticks for the pickaxe the ingots are for, and a table to make it on when
     * it carries none and has none of its own standing.
     */
    private int woodToKeep(SimpleInventory inv) {
        int keep = 2 * (int) Math.ceil(Math.max(0, 2 - count(inv, Mat.STICK)) / 4.0);
        if (count(inv, Mat.TABLE) == 0 && !(ownTable && table != null)) {
            keep += 4;
        }
        return keep;
    }

    /**
     * With iron for a pickaxe in the bag or in the furnace, a lesser tool does not get the sticks (or the planks for
     * them, and for a table) that the iron pickaxe will need: a player saves the sticks for the upgrade.
     */
    private boolean robsIronPick(ServerWorld world, SimpleInventory inv, Recipe recipe, int pick) {
        if (pick >= 3 || recipe == IRON_PICKAXE || recipe == DIAMOND_PICKAXE) {
            return false;
        }
        if (count(inv, Items.RAW_IRON) + count(inv, Mat.IRON) + ironInFurnace(world) < 3) {
            return false;
        }
        int sticks = count(inv, Mat.STICK) - recipe.cost().getOrDefault(Mat.STICK, 0);
        int planks = count(inv, Mat.PLANKS) + 4 * count(inv, Mat.LOG) - recipe.cost().getOrDefault(Mat.PLANKS, 0);
        if (sticks < 0) {
            // the recipe saws its own sticks out of planks, two planks for four sticks
            int batches = (int) Math.ceil(-sticks / 4.0);
            planks -= 2 * batches;
            sticks += 4 * batches;
        }
        int needPlanks = sticks >= 2 ? 0 : 2;
        if (count(inv, Mat.TABLE) == 0 && !(ownTable && table != null)) {
            needPlanks += 4;
        }
        return planks < needPlanks;
    }

    /** Raw iron still in its furnace and ingots waiting in it. */
    private int ironInFurnace(ServerWorld world) {
        if (!cooking || furnace == null) {
            return 0;
        }
        BlockEntity be = world.getBlockEntity(furnace);
        if (!(be instanceof AbstractFurnaceBlockEntity oven)) {
            return 0;
        }
        int n = 0;
        if (oven.getStack(0).isOf(Items.RAW_IRON)) {
            n += oven.getStack(0).getCount();
        }
        if (oven.getStack(2).isOf(Items.IRON_INGOT)) {
            n += oven.getStack(2).getCount();
        }
        return n;
    }

    /** How much of {@code mat} it may burn: all its coal, but only the wood beyond the {@code keepPlanks} it keeps. */
    private static int spare(SimpleInventory inv, Mat mat, int keepPlanks) {
        int planks = count(inv, Mat.PLANKS);
        if (mat == Mat.PLANKS) {
            return Math.max(0, planks - keepPlanks);
        }
        if (mat == Mat.LOG) {
            // what the planks do not cover of the wood it keeps is sawn from logs
            return Math.max(0, count(inv, Mat.LOG) - (int) Math.ceil(Math.max(0, keepPlanks - planks) / 4.0));
        }
        return count(inv, mat);
    }

    @Nullable
    private static Mat fuelFor(SimpleInventory inv, int items, int keepPlanks) {
        int coal = spare(inv, Mat.COAL, keepPlanks);
        int planks = spare(inv, Mat.PLANKS, keepPlanks);
        int logs = spare(inv, Mat.LOG, keepPlanks);
        if (coal * 8 >= items) {
            return Mat.COAL;
        }
        if (planks * 3 >= items * 2) {
            return Mat.PLANKS;
        }
        if (logs * 3 >= items * 2) {
            return Mat.LOG;
        }
        return coal > 0 ? Mat.COAL : planks > 0 ? Mat.PLANKS : logs > 0 ? Mat.LOG : null;
    }

    /** The fire of the furnace is burning (the block shows it lit). */
    private static boolean lit(AbstractFurnaceBlockEntity oven) {
        var world = oven.getWorld();
        BlockState state = world == null ? oven.getCachedState() : world.getBlockState(oven.getPos());
        return state.contains(AbstractFurnaceBlock.LIT) && state.get(AbstractFurnaceBlock.LIT);
    }

    /**
     * How many more items the fire already burning finishes without another lump, as a player reads it off the
     * flame: what is left of the item cooking now, then a whole cook time each. 0 when the fire is out.
     */
    private static int stillBurns(AbstractFurnaceBlockEntity oven) {
        if (!lit(oven)) {
            return 0;
        }
        NbtCompound saved = oven.createNbt();
        int burn = saved.getShort("BurnTime");
        int total = Math.max(1, (int) saved.getShort("CookTimeTotal"));
        int first = Math.max(0, total - saved.getShort("CookTime"));
        return burn < first ? 0 : 1 + (burn - first) / total;
    }

    /** The fire went out with something still in the input and nothing left to burn: it needs a hand. */
    private static boolean wentOut(AbstractFurnaceBlockEntity oven) {
        return !oven.getStack(0).isEmpty() && oven.getStack(1).isEmpty() && !lit(oven);
    }

    /** Three are out, or the ingots out make up the three it was short of for a pickaxe: time to fetch them. */
    private static boolean enoughOut(VillagerEntityMCA villager, AbstractFurnaceBlockEntity oven) {
        ItemStack out = oven.getStack(2);
        int carried = out.isOf(Items.IRON_INGOT) ? villager.getInventory().count(Items.IRON_INGOT) : 0;
        return out.getCount() >= 3 || carried < 3 && out.getCount() + carried >= 3;
    }

    /** Puts a furnace down, loads it with what is to be smelted and fuel from the bag, and goes on with its day. */
    private void smelt(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory, long now, BiConsumer<String, Object[]> say) {
        villager.getNavigation().stop();
        SimpleInventory inv = villager.getInventory();
        if (furnace == null || !world.getBlockState(furnace).isOf(Blocks.FURNACE)) {
            memory.doing("poniendo un horno", now);
            furnace = placeBeside(world, villager, Mat.FURNACE);
            furnaceStand = villager.getBlockPos().toImmutable();
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
        Predicate<ItemStack> what = goal == COOK ? Progreso::rawFood : s -> s.isOf(Items.RAW_IRON);
        if (!load(oven, inv, what, woodToKeep(inv))) {
            step = Step.PACK_UP;
            return;
        }
        world.playSound(null, furnace, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.5F, 1.0F);
        ItemStack input = oven.getStack(0);
        Text name = input.getName();
        memory.doing("fundiendo " + input.getCount() + " de " + name.getString() + "; sigue con lo suyo mientras", now);
        say.accept("chore.mining.cooking", new Object[]{input.getCount(), name});
        cooking = true;
        packFurnace = false;
        if (goal == COOK) {
            wantCook = false;
        }
        // the table goes back in the bag; the furnace stays lit while it mines nearby
        step = ownTable && table != null ? Step.PACK_UP : Step.IDLE;
        nextCheck = 20;
    }

    /**
     * What {@code what} picks from the bag into the input (only more of the same when something is in there
     * already), and fuel for all of it into the fuel slot, never the {@code keepPlanks} it keeps for its next tool.
     * False when there is nothing to smelt, or nothing to burn and the fire is out.
     */
    private static boolean load(AbstractFurnaceBlockEntity oven, SimpleInventory inv, Predicate<ItemStack> what, int keepPlanks) {
        ItemStack input = oven.getStack(0);
        int rawSlot = input.isEmpty()
                ? Hands.findSlot(inv, what)
                : Hands.findSlot(inv, s -> ItemStack.canCombine(input, s));
        if (rawSlot >= 0 && input.getCount() < input.getMaxCount()) {
            ItemStack raw = inv.getStack(rawSlot);
            int room = input.isEmpty() ? raw.getMaxCount() : input.getMaxCount() - input.getCount();
            int add = Math.min(raw.getCount(), room);
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
        boolean lit = lit(oven);
        // fuel waiting in there, and the fire already burning, cover this many items
        int covered = fuelNow.isEmpty() ? 0 : fuelNow.isOf(Items.COAL) || fuelNow.isOf(Items.CHARCOAL) ? fuelNow.getCount() * 8 : fuelNow.getCount() * 3 / 2;
        covered += stillBurns(oven);
        if (covered < toSmelt) {
            Mat fuel = fuelFor(inv, toSmelt - covered, keepPlanks);
            if (fuel == null && fuelNow.isEmpty() && !lit) {
                // nothing to burn and the fire is out: give it back
                giveBack(inv, oven, 0);
                return false;
            }
            // with the fire still going and nothing more to put on, it cooks what the flame lasts for; whatever is
            // left when it goes out is fetched then (see wentOut)
            if (fuel != null) {
                int need = toSmelt - covered;
                int items = fuel == Mat.COAL ? (int) Math.ceil(need / 8.0) : (int) Math.ceil(need / 1.5);
                items = Math.min(items, spare(inv, fuel, keepPlanks));
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

    /** Out of reach of its furnace: farther than the leash across, or more than a few blocks up or down. */
    private static boolean tooFar(VillagerEntityMCA villager, BlockPos furnace) {
        double dx = villager.getX() - (furnace.getX() + 0.5);
        double dz = villager.getZ() - (furnace.getZ() + 0.5);
        return dx * dx + dz * dz > FURNACE_LEASH * FURNACE_LEASH || Math.abs(villager.getY() - furnace.getY()) > 4;
    }

    /**
     * Back at the furnace: what is done comes out and goes to the table at once. If it brought more to smelt, that
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
            // back the way it dug, on its own feet: no walking off ledges
            BlockPos back = furnaceStand != null ? furnaceStand : furnace.up();
            if (motorRest > 0) {
                // its own steps got nowhere a moment ago: the path finder has a go (the Motor would stop it every tick)
                motorRest--;
                if ((collectTicks % 20) == 1 || villager.getNavigation().isIdle()) {
                    villager.getNavigation().startMovingTo(back.getX() + 0.5, back.getY(), back.getZ() + 0.5, 0.6);
                }
                return;
            }
            Motor.Result result = motor.step(world, villager, back, true, true);
            if (result == Motor.Result.BLOCKED) {
                motor.reset(world, villager);
                motorRest = 40;
                villager.getNavigation().startMovingTo(back.getX() + 0.5, back.getY(), back.getZ() + 0.5, 0.6);
            } else if (result == Motor.Result.ARRIVED) {
                Hands.aim(villager, Vec3d.ofCenter(furnace));
            }
            return;
        }
        if (motor.midStep(villager)) {
            // in view from the top of a jump: land first, then open the furnace
            motor.step(world, villager, furnaceStand != null ? furnaceStand : furnace.up(), true, true);
            return;
        }
        motorRest = 0;
        Motor.standStill(villager);
        motor.reset(world, villager);
        Hands.aim(villager, Vec3d.ofCenter(furnace));
        SimpleInventory inv = villager.getInventory();
        ItemStack out = oven.getStack(2);
        int done = out.getCount();
        Text outName = out.getName();
        giveBack(inv, oven, 2);
        oven.markDirty();
        world.playSound(null, furnace, SoundEvents.BLOCK_STONE_HIT, SoundCategory.BLOCKS, 0.5F, 1.0F);
        if (done > 0) {
            say.accept("chore.mining.smelted", new Object[]{done, outName});
        }
        // still lit with raw iron left, it goes on cooking: the flame burning in there counts as fuel
        boolean stillCooking = !packFurnace && load(oven, inv, Progreso::cookable, woodToKeep(inv));
        if (!stillCooking) {
            giveBack(inv, oven, 0);
            giveBack(inv, oven, 1);
            oven.markDirty();
            cooking = false;
            packFurnace = false;
        }
        String what = done > 0 ? done + " de " + outName.getString() : "nada";
        memory.doing(stillCooking ? "sacó " + what + "; el resto sigue en el horno" : "sacó " + what + " y recoge el horno", now);
        // what came out goes straight to the table
        Recipe next = nextGoal(world, villager);
        if (next != null && !smelting(next) && next.table() && affordableWithWood(inv, next)) {
            goal = next;
            step = Step.TABLE;
            return;
        }
        step = cooking ? Step.IDLE : Step.PACK_UP;
        nextCheck = 20;
    }
}
