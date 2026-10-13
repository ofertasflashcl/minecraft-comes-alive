package net.mca.util;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Hand;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface InventoryUtils {

    static Stream<ItemStack> stream(Inventory inventory) {
        return IntStream.range(0, inventory.size()).mapToObj(inventory::getStack);
    }

    static int getFirstSlotContainingItem(Inventory inv, Predicate<ItemStack> predicate) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!predicate.test(stack)) continue;
            return i;
        }
        return -1;
    }

    /**
     * Main hand shares the inventory stack, the same way hunting and chopping do.
     * A stack that exists only in the hand (food split out of the bag) is put back first.
     */
    static boolean holdMain(VillagerEntityMCA villager, Predicate<ItemStack> match) {
        ItemStack held = villager.getMainHandStack();
        if (match.test(held)) {
            return true;
        }
        SimpleInventory inv = villager.getInventory();
        int slot = getFirstSlotContainingItem(inv, match);
        if (slot < 0) {
            return false;
        }
        if (!held.isEmpty() && !sharesSlot(inv, held)) {
            ItemStack leftover = inv.addStack(held.copy());
            if (!leftover.isEmpty()) {
                villager.dropStack(leftover);
            }
        }
        villager.setStackInHand(Hand.MAIN_HAND, inv.getStack(slot));
        return match.test(villager.getMainHandStack());
    }

    private static boolean sharesSlot(SimpleInventory inv, ItemStack stack) {
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i) == stack) {
                return true;
            }
        }
        return false;
    }

    static boolean contains(Inventory inv, Class<?> clazz) {
        for (int i = 0; i < inv.size(); ++i) {
            final ItemStack stack = inv.getStack(i);
            final Item item = stack.getItem();

            if (item.getClass() == clazz) return true;
        }
        return false;
    }

    /**
     * Gets the best quality (max damage) item of the specified type that is in the inventory.
     *
     * @param type The class of item that will be returned.
     *
     * @return The item stack containing the item of the specified type with the highest max damage.
     */
    static ItemStack getBestItemOfType(Inventory inv, @Nullable Class<?> type) {
        return type == null ? ItemStack.EMPTY : inv.getStack(getBestItemOfTypeSlot(inv, type));
    }

    static int getBestItemOfTypeSlot(Inventory inv, Class<?> type) {
        int highestMaxDamage = 0;
        int best = -1;

        for (int i = 0; i < inv.size(); ++i) {
            ItemStack stackInInventory = inv.getStack(i);

            final String itemClassName = stackInInventory.getItem().getClass().getName();

            if (itemClassName.equals(type.getName()) && highestMaxDamage < stackInInventory.getMaxDamage()) {
                highestMaxDamage = stackInInventory.getMaxDamage();
                best = i;
            }
        }

        return best;
    }

    static Optional<ItemStack> getBestArmor(Inventory inv, EquipmentSlot slot) {
        return stream(inv)
                .filter(s -> s.getItem() instanceof ArmorItem)
                .filter(s -> ((ArmorItem)s.getItem()).getSlotType() == slot)
                .max(Comparator.comparingDouble(s -> ((ArmorItem)s.getItem()).getProtection()));
    }

    static Optional<ItemStack> getBestSword(Inventory inv) {
        return stream(inv)
                .filter(s -> s.getItem() instanceof SwordItem)
                .max(Comparator.comparingDouble(s -> ((SwordItem)s.getItem()).getAttackDamage()));
    }

    static Optional<ItemStack> getBestRanged(Inventory inv) {
        return stream(inv)
                .filter(s -> s.getItem() instanceof RangedWeaponItem)
                .max(Comparator.comparingDouble(s -> s.getItem().getMaxDamage()));
    }

    static void dropAllItems(Entity entity, Inventory inv) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            entity.dropStack(stack, 1.0F);
        }
        inv.clear();
    }

    static void load(Inventory inv, NbtList tagList) {
        for (int i = 0; i < inv.size(); ++i) {
            inv.setStack(i, ItemStack.EMPTY);
        }

        for (int i = 0; i < tagList.size(); ++i) {
            NbtCompound nbt = tagList.getCompound(i);
            int slot = nbt.getByte("Slot") & 255;

            if (slot < inv.size()) {
                inv.setStack(slot, ItemStack.fromNbt(nbt));
            }
        }
    }

    static NbtList save(Inventory inv) {
        NbtList tagList = new NbtList();

        for (int i = 0; i < inv.size(); ++i) {
            ItemStack itemstack = inv.getStack(i);

            if (itemstack != ItemStack.EMPTY) {
                NbtCompound nbt = new NbtCompound();
                nbt.putByte("Slot", (byte)i);
                itemstack.setNbt(nbt);
                tagList.add(nbt);
            }
        }

        return tagList;
    }

    static void saveToNBT(SimpleInventory inv, NbtCompound nbt) {
        nbt.put("Inventory", inv.toNbtList());
    }

    static void readFromNBT(SimpleInventory inv, NbtCompound nbt) {
        inv.readNbtList(nbt.getList("Inventory", 10));
    }
}
