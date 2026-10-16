package net.mca.entity.ai.sense;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.body.Mining;
import net.mca.server.world.data.Building;
import net.mca.server.world.data.TropelesMundo;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.block.AttachedStemBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CocoaBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.block.GourdBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.block.StemBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Telling a tree from a house, the way a player does at a glance. A tree is a trunk of logs that grows out of
 * the ground and carries leaves the world grew (leaves a player places are marked persistent and do not
 * count); nothing made touches it. A building is anything a player placed, anything inside a village
 * building, any made block (planks, glass, doors, fences, bricks, cobblestone, torches, crops...) and the
 * natural blocks that sit between made ones, like the dirt under a wooden floor. Crops and farmland are a
 * field, never in the way and never something to dig. A villager only breaks natural things and what it
 * placed itself.
 */
public final class Obras {
    /** How a block reads to the eye. */
    public enum Kind {
        TREE("árbol"), BUILDING("construcción"), PLAYER_BUILT("construcción de un jugador"), OWN("puesto por él mismo"), CROP("cultivo"), NATURE("natural");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    private static final int TREE_LIMIT = 256;
    private static final long CACHE_TICKS = 200;
    private static final Map<ServerWorld, Map<Long, Long>> TREE_CACHE = new WeakHashMap<>();

    private Obras() {
    }

    /** Natural ground, rock, plants and fluids: things nobody made. */
    public static boolean natural(BlockState state) {
        if (state.isAir() || state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA) || state.isOf(Blocks.BUBBLE_COLUMN)) {
            return true;
        }
        if (state.isOf(Blocks.COBBLESTONE) || state.isOf(Blocks.MOSSY_COBBLESTONE) || state.isOf(Blocks.COBBLED_DEEPSLATE)) {
            return false; // the stuff of walls and foundations
        }
        if (Mining.isRock(state) || Mining.isOre(state)) {
            return true;
        }
        if (state.isOf(Blocks.BEDROCK) || state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.ICE) || state.isOf(Blocks.PACKED_ICE)
                || state.isOf(Blocks.BLUE_ICE) || state.isOf(Blocks.MAGMA_BLOCK) || state.isOf(Blocks.CRYING_OBSIDIAN)
                || state.isOf(Blocks.SCULK) || state.isOf(Blocks.SCULK_VEIN) || state.isOf(Blocks.SCULK_SENSOR)
                || state.isOf(Blocks.SCULK_SHRIEKER) || state.isOf(Blocks.SCULK_CATALYST) || state.isOf(Blocks.BUDDING_AMETHYST)
                || state.isOf(Blocks.AMETHYST_CLUSTER) || state.isOf(Blocks.LARGE_AMETHYST_BUD) || state.isOf(Blocks.MEDIUM_AMETHYST_BUD)
                || state.isOf(Blocks.SMALL_AMETHYST_BUD) || state.isOf(Blocks.BROWN_MUSHROOM) || state.isOf(Blocks.RED_MUSHROOM)
                || state.isOf(Blocks.BROWN_MUSHROOM_BLOCK) || state.isOf(Blocks.RED_MUSHROOM_BLOCK) || state.isOf(Blocks.MUSHROOM_STEM)
                || state.isOf(Blocks.CAVE_VINES) || state.isOf(Blocks.CAVE_VINES_PLANT) || state.isOf(Blocks.SPORE_BLOSSOM)
                || state.isOf(Blocks.HANGING_ROOTS) || state.isOf(Blocks.MOSS_CARPET) || state.isOf(Blocks.BIG_DRIPLEAF)
                || state.isOf(Blocks.BIG_DRIPLEAF_STEM) || state.isOf(Blocks.SMALL_DRIPLEAF) || state.isOf(Blocks.SUGAR_CANE)
                || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.BAMBOO) || state.isOf(Blocks.KELP) || state.isOf(Blocks.KELP_PLANT)
                || state.isOf(Blocks.SEAGRASS) || state.isOf(Blocks.TALL_SEAGRASS) || state.isOf(Blocks.LILY_PAD)
                || state.isOf(Blocks.VINE) || state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.PUMPKIN) || state.isOf(Blocks.MELON)
                || state.isOf(Blocks.BEE_NEST) || state.isOf(Blocks.COBWEB) || state.isOf(Blocks.COCOA) || state.isOf(Blocks.POINTED_DRIPSTONE)) {
            return true;
        }
        if (state.isIn(BlockTags.FLOWERS) || state.isIn(BlockTags.SAPLINGS) || state.isIn(BlockTags.CORAL_BLOCKS)
                || state.isIn(BlockTags.CORALS) || state.isIn(BlockTags.WART_BLOCKS) || state.isOf(Blocks.SHROOMLIGHT)
                || state.isIn(BlockTags.NYLIUM) || state.isIn(BlockTags.REPLACEABLE_BY_TREES)) {
            return true;
        }
        if (state.isIn(BlockTags.LEAVES)) {
            return !state.contains(LeavesBlock.PERSISTENT) || !state.get(LeavesBlock.PERSISTENT);
        }
        // logs are judged as part of a whole trunk, see tree()
        return state.isIn(BlockTags.LOGS);
    }

    /**
     * Something planted or tended: crops, stems and the melons and pumpkins hanging from them, berry bushes,
     * cocoa, nether wart, and farmland. Seen at a glance by the shape, like a player does.
     */
    public static boolean crop(BlockState state) {
        Block block = state.getBlock();
        return block instanceof CropBlock || block instanceof StemBlock || block instanceof AttachedStemBlock
                || block instanceof FarmlandBlock || block instanceof NetherWartBlock || block instanceof SweetBerryBushBlock
                || block instanceof CocoaBlock;
    }

    /** A crop at {@code pos}, the ground it grows in, or a melon or pumpkin on its stem. */
    public static boolean cropAt(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (crop(state)) {
            return true;
        }
        BlockState above = world.getBlockState(pos.up());
        if (crop(above) && !(above.getBlock() instanceof CocoaBlock)) {
            return true; // the soil a crop grows in
        }
        if (state.getBlock() instanceof GourdBlock) {
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockState near = world.getBlockState(pos.offset(dir));
                if (near.getBlock() instanceof AttachedStemBlock && near.get(AttachedStemBlock.FACING) == dir.getOpposite()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Planted by a player, or growing on ground a player tilled or planted. */
    public static boolean playersField(ServerWorld world, BlockPos pos) {
        TropelesMundo mundo = mundo(world);
        return mundo != null && (mundo.isPlayerPlaced(pos) || mundo.isPlayerPlaced(pos.down()));
    }

    /** A made block: anything that is not ground, rock, plant or tree. */
    public static boolean made(BlockState state) {
        return !natural(state);
    }

    @Nullable
    private static TropelesMundo mundo(ServerWorld world) {
        try {
            return TropelesMundo.get(world);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Inside one of the buildings of an MCA village, with a block of margin for the walls. */
    public static boolean inVillageBuilding(ServerWorld world, BlockPos pos) {
        VillageManager manager = VillageManager.get(world);
        Village village = manager.findNearestVillage(pos, 16).orElse(null);
        if (village == null) {
            return false;
        }
        for (Building building : village.getBuildings().values()) {
            if (building.getBuildingType().grouped()) {
                continue; // fields, pens and the like: open ground
            }
            BlockPos a = building.getPos0();
            BlockPos b = building.getPos1();
            if (pos.getX() >= a.getX() - 1 && pos.getX() <= b.getX() + 1
                    && pos.getY() >= a.getY() - 1 && pos.getY() <= b.getY() + 1
                    && pos.getZ() >= a.getZ() - 1 && pos.getZ() <= b.getZ() + 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the log at {@code pos} belongs to a tree the world grew. Walks the whole trunk with its branches
     * and asks for natural leaves on it and nothing made against it; the answer is shared by every log of the
     * trunk for a while.
     */
    public static boolean tree(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.isIn(BlockTags.LOGS)) {
            return false;
        }
        long now = world.getTime();
        Map<Long, Long> cache = TREE_CACHE.computeIfAbsent(world, w -> new HashMap<>());
        Long cached = cache.get(pos.asLong());
        if (cached != null && Math.abs(cached) > now) {
            return cached > 0;
        }
        if (cache.size() > 20000) {
            cache.clear();
        }
        TropelesMundo mundo = mundo(world);
        Set<Long> trunk = new HashSet<>();
        Deque<BlockPos> open = new ArrayDeque<>();
        open.add(pos.toImmutable());
        trunk.add(pos.asLong());
        boolean leaves = false;
        boolean madeNear = false;
        BlockPos.Mutable n = new BlockPos.Mutable();
        while (!open.isEmpty() && !madeNear) {
            BlockPos log = open.poll();
            if (mundo != null && (mundo.isPlayerPlaced(log) || mundo.placedBy(log) != null)) {
                madeNear = true;
                break;
            }
            for (int dx = -1; dx <= 1 && !madeNear; dx++) {
                for (int dy = -1; dy <= 1 && !madeNear; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        n.set(log.getX() + dx, log.getY() + dy, log.getZ() + dz);
                        BlockState near = world.getBlockState(n);
                        if (near.isIn(BlockTags.LOGS)) {
                            if (trunk.size() < TREE_LIMIT && trunk.add(n.asLong())) {
                                open.add(n.toImmutable());
                            }
                        } else if (near.isIn(BlockTags.LEAVES)) {
                            if (natural(near)) {
                                leaves = true;
                            } else {
                                madeNear = true;
                                break;
                            }
                        } else if (near.isIn(BlockTags.WART_BLOCKS) || near.isOf(Blocks.SHROOMLIGHT)) {
                            leaves = true;
                        } else if (made(near) && !near.isIn(BlockTags.MANGROVE_LOGS_CAN_GROW_THROUGH)) {
                            madeNear = true;
                            break;
                        }
                    }
                }
            }
        }
        // natural leaves on it and nothing made against it; a trunk whose base was already cut is still a tree
        boolean result = leaves && !madeNear && trunk.size() < TREE_LIMIT;
        long until = (now + CACHE_TICKS) * (result ? 1 : -1);
        for (long log : trunk) {
            cache.put(log, until);
        }
        return result;
    }

    /** What the eye makes of the block at {@code pos}, from the point of view of {@code villager} (may be null). */
    public static Kind kind(ServerWorld world, @Nullable VillagerEntityMCA villager, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        TropelesMundo mundo = mundo(world);
        if (mundo != null) {
            UUID by = mundo.placedBy(pos);
            if (villager != null && villager.getUuid().equals(by) && !cropAt(world, pos)) {
                return Kind.OWN;
            }
            if (cropAt(world, pos)) {
                return Kind.CROP;
            }
            if (mundo.isPlayerPlaced(pos)) {
                return Kind.PLAYER_BUILT;
            }
            if (by != null) {
                return Kind.BUILDING; // another villager's
            }
        }
        if (cropAt(world, pos)) {
            return Kind.CROP;
        }
        if (state.isIn(BlockTags.LOGS)) {
            return tree(world, pos) && !inVillageBuilding(world, pos) ? Kind.TREE : Kind.BUILDING;
        }
        if (inVillageBuilding(world, pos)) {
            return Kind.BUILDING;
        }
        if (made(state)) {
            return Kind.BUILDING;
        }
        if (Mining.isOre(state) || state.getCollisionShape(world, pos).isEmpty()) {
            return Kind.NATURE;
        }
        // ground sitting among made blocks is part of the build (the dirt under a floor, the stone of a wall)
        int madeAround = 0;
        BlockPos.Mutable n = new BlockPos.Mutable();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    n.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (villager != null && mundo != null && villager.getUuid().equals(mundo.placedBy(n))) {
                        continue; // its own torch or bridge does not make the rock a wall
                    }
                    BlockState near = world.getBlockState(n);
                    if (made(near) || mundo != null && mundo.isPlayerPlaced(n)) {
                        if (++madeAround >= 2) {
                            return Kind.BUILDING;
                        }
                    }
                }
            }
        }
        return Kind.NATURE;
    }

    /** May {@code villager} break this block without being told to: only nature, trees and what it placed. */
    public static boolean mayBreak(ServerWorld world, VillagerEntityMCA villager, BlockPos pos) {
        Kind kind = kind(world, villager, pos);
        return kind == Kind.NATURE || kind == Kind.TREE || kind == Kind.OWN;
    }

    public static void placedByVillager(ServerWorld world, VillagerEntityMCA villager, BlockPos pos) {
        TropelesMundo mundo = mundo(world);
        if (mundo != null) {
            mundo.placedByVillager(villager.getUuid(), pos);
        }
    }

    public static void placedByPlayer(ServerWorld world, BlockPos pos) {
        TropelesMundo mundo = mundo(world);
        if (mundo != null) {
            mundo.placedByPlayer(pos);
        }
        TREE_CACHE.remove(world);
    }

    public static void broken(ServerWorld world, BlockPos pos) {
        TropelesMundo mundo = mundo(world);
        if (mundo != null) {
            mundo.removed(pos);
        }
    }
}
