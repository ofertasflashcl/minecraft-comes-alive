package net.mca.entity.ai.body;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Water the way a miner deals with it: it does not wade on through a flooded tunnel, it shuts the water off.
 * The cells its body is in and the ones it is about to move into are watched: a spring next to the way gets a
 * block in it (a source keeps feeding the flow, so that is what a player shuts), and so does the spring a flood
 * comes from when it is within reach; water falling onto the way is blocked above, a still source standing in
 * the next cell is filled, and running water is simply walked through. Swimming in a shaft it fills the water under its
 * own feet to climb out, the way a player spams blocks while swimming up a flooded hole.
 * Out in the open (sky above, not under water) it leaves rivers and lakes alone.
 */
public final class Agua {
    /** Blocks it spends on one flood before it gives that way up. */
    private static final int BUDGET = 40;
    private static final long BUDGET_TICKS = 1200;
    /** A player clicks a few times a second, not every tick. */
    private static final long GAP_TICKS = 3;

    private static final class State {
        long lastPlace = Long.MIN_VALUE / 2;
        long windowStart;
        int spent;
    }

    private static final Map<VillagerEntityMCA, State> STATES = new WeakHashMap<>();

    private Agua() {
    }

    public static boolean water(World world, BlockPos pos) {
        return world.getFluidState(pos).isIn(FluidTags.WATER);
    }

    public static boolean source(World world, BlockPos pos) {
        FluidState fluid = world.getFluidState(pos);
        return fluid.isIn(FluidTags.WATER) && fluid.isStill();
    }

    /** The cells the body takes up. */
    public static Set<BlockPos> body(VillagerEntityMCA villager) {
        Box box = villager.getBoundingBox().contract(1.0E-3);
        Set<BlockPos> cells = new HashSet<>();
        for (int x = MathHelper.floor(box.minX); x <= MathHelper.floor(box.maxX); x++) {
            for (int y = MathHelper.floor(box.minY); y <= MathHelper.floor(box.maxY); y++) {
                for (int z = MathHelper.floor(box.minZ); z <= MathHelper.floor(box.maxZ); z++) {
                    cells.add(new BlockPos(x, y, z));
                }
            }
        }
        return cells;
    }

    /** Whether the eye remembers water in or right beside this cell. */
    public static boolean seenNear(VisualMemory memory, BlockPos pos) {
        BlockState here = memory.stateAt(pos);
        if (here != null && here.getFluidState().isIn(FluidTags.WATER)) {
            return true;
        }
        for (Direction dir : Direction.values()) {
            BlockState seen = memory.stateAt(pos.offset(dir));
            if (seen != null && seen.getFluidState().isIn(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    /** Spent all it would on this flood lately: the way is too wet, better go another way. */
    public static boolean overwhelmed(VillagerEntityMCA villager, long now) {
        State state = STATES.get(villager);
        return state != null && now - state.windowStart <= BUDGET_TICKS && state.spent >= BUDGET;
    }

    /** Swimming in a narrow hole (three or more solid sides), or with the head under water. */
    public static boolean trapped(World world, VillagerEntityMCA villager) {
        if (villager.isSubmergedInWater()) {
            return true;
        }
        if (!villager.isTouchingWater()) {
            return false;
        }
        return walls(world, villager.getBlockPos()) >= 3;
    }

    /**
     * One tick of keeping {@code path} (the cells the body will pass through next, may be empty) and the body
     * itself dry. True when it is busy with the water this tick (it put a block down, or is about to), so the
     * caller does nothing else.
     */
    public static boolean seal(ServerWorld world, VillagerEntityMCA villager, Collection<BlockPos> path) {
        long now = world.getTime();
        BlockPos feet = villager.getBlockPos();
        boolean trapped = trapped(world, villager);
        boolean underOnly = false;
        if (world.isSkyVisible(feet.up()) && !shaft(world, villager)) {
            // a river or a lake out in the open: walk or swim like anyone, unless it is running out of air
            if (!villager.isSubmergedInWater() || villager.getAir() > villager.getMaxAir() / 2) {
                return false;
            }
            underOnly = true;
        }
        State state = STATES.computeIfAbsent(villager, v -> new State());
        if (now - state.windowStart > BUDGET_TICKS) {
            state.windowStart = now;
            state.spent = 0;
        }
        if (state.spent >= BUDGET) {
            return false;
        }
        List<BlockPos> targets = targets(world, villager, underOnly ? List.of() : path, trapped, underOnly);
        if (targets.isEmpty()) {
            return false;
        }
        ItemStack block = Hands.buildingBlock(villager);
        if (block.isEmpty()) {
            return false;
        }
        if (now - state.lastPlace < GAP_TICKS) {
            return true;
        }
        for (BlockPos target : targets) {
            if (Hands.place(world, villager, target, block)) {
                state.lastPlace = now;
                state.spent++;
                return true;
            }
        }
        return false;
    }

    /**
     * The spring feeding the water around {@code from}: the water is followed upstream (to cells as full or
     * fuller, or falling from above) through at most {@code maxCells} cells. Null when none is found.
     */
    @org.jetbrains.annotations.Nullable
    public static BlockPos feeder(World world, Collection<BlockPos> from, int maxCells) {
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos start : from) {
            for (Direction dir : Direction.values()) {
                BlockPos cell = dir == Direction.DOWN ? start : start.offset(dir);
                if (water(world, cell) && seen.add(cell)) {
                    queue.add(cell);
                }
            }
        }
        while (!queue.isEmpty() && seen.size() <= maxCells) {
            BlockPos cell = queue.poll();
            if (source(world, cell)) {
                return cell;
            }
            int level = world.getFluidState(cell).getLevel();
            for (Direction dir : Direction.values()) {
                if (dir == Direction.DOWN) {
                    continue; // water runs down and sideways, so its spring is up or to the side
                }
                BlockPos near = cell.offset(dir);
                if (seen.contains(near) || !water(world, near)) {
                    continue;
                }
                FluidState fluid = world.getFluidState(near);
                if (dir == Direction.UP || fluid.isStill() || fluid.getLevel() >= level) {
                    seen.add(near);
                    queue.add(near);
                }
            }
        }
        return null;
    }

    /** In water in a hole three or more sides walled. */
    private static boolean shaft(World world, VillagerEntityMCA villager) {
        return villager.isTouchingWater() && walls(world, villager.getBlockPos()) >= 3;
    }

    private static int walls(World world, BlockPos feet) {
        int walls = 0;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (!Mining.passable(world, feet.offset(dir))) {
                walls++;
            }
        }
        return walls;
    }

    /** The water to block off, most urgent first. */
    private static List<BlockPos> targets(ServerWorld world, VillagerEntityMCA villager, Collection<BlockPos> path, boolean trapped, boolean underOnly) {
        Set<BlockPos> body = body(villager);
        List<BlockPos> targets = new ArrayList<>();
        if (trapped || underOnly) {
            // the water under its feet becomes the floor it climbs on
            int lowest = MathHelper.floor(villager.getBoundingBox().minY + 1.0E-3);
            BlockPos under = BlockPos.ofFloored(villager.getX(), lowest - 1, villager.getZ());
            if (water(world, under) && !body.contains(under)) {
                targets.add(under);
            }
        }
        if (underOnly) {
            return targets;
        }
        boolean submerged = villager.isSubmergedInWater();
        Set<BlockPos> route = new LinkedHashSet<>(body);
        route.addAll(path);
        List<BlockPos> sources = new ArrayList<>();
        List<BlockPos> inPath = new ArrayList<>();
        List<BlockPos> flows = new ArrayList<>();
        Set<BlockPos> looked = new HashSet<>(targets);
        for (BlockPos cell : route) {
            // running water is walked through like a player does (filling it is no use while its spring runs, it
            // only comes back when the cell is dug out); a still source in the way is filled for good
            if (!body.contains(cell) && looked.add(cell) && source(world, cell)) {
                inPath.add(cell);
            }
            for (Direction dir : Direction.values()) {
                if (dir == Direction.DOWN) {
                    continue; // water below the way does not run into it
                }
                if (dir == Direction.UP && submerged && body.contains(cell)) {
                    continue; // never a lid over its own head under water: that is the way to air
                }
                BlockPos near = cell.offset(dir);
                if (route.contains(near) || !looked.add(near) || !water(world, near)) {
                    continue;
                }
                if (source(world, near)) {
                    sources.add(near); // a spring keeps feeding the flow: shut it
                } else if (dir == Direction.UP) {
                    flows.add(near); // water falling onto the way from above
                }
            }
        }
        if (sources.isEmpty() && villager.isTouchingWater()) {
            // wading in running water with no spring beside the way: where is it coming from? within reach, shut it
            BlockPos spring = feeder(world, route, 64);
            if (spring != null && villager.getEyePos().distanceTo(net.minecraft.util.math.Vec3d.ofCenter(spring)) <= Retina.BLOCK_REACH + 0.5) {
                sources.add(spring);
            }
        }
        targets.addAll(sources);
        targets.addAll(inPath);
        targets.addAll(flows);
        targets.removeIf(pos -> irrigates(world, pos));
        return targets;
    }

    /** Water that keeps a field wet (farmland within four blocks at its level or one up) belongs to the field. */
    private static boolean irrigates(World world, BlockPos water) {
        BlockPos.Mutable cell = new BlockPos.Mutable();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    cell.set(water.getX() + dx, water.getY() + dy, water.getZ() + dz);
                    if (world.getBlockState(cell).getBlock() instanceof net.minecraft.block.FarmlandBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
