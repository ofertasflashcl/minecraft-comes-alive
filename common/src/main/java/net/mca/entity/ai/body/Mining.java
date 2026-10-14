package net.mca.entity.ai.body;

import net.mca.entity.ai.sense.VisualMemory;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * What counts as ore, as rock a miner may cut, as floor and as open space. Ore is told apart by the
 * exact block the eye recorded (vanilla and modded ore tags), like the name Jade shows under the crosshair.
 */
public final class Mining {
    private static final TagKey<Block> C_ORES = blockTag("c", "ores");
    private static final TagKey<Block> FORGE_ORES = blockTag("forge", "ores");
    private static final TagKey<Block> C_STONES = blockTag("c", "stones");
    private static final TagKey<Block> C_COBBLESTONES = blockTag("c", "cobblestones");
    private static final TagKey<Block> C_GRAVELS = blockTag("c", "gravels");
    private static final TagKey<Block> C_SANDS = blockTag("c", "sands");
    private static final TagKey<Block> C_SANDSTONES = blockTag("c", "sandstones");
    private static final TagKey<Block> C_NETHERRACKS = blockTag("c", "netherracks");
    private static final TagKey<Block> C_DIRTS = blockTag("c", "dirts");
    private static final TagKey<Block> FORGE_STONE = blockTag("forge", "stone");
    private static final TagKey<Block> FORGE_COBBLESTONE = blockTag("forge", "cobblestone");
    private static final TagKey<Block> FORGE_GRAVEL = blockTag("forge", "gravel");
    private static final TagKey<Block> FORGE_SAND = blockTag("forge", "sand");
    private static final TagKey<Block> FORGE_DIRT = blockTag("forge", "dirt");
    private static final TagKey<Block> FORGE_NETHERRACK = blockTag("forge", "netherrack");

    private Mining() {
    }

    public static int oreValue(BlockState state) {
        if (state.isOf(Blocks.ANCIENT_DEBRIS)) {
            return 100;
        }
        if (state.isIn(BlockTags.DIAMOND_ORES) || state.isIn(BlockTags.EMERALD_ORES)) {
            return 80;
        }
        if (state.isIn(BlockTags.GOLD_ORES)) {
            return 50;
        }
        if (state.isIn(BlockTags.LAPIS_ORES) || state.isIn(BlockTags.REDSTONE_ORES)) {
            return 40;
        }
        if (state.isIn(BlockTags.IRON_ORES)) {
            return 30;
        }
        if (state.isIn(BlockTags.COPPER_ORES)) {
            return 20;
        }
        if (state.isIn(BlockTags.COAL_ORES) || state.isOf(Blocks.NETHER_QUARTZ_ORE)) {
            return 10;
        }
        if (state.isIn(C_ORES) || state.isIn(FORGE_ORES)) {
            return 25;
        }
        String path = Registries.BLOCK.getId(state.getBlock()).getPath();
        if (path.endsWith("_ore") || path.contains("_ore_") || path.startsWith("ore_")) {
            return 25;
        }
        return 0;
    }

    public static boolean isOre(BlockState state) {
        return oreValue(state) > 0;
    }

    /** Natural rock and soil a miner may cut through without touching anybody's build. */
    public static boolean isRock(BlockState state) {
        return state.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || state.isIn(BlockTags.BASE_STONE_NETHER)
                || state.isIn(BlockTags.STONE_ORE_REPLACEABLES)
                || state.isIn(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || state.isIn(BlockTags.DIRT)
                || state.isIn(BlockTags.SAND)
                || state.isIn(BlockTags.TERRACOTTA)
                || state.isOf(Blocks.GRAVEL)
                || state.isOf(Blocks.CLAY)
                || state.isOf(Blocks.NETHERRACK)
                || state.isOf(Blocks.SOUL_SAND)
                || state.isOf(Blocks.SOUL_SOIL)
                || state.isOf(Blocks.CALCITE)
                || state.isOf(Blocks.TUFF)
                || state.isOf(Blocks.DRIPSTONE_BLOCK)
                || state.isOf(Blocks.POINTED_DRIPSTONE)
                || state.isOf(Blocks.SMOOTH_BASALT)
                || state.isOf(Blocks.AMETHYST_BLOCK)
                || state.isOf(Blocks.COBBLESTONE)
                || state.isOf(Blocks.MOSSY_COBBLESTONE)
                || state.isOf(Blocks.COBBLED_DEEPSLATE)
                || state.isOf(Blocks.SANDSTONE)
                || state.isOf(Blocks.RED_SANDSTONE)
                || state.isOf(Blocks.SNOW_BLOCK)
                || state.isOf(Blocks.SNOW)
                || state.isOf(Blocks.MOSS_BLOCK)
                || state.isOf(Blocks.GLOW_LICHEN)
                || state.isIn(C_STONES)
                || state.isIn(C_COBBLESTONES)
                || state.isIn(C_GRAVELS)
                || state.isIn(C_SANDS)
                || state.isIn(C_SANDSTONES)
                || state.isIn(C_NETHERRACKS)
                || state.isIn(C_DIRTS)
                || state.isIn(FORGE_STONE)
                || state.isIn(FORGE_COBBLESTONE)
                || state.isIn(FORGE_GRAVEL)
                || state.isIn(FORGE_SAND)
                || state.isIn(FORGE_DIRT)
                || state.isIn(FORGE_NETHERRACK);
    }

    /** May the miner break this to make way: rock, ore, or a plant in the way. Never bedrock, chests or anyone's build. */
    public static boolean mayDig(World world, BlockPos pos, BlockState state) {
        if (state.isAir() || state.getHardness(world, pos) < 0 || state.hasBlockEntity()) {
            return false;
        }
        if (!world.isInBuildLimit(pos) || !world.getWorldBorder().contains(pos)) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.getCollisionShape(world, pos).isEmpty()) {
            return true;
        }
        return isRock(state) || isOre(state);
    }

    /** A body fits here: nothing solid, no lava. Water is fine. */
    public static boolean passable(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getFluidState().isIn(FluidTags.LAVA)) {
            return false;
        }
        return state.getCollisionShape(world, pos).isEmpty();
    }

    /** Something to stand on. */
    public static boolean standable(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getFluidState().isIn(FluidTags.LAVA)) {
            return false;
        }
        return !state.getCollisionShape(world, pos).isEmpty() && state.getCollisionShape(world, pos).getMax(Direction.Axis.Y) >= 0.5;
    }

    /** Whether the eye has seen lava in or right next to this cell. */
    public static boolean lavaSeen(VisualMemory memory, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockState seen = memory.stateAt(pos.offset(dir));
            if (seen != null && seen.getFluidState().isIn(FluidTags.LAVA)) {
                return true;
            }
        }
        BlockState here = memory.stateAt(pos);
        return here != null && here.getFluidState().isIn(FluidTags.LAVA);
    }

    /** Lava right at or beside a cell. Used as the feel of heat on the skin, only for cells the body touches. */
    public static boolean heat(World world, BlockPos pos) {
        if (world.getFluidState(pos).isIn(FluidTags.LAVA)) {
            return true;
        }
        for (Direction dir : Direction.values()) {
            if (world.getFluidState(pos.offset(dir)).isIn(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private static TagKey<Block> blockTag(String namespace, String path) {
        return TagKey.of(RegistryKeys.BLOCK, new Identifier(namespace, path));
    }
}
