package net.mca.entity.ai.brain.tasks.chore;

import com.google.common.collect.ImmutableMap;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.DistressCalls;
import net.mca.entity.ai.Threats;
import net.mca.entity.ai.WorkTools;
import net.mca.entity.ai.body.Hands;
import net.mca.entity.ai.body.Mining;
import net.mca.entity.ai.body.Motor;
import net.mca.entity.ai.relationship.AgeState;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.mca.util.InventoryUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Mining by sight. The miner only goes for ore its eye has actually seen (the exact block, as Jade would
 * name it) and only hits it once the crosshair can land on it within a player's reach. To get there it
 * walks, tunnels, digs stairs, bridges gaps and pillars up, all through {@link Motor}. With nothing in view it
 * goes down a staircase to diamond depth and branch mines, so every wall it opens passes in front of its eyes.
 * In danger it walls itself in, calls out its coordinates, or digs down and caps the hole (from the earlier
 * version of this chore).
 */
public class ProspectingTask extends AbstractChoreTask {
    private enum Phase {
        SEEK, SHELTER_DIG, SHELTER_ENTER, SHELTER_SEAL, ESCAPE, RECOVER
    }

    /** Below this the deepslate lava lakes start. */
    public static final int DEEPSLATE_DEPTH = -50;
    private static final int ORE_SEARCH_RADIUS = 32;

    private final Motor motor = new Motor();
    private final Deque<BlockPos> plan = new ArrayDeque<>();
    private final Map<Long, Long> ignored = new LinkedHashMap<>();
    private Phase phase = Phase.SEEK;
    private BlockPos ore;
    private int oreFails;
    private int tunnelDir;
    private boolean branchLeft;
    private BlockPos pocket;
    private BlockPos sealPos;
    private BlockPos escapeOrigin;
    private int escapeLeft;
    private int calmTicks;
    private long lastCry;
    private long lastOreAnnounce;
    private boolean announcedAny;
    private boolean warnedFull;

    public ProspectingTask() {
        super(ImmutableMap.of(
                MemoryModuleType.LOOK_TARGET, MemoryModuleState.REGISTERED,
                MemoryModuleType.WALK_TARGET, MemoryModuleState.REGISTERED
        ), 12000);
    }

    @Override
    protected boolean allowsWorkWhileAfraid() {
        return true;
    }

    @Override
    protected boolean shouldRun(ServerWorld world, VillagerEntityMCA villager) {
        return villager.getVillagerBrain().getCurrentJob() == Chore.PROSPECT && super.shouldRun(world, villager);
    }

    @Override
    protected boolean shouldKeepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        return shouldRun(world, villager);
    }

    @Override
    protected void run(ServerWorld world, VillagerEntityMCA villager, long time) {
        super.run(world, villager, time);
        if (tooYoung(villager)) {
            abandonJobWithMessage("chore.mining.tooyoung");
            return;
        }
        if (!equipPick(villager)) {
            abandonJobWithMessage("chore.mining.nopick");
            return;
        }
        tunnelDir = villager.getHorizontalFacing().getHorizontal();
        Retina.lookAll(world, villager);
    }

    @Override
    protected void finishRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        motor.reset(world, villager);
        plan.clear();
        ItemStack stack = villager.getMainHandStack();
        if (WorkTools.isPickaxe(stack)) {
            villager.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    @Override
    protected void keepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        super.keepRunning(world, villager, time);
        if (this.villager == null) {
            this.villager = villager;
        }
        if (villager.getVillagerBrain().getCurrentJob() != Chore.PROSPECT) {
            return;
        }
        if (!equipPick(villager)) {
            abandonJobWithMessage("chore.mining.nopick");
            return;
        }
        if ((villager.age & 1) == 0) {
            Retina.look(world, villager);
        }
        if (bagFull(villager)) {
            if (!warnedFull) {
                warnedFull = true;
                abandonJobWithMessage("chore.mining.full");
            }
            return;
        }
        warnedFull = false;

        if (phase == Phase.SEEK) {
            MobEntity threat = Threats.nearest(world, villager.getBlockPos(), 10, villager, null);
            if (threat != null) {
                beginDanger(world, threat);
            }
        }

        switch (phase) {
            case SEEK -> seek(world, villager);
            case SHELTER_DIG -> shelterDig(world, villager);
            case SHELTER_ENTER -> shelterEnter(world, villager);
            case SHELTER_SEAL -> shelterSeal(world, villager);
            case ESCAPE -> escape(world, villager);
            case RECOVER -> recover(world, villager);
        }
    }

    /** One tick of the mining loop on its own, without a job assigner; used by the in-game checks. */
    public void practice(ServerWorld world, VillagerEntityMCA villager) {
        this.villager = villager;
        if (!equipPick(villager)) {
            return;
        }
        Retina.look(world, villager);
        seek(world, villager);
    }

    // ------------------------------------------------------------------ mining

    private void seek(ServerWorld world, VillagerEntityMCA villager) {
        VisualMemory memory = Retina.memory(villager);
        long now = world.getTime();
        maybePlaceTorch(world, villager);

        if (ore == null || !stillOre(memory, ore)) {
            ore = chooseOre(world, villager, memory);
            oreFails = 0;
        }
        if (ore != null) {
            BlockState state = memory.stateAt(ore);
            String name = state == null ? "?" : state.getBlock().getName().getString();
            BlockPos feet = villager.getBlockPos();
            boolean underFeet = ore.equals(feet.down());
            if (!underFeet && Retina.visibleFace(world, villager, ore, Retina.BLOCK_REACH) != null) {
                memory.doing(String.format("picando %s a %.1f bloques", name, Math.sqrt(villager.getEyePos().squaredDistanceTo(ore.toCenterPos()))), now);
                villager.getNavigation().stop();
                int r = motor.breaker().tick(world, villager, ore);
                if (r == Hands.Breaker.DONE) {
                    Hands.collect(world, villager, ore, 2.5);
                    if (state != null) {
                        announceOre(world, state);
                    }
                    Retina.peek(world, villager, ore);
                    ore = null;
                } else if (r == Hands.Breaker.FAILED) {
                    ignore(ore, now);
                    ore = null;
                }
                return;
            }
            memory.doing(String.format("yendo hacia %s en %d %d %d", name, ore.getX(), ore.getY(), ore.getZ()), now);
            Motor.Result result = motor.step(world, villager, ore, true, true);
            if (result == Motor.Result.BLOCKED) {
                if (++oreFails > 3) {
                    ignore(ore, now);
                    ore = null;
                }
            }
            return;
        }

        // nothing seen: open rock in front of the eyes
        if (plan.isEmpty()) {
            planExploration(world, villager);
        }
        BlockPos goal = plan.peekFirst();
        if (goal == null) {
            return;
        }
        memory.doing(villager.getY() > targetDepth(world) + 2 ? "bajando en escalera a buscar minerales" : "minando en ramas", now);
        Motor.Result result = motor.step(world, villager, goal, true, true);
        if (result == Motor.Result.ARRIVED) {
            plan.pollFirst();
        } else if (result == Motor.Result.BLOCKED) {
            plan.clear();
            motor.reset(world, villager);
            tunnelDir = (tunnelDir + (villager.getRandom().nextBoolean() ? 1 : 3)) & 3;
        }
    }

    private int targetDepth(ServerWorld world) {
        int bottom = world.getBottomY();
        return bottom < 0 ? DEEPSLATE_DEPTH : bottom + 12;
    }

    /** Next stretch: a staircase down to depth, then a corridor with side branches every third block. */
    private void planExploration(ServerWorld world, VillagerEntityMCA villager) {
        BlockPos feet = villager.getBlockPos();
        Direction forward = Direction.fromHorizontal(tunnelDir);
        int depth = targetDepth(world);
        if (feet.getY() > depth + 2) {
            int steps = Math.min(4, feet.getY() - depth);
            plan.add(feet.offset(forward, steps).down(steps));
            return;
        }
        for (int k = 1; k <= 3; k++) {
            plan.add(feet.offset(forward, k));
        }
        BlockPos junction = feet.offset(forward, 3);
        Direction side = branchLeft ? forward.rotateYCounterclockwise() : forward.rotateYClockwise();
        branchLeft = !branchLeft;
        for (int j = 1; j <= 5; j++) {
            plan.add(junction.offset(side, j));
        }
        plan.add(junction);
    }

    private boolean stillOre(VisualMemory memory, BlockPos pos) {
        BlockState state = memory.stateAt(pos);
        return state != null && Mining.isOre(state);
    }

    private BlockPos chooseOre(ServerWorld world, VillagerEntityMCA villager, VisualMemory memory) {
        ItemStack tool = villager.getMainHandStack();
        long now = world.getTime();
        BlockPos feet = villager.getBlockPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (VisualMemory.SeenBlock seen : memory.blocks(s -> Mining.isOre(s.state))) {
            if (isIgnored(seen.pos, now) || !tool.isSuitableFor(seen.state)) {
                continue;
            }
            double distance = Math.sqrt(seen.pos.getSquaredDistance(feet));
            if (distance > ORE_SEARCH_RADIUS) {
                continue;
            }
            double score = distance - Mining.oreValue(seen.state) / 10.0;
            if (score < bestScore) {
                bestScore = score;
                best = seen.pos;
            }
        }
        return best;
    }

    private void ignore(BlockPos pos, long now) {
        ignored.put(pos.asLong(), now + 6000);
        if (ignored.size() > 512) {
            Iterator<Long> it = ignored.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    private boolean isIgnored(BlockPos pos, long now) {
        Long until = ignored.get(pos.asLong());
        if (until == null) {
            return false;
        }
        if (until < now) {
            ignored.remove(pos.asLong());
            return false;
        }
        return true;
    }

    private static boolean bagFull(VillagerEntityMCA villager) {
        for (int i = 0; i < villager.getInventory().size(); i++) {
            if (villager.getInventory().getStack(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void maybePlaceTorch(ServerWorld world, VillagerEntityMCA villager) {
        if ((villager.age & 31) != 0) {
            return;
        }
        BlockPos feet = villager.getBlockPos();
        if (world.getLightLevel(feet) > 7) {
            return;
        }
        int slot = InventoryUtils.getFirstSlotContainingItem(villager.getInventory(), stack -> stack.isOf(Items.TORCH));
        if (slot < 0 || !world.getBlockState(feet).isAir() || !Blocks.TORCH.getDefaultState().canPlaceAt(world, feet)) {
            return;
        }
        Hands.place(world, villager, feet, villager.getInventory().getStack(slot));
    }

    private void announceOre(ServerWorld world, BlockState state) {
        int value = Mining.oreValue(state);
        long now = world.getTime();
        if (value < 30 && announcedAny) {
            return;
        }
        if (now - lastOreAnnounce < 200) {
            return;
        }
        lastOreAnnounce = now;
        announcedAny = true;
        getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, "chore.mining.orenotify", state.getBlock().getName()));
    }

    // ------------------------------------------------------------------ danger (walls, calls, escape)

    private void beginDanger(ServerWorld world, MobEntity threat) {
        cryForHelp(world);
        motor.reset(world, villager);
        plan.clear();
        villager.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
        if (tryBeginShelter(world, threat)) {
            return;
        }
        beginEscape();
    }

    private boolean tryBeginShelter(ServerWorld world, MobEntity threat) {
        BlockPos feet = villager.getBlockPos();
        Direction away = awayFrom(villager, threat);
        BlockPos retreat = feet.offset(away);
        pocket = retreat;
        sealPos = feet;
        if (Mining.passable(world, retreat) && Mining.passable(world, retreat.up()) && !Mining.heat(world, retreat)) {
            phase = Phase.SHELTER_ENTER;
            return true;
        }
        if (Mining.mayDig(world, retreat, world.getBlockState(retreat)) && !Mining.heat(world, retreat)) {
            phase = Phase.SHELTER_DIG;
            return true;
        }
        return false;
    }

    private void beginEscape() {
        escapeOrigin = villager.getBlockPos();
        escapeLeft = 5;
        phase = Phase.ESCAPE;
    }

    private void shelterDig(ServerWorld world, VillagerEntityMCA villager) {
        if (pocket == null) {
            beginEscape();
            return;
        }
        for (BlockPos cell : new BlockPos[]{pocket.up(), pocket}) {
            if (!Mining.passable(world, cell)) {
                int r = motor.breaker().tick(world, villager, cell);
                if (r == Hands.Breaker.FAILED) {
                    beginEscape();
                } else if (r == Hands.Breaker.DONE) {
                    Hands.collect(world, villager, cell, 2.5);
                }
                return;
            }
        }
        phase = Phase.SHELTER_ENTER;
    }

    private void shelterEnter(ServerWorld world, VillagerEntityMCA villager) {
        if (pocket == null) {
            beginEscape();
            return;
        }
        villager.getMoveControl().moveTo(pocket.getX() + 0.5, pocket.getY(), pocket.getZ() + 0.5, 0.8);
        if (villager.getBlockPos().equals(pocket)) {
            phase = Phase.SHELTER_SEAL;
            cryForHelp(world);
            getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, "chore.mining.shelter"));
        }
    }

    private void shelterSeal(ServerWorld world, VillagerEntityMCA villager) {
        keepDistressAlive(world);
        if (sealPos == null) {
            phase = Phase.RECOVER;
            calmTicks = 0;
            return;
        }
        for (BlockPos spot : new BlockPos[]{sealPos, sealPos.up()}) {
            if (Mining.passable(world, spot)) {
                if (!placeSolid(world, spot)) {
                    beginEscape();
                }
                return;
            }
        }
        phase = Phase.RECOVER;
        calmTicks = 0;
    }

    private void escape(ServerWorld world, VillagerEntityMCA villager) {
        keepDistressAlive(world);
        villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        if (escapeLeft > 0) {
            BlockPos below = villager.getBlockPos().down();
            if (Mining.heat(world, below) || !Mining.mayDig(world, below, world.getBlockState(below))) {
                escapeLeft = 0;
                return;
            }
            int r = motor.breaker().tick(world, villager, below);
            if (r == Hands.Breaker.DONE) {
                Hands.collect(world, villager, below, 2.5);
                escapeLeft--;
            } else if (r == Hands.Breaker.FAILED) {
                escapeLeft = 0;
            }
            return;
        }
        if (escapeOrigin != null && Mining.passable(world, escapeOrigin) && villager.getBlockPos().getY() < escapeOrigin.getY()) {
            placeSolid(world, escapeOrigin.getY() - villager.getBlockPos().getY() > 2 ? villager.getBlockPos().up(2) : escapeOrigin);
        }
        getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, "chore.mining.escape",
                villager.getBlockPos().getX(), villager.getBlockPos().getY(), villager.getBlockPos().getZ()));
        phase = Phase.RECOVER;
        calmTicks = 0;
    }

    private void recover(ServerWorld world, VillagerEntityMCA villager) {
        MobEntity threat = Threats.nearest(world, villager.getBlockPos(), 8, villager, null);
        if (threat != null) {
            calmTicks = 0;
            keepDistressAlive(world);
            if (sealPos != null && Mining.passable(world, sealPos)) {
                beginDanger(world, threat);
            }
            return;
        }
        if (++calmTicks > 80) {
            getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, "chore.mining.safe"));
            phase = Phase.SEEK;
            ore = null;
            calmTicks = 0;
        }
    }

    /** A wall block from the bag, placed like a player; if the face is out of sight in the rush, it is set directly. */
    private boolean placeSolid(ServerWorld world, BlockPos pos) {
        if (!world.getBlockState(pos).isReplaceable() || Mining.heat(world, pos)) {
            return !world.getBlockState(pos).isReplaceable();
        }
        if (!world.isInBuildLimit(pos) || !world.getWorldBorder().contains(pos)) {
            return false;
        }
        ItemStack block = Hands.buildingBlock(villager);
        if (block.isEmpty()) {
            return false;
        }
        if (Hands.place(world, villager, pos, block)) {
            return true;
        }
        if (!world.getOtherEntities(null, new net.minecraft.util.math.Box(pos)).isEmpty()) {
            return false;
        }
        world.setBlockState(pos, ((net.minecraft.item.BlockItem) block.getItem()).getBlock().getDefaultState(), 3);
        block.decrement(1);
        return true;
    }

    private void cryForHelp(ServerWorld world) {
        long now = world.getTime();
        Optional<PlayerEntity> player = getAssigningPlayer();
        player.ifPresent(assigner -> {
            DistressCalls.raise(world, villager, assigner.getUuid());
            if (now - lastCry > 400) {
                lastCry = now;
                BlockPos pos = villager.getBlockPos();
                villager.sendChatMessage(assigner, "chore.mining.help", pos.getX(), pos.getY(), pos.getZ());
            }
        });
    }

    private void keepDistressAlive(ServerWorld world) {
        if ((villager.age % 80) != 0) {
            return;
        }
        getAssigningPlayer().ifPresent(player -> DistressCalls.raise(world, villager, player.getUuid()));
    }

    private boolean equipPick(VillagerEntityMCA villager) {
        return InventoryUtils.holdMain(villager, WorkTools::isPickaxe);
    }

    private boolean tooYoung(VillagerEntityMCA villager) {
        return villager.getAgeState().ordinal() <= AgeState.TODDLER.ordinal();
    }

    private static Direction awayFrom(VillagerEntityMCA villager, MobEntity threat) {
        double dx = villager.getX() - threat.getX();
        double dz = villager.getZ() - threat.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
