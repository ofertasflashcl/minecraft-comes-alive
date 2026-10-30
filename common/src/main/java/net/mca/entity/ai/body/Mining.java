package net.mca.entity.ai.body;

import net.mca.entity.ai.sense.VisualMemory;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Blocks whose registry name reads like an ore ("..._ore", "ore_..."). Names never change while the game runs,
     * so each block is looked up once; the tags are checked live, as a datapack reload may change them.
     */
    private static final Map<Block, Boolean> NAMED_ORE = new ConcurrentHashMap<>();

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
        return NAMED_ORE.computeIfAbsent(state.getBlock(), Mining::namedOre) ? 25 : 0;
    }

    private static boolean namedOre(Block block) {
        String path = Registries.BLOCK.getId(block).getPath();
        return path.endsWith("_ore") || path.contains("_ore_") || path.startsWith("ore_");
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

    /**
     * The cell the body stands in. Its block position, or the cell over it when the feet are on the top of a low block
     * that fills the bottom of that same cell (a dirt path or farmland, 15/16 high; soul sand, mud, a bed, a bottom
     * slab): on a village path the body stands in the cell over the path, the cell every way it plans, remembers and
     * arrives at, never "inside" the path. Ladders and other climbables keep their own cell.
     */
    public static BlockPos pies(Entity entity) {
        BlockPos pos = entity.getBlockPos();
        if (entity.getY() - pos.getY() < 0.5 - 1.0E-4) {
            return pos;
        }
        World world = entity.getWorld();
        BlockState state = world.getBlockState(pos);
        if (state.isIn(net.minecraft.registry.tag.BlockTags.CLIMBABLE) || state.getCollisionShape(world, pos).isEmpty()) {
            return pos;
        }
        return state.getCollisionShape(world, pos).getMax(Direction.Axis.Y) <= entity.getY() - pos.getY() + 1.0E-3 ? pos.up() : pos;
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

    /**
     * Out in the open, the way a player feels it: nothing over its head but leaves, water and sky, and a neighbouring
     * cell it could step out to that is not walled in either. True on the ground under a tree and wading or swimming
     * at the top of a pond or a river; false at the bottom of an open one-wide shaft, in a flooded hole, or in a
     * ravine with walls on every side.
     */
    public static boolean outdoors(World world, BlockPos feet) {
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
        if (feet.getY() < top) {
            // the heightmap counts water: only water up to the open sky over the feet is still no roof
            if (top - feet.getY() > 8) {
                return false;
            }
            BlockPos.Mutable cell = feet.mutableCopy();
            for (int y = feet.getY(); y < top; y++) {
                cell.setY(y);
                if (!world.getFluidState(cell).isIn(FluidTags.WATER) || !passable(world, cell)) {
                    return false;
                }
            }
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos next = feet.offset(dir);
            if (world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, next.getX(), next.getZ()) <= feet.getY() + 1 && passable(world, next.up())) {
                return true;
            }
        }
        return false;
    }

    /** Outdoors and near the level of the land, not out in a deep pit open to the sky. */
    public static boolean onSurface(World world, BlockPos feet) {
        return outdoors(world, feet) && feet.getY() >= world.getSeaLevel() - 12;
    }

    /**
     * Out on open ground, not only under open sky: outdoors, and either nothing beside it reaches up to its head (flat
     * land, a hilltop, a ditch one step deep) or there is ground on two sides or more it could walk out onto at its
     * own level or a step down, open to the sky as well (beside a wall or a tree, on a slope, the top of the water
     * when it swims). False in the open top of a one-wide staircase still two steps or more under the land: the
     * walls beside it rise over its head and the step below is the only open side.
     */
    public static boolean openGround(World world, BlockPos feet) {
        if (!outdoors(world, feet)) {
            return false;
        }
        // its level: the feet on dry land, the top of the water when it swims
        int level = Math.max(feet.getY(), world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()));
        boolean walled = false;
        int sides = 0;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos next = feet.offset(dir);
            int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, next.getX(), next.getZ());
            if (top > level + 1) {
                walled = true; // a block beside it at head height or higher
            }
            if (top <= level && top >= level - 1 && passable(world, next) && passable(world, next.up())) {
                sides++;
            }
        }
        return !walled || sides >= 2;
    }

    /**
     * The open sky is at most {@code roof} blocks over the feet here and in the four cells beside it: out in the
     * open or under a roof at the level of the land (a house, an overhang), not at the bottom of a shaft or in a
     * pocket deep in the rock. Only the shape of the place, for the body's own moves; whether it is "down in the
     * rock" is {@link net.mca.entity.ai.sense.Entorno}'s to say.
     */
    public static boolean nearTheSky(World world, BlockPos feet, int roof) {
        if (world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()) - feet.getY() > roof) {
            return false;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos next = feet.offset(dir);
            if (world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, next.getX(), next.getZ()) - feet.getY() > roof) {
                return false;
            }
        }
        return true;
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
