package net.mca.entity.ai.brain.tasks.chore;

import com.google.common.collect.ImmutableMap;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.DistressCalls;
import net.mca.entity.ai.Threats;
import net.mca.entity.ai.WorkTools;
import net.mca.entity.ai.relationship.AgeState;
import net.mca.util.InventoryUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Continuous miner. Looks for ore, then caves, then digs a shaft and keeps going.
 * In danger they wall themselves in, call out their coordinates, or dig down and cap the hole.
 */
public class ProspectingTask extends AbstractChoreTask {
    private static final TagKey<Block> C_ORES = blockTag("c", "ores");
    private static final TagKey<Block> FORGE_ORES = blockTag("forge", "ores");
    private static final TagKey<Block> C_STONES = blockTag("c", "stones");
    private static final TagKey<Block> C_COBBLESTONES = blockTag("c", "cobblestones");
    private static final TagKey<Block> C_GRAVELS = blockTag("c", "gravels");
    private static final TagKey<Block> C_SANDS = blockTag("c", "sands");
    private static final TagKey<Block> C_SANDSTONES = blockTag("c", "sandstones");
    private static final TagKey<Block> C_NETHERRACKS = blockTag("c", "netherracks");
    private static final TagKey<Block> C_DIRTS = blockTag("c", "dirts");
    private static final TagKey<Block> C_DEEPSLATES = blockTag("c", "deepslate");
    private static final TagKey<Block> FORGE_STONE = blockTag("forge", "stone");
    private static final TagKey<Block> FORGE_COBBLESTONE = blockTag("forge", "cobblestone");
    private static final TagKey<Block> FORGE_GRAVEL = blockTag("forge", "gravel");
    private static final TagKey<Block> FORGE_SAND = blockTag("forge", "sand");
    private static final TagKey<Block> FORGE_DIRT = blockTag("forge", "dirt");
    private static final TagKey<Block> FORGE_NETHERRACK = blockTag("forge", "netherrack");

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
    private int stillTicks;
    private BlockPos lastFeet;
    private final Set<Long> visited = new LinkedHashSet<>();
    private final Set<Long> ignored = new LinkedHashSet<>();

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
        }
    }

    @Override
    protected void finishRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
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
        noteMotion(villager);
        if (!equipPick(villager)) {
            abandonJobWithMessage("chore.mining.nopick");
            return;
        }

        if (phase == Phase.SEEK || phase == Phase.DIG) {
            MobEntity threat = Threats.nearest(world, villager.getBlockPos(), 10, villager, null);
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

    private void beginDanger(ServerWorld world, MobEntity threat) {
        cryForHelp(world);
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
        if (world.isAir(retreat) && world.isAir(retreat.up()) && !Threats.lavaNear(world, retreat)) {
            phase = Phase.SHELTER_ENTER;
            return true;
        }
        if (isDiggable(world, retreat, false) && !Threats.lavaNear(world, retreat)) {
            phase = Phase.SHELTER_DIG;
            digTarget = retreat;
            digTicks = 0;
            digTicksNeeded = ticksFor(world.getBlockState(retreat));
            return true;
        }
        return false;
    }

    private void beginEscape() {
        escapeOrigin = villager.getBlockPos();
        escapeLeft = 5;
        phase = Phase.ESCAPE;
        digTicks = 0;
    }

    private void seek(ServerWorld world, VillagerEntityMCA villager) {
        maybePlaceTorch(world, villager);
        BlockPos head = villager.getBlockPos().up();
        if (!world.isAir(head) && isDiggable(world, head, false)) {
            digTarget = head;
            phase = Phase.DIG;
            digTicks = 0;
            digTicksNeeded = ticksFor(world.getBlockState(head));
            return;
        }
        if (stillTicks > 40) {
            BlockPos front = villager.getBlockPos().offset(villager.getHorizontalFacing());
            if (isDiggable(world, front, true)) {
                digTarget = front;
                phase = Phase.DIG;
                digTicks = 0;
                digTicksNeeded = ticksFor(world.getBlockState(front));
                stillTicks = 0;
                return;
            }
        }
        if (scanCooldown-- > 0 && digTarget != null) {
            phase = Phase.DIG;
            return;
        }
        scanCooldown = 12;
        BlockPos ore = findOre(world, villager.getBlockPos());
        if (ore != null) {
            digTarget = nextSolidToward(world, villager.getBlockPos(), ore);
            if (digTarget == null) {
                remember(ignored, ore.asLong(), 256);
                return;
            }
            phase = Phase.DIG;
            digTicks = 0;
            digTicksNeeded = ticksFor(world.getBlockState(digTarget));
            return;
        }

        BlockPos cave = findCave(world, villager.getBlockPos());
        if (cave != null) {
            if (villager.getBlockPos().getSquaredDistance(cave) <= 4) {
                remember(visited, cave.asLong(), 512);
                return;
            }
            villager.moveTowards(cave, speed(villager), 1);
            stepToward(villager, cave, speed(villager));
            return;
        }

        if (tunnelLeft <= 0) {
            tunnelLeft = 8 + villager.getRandom().nextInt(6);
            if (villager.getRandom().nextFloat() < 0.4F) {
                tunnelDir = villager.getRandom().nextInt(4);
            }
        }
        Direction facing = Direction.fromHorizontal(tunnelDir);
        boolean descend = villager.getY() > 16 && villager.getRandom().nextFloat() < (villager.getY() > 40 ? 0.75F : 0.35F);
        BlockPos ahead = villager.getBlockPos().offset(facing);
        BlockPos step = descend ? ahead.down() : ahead;
        if (Threats.lavaNear(world, step) || Threats.lavaNear(world, ahead)) {
            tunnelDir = (tunnelDir + 1) & 3;
            tunnelLeft = 6;
            return;
        }
        if (world.isAir(step) && world.isAir(step.up())) {
            tunnelLeft--;
            remember(visited, step.asLong(), 512);
            villager.moveTowards(step, speed(villager), 0);
            stepToward(villager, step, speed(villager));
            return;
        }
        digTarget = !world.isAir(step) ? step : step.up();
        if (!isDiggable(world, digTarget, false)) {
            tunnelDir = (tunnelDir + 1) & 3;
            tunnelLeft = 6;
            digTarget = null;
            return;
        }
        phase = Phase.DIG;
        digTicks = 0;
        digTicksNeeded = ticksFor(world.getBlockState(digTarget));
    }

    private void dig(ServerWorld world, VillagerEntityMCA villager) {
        if (digTarget == null || world.isAir(digTarget)) {
            if (digTarget != null && !world.isAir(digTarget.up()) && isDiggable(world, digTarget.up(), false)) {
                digTarget = digTarget.up();
                digTicks = 0;
                digTicksNeeded = ticksFor(world.getBlockState(digTarget));
            } else {
                digTarget = null;
                phase = Phase.SEEK;
                tunnelLeft = Math.max(0, tunnelLeft - 1);
            }
            return;
        }
        if (villager.getBlockPos().getSquaredDistance(digTarget) > 6.25) {
            walk(villager, digTarget, speed(villager));
            if (stillTicks > 25) {
                BlockPos wall = nextSolidToward(world, villager.getBlockPos(), digTarget);
                if (wall != null && villager.getBlockPos().getSquaredDistance(wall) <= 6.25) {
                    digTarget = wall;
                    digTicks = 0;
                    digTicksNeeded = ticksFor(world.getBlockState(wall));
                    stillTicks = 0;
                }
            }
            return;
        }
        BlockState state = world.getBlockState(digTarget);
        if (oreValue(state) > 0 && !tool().isSuitableFor(state)) {
            remember(ignored, digTarget.asLong(), 256);
            digTarget = null;
            phase = Phase.SEEK;
            return;
        }
        villager.swingHand(Hand.MAIN_HAND);
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
        } else {
            remember(ignored, digTarget.asLong(), 256);
        }
        digTicks = 0;
        digTarget = null;
        phase = Phase.SEEK;
    }

    private void shelterDig(ServerWorld world, VillagerEntityMCA villager) {
        if (pocket == null) {
            beginEscape();
            return;
        }
        boolean open = world.isAir(pocket) && world.isAir(pocket.up());
        if (!open) {
            BlockPos next = !world.isAir(pocket) ? pocket : pocket.up();
            if (!breakProgress(world, villager, next)) {
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
        villager.moveTowards(pocket, 0.8F, 0);
        if (villager.getBlockPos().getSquaredDistance(pocket) <= 2.25) {
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
        boolean closed = !world.isAir(sealPos) && !world.isAir(sealPos.up());
        if (!closed) {
            BlockPos spot = world.isAir(sealPos) ? sealPos : sealPos.up();
            if (!placeSolid(world, spot)) {
                BlockPos donor = findDonor(world, villager.getBlockPos());
                if (donor == null || !breakProgress(world, villager, donor)) {
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
            if (Threats.lavaNear(world, below) || !isDiggable(world, below, false)) {
                escapeLeft = 0;
                return;
            }
            if (!world.isAir(below) && !breakProgress(world, villager, below)) {
                return;
            }
            escapeLeft--;
            return;
        }
        if (escapeOrigin != null && world.isAir(escapeOrigin)) {
            placeSolid(world, escapeOrigin);
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
            if (sealPos != null && world.isAir(sealPos)) {
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

    private boolean breakProgress(ServerWorld world, VillagerEntityMCA villager, BlockPos pos) {
        if (world.isAir(pos)) {
            digTicks = 0;
            return true;
        }
        if (digTarget == null || !digTarget.equals(pos)) {
            digTarget = pos;
            digTicks = 0;
            digTicksNeeded = ticksFor(world.getBlockState(pos));
        }
        villager.swingHand(Hand.MAIN_HAND);
        villager.lookAt(pos);
        if (++digTicks < digTicksNeeded) {
            return false;
        }
        digTicks = 0;
        boolean broken = breakBlock(world, pos);
        vacuum(world, pos);
        return broken || world.isAir(pos);
    }

    private boolean breakBlock(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.hasBlockEntity() || state.getHardness(world, pos) < 0 || state.isAir()) {
            return false;
        }
        ItemStack tool = tool();
        if (oreValue(state) > 0 && !tool.isSuitableFor(state)) {
            return false;
        }
        if (!canBreak(world, pos)) {
            remember(ignored, pos.asLong(), 512);
            return false;
        }
        boolean broken = world.breakBlock(pos, true, villager);
        if (broken) {
            tool.damage(1, villager, e -> e.sendEquipmentBreakStatus(EquipmentSlot.MAINHAND));
        }
        return broken;
    }

    private void vacuum(ServerWorld world, BlockPos pos) {
        for (ItemEntity item : world.getNonSpectatingEntities(ItemEntity.class, new Box(pos).expand(2.5))) {
            item.setPickupDelay(0);
            ItemStack leftover = villager.getInventory().addStack(item.getStack().copy());
            if (leftover.isEmpty()) {
                item.discard();
            } else {
                item.setStack(leftover);
            }
        }
    }

    private boolean placeSolid(ServerWorld world, BlockPos pos) {
        if (!world.getBlockState(pos).isReplaceable() || Threats.lavaNear(world, pos)) {
            return !world.isAir(pos);
        }
        if (!canBreak(world, pos)) {
            return false;
        }
        int slot = InventoryUtils.getFirstSlotContainingItem(villager.getInventory(), stack -> {
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                return false;
            }
            Block block = blockItem.getBlock();
            return block != Blocks.TORCH && block.getDefaultState().blocksMovement();
        });
        if (slot < 0) {
            return false;
        }
        ItemStack stack = villager.getInventory().getStack(slot);
        BlockState state = ((BlockItem) stack.getItem()).getBlock().getDefaultState();
        world.setBlockState(pos, state, 3);
        stack.decrement(1);
        return true;
    }

    private BlockPos findDonor(ServerWorld world, BlockPos origin) {
        for (BlockPos pos : BlockPos.iterate(origin.add(-2, -1, -2), origin.add(2, 2, 2))) {
            if (pos.equals(pocket) || pos.equals(pocket == null ? origin : pocket.up())) {
                continue;
            }
            BlockPos frozen = pos.toImmutable();
            if (isDiggable(world, frozen, false) && !Threats.lavaNear(world, frozen)) {
                return frozen;
            }
        }
        return null;
    }

    private BlockPos findOre(ServerWorld world, BlockPos origin) {
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
                    BlockPos pos = origin.add(dx, dy, dz);
                    if (ignored.contains(pos.asLong())) {
                        continue;
                    }
                    BlockState state = world.getBlockState(pos);
                    int value = oreValue(state);
                    if (value == 0 || !tool.isSuitableFor(state)) {
                        continue;
                    }
                    double distance = pos.getSquaredDistance(origin);
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

    private BlockPos findCave(ServerWorld world, BlockPos origin) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos.Mutable cursor = new BlockPos.Mutable();
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
                    if (!world.isAir(cursor) || !world.isAir(cursor.up())) {
                        continue;
                    }
                    if (!world.getBlockState(cursor.down()).blocksMovement()) {
                        continue;
                    }
                    if (world.isAir(cursor.up(3)) && world.isAir(cursor.up(4))) {
                        continue;
                    }
                    double score = cursor.getSquaredDistance(origin) + cursor.getY() * 3.0;
                    if (score < bestScore) {
                        bestScore = score;
                        best = cursor.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    private BlockPos nextSolidToward(ServerWorld world, BlockPos from, BlockPos ore) {
        if (from.getSquaredDistance(ore) <= 9 && !world.isAir(ore)) {
            return ore;
        }
        BlockPos.Mutable cursor = from.mutableCopy();
        for (int step = 0; step < 16 && !cursor.equals(ore); step++) {
            int dx = Integer.signum(ore.getX() - cursor.getX());
            int dy = Integer.signum(ore.getY() - cursor.getY());
            int dz = Integer.signum(ore.getZ() - cursor.getZ());
            cursor.move(dx, dy, dz);
            BlockState state = world.getBlockState(cursor);
            if (!state.isAir() && !state.isReplaceable()) {
                BlockPos frozen = cursor.toImmutable();
                if (!isDiggable(world, frozen, true) || Threats.lavaNear(world, frozen)) {
                    return null;
                }
                return frozen;
            }
        }
        return world.isAir(ore) ? null : ore;
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
        if (slot < 0 || !world.getBlockState(feet).isReplaceable() || !canBreak(world, feet)) {
            return;
        }
        BlockState torch = Blocks.TORCH.getDefaultState();
        if (!torch.canPlaceAt(world, feet)) {
            return;
        }
        world.setBlockState(feet, torch, 3);
        villager.getInventory().getStack(slot).decrement(1);
    }

    private void announceOre(ServerWorld world, BlockState state) {
        int value = oreValue(state);
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

    private ItemStack tool() {
        return villager.getMainHandStack();
    }

    private void walk(VillagerEntityMCA villager, BlockPos pos, float speed) {
        villager.moveTowards(pos, speed, 0);
        stepToward(villager, pos, speed);
    }

    private void stepToward(VillagerEntityMCA villager, BlockPos pos, float speed) {
        if ((villager.age & 3) == 0) {
            villager.getNavigation().startMovingTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, Math.max(0.7F, speed));
        }
    }

    private void noteMotion(VillagerEntityMCA villager) {
        BlockPos now = villager.getBlockPos();
        if (lastFeet != null && lastFeet.equals(now)) {
            stillTicks++;
        } else {
            stillTicks = 0;
            lastFeet = now.toImmutable();
        }
    }

    private int ticksFor(BlockState state) {
        float speed = Math.max(1.0F, tool().getMiningSpeedMultiplier(state));
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

    private boolean isDiggable(ServerWorld world, BlockPos pos, boolean allowOre) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getHardness(world, pos) < 0) {
            return false;
        }
        if (!allowOre && oreValue(state) > 0) {
            return false;
        }
        if (state.hasBlockEntity()) {
            return false;
        }
        return state.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || state.isIn(BlockTags.BASE_STONE_NETHER)
                || state.isIn(BlockTags.STONE_ORE_REPLACEABLES)
                || state.isIn(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || state.isIn(BlockTags.DIRT)
                || state.isIn(BlockTags.SAND)
                || state.isOf(Blocks.GRAVEL)
                || state.isOf(Blocks.NETHERRACK)
                || state.isOf(Blocks.SOUL_SAND)
                || state.isOf(Blocks.SOUL_SOIL)
                || state.isOf(Blocks.CALCITE)
                || state.isOf(Blocks.TUFF)
                || state.isOf(Blocks.SMOOTH_BASALT)
                || state.isOf(Blocks.COBBLESTONE)
                || state.isOf(Blocks.COBBLED_DEEPSLATE)
                || state.isOf(Blocks.SANDSTONE)
                || state.isIn(C_STONES)
                || state.isIn(C_COBBLESTONES)
                || state.isIn(C_GRAVELS)
                || state.isIn(C_SANDS)
                || state.isIn(C_SANDSTONES)
                || state.isIn(C_NETHERRACKS)
                || state.isIn(C_DIRTS)
                || state.isIn(C_DEEPSLATES)
                || state.isIn(FORGE_STONE)
                || state.isIn(FORGE_COBBLESTONE)
                || state.isIn(FORGE_GRAVEL)
                || state.isIn(FORGE_SAND)
                || state.isIn(FORGE_DIRT)
                || state.isIn(FORGE_NETHERRACK)
                || (allowOre && oreValue(state) > 0);
    }

    private int oreValue(BlockState state) {
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

    private boolean canBreak(ServerWorld world, BlockPos pos) {
        return world.isInBuildLimit(pos) && world.getWorldBorder().contains(pos);
    }

    private static TagKey<Block> blockTag(String namespace, String path) {
        return TagKey.of(RegistryKeys.BLOCK, new Identifier(namespace, path));
    }

    private static Direction awayFrom(VillagerEntityMCA villager, MobEntity threat) {
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
