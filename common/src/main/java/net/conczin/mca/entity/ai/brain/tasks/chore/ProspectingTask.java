package net.conczin.mca.entity.ai.brain.tasks.chore;

import com.google.common.collect.ImmutableMap;
import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.entity.ai.Chore;
import net.conczin.mca.entity.ai.DistressCalls;
import net.conczin.mca.entity.ai.Threats;
import net.conczin.mca.entity.ai.relationship.AgeState;
import net.conczin.mca.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Continuous miner. Looks for ore, then caves, then digs a shaft and keeps going.
 * In danger a child does not stand and swing: they wall themselves in, call out
 * their coordinates, or dig down and cap the hole.
 */
public class ProspectingTask extends AbstractChoreTask {
    private enum Phase {
        SEEK, DIG, SHELTER_DIG, SHELTER_ENTER, SHELTER_SEAL, ESCAPE, RECOVER
    }

    private Phase phase = Phase.SEEK;
    private BlockPos digTarget;
    private BlockPos pocket;
    private BlockPos sealPos;
    private BlockPos escapeOrigin;
    private int digTicks;
    private int digTicksNeeded = 12;
    private int tunnelLeft;
    private int tunnelDir;
    private int escapeLeft;
    private int calmTicks;
    private int scanCooldown;
    private long lastCry;
    private long lastOreAnnounce;
    private boolean announcedAny;
    private boolean griefWarned;
    private final Set<Long> visited = new LinkedHashSet<>();
    private final Set<Long> ignored = new LinkedHashSet<>();

    public ProspectingTask() {
        super(ImmutableMap.of(
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 12000);
    }

    @Override
    protected boolean allowsWorkWhileAfraid() {
        return true;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel world, VillagerEntityMCA villager) {
        return villager.getVillagerBrain().getCurrentJob() == Chore.PROSPECT;
    }

    @Override
    protected boolean canStillUse(ServerLevel world, VillagerEntityMCA villager, long time) {
        return checkExtraStartConditions(world, villager);
    }

    @Override
    protected void start(ServerLevel world, VillagerEntityMCA villager, long time) {
        super.start(world, villager, time);
        if (tooYoung(villager)) {
            abandonJobWithMessage("chore.mining.tooyoung");
            return;
        }
        if (!equipPick(villager)) {
            abandonJobWithMessage("chore.mining.nopick");
        }
    }

    @Override
    protected void stop(ServerLevel world, VillagerEntityMCA villager, long time) {
        ItemStack stack = villager.getItemInHand(villager.getDominantHand());
        if (!stack.isEmpty()) {
            villager.setItemInHand(villager.getDominantHand(), ItemStack.EMPTY);
        }
    }

    @Override
    protected void tick(ServerLevel world, VillagerEntityMCA villager, long time) {
        super.tick(world, villager, time);
        if (this.villager == null) {
            this.villager = villager;
        }
        if (villager.getVillagerBrain().getCurrentJob() != Chore.PROSPECT) {
            return;
        }
        if (!world.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            if (!griefWarned) {
                griefWarned = true;
                abandonJobWithMessage("chore.mining.nogrief");
            }
            return;
        }
        if (!equipPick(villager)) {
            abandonJobWithMessage("chore.mining.nopick");
            return;
        }

        if (phase == Phase.SEEK || phase == Phase.DIG) {
            Mob threat = Threats.nearest(world, villager.blockPosition(), 10, villager, null);
            if (threat != null) {
                beginDanger(world, threat);
            }
        }

        switch (phase) {
            case SEEK -> seek(world, villager);
            case DIG -> dig(world, villager);
            case SHELTER_DIG -> shelterDig(world, villager);
            case SHELTER_ENTER -> shelterEnter(world, villager);
            case SHELTER_SEAL -> shelterSeal(world, villager);
            case ESCAPE -> escape(world, villager);
            case RECOVER -> recover(world, villager);
        }
    }

    private void beginDanger(ServerLevel world, Mob threat) {
        cryForHelp(world);
        villager.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        if (tryBeginShelter(world, threat)) {
            return;
        }
        beginEscape();
    }

    private boolean tryBeginShelter(ServerLevel world, Mob threat) {
        BlockPos feet = villager.blockPosition();
        Direction away = awayFrom(villager, threat);
        BlockPos retreat = feet.relative(away);
        pocket = retreat;
        sealPos = feet;
        if (world.isEmptyBlock(retreat) && world.isEmptyBlock(retreat.above()) && !Threats.lavaNear(world, retreat)) {
            phase = Phase.SHELTER_ENTER;
            return true;
        }
        if (isDiggable(world, world.getBlockState(retreat), false) && !Threats.lavaNear(world, retreat)) {
            phase = Phase.SHELTER_DIG;
            digTarget = retreat;
            digTicks = 0;
            digTicksNeeded = ticksFor(world.getBlockState(retreat));
            return true;
        }
        return false;
    }

    private void beginEscape() {
        escapeOrigin = villager.blockPosition();
        escapeLeft = 5;
        phase = Phase.ESCAPE;
        digTicks = 0;
    }

    private void seek(ServerLevel world, VillagerEntityMCA villager) {
        maybePlaceTorch(world, villager);
        if (scanCooldown-- > 0 && digTarget != null) {
            phase = Phase.DIG;
            return;
        }
        scanCooldown = 12;
        BlockPos ore = findOre(world, villager.blockPosition());
        if (ore != null) {
            digTarget = nextSolidToward(world, villager.blockPosition(), ore);
            if (digTarget == null) {
                remember(ignored, ore.asLong(), 256);
                return;
            }
            phase = Phase.DIG;
            digTicks = 0;
            digTicksNeeded = ticksFor(world.getBlockState(digTarget));
            return;
        }

        BlockPos cave = findCave(world, villager.blockPosition());
        if (cave != null) {
            if (villager.blockPosition().distSqr(cave) <= 4) {
                remember(visited, cave.asLong(), 512);
                return;
            }
            villager.moveTowards(cave, speed(villager), 1);
            return;
        }

        if (tunnelLeft <= 0) {
            tunnelLeft = 8 + villager.getRandom().nextInt(6);
            if (villager.getRandom().nextFloat() < 0.4F) {
                tunnelDir = villager.getRandom().nextInt(4);
            }
        }
        Direction facing = Direction.from2DDataValue(tunnelDir);
        boolean descend = villager.getY() > 16 && villager.getRandom().nextFloat() < (villager.getY() > 40 ? 0.75F : 0.35F);
        BlockPos ahead = villager.blockPosition().relative(facing);
        BlockPos step = descend ? ahead.below() : ahead;
        if (Threats.lavaNear(world, step) || Threats.lavaNear(world, ahead)) {
            tunnelDir = (tunnelDir + 1) & 3;
            tunnelLeft = 6;
            return;
        }
        if (world.isEmptyBlock(step) && world.isEmptyBlock(step.above())) {
            tunnelLeft--;
            remember(visited, step.asLong(), 512);
            villager.moveTowards(step, speed(villager), 0);
            return;
        }
        digTarget = !world.isEmptyBlock(step) ? step : step.above();
        if (!isDiggable(world, world.getBlockState(digTarget), false)) {
            tunnelDir = (tunnelDir + 1) & 3;
            tunnelLeft = 6;
            digTarget = null;
            return;
        }
        phase = Phase.DIG;
        digTicks = 0;
        digTicksNeeded = ticksFor(world.getBlockState(digTarget));
    }

    private void dig(ServerLevel world, VillagerEntityMCA villager) {
        if (digTarget == null || world.isEmptyBlock(digTarget)) {
            // also clear headroom on the same column when tunneling
            if (digTarget != null && !world.isEmptyBlock(digTarget.above()) && isDiggable(world, world.getBlockState(digTarget.above()), false)) {
                digTarget = digTarget.above();
                digTicks = 0;
                digTicksNeeded = ticksFor(world.getBlockState(digTarget));
            } else {
                digTarget = null;
                phase = Phase.SEEK;
                tunnelLeft = Math.max(0, tunnelLeft - 1);
            }
            return;
        }
        if (villager.blockPosition().distSqr(digTarget) > 9) {
            villager.moveTowards(digTarget, speed(villager), 1);
            return;
        }
        BlockState state = world.getBlockState(digTarget);
        if (oreValue(state) > 0 && !tool().isCorrectToolForDrops(state)) {
            remember(ignored, digTarget.asLong(), 256);
            digTarget = null;
            phase = Phase.SEEK;
            return;
        }
        villager.swing(villager.getDominantHand());
        villager.lookAt(digTarget);
        if (++digTicks < digTicksNeeded) {
            return;
        }
        boolean ore = oreValue(state) > 0;
        if (breakBlock(world, digTarget)) {
            if (ore) {
                announceOre(world, state);
            }
            vacuum(world, digTarget);
        }
        digTicks = 0;
        digTarget = null;
        phase = Phase.SEEK;
    }

    private void shelterDig(ServerLevel world, VillagerEntityMCA villager) {
        if (pocket == null) {
            beginEscape();
            return;
        }
        boolean open = world.isEmptyBlock(pocket) && world.isEmptyBlock(pocket.above());
        if (!open) {
            BlockPos next = !world.isEmptyBlock(pocket) ? pocket : pocket.above();
            if (!breakProgress(world, villager, next)) {
                return;
            }
        }
        phase = Phase.SHELTER_ENTER;
    }

    private void shelterEnter(ServerLevel world, VillagerEntityMCA villager) {
        if (pocket == null) {
            beginEscape();
            return;
        }
        villager.moveTowards(pocket, 0.8F, 0);
        if (villager.blockPosition().distSqr(pocket) <= 2.25) {
            phase = Phase.SHELTER_SEAL;
            cryForHelp(world);
            getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, "chore.mining.shelter"));
        }
    }

    private void shelterSeal(ServerLevel world, VillagerEntityMCA villager) {
        keepDistressAlive(world);
        if (sealPos == null) {
            phase = Phase.RECOVER;
            calmTicks = 0;
            return;
        }
        boolean closed = !world.isEmptyBlock(sealPos) && !world.isEmptyBlock(sealPos.above());
        if (!closed) {
            BlockPos spot = world.isEmptyBlock(sealPos) ? sealPos : sealPos.above();
            if (!placeSolid(world, spot)) {
                // Mine a stone so the drop becomes a block we can place.
                BlockPos donor = findDonor(world, villager.blockPosition());
                if (donor == null || !breakProgress(world, villager, donor)) {
                    beginEscape();
                }
                return;
            }
        }
        phase = Phase.RECOVER;
        calmTicks = 0;
    }

    private void escape(ServerLevel world, VillagerEntityMCA villager) {
        keepDistressAlive(world);
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        if (escapeLeft > 0) {
            BlockPos below = villager.blockPosition().below();
            if (Threats.lavaNear(world, below) || !isDiggable(world, world.getBlockState(below), false)) {
                escapeLeft = 0;
                return;
            }
            if (!world.isEmptyBlock(below) && !breakProgress(world, villager, below)) {
                return;
            }
            escapeLeft--;
            return;
        }
        if (escapeOrigin != null && world.isEmptyBlock(escapeOrigin)) {
            placeSolid(world, escapeOrigin);
        }
        getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, "chore.mining.escape",
                villager.blockPosition().getX(), villager.blockPosition().getY(), villager.blockPosition().getZ()));
        phase = Phase.RECOVER;
        calmTicks = 0;
    }

    private void recover(ServerLevel world, VillagerEntityMCA villager) {
        Mob threat = Threats.nearest(world, villager.blockPosition(), 8, villager, null);
        if (threat != null) {
            calmTicks = 0;
            keepDistressAlive(world);
            // Stay sealed. If the wall is gone, try again.
            if (sealPos != null && world.isEmptyBlock(sealPos)) {
                beginDanger(world, threat);
            }
            return;
        }
        if (++calmTicks > 80) {
            getAssigningPlayer().ifPresent(player -> villager.sendChatMessage(player, "chore.mining.safe"));
            phase = Phase.SEEK;
            digTarget = null;
            calmTicks = 0;
        }
    }

    private boolean breakProgress(ServerLevel world, VillagerEntityMCA villager, BlockPos pos) {
        if (world.isEmptyBlock(pos)) {
            digTicks = 0;
            return true;
        }
        if (digTarget == null || !digTarget.equals(pos)) {
            digTarget = pos;
            digTicks = 0;
            digTicksNeeded = ticksFor(world.getBlockState(pos));
        }
        villager.swing(villager.getDominantHand());
        villager.lookAt(pos);
        if (++digTicks < digTicksNeeded) {
            return false;
        }
        digTicks = 0;
        boolean broken = breakBlock(world, pos);
        vacuum(world, pos);
        return broken || world.isEmptyBlock(pos);
    }

    private boolean breakBlock(ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getDestroySpeed(world, pos) < 0 || state.isAir()) {
            return false;
        }
        ItemStack tool = tool();
        if (oreValue(state) > 0 && !tool.isCorrectToolForDrops(state)) {
            return false;
        }
        boolean broken = world.destroyBlock(pos, true, villager);
        if (broken) {
            tool.hurtAndBreak(1, villager, villager.getDominantSlot());
        }
        return broken;
    }

    private void vacuum(ServerLevel world, BlockPos pos) {
        for (ItemEntity item : world.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2.5))) {
            item.setPickUpDelay(0);
            ItemStack leftover = villager.getInventory().addItem(item.getItem().copy());
            if (leftover.isEmpty()) {
                item.discard();
            } else {
                item.setItem(leftover);
            }
        }
    }

    private boolean placeSolid(ServerLevel world, BlockPos pos) {
        if (!world.getBlockState(pos).canBeReplaced() || Threats.lavaNear(world, pos)) {
            return !world.isEmptyBlock(pos);
        }
        int slot = InventoryUtils.getFirstSlotContainingItem(villager.getInventory(), stack -> {
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                return false;
            }
            Block block = blockItem.getBlock();
            return block != Blocks.TORCH && block.defaultBlockState().blocksMotion();
        });
        if (slot < 0) {
            return false;
        }
        ItemStack stack = villager.getInventory().getItem(slot);
        BlockState state = ((BlockItem) stack.getItem()).getBlock().defaultBlockState();
        world.setBlock(pos, state, 3);
        stack.shrink(1);
        return true;
    }

    private BlockPos findDonor(ServerLevel world, BlockPos origin) {
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-2, -1, -2), origin.offset(2, 2, 2))) {
            if (pos.equals(pocket) || pos.equals(pocket == null ? origin : pocket.above())) {
                continue;
            }
            if (isDiggable(world, world.getBlockState(pos), false) && !Threats.lavaNear(world, pos)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findOre(ServerLevel world, BlockPos origin) {
        BlockPos best = null;
        int bestValue = 0;
        double bestDistance = Double.MAX_VALUE;
        ItemStack tool = tool();
        for (int dy = -6; dy <= 6; dy++) {
            for (int dx = -12; dx <= 12; dx++) {
                for (int dz = -12; dz <= 12; dz++) {
                    if (dx * dx + dz * dz > 144) {
                        continue;
                    }
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (ignored.contains(pos.asLong())) {
                        continue;
                    }
                    BlockState state = world.getBlockState(pos);
                    int value = oreValue(state);
                    if (value == 0 || !tool.isCorrectToolForDrops(state)) {
                        continue;
                    }
                    double distance = pos.distSqr(origin);
                    if (value > bestValue || (value == bestValue && distance < bestDistance)) {
                        bestValue = value;
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private BlockPos findCave(ServerLevel world, BlockPos origin) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = -8; dy <= 3; dy++) {
            for (int dx = -10; dx <= 10; dx++) {
                for (int dz = -10; dz <= 10; dz++) {
                    if (dx * dx + dz * dz > 100) {
                        continue;
                    }
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (visited.contains(cursor.asLong())) {
                        continue;
                    }
                    if (!world.isEmptyBlock(cursor) || !world.isEmptyBlock(cursor.above())) {
                        continue;
                    }
                    if (!world.getBlockState(cursor.below()).blocksMotion()) {
                        continue;
                    }
                    // A real opening, not a single skylight: something solid above the headroom.
                    if (world.isEmptyBlock(cursor.above(3)) && world.isEmptyBlock(cursor.above(4))) {
                        continue;
                    }
                    double score = cursor.distSqr(origin) + cursor.getY() * 3.0;
                    if (score < bestScore) {
                        bestScore = score;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }

    private BlockPos nextSolidToward(ServerLevel world, BlockPos from, BlockPos ore) {
        if (from.distSqr(ore) <= 9 && !world.isEmptyBlock(ore)) {
            return ore;
        }
        BlockPos.MutableBlockPos cursor = from.mutable();
        for (int step = 0; step < 16 && !cursor.equals(ore); step++) {
            int dx = Integer.signum(ore.getX() - cursor.getX());
            int dy = Integer.signum(ore.getY() - cursor.getY());
            int dz = Integer.signum(ore.getZ() - cursor.getZ());
            cursor.move(dx, dy, dz);
            BlockState state = world.getBlockState(cursor);
            if (!state.isAir() && !state.canBeReplaced()) {
                if (!isDiggable(world, state, true) || Threats.lavaNear(world, cursor)) {
                    return null;
                }
                return cursor.immutable();
            }
        }
        return world.isEmptyBlock(ore) ? null : ore;
    }

    private void maybePlaceTorch(ServerLevel world, VillagerEntityMCA villager) {
        if ((villager.tickCount & 31) != 0) {
            return;
        }
        BlockPos feet = villager.blockPosition();
        if (world.getMaxLocalRawBrightness(feet) > 7) {
            return;
        }
        int slot = InventoryUtils.getFirstSlotContainingItem(villager.getInventory(), stack -> stack.is(Items.TORCH));
        if (slot < 0 || !world.getBlockState(feet).canBeReplaced()) {
            return;
        }
        BlockState torch = Blocks.TORCH.defaultBlockState();
        if (!torch.canSurvive(world, feet)) {
            return;
        }
        world.setBlock(feet, torch, 3);
        villager.getInventory().getItem(slot).shrink(1);
    }

    private void announceOre(ServerLevel world, BlockState state) {
        int value = oreValue(state);
        long now = world.getGameTime();
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

    private void cryForHelp(ServerLevel world) {
        long now = world.getGameTime();
        Optional<Player> player = getAssigningPlayer();
        player.ifPresent(assigner -> {
            DistressCalls.raise(world, villager, assigner.getUUID());
            if (now - lastCry > 400) {
                lastCry = now;
                BlockPos pos = villager.blockPosition();
                villager.sendChatMessage(assigner, "chore.mining.help", pos.getX(), pos.getY(), pos.getZ());
            }
        });
    }

    private void keepDistressAlive(ServerLevel world) {
        if ((villager.tickCount % 80) != 0) {
            return;
        }
        getAssigningPlayer().ifPresent(player -> DistressCalls.raise(world, villager, player.getUUID()));
    }

    private boolean equipPick(VillagerEntityMCA villager) {
        InteractionHand hand = villager.getDominantHand();
        if (villager.getItemInHand(hand).getItem() instanceof PickaxeItem) {
            return true;
        }
        int slot = InventoryUtils.getFirstSlotContainingItem(villager.getInventory(), stack -> stack.getItem() instanceof PickaxeItem);
        if (slot < 0) {
            return false;
        }
        villager.setItemInHand(hand, villager.getInventory().getItem(slot));
        return true;
    }

    private ItemStack tool() {
        return villager.getItemInHand(villager.getDominantHand());
    }

    private int ticksFor(BlockState state) {
        float speed = Math.max(1.0F, tool().getDestroySpeed(state));
        int ticks = Math.max(6, (int) (28.0F / speed));
        if (villager.getAgeState().ordinal() < AgeState.ADULT.ordinal()) {
            ticks = (int) (ticks * 1.45F);
        }
        return ticks;
    }

    private float speed(VillagerEntityMCA villager) {
        return villager.getAgeState().ordinal() < AgeState.ADULT.ordinal() ? 0.55F : 0.7F;
    }

    private boolean tooYoung(VillagerEntityMCA villager) {
        return villager.getAgeState().ordinal() <= AgeState.TODDLER.ordinal();
    }

    private boolean isDiggable(ServerLevel world, BlockState state, boolean allowOre) {
        if (state.isAir() || state.getDestroySpeed(world, BlockPos.ZERO) < 0) {
            return false;
        }
        if (!allowOre && oreValue(state) > 0) {
            return false;
        }
        if (state.hasBlockEntity()) {
            return false;
        }
        return state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.STONE_ORE_REPLACEABLES)
                || state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.NETHERRACK)
                || state.is(Blocks.SOUL_SAND)
                || state.is(Blocks.SOUL_SOIL)
                || state.is(Blocks.CALCITE)
                || state.is(Blocks.TUFF)
                || state.is(Blocks.SMOOTH_BASALT)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.SANDSTONE)
                || (allowOre && oreValue(state) > 0);
    }

    private int oreValue(BlockState state) {
        if (state.is(Blocks.ANCIENT_DEBRIS)) {
            return 100;
        }
        if (state.is(BlockTags.DIAMOND_ORES) || state.is(BlockTags.EMERALD_ORES)) {
            return 80;
        }
        if (state.is(BlockTags.GOLD_ORES)) {
            return 50;
        }
        if (state.is(BlockTags.LAPIS_ORES) || state.is(BlockTags.REDSTONE_ORES)) {
            return 40;
        }
        if (state.is(BlockTags.IRON_ORES)) {
            return 30;
        }
        if (state.is(BlockTags.COPPER_ORES)) {
            return 20;
        }
        if (state.is(BlockTags.COAL_ORES) || state.is(Blocks.NETHER_QUARTZ_ORE)) {
            return 10;
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (path.endsWith("_ore") || path.contains("_ore_")) {
            return 25;
        }
        return 0;
    }

    private static Direction awayFrom(VillagerEntityMCA villager, Mob threat) {
        double dx = villager.getX() - threat.getX();
        double dz = villager.getZ() - threat.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static void remember(Set<Long> set, long value, int cap) {
        set.add(value);
        if (set.size() <= cap) {
            return;
        }
        Iterator<Long> iterator = set.iterator();
        iterator.next();
        iterator.remove();
    }
}
