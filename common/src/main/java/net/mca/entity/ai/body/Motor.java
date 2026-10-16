package net.mca.entity.ai.body;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Moves a villager one block at a time the way a player gets through terrain: walks when the way is
 * open, cuts a two-high tunnel through rock, digs stairs down and up, bridges a gap by placing a block
 * against the side of the one it stands on (edging out like a sneaking player), and pillars straight
 * up by jumping and placing under its feet. Every block it breaks or places goes through {@link Hands},
 * so it only touches what its eye can put a crosshair on within reach.
 */
public final class Motor {
    public enum Result {RUNNING, ARRIVED, BLOCKED}

    private static final int STEP_TIMEOUT = 240;
    /**
     * Extra push per tick when walking in water. A mob's legs give it 0.3 x 0.02 there while a current pushes
     * with 0.014, so it could never walk upstream; a player's legs give about 0.02 and wade against it.
     */
    private static final double WADE = 0.014;

    private final Hands.Breaker breaker = new Hands.Breaker();
    private final Deque<BlockPos> safeTrail = new ArrayDeque<>();
    @Nullable
    private BlockPos stand;
    @Nullable
    private Direction dir;
    private Kind kind = Kind.LEVEL;
    private int stepTicks;
    @Nullable
    private BlockPos pillarFrom;
    @Nullable
    private BlockPos justBroken;
    private int plugTicks;
    private String doing = "";

    private enum Kind {LEVEL, DOWN, UP, PILLAR}

    public String doing() {
        return doing;
    }

    public Hands.Breaker breaker() {
        return breaker;
    }

    public void reset(ServerWorld world, VillagerEntityMCA villager) {
        breaker.reset(world, villager);
        stand = null;
        dir = null;
        stepTicks = 0;
        pillarFrom = null;
        villager.setSneaking(false);
    }

    /**
     * One tick of getting to {@code goal}. With {@code dig} it may cut natural rock; with {@code build} it may
     * spend building blocks from the bag on bridges and pillars.
     */
    public Result step(ServerWorld world, VillagerEntityMCA villager, BlockPos goal, boolean dig, boolean build) {
        villager.getBrain().forget(net.minecraft.entity.ai.brain.MemoryModuleType.WALK_TARGET);
        BlockPos feet = villager.getBlockPos();
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
        if (plugLava(world, villager)) {
            return Result.RUNNING;
        }
        // water: shut it out of the way before going on, and climb out of it when swimming in a hole
        if (keepDry(world, villager, stand == null ? java.util.List.of() : route(feet))) {
            return Result.RUNNING;
        }
        if (stand != null && Agua.overwhelmed(villager, world.getTime()) && route(feet).stream().anyMatch(cell -> Agua.water(world, cell))) {
            // more water than blocks to stop it: this way is drowned, take another
            reset(world, villager);
            return Result.BLOCKED;
        }
        if (villager.isSubmergedInWater()) {
            villager.getJumpControl().setActive();
            if (villager.getAir() < villager.getMaxAir() / 3 && !safeTrail.isEmpty()) {
                // out of breath and nothing more to block: back to the last dry spot
                return retreat(world, villager);
            }
        }
        if (villager.isInsideWall() && dig) {
            BlockPos head = BlockPos.ofFloored(villager.getEyePos());
            if (!Mining.passable(world, head) && Mining.mayDig(world, head, world.getBlockState(head))) {
                int r = breaker.tick(world, villager, head);
                if (r == Hands.Breaker.DONE) {
                    afterBreak(world, villager, head);
                }
                return Result.RUNNING;
            }
        }
        if (feet.equals(goal) || feet.isWithinDistance(goal, 0.5)) {
            villager.setSneaking(false);
            return Result.ARRIVED;
        }

        boolean footing = villager.isOnGround() || villager.isTouchingWater();
        if (stand == null || feet.equals(stand) && footing && kind != Kind.PILLAR
                || kind == Kind.PILLAR && pillarFrom != null && feet.getY() > pillarFrom.getY() && villager.isOnGround()) {
            plan(world, villager, feet, goal, build);
            stepTicks = 0;
            if (stand == null) {
                return Result.BLOCKED;
            }
        }
        if (++stepTicks > STEP_TIMEOUT) {
            reset(world, villager);
            return Result.BLOCKED;
        }

        // 1. clear the cells the body will pass through, top first so each lower one comes into view
        for (BlockPos cell : clearance(feet)) {
            if (Mining.passable(world, cell)) {
                continue;
            }
            if (!dig) {
                return Result.BLOCKED;
            }
            if (!Mining.mayDig(world, cell, world.getBlockState(cell)) || Mining.lavaSeen(Retina.memory(villager), cell)) {
                reset(world, villager);
                return Result.BLOCKED;
            }
            doing = "cavando";
            int r = breaker.tick(world, villager, cell);
            if (r == Hands.Breaker.FAILED) {
                // not in reach or not visible from here: get closer by the way already open
                reset(world, villager);
                return Result.BLOCKED;
            }
            if (r == Hands.Breaker.DONE) {
                afterBreak(world, villager, cell);
            }
            return Result.RUNNING;
        }

        // 2. make sure there is something to stand on
        if (kind == Kind.PILLAR) {
            return pillar(world, villager, feet, build);
        }
        BlockPos floor = stand.down();
        if (!Mining.standable(world, floor)) {
            boolean lavaBelow = world.getFluidState(floor).isIn(FluidTags.LAVA) || Mining.lavaSeen(Retina.memory(villager), floor);
            ItemStack block = build ? Hands.buildingBlock(villager) : ItemStack.EMPTY;
            if (!block.isEmpty()) {
                doing = "haciendo un puente";
                if (Hands.place(world, villager, floor, block)) {
                    villager.setSneaking(false);
                    return Result.RUNNING;
                }
                if (kind != Kind.DOWN) {
                    // the side face is not in sight yet: edge out like a sneaking player
                    edgeToward(villager, feet, dir);
                    return Result.RUNNING;
                }
                // a step down with nothing to set a block against: only if the drop is short
            }
            if (lavaBelow || dropDepth(world, floor) > 2) {
                reset(world, villager);
                return Result.BLOCKED;
            }
        }

        // 3. walk onto the prepared cell
        villager.setSneaking(false);
        villager.getNavigation().stop();
        doing = switch (kind) {
            case DOWN -> "bajando por la escalera";
            case UP -> "subiendo";
            default -> "caminando";
        };
        Vec3d target = Vec3d.ofBottomCenter(stand);
        villager.getMoveControl().moveTo(target.x, target.y, target.z, 0.6);
        if (villager.isTouchingWater()) {
            wade(villager, target);
        }
        if (kind == Kind.UP && (villager.isOnGround() || villager.isTouchingWater()) && villager.horizontalCollision) {
            villager.getJumpControl().setActive();
        }
        return Result.RUNNING;
    }

    /**
     * Blocks water off the body and the way ahead (see {@link Agua}). True while busy with it this tick. Callers
     * that work in place (hitting an ore in front of them) use it too.
     */
    public boolean keepDry(ServerWorld world, VillagerEntityMCA villager, java.util.Collection<BlockPos> path) {
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
        return plugLava(world, villager) || keepDry(world, villager, java.util.List.of());
    }

    /** It broke {@code cell} by hand outside of a step: picks up the drop and watches the hole for a moment. */
    public void broke(ServerWorld world, VillagerEntityMCA villager, BlockPos cell) {
        afterBreak(world, villager, cell);
    }

    /** The cells the body passes through on the step being made. */
    private java.util.List<BlockPos> route(BlockPos feet) {
        java.util.List<BlockPos> cells = new java.util.ArrayList<>(java.util.List.of(clearance(feet)));
        if (stand != null && !cells.contains(stand)) {
            cells.add(stand);
        }
        return cells;
    }

    private void plan(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, BlockPos goal, boolean build) {
        int dx = goal.getX() - feet.getX();
        int dy = goal.getY() - feet.getY();
        int dz = goal.getZ() - feet.getZ();
        Direction horizontal = null;
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            horizontal = dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (dz != 0) {
            horizontal = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        pillarFrom = null;
        if (horizontal == null) {
            if (dy > 0) {
                kind = Kind.PILLAR;
                dir = villager.getHorizontalFacing();
                stand = feet.up();
                return;
            }
            // straight below: never dig under your own feet, go down a staircase instead
            horizontal = villager.getHorizontalFacing();
        }
        dir = horizontal;
        if (dy < 0) {
            kind = Kind.DOWN;
            stand = feet.offset(horizontal).down();
        } else if (dy > 0 && Math.abs(dx) + Math.abs(dz) <= 1 && !Mining.standable(world, feet.offset(horizontal))) {
            kind = Kind.PILLAR;
            stand = feet.up();
        } else if (dy > 0) {
            kind = Kind.UP;
            stand = feet.offset(horizontal).up();
        } else {
            kind = Kind.LEVEL;
            stand = feet.offset(horizontal);
        }
    }

    private BlockPos[] clearance(BlockPos feet) {
        return switch (kind) {
            case LEVEL -> new BlockPos[]{stand.up(), stand};
            case DOWN -> new BlockPos[]{stand.up(2), stand.up(), stand};
            case UP -> new BlockPos[]{feet.up(2), stand.up(), stand};
            case PILLAR -> new BlockPos[]{feet.up(2)};
        };
    }

    private Result pillar(ServerWorld world, VillagerEntityMCA villager, BlockPos feet, boolean build) {
        ItemStack block = build ? Hands.buildingBlock(villager) : ItemStack.EMPTY;
        if (block.isEmpty()) {
            reset(world, villager);
            return Result.BLOCKED;
        }
        doing = "subiendo con bloques bajo los pies";
        villager.getNavigation().stop();
        if (pillarFrom == null) {
            if (!villager.isOnGround()) {
                return Result.RUNNING;
            }
            pillarFrom = feet.toImmutable();
            Vec3d center = Vec3d.ofBottomCenter(feet);
            villager.getMoveControl().moveTo(center.x, center.y, center.z, 0.3);
            villager.getJumpControl().setActive();
            return Result.RUNNING;
        }
        if (villager.getY() >= pillarFrom.getY() + 1.0 && Mining.passable(world, pillarFrom)) {
            Hands.aim(villager, Vec3d.ofCenter(pillarFrom.down()).add(0, 0.5, 0));
            if (Hands.place(world, villager, pillarFrom, block)) {
                stand = pillarFrom.up();
            }
            return Result.RUNNING;
        }
        if (villager.isOnGround() && villager.getBlockPos().equals(pillarFrom)) {
            // landed without placing: jump again
            villager.getJumpControl().setActive();
        }
        return Result.RUNNING;
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
        Hands.collect(world, villager, cell, 2.5);
        justBroken = cell.toImmutable();
        plugTicks = 12;
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
        boolean lava = world.getFluidState(hole).isIn(FluidTags.LAVA) || Mining.lavaSeen(memory, hole);
        // water pouring into the hole it just opened, underground: plug it like lava, it is only less deadly
        // (only when a spring opened in it: running water is walked through, and plugging it would only bring it
        // back when the hole is dug again; the spring it runs from is Agua's business)
        boolean water = !lava && Agua.source(world, hole) && !world.isSkyVisible(hole) && !Agua.body(villager).contains(hole);
        if (!lava && !water) {
            return false;
        }
        doing = lava ? "tapando lava" : "tapando el agua";
        ItemStack block = Hands.buildingBlock(villager);
        BlockPos plug = world.getFluidState(hole).isIn(FluidTags.LAVA) || water || Mining.passable(world, hole) ? hole : null;
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
        while (!safeTrail.isEmpty() && safeTrail.peekLast().equals(feet)) {
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
