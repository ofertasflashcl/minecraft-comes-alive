package net.mca.entity.ai.body;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Relationship;
import net.mca.entity.ai.sense.Retina;
import net.mca.server.world.data.TropelesMundo;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A villager's own chests. It knows what it left in each one; when somebody who is not family takes from
 * them it finds out (at once if it sees it happen, otherwise the next time it is by the chest or sees the
 * thief) and thinks less of them. Gifts that do not fit in its bag go into its chest.
 */
public final class Cofres {
    /** How far from its home or its spot a chest may be and still count as "at home". */
    public static final int HOME_RANGE = 24;

    private Cofres() {
    }

    @Nullable
    public static Inventory inventory(ServerWorld world, BlockPos pos) {
        if (!(world.getBlockState(pos).getBlock() instanceof ChestBlock)) {
            return null;
        }
        return HopperBlockEntity.getInventoryAt(world, pos);
    }

    public static Map<String, Integer> contents(Inventory inv) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                counts.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /** Its chests that still stand, nearest to {@code near} first. */
    public static List<BlockPos> own(ServerWorld world, VillagerEntityMCA villager, BlockPos near) {
        TropelesMundo mundo = TropelesMundo.get(world);
        List<BlockPos> list = new ArrayList<>();
        for (BlockPos pos : mundo.chestsOf(villager.getUuid())) {
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }
            if (world.getBlockState(pos).getBlock() instanceof ChestBlock) {
                list.add(pos);
            } else {
                mundo.forgetChest(pos);
            }
        }
        list.sort((a, b) -> Double.compare(a.getSquaredDistance(near), b.getSquaredDistance(near)));
        return list;
    }

    public static void claim(ServerWorld world, VillagerEntityMCA villager, BlockPos pos) {
        TropelesMundo mundo = TropelesMundo.get(world);
        mundo.ownChest(pos, villager.getUuid());
        remember(world, pos);
    }

    /** The owner looked inside: what is there now is what it expects to find next time. */
    public static void remember(ServerWorld world, BlockPos pos) {
        TropelesMundo.Chest chest = TropelesMundo.get(world).chest(pos);
        Inventory inv = inventory(world, pos);
        if (chest == null || inv == null) {
            return;
        }
        chest.snapshot.clear();
        chest.snapshot.putAll(contents(inv));
        TropelesMundo.get(world).markDirty();
    }

    private static boolean fits(Inventory inv, ItemStack stack) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack slot = inv.getStack(i);
            if (slot.isEmpty() || ItemStack.canCombine(slot, stack) && slot.getCount() < slot.getMaxCount()) {
                return true;
            }
        }
        return false;
    }

    /** Puts as much of {@code stack} as fits into {@code inv}; what is left stays in {@code stack}. */
    public static void insert(Inventory inv, ItemStack stack) {
        for (int i = 0; i < inv.size() && !stack.isEmpty(); i++) {
            ItemStack slot = inv.getStack(i);
            if (!slot.isEmpty() && ItemStack.canCombine(slot, stack) && slot.getCount() < slot.getMaxCount()) {
                int moved = Math.min(stack.getCount(), slot.getMaxCount() - slot.getCount());
                slot.increment(moved);
                stack.decrement(moved);
            }
        }
        for (int i = 0; i < inv.size() && !stack.isEmpty(); i++) {
            if (inv.getStack(i).isEmpty()) {
                inv.setStack(i, stack.split(stack.getCount()));
            }
        }
        inv.markDirty();
    }

    /** Moves everything it does not want to carry from its bag into the chest. Returns how many items moved. */
    public static int store(ServerWorld world, VillagerEntityMCA villager, BlockPos pos, Predicate<ItemStack> keep) {
        Inventory inv = inventory(world, pos);
        if (inv == null) {
            return 0;
        }
        SimpleInventory bag = villager.getInventory();
        int moved = 0;
        for (int i = 0; i < bag.size(); i++) {
            ItemStack stack = bag.getStack(i);
            if (stack.isEmpty() || keep.test(stack)) {
                continue;
            }
            int before = stack.getCount();
            insert(inv, stack);
            moved += before - stack.getCount();
        }
        bag.markDirty();
        Hands.aim(villager, net.minecraft.util.math.Vec3d.ofCenter(pos));
        villager.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        world.playSound(null, pos, net.minecraft.sound.SoundEvents.BLOCK_CHEST_CLOSE, net.minecraft.sound.SoundCategory.BLOCKS, 0.5F, 1.0F);
        remember(world, pos);
        return moved;
    }

    /** Takes up to {@code max} items matching {@code want} from the chest into its bag. */
    public static int take(ServerWorld world, VillagerEntityMCA villager, BlockPos pos, Predicate<ItemStack> want, int max) {
        Inventory inv = inventory(world, pos);
        if (inv == null) {
            return 0;
        }
        int taken = 0;
        for (int i = 0; i < inv.size() && taken < max; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty() || !want.test(stack)) {
                continue;
            }
            ItemStack part = stack.split(Math.min(stack.getCount(), max - taken));
            int count = part.getCount();
            ItemStack left = villager.getInventory().addStack(part);
            taken += count - left.getCount();
            if (!left.isEmpty()) {
                insert(inv, left);
                break;
            }
        }
        inv.markDirty();
        remember(world, pos);
        return taken;
    }

    /** Whether one of its chests has room for {@code stack}. */
    public static boolean hasRoom(ServerWorld world, VillagerEntityMCA villager, ItemStack stack) {
        for (BlockPos pos : own(world, villager, villager.getBlockPos())) {
            Inventory inv = inventory(world, pos);
            if (inv != null && fits(inv, stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A gift that does not fit in the bag: into its nearest chest with room, if it has one. True when stored.
     */
    public static boolean storeGift(ServerWorld world, VillagerEntityMCA villager, ItemStack stack) {
        for (BlockPos pos : own(world, villager, villager.getBlockPos())) {
            Inventory inv = inventory(world, pos);
            if (inv != null && fits(inv, stack)) {
                insert(inv, stack);
                remember(world, pos);
                return stack.isEmpty();
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ theft

    /** A player closed a container: if it was a villager's chest and things are missing, that is a theft. */
    public static void closedBy(ServerPlayerEntity player) {
        if (!(player.getWorld() instanceof ServerWorld world)) {
            return;
        }
        TropelesMundo mundo = TropelesMundo.get(world);
        List<BlockPos> near = new ArrayList<>();
        for (var entry : mundo.allChests()) {
            BlockPos pos = BlockPos.fromLong(entry.getLongKey());
            if (pos.isWithinDistance(player.getPos(), 8)) {
                near.add(pos);
            }
        }
        for (BlockPos pos : near) {
            check(world, pos, player);
        }
    }

    /** Compares the chest with what its owner left; anything missing is put down to {@code player}. */
    public static int check(ServerWorld world, BlockPos pos, PlayerEntity player) {
        TropelesMundo mundo = TropelesMundo.get(world);
        TropelesMundo.Chest chest = mundo.chest(pos);
        Inventory inv = inventory(world, pos);
        if (chest == null || inv == null) {
            return 0;
        }
        Map<String, Integer> now = contents(inv);
        int missing = 0;
        String what = "";
        int most = 0;
        for (Map.Entry<String, Integer> e : chest.snapshot.entrySet()) {
            int gone = e.getValue() - now.getOrDefault(e.getKey(), 0);
            if (gone > 0) {
                missing += gone;
                if (gone > most) {
                    most = gone;
                    what = e.getKey();
                }
            }
        }
        chest.snapshot.clear();
        chest.snapshot.putAll(now);
        mundo.markDirty();
        if (missing == 0) {
            return 0;
        }
        Entity ownerEntity = world.getEntity(chest.owner);
        VillagerEntityMCA owner = ownerEntity instanceof VillagerEntityMCA v ? v : null;
        if (owner == null) {
            // not indexed yet (a chunk that just started ticking): look for it around its chest
            owner = world.getEntitiesByClass(VillagerEntityMCA.class, new net.minecraft.util.math.Box(pos).expand(HOME_RANGE),
                    v -> v.getUuid().equals(chest.owner)).stream().findFirst().orElse(null);
        }
        if (owner != null && Relationship.IS_FAMILY.test(owner, player.getUuid())) {
            return 0; // family may take from the family chest
        }
        TropelesMundo.Theft theft = new TropelesMundo.Theft(chest.owner, player.getUuid(), player.getName().getString(), pos.asLong(), missing, what, world.getTime());
        mundo.theft(theft);
        if (owner != null && (Retina.canSee(world, owner, player) || owner.getBlockPos().isWithinDistance(pos, 6))) {
            notice(world, owner, theft, player, true);
        }
        return missing;
    }

    // ------------------------------------------------------------------ "store your things", asked in words

    private record Pending(BlockPos chest, java.util.UUID player, long until, net.mca.entity.ai.MoveState before) {
    }

    private static final Map<VillagerEntityMCA, Pending> PENDING = new java.util.WeakHashMap<>();

    /** How "store your things" went. */
    public enum Store { WALKING, PLACED, NONE }

    /**
     * Asked to put its things away: it walks to its nearest chest (or sets down the one in its bag) and stores
     * all but its tools, weapons, armour, torches and food.
     */
    public static Store orderStore(VillagerEntityMCA villager, ServerPlayerEntity player) {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return Store.NONE;
        }
        BlockPos chest = own(world, villager, villager.getBlockPos()).stream()
                .filter(pos -> pos.isWithinDistance(villager.getBlockPos(), HOME_RANGE))
                .findFirst().orElse(null);
        Store how = Store.WALKING;
        if (chest == null && villager.getInventory().count(Items.CHEST) > 0) {
            BlockPos placed = Progreso.placeBeside(world, villager, Cofres::isChestItem);
            if (placed != null) {
                claim(world, villager, placed);
                chest = placed;
                how = Store.PLACED;
            }
        }
        if (chest == null) {
            return Store.NONE;
        }
        // following or told to stay, it still has to walk to the chest; it goes back to that afterwards
        net.mca.entity.ai.MoveState before = villager.getVillagerBrain().getMoveState();
        if (before != net.mca.entity.ai.MoveState.MOVE) {
            villager.getVillagerBrain().setMoveState(net.mca.entity.ai.MoveState.MOVE, null);
        }
        PENDING.put(villager, new Pending(chest, player.getUuid(), world.getTime() + 600, before));
        return how;
    }

    /** Whether it is on its way to store its things. */
    public static boolean storing(VillagerEntityMCA villager) {
        return PENDING.containsKey(villager);
    }

    private static boolean keepOnYou(ItemStack stack) {
        return stack.getItem() instanceof net.minecraft.item.ToolItem || stack.getItem() instanceof net.minecraft.item.RangedWeaponItem
                || stack.getItem() instanceof net.minecraft.item.ArmorItem || stack.getItem() instanceof net.minecraft.item.ArrowItem
                || stack.getItem() instanceof net.minecraft.item.ShieldItem || stack.isFood() || stack.isOf(Items.TORCH);
    }

    private static void storeTick(ServerWorld world, VillagerEntityMCA villager, Pending pending) {
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(pending.player());
        if (world.getTime() > pending.until() || !(world.getBlockState(pending.chest()).getBlock() instanceof ChestBlock)) {
            PENDING.remove(villager);
            restore(villager, pending, player);
            return;
        }
        if (Retina.visibleFace(world, villager, pending.chest(), Retina.BLOCK_REACH) == null) {
            if ((villager.age % 20) == 0 || villager.getNavigation().isIdle()) {
                villager.getNavigation().startMovingTo(pending.chest().getX() + 0.5, pending.chest().getY(), pending.chest().getZ() + 0.5, 0.6);
            }
            return;
        }
        villager.getNavigation().stop();
        int stored = store(world, villager, pending.chest(), Cofres::keepOnYou);
        PENDING.remove(villager);
        Retina.memory(villager).doing("guardó " + stored + " cosas en su cofre", world.getTime());
        if (player != null && stored > 0) {
            net.mca.entity.ai.chatAI.Acciones.say(player, villager, "chore.mining.stored", stored);
        }
        restore(villager, pending, player);
    }

    private static void restore(VillagerEntityMCA villager, Pending pending, @Nullable ServerPlayerEntity player) {
        if (pending.before() == net.mca.entity.ai.MoveState.FOLLOW && player != null) {
            villager.getVillagerBrain().setMoveState(net.mca.entity.ai.MoveState.FOLLOW, player);
        } else if (pending.before() == net.mca.entity.ai.MoveState.STAY) {
            villager.getVillagerBrain().setMoveState(net.mca.entity.ai.MoveState.STAY, null);
        }
    }

    /** Every so often: the owner finds out about thefts once it is by the chest or sees the thief. */
    public static void tick(ServerWorld world, VillagerEntityMCA villager) {
        Pending pending = PENDING.get(villager);
        if (pending != null) {
            storeTick(world, villager, pending);
        }
        if ((villager.age % 40) != 7) {
            return;
        }
        TropelesMundo mundo = TropelesMundo.get(world);
        for (TropelesMundo.Theft theft : mundo.theftsAgainst(villager.getUuid())) {
            PlayerEntity thief = world.getServer().getPlayerManager().getPlayer(theft.thief());
            boolean byChest = villager.getBlockPos().isWithinDistance(BlockPos.fromLong(theft.chest()), 6);
            boolean seesThief = thief != null && thief.getWorld() == world && Retina.canSee(world, villager, thief);
            if (thief != null && (byChest || seesThief)) {
                notice(world, villager, theft, thief, false);
            }
        }
    }

    /** The owner knows. Hearts go down with how much was taken, and it says so to the thief. */
    public static int notice(ServerWorld world, VillagerEntityMCA owner, TropelesMundo.Theft theft, PlayerEntity thief, boolean caughtInTheAct) {
        TropelesMundo.get(world).noticed(theft);
        int loss = Math.min(40, 5 + theft.items() * 2);
        owner.getVillagerBrain().getMemoriesForPlayer(thief).modHearts(-loss);
        owner.getVillagerBrain().modifyMoodValue(-5);
        Item item = Registries.ITEM.get(new Identifier(theft.what()));
        if (thief instanceof ServerPlayerEntity sp && sp.networkHandler != null) {
            owner.sendChatMessage(sp, caughtInTheAct ? "chore.chest.caught" : "chore.chest.stolen", theft.items(), item.getName());
        }
        Retina.memory(owner).doing("le robaron " + theft.items() + " cosas de su cofre: " + thief.getName().getString(), world.getTime());
        return loss;
    }

    /** True when the block is a chest it could place: used to name the item. */
    public static boolean isChestItem(ItemStack stack) {
        return stack.isOf(Blocks.CHEST.asItem());
    }
}
