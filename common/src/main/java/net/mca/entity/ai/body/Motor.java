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
import net.minecraft.entity.ai.pathing.Path;
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
 * Careful like a player at a drop: it never walks off one that would hurt (more than three blocks with no water at
 * the bottom), it crouches ({@link Postura}, "borde") while it works at the edge of one, and it bridges crouched
 * ("puente"), backing out over the gap facing the way it came and setting each block against the side of the one
 * under its feet. Before it bridges a real gap it looks for a walking way round ({@link #DETOUR_RATIO}) and takes
 * that when it is not much longer.
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
    /**
     * Other ways tried toward the same goal after a step toward it failed, before BLOCKED is reported: a hop onto what
     * stops it, then a step round it to either side.
     */
    private static final int ALTERNATIVES = 3;
    /**
     * Failures toward the same goal (within {@link #SAME_GOAL} blocks of it) less than this many ticks apart count as the
     * same failing step: longer than a step may take ({@link #HARD_TIMEOUT}).
     */
    private static final int REPEAT_WINDOW = 2400;
    private static final double SAME_GOAL = 3.0;
    /** After this many failures toward the same goal it does not try again for {@link #REFUSE_TICKS}: BLOCKED at once. */
    private static final int MAX_REPEATS = 8;
    private static final int REFUSE_TICKS = 100;

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

    /** A way round a gap is walked instead of bridging when it is at most this many times the straight line... */
    private static final double DETOUR_RATIO = 2.5;
    /** ...and no longer than this many blocks. */
    private static final int MAX_DETOUR = 40;
    /** Ticks before it looks again for a way round toward (about) the same goal. */
    private static final int DETOUR_RECHECK = 200;
    /** A fall of more than this many blocks onto something hard hurts (a player takes damage past three). */
    public static final int SAFE_DROP = 3;

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
    /** The way round a gap it is walking instead of bridging, node by node. */
    @Nullable
    private Path detour;
    @Nullable
    private BlockPos detourGoal;
    private int detourTicks;
    @Nullable
    private BlockPos detourCheckedFor;
    private long detourCheckedAt = Long.MIN_VALUE / 2;
    /** This step's floor is a block it set itself over the gap: it backs onto it crouched. */
    private boolean bridged;
    /** The crouch this tick asks for: over a gap it is bridging, at the edge of a drop it is working by. */
    private boolean wantPuente;
    private boolean wantBorde;
    /** The goal steps toward it kept failing for, from about where, how often, and when last. */
    @Nullable
    private BlockPos failGoal;
    private int failCount;
    private long failAt = Long.MIN_VALUE / 2;
    /** The other way the next plan tries: 0 the usual one, 1 a hop onto what is in the way, 2 and 3 a step round it. */
    private int alt;
    /** This call is a pillar step straight up ({@link #pillarStep}): never a stair sideways, never another way. */
    private boolean straightUp;

    public String doing() {
        return doing;
    }

    public Hands.Breaker breaker() {
        return breaker;
    }

    public void reset(ServerWorld world, VillagerEntityMCA villager) {
        breaker.reset(world, villager);
        endStep();
        alt = 0;
        lastFeet = null;
        settleTicks = 0;
        airTicks = 0;
        detour = null;
        detourGoal = null;
        Postura.agachar(villager, "puente", false);
        Postura.agachar(villager, "borde", false);
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
        bridged = false;
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
        wantPuente = false;
        wantBorde = false;
        Result r = stepBody(world, villager, goal, dig, build);
        if (r == Result.RUNNING && from != null && phase == Phase.PREPARE && kind != Kind.PILLAR && kind != Kind.CLIMB && edgeNear(world, from)) {
            // working (digging, setting a block, centring) right by a drop that would hurt: crouched, like a player
            wantBorde = true;
        }
        if (r != Result.RUNNING) {
            wantPuente = false;
            wantBorde = false;
        }
        Postura.agachar(villager, "puente", wantPuente);
        Postura.agachar(villager, "borde", wantBorde);
        return r;
    }

    private Result stepBody(ServerWorld world, VillagerEntityMCA villager, BlockPos goal, boolean dig, boolean build) {
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
                return blockedHard(world, villager);
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
                    return blockedHard(world, villager);
                }
                if (r == Hands.Breaker.DONE) {
                    afterBreak(world, villager, head);
                }
                return Result.RUNNING;
            }
        }
        if (feet.equals(goal) && !rising && onCell(world, villager, feet)) {
            // never mid-jump, never from the lip with its centre over the gap it is bridging
            endStep();
            detour = null;
            return Result.ARRIVED;
        }
        if (detour != null && !rising) {
            Result r = followDetour(world, villager, feet, goal);
            if (r != null) {
                return r;
            }
        }
        if (!settled && (from == null || phase == Phase.PREPARE || phase == Phase.CENTER)) {
            // falling or knocked into the air, not one of its own jumps: land first, plan after
            return ++airTicks > AIR_TIMEOUT ? blockedHard(world, villager) : Result.RUNNING;
        }
        if (settled) {
            airTicks = 0;
        }
        if (needsPlan(world, villager, feet, goal)) {
            if (from == null && refused(goal, now)) {
                // the same step failed over and over just now: the caller has to choose something else
                return Result.BLOCKED;
            }
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

    /**
     * The step failed. The first few times toward the same goal another way is tried (a hop onto what is in the way,
     * a step round it to one side, then the other) before BLOCKED is reported; a step that keeps failing toward the
     * same goal is not tried over and over: BLOCKED at once for a moment.
     */
    private Result blocked(ServerWorld world, VillagerEntityMCA villager) {
        BlockPos goal = planGoal;
        long now = world.getTime();
        boolean again = goal != null && failGoal != null && goal.isWithinDistance(failGoal, SAME_GOAL) && now - failAt < REPEAT_WINDOW;
        failCount = again ? failCount + 1 : 1;
        failGoal = goal;
        failAt = now;
        boolean sideways = goal != null && from != null && (goal.getX() != from.getX() || goal.getZ() != from.getZ());
        reset(world, villager);
        if (goal != null && sideways && !straightUp && failCount <= ALTERNATIVES) {
            alt = failCount;
            doing = alt == 1 ? "prueba a saltar lo que le corta el paso" : "prueba a rodear lo que le corta el paso";
            return Result.RUNNING;
        }
        return Result.BLOCKED;
    }

    /** Water it cannot hold off, buried, held in the air, lava: no other way is tried from here, BLOCKED now. */
    private Result blockedHard(ServerWorld world, VillagerEntityMCA villager) {
        reset(world, villager);
        return Result.BLOCKED;
    }

    /** Toward this goal its steps kept failing just now: it does not try the same thing yet again. */
    private boolean refused(BlockPos goal, long now) {
        return failCount > MAX_REPEATS && failGoal != null && goal.isWithinDistance(failGoal, SAME_GOAL) && now - failAt < REFUSE_TICKS;
    }

    /**
     * One tick of going straight up one block on a block of its own under its feet (a pillar), as at a tall trunk:
     * never a stair to the side, never another way round. BLOCKED when it cannot.
     */
    public Result pillarStep(ServerWorld world, VillagerEntityMCA villager, BlockPos goal) {
        straightUp = true;
        try {
            return step(world, villager, goal, false, true);
        } finally {
            straightUp = false;
        }
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
        bridged = false;
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
        int other = alt;
        alt = 0;
        if (other > 0 && toward != null && alternative(world, villager, feet, goal, toward, other)) {
            return;
        }
        if (straightUp) {
            // a pillar or nothing: a stair would take it off the column it is climbing
            if (horizontal == 0 && dy > 0 && build && !Hands.buildingBlock(villager).isEmpty()
                    && pillarSafe(world, villager, feet, dig, Retina.memory(villager))) {
                kind = Kind.PILLAR;
                stand = feet.up();
            }
            return;
        }
        if (toward != null && horizontal > Math.abs(dy) && (walkable(world, feet.offset(toward)) || throughLeaves(world, villager, feet.offset(toward)))) {
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

    /**
     * Another way than the usual one after a step failed: 1 hops onto the block in the way when there is room over it
     * (falling back to the side steps), 2 and 3 step round it, to the side nearer the goal first. False when there is
     * no such way from here (the usual plan is made then).
     */
    private boolean alternative(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, BlockPos goal, Direction toward, int which) {
        BlockPos ahead = feet.offset(toward);
        if (which == 1 && Mining.standable(world, ahead) && walkable(world, ahead.up()) && open(world, feet.up(2))
                && !Mining.lavaSeen(Retina.memory(villager), ahead.up())) {
            kind = Kind.UP;
            dir = toward;
            stand = ahead.up();
            doing = "salta sobre lo que le corta el paso";
            return true;
        }
        Direction left = toward.rotateYCounterclockwise();
        Direction right = toward.rotateYClockwise();
        // the side the goal lies to first
        int lean = (goal.getX() - feet.getX()) * left.getOffsetX() + (goal.getZ() - feet.getZ()) * left.getOffsetZ();
        Direction first = lean >= 0 ? left : right;
        Direction second = first == left ? right : left;
        Direction[] order = which == 3 ? new Direction[]{second, first} : new Direction[]{first, second};
        for (Direction side : order) {
            BlockPos cell = feet.offset(side);
            if (walkable(world, cell) && !Mining.lavaSeen(Retina.memory(villager), cell) && !Mining.heat(world, cell) && !hurts(world, cell.down())) {
                kind = Kind.LEVEL;
                dir = side;
                stand = cell;
                doing = "rodea lo que le corta el paso";
                return true;
            }
        }
        return false;
    }

    /** Open to walk into on its own feet, with a floor. */
    private static boolean walkable(World world, BlockPos cell) {
        return open(world, cell) && open(world, cell.up()) && Mining.standable(world, cell.down());
    }

    /** Walkable once the natural leaves in it (at the feet, the head or both) are clicked away. */
    private static boolean throughLeaves(ServerWorld world, VillagerEntityMCA villager, BlockPos cell) {
        boolean leafy = false;
        for (BlockPos c : new BlockPos[]{cell, cell.up()}) {
            if (Hands.naturalLeaf(world, villager, c)) {
                leafy = true;
            } else if (!open(world, c)) {
                return false;
            }
        }
        return leafy && Mining.standable(world, cell.down()) && !Mining.lavaSeen(Retina.memory(villager), cell);
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
            if (Hands.naturalLeaf(world, villager, cell) && !Mining.lavaSeen(memory, cell)) {
                cost += COST_DIG; // leaves over the step: by hand, pick or no pick
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
        if (!open(world, head) && !Hands.naturalLeaf(world, villager, head)
                && !(dig && Mining.mayDig(world, head, world.getBlockState(head)) && Obras.mayBreak(world, villager, head))) {
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
            // natural leaves across the way, over its head on a pillar or a climb: clicked away by hand like a player,
            // also on a step that may not dig (a persistent leaf, a player's, is a build like any other)
            boolean leaf = Hands.naturalLeaf(world, villager, cell);
            if (!dig && !leaf) {
                return blocked(world, villager);
            }
            BlockState state = world.getBlockState(cell);
            if (!leaf && !Mining.mayDig(world, cell, state) || Mining.lavaSeen(Retina.memory(villager), cell)) {
                return blockedHard(world, villager);
            }
            // still first: legs off, then the pick
            standStill(villager);
            doing = leaf ? "quita las hojas del paso" : "cavando";
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
        boolean overGap = kind == Kind.LEVEL && !stand.equals(from);
        if (overGap && placeTicks == 0 && !villager.isTouchingWater() && goAround(world, villager, ground)) {
            // a gap with a short walk round it: walk it, the blocks stay in the bag
            return Result.RUNNING;
        }
        if (!block.isEmpty() && world.getBlockState(ground).isReplaceable() && !lavaBelow) {
            doing = kind == Kind.UP ? "pone un escalón" : "haciendo un puente";
            if (overGap && !villager.isTouchingWater()) {
                // bridging is done crouched, from the first block: the edge guard holds it on the lip
                wantPuente = true;
                if (!villager.isSneaking()) {
                    standStill(villager);
                    return Result.RUNNING;
                }
            }
            if (Hands.place(world, villager, ground, block)) {
                acted = true;
                placeTicks = 0;
                bridged = overGap;
                return Result.RUNNING;
            }
            if (++placeTicks > PLACE_TICKS) {
                return blocked(world, villager);
            }
            if (overGap) {
                // the side face is not in sight yet: back out over the edge like a sneaking player, from the block it stands on
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
            // a drop it could not bridge and would not jump down: never off an edge that hurts
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
        villager.getNavigation().stop();
        doing = switch (kind) {
            case DOWN -> "bajando por la escalera";
            case UP -> "subiendo";
            default -> bridged ? "haciendo un puente" : "caminando";
        };
        if (kind == Kind.DOWN || dropOk) {
            // a step down it planned: crouched for someone else, it lets go of the edge for this one
            Postura.bajar(villager);
        }
        // steering also in the air: it never hits anything while off its feet
        Vec3d target = Vec3d.ofBottomCenter(stand);
        if (bridged && settled && !villager.isTouchingWater()) {
            // onto the block it just set, still crouched and facing back the way it came, like a player bridging
            wantPuente = true;
            backTo(villager, target, dir);
            return Result.RUNNING;
        }
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
        // 2b. a crown's leaves beside the column that the body would brush on the way up (it is never quite centred):
        // clicked away first, or the jump stops short under them and no block fits in
        BlockPos brush = leafInTheRise(world, villager);
        if (brush != null) {
            standStill(villager);
            doing = "quita las hojas del paso";
            int hit = breaker.tick(world, villager, brush);
            if (hit == Hands.Breaker.DONE) {
                afterBreak(world, villager, brush);
            }
            if (hit != Hands.Breaker.FAILED) {
                return Result.RUNNING;
            }
            // out of sight from here: the jump is tried as it is
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

    /**
     * Natural leaves the body's box would run into on a pillar jump (its box raised by the jump), in sight: the nearest
     * one, or null. The column itself is {@link #clearance()}'s; these are the cells beside it the box overlaps.
     */
    @Nullable
    private BlockPos leafInTheRise(ServerWorld world, VillagerEntityMCA villager) {
        net.minecraft.util.math.Box rise = villager.getBoundingBox().stretch(0, 1.3, 0).contract(1.0E-3);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos cell : BlockPos.iterate(BlockPos.ofFloored(rise.minX, rise.minY, rise.minZ), BlockPos.ofFloored(rise.maxX, rise.maxY, rise.maxZ))) {
            if (cell.getX() == from.getX() && cell.getZ() == from.getZ() || !Hands.naturalLeaf(world, villager, cell)) {
                continue;
            }
            double distance = villager.getEyePos().squaredDistanceTo(Vec3d.ofCenter(cell));
            if (distance < bestDistance && Retina.visibleFace(world, villager, cell, Retina.BLOCK_REACH) != null) {
                best = cell.toImmutable();
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Natural rock worth one more dig for room: never its own or Agua's seal, never next to water or lava it saw. */
    private static boolean worthDigging(ServerWorld world, VillagerEntityMCA villager, BlockPos pos, boolean dig) {
        if (open(world, pos)) {
            return false;
        }
        if (Hands.naturalLeaf(world, villager, pos)) {
            return true; // leaves its head hit on the jump: by hand, dig or no dig
        }
        if (!dig) {
            return false;
        }
        VisualMemory memory = Retina.memory(villager);
        return Mining.mayDig(world, pos, world.getBlockState(pos)) && !Mining.lavaSeen(memory, pos) && !Agua.seenNear(memory, pos)
                && Obras.kind(world, villager, pos) == Obras.Kind.NATURE;
    }

    /**
     * Backs the body out to the edge of the block it stands on, facing the way it came, and holds it there: crouched
     * the edge guard ({@link Postura#edge}) keeps the feet on the block, and this keeps it there even if it is not.
     */
    private void edgeToward(VillagerEntityMCA villager, BlockPos feet, @Nullable Direction direction) {
        if (direction == null) {
            return;
        }
        Vec3d center = Vec3d.ofBottomCenter(feet);
        Vec3d axis = Vec3d.of(direction.getVector());
        // the centre may go as far out as half its width past the edge: the back of its feet still on the block
        double limit = 0.5 + villager.getWidth() / 2.0 - 0.02;
        double along = villager.getPos().subtract(center).dotProduct(axis);
        Vec3d edge = center.add(axis.multiply(limit));
        if (villager.isTouchingWater()) {
            // wading: its legs, against the current
            villager.getNavigation().stop();
            villager.getMoveControl().moveTo(edge.x, edge.y, edge.z, 0.2);
        } else {
            wantPuente = true;
            backTo(villager, new Vec3d(edge.x, villager.getY(), edge.z), direction);
        }
        if (along > limit) {
            Vec3d v = villager.getVelocity();
            double out = v.dotProduct(axis);
            if (out > 0) {
                villager.setVelocity(v.subtract(axis.multiply(out)));
            }
            Vec3d back = villager.getPos().subtract(axis.multiply(along - limit));
            villager.setPosition(back.x, villager.getY(), back.z);
        }
    }

    /**
     * Moves the body toward {@code target} at a sneaking player's pace by its own legs (the velocity, not the mob's
     * move control, which would turn it round), facing back against {@code going}, as a player bridging walks backwards.
     */
    private static void backTo(VillagerEntityMCA villager, Vec3d target, @Nullable Direction going) {
        standStill(villager);
        if (going != null) {
            float yaw = going.getOpposite().asRotation();
            villager.setYaw(yaw);
            villager.setBodyYaw(yaw);
            villager.setHeadYaw(yaw);
        }
        double dx = target.x - villager.getX();
        double dz = target.z - villager.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        Vec3d v = villager.getVelocity();
        if (d < 0.02) {
            villager.setVelocity(0, v.y, 0);
            return;
        }
        double speed = Math.min(Postura.SNEAK_SPEED, d);
        villager.setVelocity(dx / d * speed, v.y, dz / d * speed);
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

    // ------------------------------------------------------------------ gaps: round them or over them

    /**
     * Before bridging the gap under {@code ground}: a walking way to the goal that is not much longer than the straight
     * line (at most {@link #DETOUR_RATIO} times it and {@link #MAX_DETOUR} blocks), dry all the way, found by the same
     * pathfinder its legs use. True when it takes it. Looked for once per gap and goal, from natural ground only: half
     * way over its own bridge it goes on.
     */
    private boolean goAround(ServerWorld world, VillagerEntityMCA villager, BlockPos ground) {
        if (planGoal == null || from == null || dropDepth(world, ground) < 2 && !Agua.water(world, ground)) {
            return false; // a one-block dip is stepped in and out of, or bridged on the spot
        }
        if (Obras.kind(world, villager, from.down()) == Obras.Kind.OWN) {
            return false;
        }
        long now = world.getTime();
        if (detourCheckedFor != null && now - detourCheckedAt < DETOUR_RECHECK && detourCheckedFor.isWithinDistance(planGoal, 4.0)) {
            return false;
        }
        detourCheckedFor = planGoal;
        detourCheckedAt = now;
        double straight = Math.sqrt(from.getSquaredDistance(planGoal));
        if (straight < 2.0) {
            return false;
        }
        Path path = villager.getNavigation().findPathTo(planGoal, 0);
        if (path == null || !path.reachesTarget() || path.getLength() < 2) {
            return false;
        }
        int length = path.getLength();
        if (length > MAX_DETOUR || length > straight * DETOUR_RATIO) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            BlockPos node = path.getNodePos(i);
            if (Agua.water(world, node) || Agua.water(world, node.down()) || Mining.heat(world, node)) {
                return false; // a way round that swims is no walk
            }
        }
        net.mca.MCA.LOGGER.info("[Tropeles] {} rodea el hueco hacia {}: {} bloques a pie frente a {} en línea recta",
                villager.getName().getString(), planGoal.toShortString(), length, String.format("%.1f", straight));
        detour = path;
        detourGoal = planGoal;
        detourTicks = 0;
        endStep();
        return true;
    }

    /** One tick along the way round. Null when it is over (walked, or given up) and the steps take over again. */
    @Nullable
    private Result followDetour(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, BlockPos goal) {
        Path path = detour;
        if (path == null || detourGoal == null || !detourGoal.isWithinDistance(goal, 3.0) || ++detourTicks > path.getLength() * 30 + 100) {
            detour = null;
            return null;
        }
        while (!path.isFinished()) {
            BlockPos node = path.getCurrentNodePos();
            if (feet.getX() == node.getX() && feet.getZ() == node.getZ() && Math.abs(feet.getY() - node.getY()) <= 1 && settled(villager)) {
                path.next();
            } else {
                break;
            }
        }
        if (path.isFinished()) {
            detour = null;
            endStep();
            return null;
        }
        BlockPos node = path.getCurrentNodePos();
        if (node.getY() < feet.getY()) {
            Postura.bajar(villager);
        }
        doing = "rodeando";
        villager.getNavigation().stop();
        Vec3d target = Vec3d.ofBottomCenter(node);
        villager.getMoveControl().moveTo(target.x, target.y, target.z, 0.6);
        acted = true;
        return Result.RUNNING;
    }

    /** Walking it now round a gap instead of over it. */
    public boolean goingAround() {
        return detour != null;
    }

    /** A drop that would hurt right beside {@code feet}: open to step into, nothing to land on for more than {@link #SAFE_DROP} blocks. */
    public static boolean edgeNear(World world, BlockPos feet) {
        for (Direction side : Direction.Type.HORIZONTAL) {
            BlockPos next = feet.offset(side);
            if (open(world, next) && open(world, next.up()) && hurts(world, next.down())) {
                return true;
            }
        }
        return false;
    }

    /** Walking off into {@code floor} (the cell under where the feet would be) falls far enough to hurt. */
    public static boolean hurts(World world, BlockPos floor) {
        BlockPos cursor = floor;
        for (int depth = 0; depth <= SAFE_DROP; depth++) {
            if (Mining.standable(world, cursor) || Agua.water(world, cursor) || world.getBlockState(cursor).getBlock() instanceof LadderBlock) {
                return false;
            }
            cursor = cursor.down();
        }
        // more than SAFE_DROP blocks of nothing: whatever is under that hurts unless it is water
        for (int depth = SAFE_DROP + 1; depth < 64; depth++) {
            if (Agua.water(world, cursor)) {
                return false;
            }
            if (Mining.standable(world, cursor) || !world.getBlockState(cursor).getFluidState().isEmpty()) {
                return true;
            }
            cursor = cursor.down();
        }
        return true;
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
