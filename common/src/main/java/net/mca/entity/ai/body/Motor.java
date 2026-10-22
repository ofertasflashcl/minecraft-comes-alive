package net.mca.entity.ai.body;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.sense.Obras;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.TwistingVinesBlock;
import net.minecraft.block.TwistingVinesPlantBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

/**
 * Moves a villager one block at a time the way a player gets through terrain: walks when the way is
 * open, cuts a two-high tunnel through rock, digs stairs down and up, bridges a gap by placing a block
 * against the side of the one it stands on (edging out like a sneaking player), climbs ladders, and
 * pillars straight up by jumping and placing under its feet. Every block it breaks or places goes
 * through {@link Hands}, so it only touches what its eye can put a crosshair on within reach.
 * <p>
 * Each step is a small machine anchored to the cell the feet were in when it was planned ({@code from}):
 * every cell of the step hangs off that origin, never off where the body happens to be mid-jump. It
 * breaks only standing on the ground and still, jumps only once the way overhead is open (and, for a
 * pillar, centred and still), puts the block under its feet at the top of the jump, and checks where it
 * landed before taking the next step.
 */
public final class Motor {
    public enum Result {RUNNING, ARRIVED, BLOCKED}

    private enum Kind {LEVEL, DOWN, UP, PILLAR, CLIMB}

    /** Where in a step the body is: getting the way ready, centring for a jump, moving, rising off the ground, coming down. */
    private enum Phase {PREPARE, CENTER, WALK, RISE, LAND}

    /** Ticks with nothing moving, breaking or being placed before a step is given up. Digging time is progress. */
    private static final int IDLE_TIMEOUT = 200;
    /** A single step taking longer than this is given up whatever it is doing. */
    private static final int HARD_TIMEOUT = 1600;
    /** Off its feet for this long (not one of its own jumps): something holds it in the air. */
    private static final int AIR_TIMEOUT = 100;
    /** Jumps that come back down where they started before the step is given up. */
    private static final int MAX_JUMPS = 4;
    /** Ticks it takes at most to settle in the middle of its cell before a pillar jump. */
    private static final int CENTER_TICKS = 20;
    /** Ticks it tries to set a block in place (edging out included) before it gives that way up. */
    private static final int PLACE_TICKS = 100;
    /** Ticks it waits after breaking a block that sand or gravel sits on. */
    private static final int SETTLE_TICKS = 6;
    /** Ticks it watches a hole it just opened for lava or a spring (overworld lava moves every 30 ticks). */
    private static final int WATCH_TICKS = 40;
    /** How high over its cell the feet must be in a pillar jump before the block fits in under them (the body clear of it). */
    private static final double PLACE_HEIGHT = 1.001;
    /** Ticks without a call to {@link #step} after which a step is planned afresh: the caller did something else meanwhile. */
    private static final int STALE_TICKS = 5;

    // what a way up costs, to choose between stairs and a pillar like a player would
    private static final int COST_DIG = 2;
    private static final int COST_PLACE = 3;
    private static final int COST_PILLAR = 2;
    private static final int COST_OFF_AXIS = 2;
    private static final int COST_SIDE = 4;
    private static final int COST_BACK = 8;

    /**
     * Extra push per tick when walking in water. A mob's legs give it 0.3 x 0.02 there while a current pushes
     * with 0.014, so it could never walk upstream; a player's legs give about 0.02 and wade against it.
     */
    private static final double WADE = 0.014;

    private final Hands.Breaker breaker = new Hands.Breaker();
    private final Deque<BlockPos> safeTrail = new ArrayDeque<>();
    /** The feet when the step was planned: every cell of the step hangs off it. */
    @Nullable
    private BlockPos from;
    @Nullable
    private BlockPos stand;
    /** The goal the step was planned for. */
    @Nullable
    private BlockPos planGoal;
    @Nullable
    private BlockPos lastFeet;
    @Nullable
    private Direction dir;
    private Kind kind = Kind.LEVEL;
    private Phase phase = Phase.PREPARE;
    private int tries;
    private int centerTicks;
    private int placeTicks;
    private int settleTicks;
    private int idleTicks;
    private int stepTicks;
    private int airTicks;
    private long jumpAt;
    private long lastStepAt;
    private float lastProgress;
    /** One more block of room over the head: dug only after a jump that came back down. */
    private boolean headroom;
    private boolean wasAirborne;
    /** A short drop ahead it may walk off: the floor is not checked again on the way. */
    private boolean dropOk;
    /** It broke, placed or jumped this tick: the step is getting somewhere. */
    private boolean acted;
    @Nullable
    private BlockPos justBroken;
    private int plugTicks;
    private String doing = "";

    public String doing() {
        return doing;
    }

    public Hands.Breaker breaker() {
        return breaker;
    }

    public void reset(ServerWorld world, VillagerEntityMCA villager) {
        breaker.reset(world, villager);
        endStep();
        lastFeet = null;
        settleTicks = 0;
        airTicks = 0;
        villager.setSneaking(false);
    }

    /** The step is over (made, or given up): the next call plans a new one. */
    private void endStep() {
        from = null;
        stand = null;
        planGoal = null;
        dir = null;
        kind = Kind.LEVEL;
        phase = Phase.PREPARE;
        tries = 0;
        centerTicks = 0;
        placeTicks = 0;
        stepTicks = 0;
        idleTicks = 0;
        headroom = false;
        wasAirborne = false;
        dropOk = false;
    }

    // ------------------------------------------------------------------ what other code may ask

    /** On its feet: standing on something, in water, or holding on to a ladder. */
    public static boolean settled(LivingEntity entity) {
        return entity.isOnGround() || entity.isTouchingWater() || entity.isClimbing();
    }

    /** Standing ON this cell, not on the lip of the block beside it with its centre already over a gap. */
    public static boolean onCell(World world, LivingEntity entity, BlockPos feet) {
        if (!settled(entity)) {
            return false;
        }
        if (entity.isTouchingWater() || entity.isClimbing()) {
            return true;
        }
        // a slab, path, soul sand or carpet in the cell itself counts as its floor
        return Mining.standable(world, feet.down()) || !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty();
    }

    /** Legs off: no path, no walking, no strafing. The head and hands stay free. */
    public static void standStill(MobEntity mob) {
        mob.getNavigation().stop();
        mob.getMoveControl().moveTo(mob.getX(), mob.getY(), mob.getZ(), 0.0);
        mob.setForwardSpeed(0.0F);
        mob.setSidewaysSpeed(0.0F);
    }

    /**
     * In the middle of a jump, a step up or a fall that is part of a step: nothing else should take the body
     * (or the hands) until it has landed.
     */
    public boolean midStep(LivingEntity entity) {
        return from != null && (phase == Phase.RISE || phase == Phase.LAND || phase == Phase.WALK && !settled(entity));
    }

    /** Going up a pillar of its own blocks or a ladder right now. */
    public boolean climbingUp() {
        return from != null && (kind == Kind.PILLAR || kind == Kind.CLIMB);
    }

    /** A step is planned and not finished yet. */
    public boolean busy() {
        return from != null;
    }

    // ------------------------------------------------------------------ the step

    /**
     * One tick of getting to {@code goal}. With {@code dig} it may cut natural rock; with {@code build} it may
     * spend building blocks from the bag on bridges and pillars.
     */
    public Result step(ServerWorld world, VillagerEntityMCA villager, BlockPos goal, boolean dig, boolean build) {
        villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        BlockPos feet = villager.getBlockPos();
        boolean settled = settled(villager);
        acted = false;
        long now = world.getTime();
        if (from != null && settled && now - lastStepAt > STALE_TICKS) {
            // the caller hit an ore, ate or fought in between: the way may have changed, so plan the step again from here
            endStep();
        }
        lastStepAt = now;
        if (villager.isOnGround() && Mining.passable(world, feet) && !Mining.heat(world, feet) && !Agua.water(world, feet)) {
            if (safeTrail.isEmpty() || !safeTrail.peekLast().equals(feet)) {
                safeTrail.addLast(feet.toImmutable());
                if (safeTrail.size() > 16) {
                    safeTrail.removeFirst();
                }
            }
        }
        if (villager.isInLava() || villager.isOnFire() && Mining.heat(world, feet)) {
            return retreat(world, villager);
        }
        boolean rising = phase == Phase.RISE;
        if (!rising) {
            // the few ticks at the top of a pillar jump belong to the block under the feet, nothing else
            if (plugLava(world, villager)) {
                return Result.RUNNING;
            }
            // water: shut it out of the way before going on, and climb out of it when swimming in a hole
            if (keepDry(world, villager, from == null ? List.of() : route())) {
                return Result.RUNNING;
            }
            if (from != null && Agua.overwhelmed(villager, world.getTime()) && route().stream().anyMatch(cell -> Agua.water(world, cell))) {
                // more water than blocks to stop it: this way is drowned, take another
                return blocked(world, villager);
            }
        }
        if (villager.isSubmergedInWater()) {
            villager.getJumpControl().setActive();
            if (villager.getAir() < villager.getMaxAir() / 3 && !safeTrail.isEmpty()) {
                // out of breath and nothing more to block: back to the last dry spot
                return retreat(world, villager);
            }
        }
        if (villager.isInsideWall() && dig) {
            // sand or gravel came down on its head: dig out of it right away, in the air if it has to
            BlockPos head = BlockPos.ofFloored(villager.getEyePos());
            if (!Mining.passable(world, head) && Mining.mayDig(world, head, world.getBlockState(head))) {
                standStill(villager);
                doing = "cavando para respirar";
                int r = breaker.tick(world, villager, head, true);
                if (r == Hands.Breaker.FAILED) {
                    return blocked(world, villager);
                }
                if (r == Hands.Breaker.DONE) {
                    afterBreak(world, villager, head);
                }
                return Result.RUNNING;
            }
        }
        if (feet.equals(goal) && !rising && onCell(world, villager, feet)) {
            // never mid-jump, never from the lip with its centre over the gap it is bridging
            villager.setSneaking(false);
            endStep();
            return Result.ARRIVED;
        }
        if (!settled && (from == null || phase == Phase.PREPARE || phase == Phase.CENTER)) {
            // falling or knocked into the air, not one of its own jumps: land first, plan after
            return ++airTicks > AIR_TIMEOUT ? blocked(world, villager) : Result.RUNNING;
        }
        if (settled) {
            airTicks = 0;
        }
        if (needsPlan(world, villager, feet, goal)) {
            plan(world, villager, feet, goal, dig, build);
            if (stand == null) {
                return blocked(world, villager);
            }
        }
        Result r = switch (kind) {
            case PILLAR -> pillar(world, villager, dig, build);
            case CLIMB -> climb(world, villager, dig);
            default -> walk(world, villager, feet, dig, build);
        };
        return r == Result.RUNNING && stalled(feet) ? blocked(world, villager) : r;
    }

    /**
     * Blocks water off the body and the way ahead (see {@link Agua}). True while busy with it this tick. Callers
     * that work in place (hitting an ore in front of them) use it too.
     */
    public boolean keepDry(ServerWorld world, VillagerEntityMCA villager, Collection<BlockPos> path) {
        if (!Agua.seal(world, villager, path)) {
            return false;
        }
        doing = "tapando el agua";
        villager.getNavigation().stop();
        return true;
    }

    /**
     * For work done standing still (hitting an ore in front): plugs lava or water coming out of what it just
     * broke and keeps water off the body. True while busy with it this tick.
     */
    public boolean tend(ServerWorld world, VillagerEntityMCA villager) {
        return plugLava(world, villager) || keepDry(world, villager, List.of());
    }

    /** It broke {@code cell} by hand outside of a step: picks up the drop and watches the hole for a moment. */
    public void broke(ServerWorld world, VillagerEntityMCA villager, BlockPos cell) {
        afterBreak(world, villager, cell);
    }

    private Result blocked(ServerWorld world, VillagerEntityMCA villager) {
        reset(world, villager);
        return Result.BLOCKED;
    }

    private boolean needsPlan(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, BlockPos goal) {
        if (from == null || stand == null) {
            return true;
        }
        if (!settled(villager) || phase == Phase.RISE) {
            return false;
        }
        if (kind == Kind.PILLAR || kind == Kind.CLIMB) {
            if (!feet.equals(from)) {
                return true; // one level up (the normal way), or knocked aside
            }
        } else if (feet.equals(stand)) {
            if (onCell(world, villager, feet)) {
                return true; // there; on the lip it goes on bridging from 'from'
            }
        } else if (!allowed().contains(feet)) {
            return true; // pushed, slid or dropped off the way
        }
        // a new goal is taken up only between steps, never during a jump, a dig or an edge-out (a block the caller left
        // half broken somewhere else, an ore, does not hold the step back)
        BlockPos hitting = breaker.target();
        boolean digging = hitting != null && (hitting.equals(from) || route().contains(hitting));
        return !goal.equals(planGoal) && phase == Phase.PREPARE && feet.equals(from) && !digging && placeTicks == 0;
    }

    /** The cells the feet may be in while the step is under way. */
    private List<BlockPos> allowed() {
        return switch (kind) {
            case LEVEL -> List.of(from, stand);
            case DOWN -> List.of(from, stand.up(), stand);
            case UP -> List.of(from, from.up(), stand);
            case PILLAR, CLIMB -> List.of(from);
        };
    }

    /** Nothing moved, broke or was placed for too long, or the step has gone on far too long. */
    private boolean stalled(BlockPos feet) {
        float progress = breaker.progress();
        boolean moved = !feet.equals(lastFeet) || acted || breaker.target() != null && progress > lastProgress;
        lastFeet = feet.toImmutable();
        lastProgress = progress;
        idleTicks = moved ? 0 : idleTicks + 1;
        return idleTicks > IDLE_TIMEOUT || ++stepTicks > HARD_TIMEOUT;
    }

    // ------------------------------------------------------------------ planning

    private void plan(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, BlockPos goal, boolean dig, boolean build) {
        from = feet.toImmutable();
        planGoal = goal.toImmutable();
        phase = Phase.PREPARE;
        tries = 0;
        centerTicks = 0;
        placeTicks = 0;
        stepTicks = 0;
        headroom = false;
        wasAirborne = false;
        dropOk = false;
        stand = null;
        int dx = goal.getX() - feet.getX();
        int dy = goal.getY() - feet.getY();
        int dz = goal.getZ() - feet.getZ();
        Direction toward = null;
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            toward = dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (dz != 0) {
            toward = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        // straight below: never dig under its own feet, go down a staircase instead
        Direction facing = toward != null ? toward : villager.getHorizontalFacing();
        dir = facing;
        if (dx == 0 && dy == 0 && dz == 0) {
            // on the goal but not on its floor (the lip of a gap): bridge under it
            kind = Kind.LEVEL;
            stand = from;
            return;
        }
        int horizontal = Math.abs(dx) + Math.abs(dz);
        if (toward != null && horizontal > Math.abs(dy) && walkable(world, feet.offset(toward))) {
            // more ahead than up or down, and the way ahead is open: walk it first, climb or go down nearer the goal
            kind = Kind.LEVEL;
            stand = feet.offset(toward);
            return;
        }
        if (dy < 0) {
            kind = Kind.DOWN;
            stand = feet.offset(facing).down();
            return;
        }
        if (dy == 0) {
            kind = Kind.LEVEL;
            stand = feet.offset(facing);
            return;
        }
        // up: a ladder if there is one that leads somewhere, else whatever way up costs least
        if (climbFrom(world, feet)) {
            kind = Kind.CLIMB;
            stand = feet.up();
            return;
        }
        VisualMemory memory = Retina.memory(villager);
        boolean blocks = build && !Hands.buildingBlock(villager).isEmpty();
        // the goal right overhead: stairs lead away from it and a pillar goes straight there (a tie goes to the pillar);
        // the goal off to the side: a pillar is the detour
        int stairDetour = horizontal == 0 ? COST_OFF_AXIS + 1 : 0;
        int pillarDetour = horizontal > 1 ? COST_OFF_AXIS : 0;
        int best = Integer.MAX_VALUE;
        int ahead = stairCost(world, villager, feet, facing, dig, blocks, memory);
        if (ahead >= 0) {
            best = ahead + stairDetour;
            kind = Kind.UP;
            dir = facing;
            stand = feet.offset(facing).up();
        }
        if (blocks && pillarSafe(world, villager, feet, dig, memory)) {
            int cost = (open(world, feet.up(2)) ? 0 : COST_DIG) + COST_PLACE + COST_PILLAR + pillarDetour;
            if (cost < best) {
                best = cost;
                kind = Kind.PILLAR;
                dir = facing;
                stand = feet.up();
            }
        }
        Direction[] others = {facing.rotateYClockwise(), facing.rotateYCounterclockwise(), facing.getOpposite()};
        int[] penalty = {COST_SIDE, COST_SIDE, COST_BACK};
        for (int i = 0; i < others.length; i++) {
            int cost = stairCost(world, villager, feet, others[i], dig, blocks, memory);
            if (cost >= 0 && cost + stairDetour + penalty[i] < best) {
                best = cost + stairDetour + penalty[i];
                kind = Kind.UP;
                dir = others[i];
                stand = feet.offset(others[i]).up();
            }
        }
        // boxed in with no way up: stand stays null, so BLOCKED
    }

    /** Open to walk into on its own feet, with a floor. */
    private static boolean walkable(World world, BlockPos cell) {
        return open(world, cell) && open(world, cell.up()) && Mining.standable(world, cell.down());
    }

    /**
     * What a step up toward {@code d} costs: the cells to dig and the block to set as the step, or -1 when it
     * cannot be done (a build in the way, lava seen there, nothing to stand on and nothing to set a block on).
     */
    private static int stairCost(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, Direction d, boolean dig, boolean blocks, VisualMemory memory) {
        BlockPos step = feet.offset(d);
        BlockPos stair = step.up();
        int cost = 0;
        for (BlockPos cell : new BlockPos[]{feet.up(2), stair.up(), stair}) {
            if (open(world, cell)) {
                continue;
            }
            if (!dig || !Mining.mayDig(world, cell, world.getBlockState(cell)) || Mining.lavaSeen(memory, cell) || !Obras.mayBreak(world, villager, cell)) {
                return -1;
            }
            cost += COST_DIG;
        }
        if (Mining.standable(world, step)) {
            return Mining.lavaSeen(memory, step) ? -1 : cost;
        }
        if (!blocks || !world.getBlockState(step).isReplaceable() || Mining.lavaSeen(memory, step)) {
            return -1;
        }
        // the step is a block of its own, set against the floor under it or a wall beside it, both in sight from here
        if (Mining.standable(world, step.down())) {
            return cost + COST_PLACE;
        }
        for (Direction side : Direction.Type.HORIZONTAL) {
            if (side != d.getOpposite() && Mining.standable(world, step.offset(side))) {
                return cost + COST_PLACE;
            }
        }
        return -1;
    }

    /** Whether it may go straight up from {@code feet} on blocks of its own, judged by what the eye has seen. */
    private static boolean pillarSafe(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, boolean dig, VisualMemory memory) {
        for (int k = 2; k <= 3; k++) {
            if (Mining.lavaSeen(memory, feet.up(k))) {
                return false;
            }
        }
        if (Agua.seenNear(memory, feet.up(2)) && Hands.buildingBlockCount(villager) < 16) {
            return false; // a stream down the shaft and few blocks to wall it off: better sideways
        }
        BlockPos head = feet.up(2);
        if (!open(world, head) && !(dig && Mining.mayDig(world, head, world.getBlockState(head)) && Obras.mayBreak(world, villager, head))) {
            return false;
        }
        BlockState at = world.getBlockState(feet);
        if (at.getBlock() instanceof LadderBlock) {
            return false; // a ladder takes no block over it: it is climbed, or left for stairs
        }
        if (at.isReplaceable()) {
            return true;
        }
        VoxelShape shape = at.getCollisionShape(world, feet);
        if (shape.isEmpty()) {
            return Mining.mayDig(world, feet, at) && Obras.mayBreak(world, villager, feet); // its own torch: knocked over first
        }
        // gravel that came down into its feet: yes, it is the next block; standing ON a slab or a path: no, stairs
        return villager.getY() < feet.getY() + shape.getMax(Direction.Axis.Y) - 1.0E-3;
    }

    /** The cells the body passes through on the step, top first so each lower one comes into view. */
    private BlockPos[] clearance() {
        return switch (kind) {
            case LEVEL -> new BlockPos[]{stand.up(), stand};
            case DOWN -> new BlockPos[]{stand.up(2), stand.up(), stand};
            case UP -> headroom
                    ? new BlockPos[]{from.up(2), from.up(3), stand.up(), stand}
                    : new BlockPos[]{from.up(2), stand.up(), stand};
            // one block over the head, like a player; a second one only after a jump that hit its head
            case PILLAR -> headroom ? new BlockPos[]{from.up(2), from.up(3)} : new BlockPos[]{from.up(2)};
            case CLIMB -> new BlockPos[]{from.up(2)};
        };
    }

    /** The cells the body passes through on the step being made. */
    private List<BlockPos> route() {
        List<BlockPos> cells = new ArrayList<>(List.of(clearance()));
        if (stand != null && !cells.contains(stand)) {
            cells.add(stand);
        }
        return cells;
    }

    // ------------------------------------------------------------------ doing it

    /**
     * Opens the given cells, the only place a step breaks anything: standing on the ground and still, top
     * cell first. Null when every cell is open.
     */
    @Nullable
    private Result clear(ServerWorld world, VillagerEntityMCA villager, BlockPos[] cells, boolean dig) {
        if (settleTicks > 0) {
            settleTicks--;
            standStill(villager);
            doing = "esperando que caiga la grava";
            return Result.RUNNING;
        }
        for (BlockPos cell : cells) {
            if (open(world, cell)) {
                continue;
            }
            if (!dig) {
                return blocked(world, villager);
            }
            BlockState state = world.getBlockState(cell);
            if (!Mining.mayDig(world, cell, state) || Mining.lavaSeen(Retina.memory(villager), cell)) {
                return blocked(world, villager);
            }
            // still first: legs off, then the pick
            standStill(villager);
            doing = "cavando";
            int r = breaker.tick(world, villager, cell);
            if (r == Hands.Breaker.FAILED) {
                // not in reach or not visible from here, or not its to break: another way
                return blocked(world, villager);
            }
            if (r == Hands.Breaker.DONE) {
                afterBreak(world, villager, cell);
            }
            return Result.RUNNING;
        }
        return null;
    }

    /**
     * Something to stand on at the end of the step: null when there is (or there is a short drop it may walk
     * off), RUNNING while it sets a block or edges out to see where one goes, BLOCKED for lava or a deep drop.
     */
    @Nullable
    private Result floor(ServerWorld world, VillagerEntityMCA villager, boolean build) {
        BlockPos ground = stand.down();
        if (Mining.standable(world, ground)) {
            return null;
        }
        boolean lavaBelow = world.getFluidState(ground).isIn(FluidTags.LAVA) || Mining.lavaSeen(Retina.memory(villager), ground);
        ItemStack block = build ? Hands.buildingBlock(villager) : ItemStack.EMPTY;
        if (!block.isEmpty() && world.getBlockState(ground).isReplaceable() && !lavaBelow) {
            doing = kind == Kind.UP ? "pone un escalón" : "haciendo un puente";
            if (Hands.place(world, villager, ground, block)) {
                villager.setSneaking(false);
                acted = true;
                placeTicks = 0;
                return Result.RUNNING;
            }
            if (++placeTicks > PLACE_TICKS) {
                return blocked(world, villager);
            }
            if (kind == Kind.LEVEL && !stand.equals(from)) {
                // the side face is not in sight yet: edge out like a sneaking player, from the block it stands on
                edgeToward(villager, from, dir);
                return Result.RUNNING;
            }
            if (kind == Kind.UP) {
                // the step goes where its own body leans over: back to the middle of its cell and try again
                Vec3d c = Vec3d.ofBottomCenter(from);
                villager.getNavigation().stop();
                villager.getMoveControl().moveTo(c.x, villager.getY(), c.z, 0.25);
                return Result.RUNNING;
            }
            // a step down with nothing to set a block against: only if the drop is short
        }
        if (lavaBelow || dropDepth(world, ground) > 2 || kind == Kind.UP) {
            return blocked(world, villager);
        }
        dropOk = true;
        return null;
    }

    /** LEVEL, DOWN and UP: open the way standing still, make sure of a floor, then walk (and hop) onto it. */
    private Result walk(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, boolean dig, boolean build) {
        boolean settled = settled(villager);
        if (phase == Phase.PREPARE) {
            // step() has already returned while it was off its feet in PREPARE
            Result r = clear(world, villager, clearance(), dig);
            if (r != null) {
                return r;
            }
            r = floor(world, villager, build);
            if (r != null) {
                return r;
            }
            if (fallingNear(world, villager)) {
                standStill(villager);
                doing = "esperando que caiga la grava";
                return Result.RUNNING;
            }
            phase = Phase.WALK;
            wasAirborne = false;
        }
        if (kind == Kind.UP && settled && wasAirborne && feet.equals(from)) {
            // jumped and came back down where it started
            if (++tries > MAX_JUMPS) {
                return blocked(world, villager);
            }
            if (tries >= 2 && !headroom && worthDigging(world, villager, from.up(3), dig)) {
                // its head may be what stops it: one more block of room, dug standing still
                headroom = true;
                phase = Phase.PREPARE;
                wasAirborne = false;
                return Result.RUNNING;
            }
        }
        wasAirborne = !settled;
        if (settled) {
            for (BlockPos cell : clearance()) {
                if (!open(world, cell)) {
                    // gravel came down or water was sealed in the way: open it again
                    phase = Phase.PREPARE;
                    return Result.RUNNING;
                }
            }
            if (!dropOk && !Mining.standable(world, stand.down()) && !villager.isTouchingWater()) {
                phase = Phase.PREPARE;
                return Result.RUNNING;
            }
        }
        villager.setSneaking(false);
        villager.getNavigation().stop();
        doing = switch (kind) {
            case DOWN -> "bajando por la escalera";
            case UP -> "subiendo";
            default -> "caminando";
        };
        // steering also in the air: it never hits anything while off its feet
        Vec3d target = Vec3d.ofBottomCenter(stand);
        villager.getMoveControl().moveTo(target.x, target.y, target.z, 0.6);
        if (villager.isTouchingWater()) {
            wade(villager, target);
        }
        if (kind == Kind.UP && settled && feet.equals(from) && villager.horizontalCollision) {
            // pressed against the step with the way over it open: up it goes
            villager.getJumpControl().setActive();
            if (villager.isOnGround()) {
                acted = true; // a hop off the ground; pressing on while swimming is no progress by itself
            }
        }
        return Result.RUNNING;
    }

    /**
     * One level of a pillar, the way a player does it: stand still and dig the block over the head, centre,
     * jump, put a block under the feet at the top of the jump, land on it.
     */
    private Result pillar(ServerWorld world, VillagerEntityMCA villager, boolean dig, boolean build) {
        ItemStack block = build ? Hands.buildingBlock(villager) : ItemStack.EMPTY;
        long now = world.getTime();
        if (phase == Phase.RISE) {
            standStill(villager);
            doing = "salta y pone un bloque bajo los pies";
            if (!world.getBlockState(from).isReplaceable()) {
                // already filled under it: gravel came down, or the water block Agua set there
                phase = Phase.LAND;
                return Result.RUNNING;
            }
            if (villager.getY() >= from.getY() + PLACE_HEIGHT) {
                if (!block.isEmpty() && Hands.place(world, villager, from, block)) {
                    acted = true;
                    phase = Phase.LAND;
                }
                return Result.RUNNING;
            }
            if (now - jumpAt > 3 && (villager.isOnGround() || villager.getVelocity().y < 0)) {
                // past the top with no block under it, or it never left the ground
                phase = Phase.LAND;
            }
            return Result.RUNNING;
        }
        if (phase == Phase.LAND) {
            if (!villager.isOnGround() && !villager.isTouchingWater()) {
                return Result.RUNNING;
            }
            // a landing one level up is taken by needsPlan(); this one came back down on 'from'
            if (++tries > MAX_JUMPS) {
                return blocked(world, villager);
            }
            if (!headroom && worthDigging(world, villager, from.up(3), dig)) {
                headroom = true; // its head may have cut the jump short: one more block of room
            }
            phase = Phase.PREPARE;
            return Result.RUNNING;
        }
        if (!villager.isOnGround()) {
            // swimming in a hole: the submerged jump and Agua's blocks under the feet lift it
            return Result.RUNNING;
        }
        // 0. the feet cell has to take a block
        BlockState at = world.getBlockState(from);
        if (!at.isReplaceable()) {
            if (at.getBlock() instanceof LadderBlock) {
                return blocked(world, villager); // a ladder in its feet: the next plan climbs it or cuts stairs
            }
            VoxelShape shape = at.getCollisionShape(world, from);
            if (!shape.isEmpty() && villager.getY() < from.getY() + shape.getMax(Direction.Axis.Y) - 1.0E-3) {
                // sand or gravel came down into its feet: up onto it, it is the next block of the pillar
                standStill(villager);
                villager.getJumpControl().setActive();
                jumpAt = now;
                phase = Phase.RISE;
                acted = true;
                doing = "sube sobre lo que le cayó a los pies";
                return Result.RUNNING;
            }
            if (shape.isEmpty() && Mining.mayDig(world, from, at)) {
                // its own torch at its feet takes no block on top: one hit knocks it over (a player's stays: FAILED)
                standStill(villager);
                doing = "quita la antorcha de sus pies";
                int r = breaker.tick(world, villager, from);
                if (r == Hands.Breaker.FAILED) {
                    return blocked(world, villager);
                }
                if (r == Hands.Breaker.DONE) {
                    acted = true;
                    Hands.collect(world, villager, from, 1.5);
                }
                return Result.RUNNING;
            }
            // standing on a slab, a path or soul sand: no pillar from here, the next plan takes stairs
            return blocked(world, villager);
        }
        // 1. room overhead, dug standing still
        Result r = clear(world, villager, clearance(), dig);
        if (r != null) {
            return r;
        }
        if (block.isEmpty()) {
            return blocked(world, villager);
        }
        if (fallingNear(world, villager)) {
            standStill(villager);
            doing = "esperando que caiga la grava";
            return Result.RUNNING;
        }
        // 2. still and centred over its own cell
        Vec3d center = Vec3d.ofBottomCenter(from);
        double ox = villager.getX() - center.x;
        double oz = villager.getZ() - center.z;
        boolean off = ox * ox + oz * oz > 0.0625;
        boolean moving = villager.getVelocity().horizontalLengthSquared() > 1.0E-4;
        if ((off || moving) && ++centerTicks < CENTER_TICKS) {
            phase = Phase.CENTER;
            doing = "se centra para subir";
            if (off) {
                villager.getNavigation().stop();
                villager.getMoveControl().moveTo(center.x, villager.getY(), center.z, 0.25);
            } else {
                standStill(villager);
            }
            return Result.RUNNING;
        }
        // 3. jump; the block goes in at the top (RISE)
        centerTicks = 0;
        standStill(villager);
        Hands.aim(villager, Vec3d.ofCenter(from.down()).add(0, 0.5, 0));
        villager.getJumpControl().setActive();
        jumpAt = now;
        phase = Phase.RISE;
        acted = true;
        doing = "salta y pone un bloque bajo los pies";
        return Result.RUNNING;
    }

    /** Up a ladder or vines: press into it and hold jump, one level at a time. */
    private Result climb(ServerWorld world, VillagerEntityMCA villager, boolean dig) {
        BlockPos ceiling = from.up(2);
        if (!open(world, ceiling)) {
            if (villager.isClimbing()) {
                // the ladder runs into rock: hold on (pressing up) and dig through it
                villager.getJumpControl().setActive();
            }
            Result r = clear(world, villager, clearance(), dig);
            if (r != null) {
                return r;
            }
        }
        phase = Phase.WALK;
        doing = "subiendo por la escalera de mano";
        villager.getNavigation().stop();
        BlockState rung = world.getBlockState(ladderOrVine(world.getBlockState(from)) ? from : from.up());
        Vec3d target = Vec3d.ofBottomCenter(from);
        if (rung.getBlock() instanceof LadderBlock && rung.contains(Properties.HORIZONTAL_FACING)) {
            // into the wall the ladder hangs on: that is what holds it up
            target = target.add(Vec3d.of(rung.get(Properties.HORIZONTAL_FACING).getOpposite().getVector()).multiply(0.45));
        }
        villager.getMoveControl().moveTo(target.x, from.getY() + 1, target.z, 0.3);
        villager.getJumpControl().setActive();
        return Result.RUNNING; // needsPlan() takes it up one level at a time; stalled() ends a ladder that leads nowhere
    }

    // ------------------------------------------------------------------ the little things

    /** A ladder or vines it can climb (not BlockTags.CLIMBABLE: hanging cave and weeping vines lead only to a ceiling). */
    private static boolean ladderOrVine(BlockState state) {
        Block block = state.getBlock();
        return block instanceof LadderBlock || block instanceof VineBlock || block instanceof TwistingVinesBlock || block instanceof TwistingVinesPlantBlock;
    }

    /**
     * A ladder or vines worth climbing from {@code feet}: they go on over its head, or it stands on the top rung with a
     * floor beside it to step off onto. A ladder that ends under more open shaft leads nowhere a climb can stop.
     */
    private static boolean climbFrom(World world, BlockPos feet) {
        if (ladderOrVine(world.getBlockState(feet.up()))) {
            return true;
        }
        if (!ladderOrVine(world.getBlockState(feet))) {
            return false;
        }
        for (Direction side : Direction.Type.HORIZONTAL) {
            BlockPos next = feet.offset(side);
            if (Mining.standable(world, next) && open(world, next.up()) && open(world, next.up(2))) {
                return true;
            }
        }
        return false;
    }

    /** The body fits through here (a ladder counts: it is climbed, never dug). */
    private static boolean open(World world, BlockPos pos) {
        return Mining.passable(world, pos) || world.getBlockState(pos).getBlock() instanceof LadderBlock;
    }

    /** Sand or gravel falling right over it or the way. */
    private static boolean fallingNear(ServerWorld world, VillagerEntityMCA villager) {
        return !world.getEntitiesByClass(FallingBlockEntity.class, villager.getBoundingBox().stretch(0, 6, 0).expand(1.0), Entity::isAlive).isEmpty();
    }

    /** Natural rock worth one more dig for room: never its own or Agua's seal, never next to water or lava it saw. */
    private static boolean worthDigging(ServerWorld world, VillagerEntityMCA villager, BlockPos pos, boolean dig) {
        if (!dig || open(world, pos)) {
            return false;
        }
        VisualMemory memory = Retina.memory(villager);
        return Mining.mayDig(world, pos, world.getBlockState(pos)) && !Mining.lavaSeen(memory, pos) && !Agua.seenNear(memory, pos)
                && Obras.kind(world, villager, pos) == Obras.Kind.NATURE;
    }

    /** Moves the body toward the edge of the block it stands on and holds it there, the edge guard of sneaking. */
    private void edgeToward(VillagerEntityMCA villager, BlockPos feet, @Nullable Direction direction) {
        if (direction == null) {
            return;
        }
        villager.setSneaking(true);
        villager.getNavigation().stop();
        Vec3d center = Vec3d.ofBottomCenter(feet);
        Vec3d edge = center.add(Vec3d.of(direction.getVector()).multiply(0.72));
        villager.getMoveControl().moveTo(edge.x, edge.y, edge.z, 0.2);
        double along = villager.getPos().subtract(center).dotProduct(Vec3d.of(direction.getVector()));
        if (along > 0.78) {
            Vec3d v = villager.getVelocity();
            Vec3d axis = Vec3d.of(direction.getVector());
            double out = v.dotProduct(axis);
            if (out > 0) {
                villager.setVelocity(v.subtract(axis.multiply(out)));
            }
            Vec3d back = villager.getPos().subtract(axis.multiply(along - 0.78));
            villager.setPosition(back.x, villager.getY(), back.z);
        }
    }

    private void afterBreak(ServerWorld world, VillagerEntityMCA villager, BlockPos cell) {
        acted = true;
        Hands.collect(world, villager, cell, 2.5);
        justBroken = cell.toImmutable();
        plugTicks = WATCH_TICKS;
        if (world.getBlockState(cell.up()).getBlock() instanceof FallingBlock) {
            settleTicks = SETTLE_TICKS; // what sat on it comes down next: let it land before going on
        }
        // look into the hole: what was behind it is now in view
        Retina.peek(world, villager, cell);
    }

    /** Right after opening a hole the eye checks it; lava pouring in gets a block slapped on it. */
    private boolean plugLava(ServerWorld world, VillagerEntityMCA villager) {
        if (justBroken == null) {
            return false;
        }
        if (plugTicks-- <= 0) {
            justBroken = null;
            return false;
        }
        VisualMemory memory = Retina.memory(villager);
        BlockPos hole = justBroken;
        boolean lavaHere = world.getFluidState(hole).isIn(FluidTags.LAVA);
        boolean lava = lavaHere || Mining.lavaSeen(memory, hole);
        if (lava && !lavaHere && Agua.body(villager).contains(hole)) {
            // it stands in that hole and only remembers lava near it: if lava comes, heat or burning moves it
            justBroken = null;
            return false;
        }
        // water pouring into the hole it just opened, underground: plug it like lava, it is only less deadly
        // (only when a spring opened in it: running water is walked through, and plugging it would only bring it
        // back when the hole is dug again; the spring it runs from is Agua's business)
        boolean water = !lava && Agua.source(world, hole) && !world.isSkyVisible(hole) && !Agua.body(villager).contains(hole);
        if (!lava && !water) {
            return false;
        }
        doing = lava ? "tapando lava" : "tapando el agua";
        ItemStack block = Hands.buildingBlock(villager);
        BlockPos plug = lavaHere || water || Mining.passable(world, hole) ? hole : null;
        if (plug != null && !block.isEmpty() && Hands.place(world, villager, plug, block)) {
            justBroken = null;
            reset(world, villager);
            return true;
        }
        if (water) {
            // nothing to plug it with: the water is no danger, the way just gets wet
            justBroken = null;
            return false;
        }
        // could not plug it: step back the way it came
        retreat(world, villager);
        justBroken = null;
        return true;
    }

    /** Walks through running water like a player does, even against the current. */
    private static void wade(VillagerEntityMCA villager, Vec3d target) {
        double dx = target.x - villager.getX();
        double dz = target.z - villager.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 0.15) {
            return;
        }
        villager.addVelocity(dx / d * WADE, 0, dz / d * WADE);
    }

    private Result retreat(ServerWorld world, VillagerEntityMCA villager) {
        doing = "retrocediendo";
        reset(world, villager);
        BlockPos feet = villager.getBlockPos();
        // its own pillar blocks, Agua's seals and fallen gravel are no way back
        while (!safeTrail.isEmpty() && (safeTrail.peekLast().equals(feet)
                || !Mining.passable(world, safeTrail.peekLast()) || !Mining.passable(world, safeTrail.peekLast().up()))) {
            safeTrail.removeLast();
        }
        BlockPos back = safeTrail.peekLast();
        if (back != null) {
            Vec3d target = Vec3d.ofBottomCenter(back);
            villager.getMoveControl().moveTo(target.x, target.y, target.z, 0.9);
            if (villager.isTouchingWater()) {
                wade(villager, target);
            }
        }
        return Result.BLOCKED;
    }

    private static int dropDepth(ServerWorld world, BlockPos floor) {
        int depth = 0;
        BlockPos cursor = floor;
        while (depth < 6 && !Mining.standable(world, cursor)) {
            cursor = cursor.down();
            depth++;
        }
        return depth;
    }
}
