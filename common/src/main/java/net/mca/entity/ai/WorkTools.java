package net.mca.entity.ai;

import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.SwordItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.block.Blocks;

/**
 * Tool checks that also see modded items. Many mods do not extend
 * {@link PickaxeItem} or {@link SwordItem}; they use item tags instead.
 */
public final class WorkTools {
    private static final TagKey<Item> C_PICKAXES = item("c", "tools/pickaxes");
    private static final TagKey<Item> C_PICKAXES_ALT = item("c", "pickaxes");
    private static final TagKey<Item> FORGE_PICKAXES = item("forge", "tools/pickaxes");
    private static final TagKey<Item> C_SWORDS = item("c", "tools/swords");
    private static final TagKey<Item> FORGE_SWORDS = item("forge", "tools/swords");
    private static final TagKey<Item> C_AXES = item("c", "tools/axes");
    private static final TagKey<Item> FORGE_AXES = item("forge", "tools/axes");
    private static final TagKey<Item> C_MELEE = item("c", "tools/melee_weapons");

    private WorkTools() {
    }

    public static boolean isPickaxe(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof PickaxeItem || stack.isIn(ItemTags.PICKAXES)) {
            return true;
        }
        if (stack.isIn(C_PICKAXES) || stack.isIn(C_PICKAXES_ALT) || stack.isIn(FORGE_PICKAXES)) {
            return true;
        }
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        if (path.contains("pickaxe") || path.endsWith("_pick")) {
            return true;
        }
        return stack.isSuitableFor(Blocks.IRON_ORE.getDefaultState())
                && stack.getMiningSpeedMultiplier(Blocks.IRON_ORE.getDefaultState()) > 1.5F;
    }

    public static boolean isWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem) {
            return true;
        }
        if (stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES)) {
            return true;
        }
        if (stack.isIn(C_SWORDS) || stack.isIn(FORGE_SWORDS) || stack.isIn(C_AXES) || stack.isIn(FORGE_AXES) || stack.isIn(C_MELEE)) {
            return true;
        }
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        if (path.contains("pickaxe") || path.contains("pick_")) {
            return false;
        }
        return path.contains("sword")
                || path.contains("saber")
                || path.contains("katana")
                || path.contains("mace")
                || path.contains("halberd")
                || path.contains("battleaxe")
                || path.contains("battle_axe")
                || path.endsWith("_axe");
    }

    private static TagKey<Item> item(String namespace, String path) {
        return TagKey.of(RegistryKeys.ITEM, new Identifier(namespace, path));
    }
}
