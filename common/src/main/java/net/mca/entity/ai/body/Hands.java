package net.mca.entity.ai.body;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.sense.Obras;
import net.mca.entity.ai.sense.Retina;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.BlockPosLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.effect.StatusEffectUtil;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * The villager's hands, held to the rules a survival player plays by: a block is broken or placed only
 * where the crosshair can land on it, within 4.5 blocks of the eye, at the speed the held tool really
 * mines (efficiency, haste, fatigue, water and air all count), and it drops what a player would get
 * with that tool, fortune and silk touch included.
 */
public final class Hands {
    /** Items each villager threw away on purpose, with the world time until which they are left on the ground. */
    private static final Map<VillagerEntityMCA, Map<Item, Long>> REFUSED = new WeakHashMap<>();
    /** Ticks nobody can pick up an item a player throws (the Q key). */
    public static final int TOSS_DELAY = 40;

    private Hands() {
    }

    /**
     * Turns head and eyes exactly toward a point, so the next sight ray goes through it. A point (nearly) straight
     * above or below only tilts the head: the body keeps facing the way it is going, as a player looking down at
     * their feet while jumping still moves forward.
     */
    public static void aim(MobEntity entity, Vec3d point) {
        Vec3d eye = entity.getEyePos();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float pitch = (float) -(MathHelper.atan2(dy, horizontal) * (180.0 / Math.PI));
        entity.setPitch(MathHelper.clamp(pitch, -90.0F, 90.0F));
        if (horizontal > 0.3) {
            entity.setHeadYaw(yaw);
            if (Math.abs(MathHelper.wrapDegrees(yaw - entity.getBodyYaw())) > 45.0F) {
                entity.setBodyYaw(yaw);
            }
            if (entity.getNavigation().isIdle()) {
                entity.setYaw(yaw);
            }
        }
        entity.getLookControl().lookAt(point.x, point.y, point.z, 360.0F, 360.0F);
        entity.getBrain().remember(MemoryModuleType.LOOK_TARGET, new BlockPosLookTarget(point));
    }

    public static boolean canHarvest(BlockState state, ItemStack tool) {
        return !state.isToolRequired() || tool.isSuitableFor(state);
    }

    /** The fraction of the block broken per tick, same formula as {@code AbstractBlockState#calcBlockBreakingDelta}. */
    public static float breakDelta(LivingEntity miner, World world, BlockPos pos, BlockState state, ItemStack tool) {
        float hardness = state.getHardness(world, pos);
        if (hardness < 0) {
            return 0.0F;
        }
        if (hardness == 0) {
            return 1.0F;
        }
        float speed = tool.getMiningSpeedMultiplier(state);
        if (speed > 1.0F) {
            int efficiency = EnchantmentHelper.getEfficiency(miner);
            if (efficiency > 0 && !tool.isEmpty()) {
                speed += efficiency * efficiency + 1;
            }
        }
        if (StatusEffectUtil.hasHaste(miner)) {
            speed *= 1.0F + (StatusEffectUtil.getHasteAmplifier(miner) + 1) * 0.2F;
        }
        if (miner.hasStatusEffect(StatusEffects.MINING_FATIGUE)) {
            int level = miner.getStatusEffect(StatusEffects.MINING_FATIGUE).getAmplifier();
            speed *= switch (level) {
                case 0 -> 0.3F;
                case 1 -> 0.09F;
                case 2 -> 0.0027F;
                default -> 8.1E-4F;
            };
        }
        if (miner.isSubmergedIn(FluidTags.WATER) && !EnchantmentHelper.hasAquaAffinity(miner)) {
            speed /= 5.0F;
        }
        if (!miner.isOnGround()) {
            speed /= 5.0F;
        }
        return speed / hardness / (canHarvest(state, tool) ? 30.0F : 100.0F);
    }

    /** Ticks a player with this tool needs for the block. */
    public static int ticksToBreak(LivingEntity miner, World world, BlockPos pos, BlockState state, ItemStack tool) {
        float delta = breakDelta(miner, world, pos, state, tool);
        return delta <= 0 ? Integer.MAX_VALUE : (int) Math.ceil(1.0F / delta);
    }

    /** One block being broken over several ticks, with the cracks shown to everyone. */
    public static final class Breaker {
        public static final int RUNNING = 0;
        public static final int DONE = 1;
        public static final int FAILED = -1;

        @Nullable
        private BlockPos pos;
        private float progress;
        private int stage = -1;
        private int ticks;
        private int blindTicks;
        private int airTicks;

        @Nullable
        public BlockPos target() {
            return pos;
        }

        public float progress() {
            return progress;
        }

        public void reset(World world, LivingEntity miner) {
            if (pos != null && stage >= 0) {
                world.setBlockBreakingInfo(miner.getId(), pos, -1);
            }
            pos = null;
            progress = 0;
            stage = -1;
            ticks = 0;
            blindTicks = 0;
            airTicks = 0;
        }

        /** One tick of breaking {@code target}; only with its feet on something, like a player who lands before swinging. */
        public int tick(ServerWorld world, VillagerEntityMCA miner, BlockPos target) {
            return tick(world, miner, target, false);
        }

        /**
         * One tick of breaking {@code target}. Off its feet (jumping, falling) it does not swing and the cracks stay
         * as they are, unless {@code urgent} (buried by sand or gravel: it digs out wherever it is).
         */
        public int tick(ServerWorld world, VillagerEntityMCA miner, BlockPos target, boolean urgent) {
            BlockState state = world.getBlockState(target);
            if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(world, target).isEmpty()) {
                reset(world, miner);
                return DONE;
            }
            if (state.getHardness(world, target) < 0) {
                reset(world, miner);
                return FAILED;
            }
            if (!target.equals(pos)) {
                reset(world, miner);
                // a tree, rock or its own block may go; a house, a player's build or a village wall never
                if (!Obras.mayBreak(world, miner, target)) {
                    Retina.memory(miner).doing("no rompe " + state.getBlock().getName().getString() + ": es " + Obras.kind(world, miner, target).label, world.getTime());
                    return FAILED;
                }
                pos = target.toImmutable();
                holdBestTool(miner, state);
            }
            if (!urgent && !miner.isOnGround() && !miner.isTouchingWater() && !miner.isClimbing() && !miner.hasVehicle()) {
                if (++airTicks > 100) {
                    reset(world, miner);
                    return FAILED;
                }
                return RUNNING;
            }
            airTicks = 0;
            BlockHitResult face = Retina.visibleFace(world, miner, target, Retina.BLOCK_REACH);
            if (face == null) {
                // a player cannot hit what the crosshair cannot reach; give the body a moment to settle
                if (++blindTicks > 10) {
                    reset(world, miner);
                    return FAILED;
                }
                aim(miner, Vec3d.ofCenter(target));
                return RUNNING;
            }
            blindTicks = 0;
            aim(miner, face.getPos());
            Retina.memory(miner).see(target, state, face.getSide(), face.getPos(), miner.getEyePos().distanceTo(face.getPos()), world.getLightLevel(target.offset(face.getSide())), world.getTime());
            ItemStack tool = miner.getMainHandStack();
            if ((ticks++ & 3) == 0) {
                miner.swingHand(Hand.MAIN_HAND);
                BlockSoundGroup sounds = state.getSoundGroup();
                world.playSound(null, target, sounds.getHitSound(), SoundCategory.BLOCKS, (sounds.getVolume() + 1.0F) / 8.0F, sounds.getPitch() * 0.5F);
            }
            progress += breakDelta(miner, world, target, state, tool);
            int now = Math.min(9, (int) (progress * 10.0F));
            if (now != stage) {
                stage = now;
                world.setBlockBreakingInfo(miner.getId(), target, stage);
            }
            if (progress < 1.0F) {
                return RUNNING;
            }
            finish(world, miner, target, state, tool);
            reset(world, miner);
            return DONE;
        }
    }

    /** Like a player picking the hotbar slot: the tool from the bag that cuts this block fastest. */
    public static void holdBestTool(VillagerEntityMCA miner, BlockState state) {
        ItemStack held = miner.getMainHandStack();
        float heldScore = toolScore(held, state);
        ItemStack best = null;
        float bestScore = heldScore;
        var inv = miner.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty() || stack == held) {
                continue;
            }
            float score = toolScore(stack, state);
            if (score > bestScore) {
                best = stack;
                bestScore = score;
            }
        }
        if (best != null) {
            ItemStack chosen = best;
            net.mca.util.InventoryUtils.holdMain(miner, s -> s == chosen);
        }
    }

    private static float toolScore(ItemStack stack, BlockState state) {
        if (stack.isEmpty()) {
            return 1.0F;
        }
        float speed = stack.getMiningSpeedMultiplier(state);
        if (state.isToolRequired() && stack.isSuitableFor(state)) {
            speed += 100.0F;
        }
        return speed;
    }

    private static void finish(ServerWorld world, VillagerEntityMCA miner, BlockPos pos, BlockState state, ItemStack tool) {
        boolean harvest = canHarvest(state, tool);
        BlockEntity blockEntity = state.hasBlockEntity() ? world.getBlockEntity(pos) : null;
        ItemStack used = tool.copy();
        if (!world.breakBlock(pos, false, miner)) {
            return;
        }
        Obras.broken(world, pos);
        if (!tool.isEmpty()) {
            tool.getItem().postMine(tool, world, state, pos, miner);
        }
        if (harvest) {
            Block.dropStacks(state, world, pos, blockEntity, miner, used);
            state.onStacksDropped(world, pos, used, true);
        }
    }

    /** Picks up loose items around a spot, the way walking over them would. What it threw away stays on the ground. */
    public static void collect(ServerWorld world, VillagerEntityMCA villager, BlockPos around, double radius) {
        collect(world, villager, around, radius, stack -> true);
    }

    /** Picks up the loose items around a spot that {@code accept} wants; what it threw away stays on the ground. */
    public static void collect(ServerWorld world, VillagerEntityMCA villager, BlockPos around, double radius, Predicate<ItemStack> accept) {
        long now = world.getTime();
        for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class, new Box(around).expand(radius), e -> e.isAlive() && !e.getStack().isEmpty())) {
            ItemStack stack = item.getStack();
            if (!accept.test(stack) || refused(villager, stack, now)) {
                continue;
            }
            ItemStack leftover = villager.getInventory().addStack(item.getStack().copy());
            if (leftover.isEmpty()) {
                item.discard();
            } else {
                item.setStack(leftover);
            }
        }
    }

    /**
     * The face of a solid neighbor of {@code target} that the eye can put its crosshair on within reach,
     * as a player looks at the side of a block to place against it.
     */
    @Nullable
    public static BlockHitResult supportFace(World world, LivingEntity entity, BlockPos target) {
        Vec3d eye = entity.getEyePos();
        BlockHitResult best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = target.offset(dir);
            BlockState state = world.getBlockState(neighbor);
            if (state.isAir() || state.isReplaceable() || !state.getFluidState().isEmpty() && state.getCollisionShape(world, neighbor).isEmpty()) {
                continue;
            }
            Direction side = dir.getOpposite();
            Vec3d face = Vec3d.ofCenter(target).add(Vec3d.of(dir.getVector()).multiply(0.5));
            for (Vec3d point : new Vec3d[]{face, face.add(offsetOnFace(side, 0.3, 0.3)), face.add(offsetOnFace(side, -0.3, 0.3)), face.add(offsetOnFace(side, 0.3, -0.3)), face.add(offsetOnFace(side, -0.3, -0.3))}) {
                double distance = eye.distanceTo(point);
                if (distance > Retina.BLOCK_REACH || distance >= bestDistance) {
                    continue;
                }
                Vec3d past = point.add(point.subtract(eye).normalize().multiply(0.05));
                BlockHitResult hit = world.raycast(new RaycastContext(eye, past, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, entity));
                if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(neighbor) && hit.getSide() == side) {
                    best = hit;
                    bestDistance = distance;
                    break;
                }
            }
        }
        return best;
    }

    private static Vec3d offsetOnFace(Direction side, double a, double b) {
        return switch (side.getAxis()) {
            case X -> new Vec3d(0, a, b);
            case Y -> new Vec3d(a, 0, b);
            case Z -> new Vec3d(a, b, 0);
        };
    }

    /** Places one block of {@code stack} into {@code target} against a face the eye can see. */
    public static boolean place(ServerWorld world, VillagerEntityMCA villager, BlockPos target, ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem) || stack.isEmpty()) {
            return false;
        }
        if (!world.getBlockState(target).isReplaceable()) {
            return false;
        }
        BlockHitResult support = supportFace(world, villager, target);
        if (support == null) {
            return false;
        }
        aim(villager, support.getPos());
        VillagerPlacementContext context = new VillagerPlacementContext(world, villager, stack, support);
        if (!context.getBlockPos().equals(target)) {
            return false;
        }
        ActionResult result = blockItem.place(context);
        if (result.isAccepted()) {
            Obras.placedByVillager(world, villager, target);
            villager.swingHand(Hand.MAIN_HAND);
            Retina.memory(villager).see(target, world.getBlockState(target), support.getSide(), support.getPos(), villager.getEyePos().distanceTo(support.getPos()), world.getLightLevel(target), world.getTime());
            return true;
        }
        return false;
    }

    /**
     * Leaves the world grew ({@link Obras#natural}: never the persistent ones a player places) and that are nobody's
     * (no player's, no village's): what a player clicks away by hand when they stand between it and a trunk, over its
     * head on a pillar or a jump, or across the way it walks. No pick needed, so a step that may not dig still may.
     */
    public static boolean naturalLeaf(ServerWorld world, VillagerEntityMCA villager, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isIn(BlockTags.LEAVES) && Obras.natural(state) && Obras.mayBreak(world, villager, pos);
    }

    /**
     * Natural leaves right across the way toward {@code target}: the next one or two cells ahead of its feet, at its feet
     * or its head, in reach and in sight (head height first, the way a player clears a hedge). Null when nothing
     * leafy stops it there.
     */
    @Nullable
    public static BlockPos leafAhead(ServerWorld world, VillagerEntityMCA villager, Vec3d target) {
        double dx = target.x - villager.getX();
        double dz = target.z - villager.getZ();
        if (dx * dx + dz * dz < 0.25) {
            return null;
        }
        Direction toward = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        BlockPos ahead = villager.getBlockPos();
        for (int k = 1; k <= 2; k++) {
            ahead = ahead.offset(toward);
            for (BlockPos cell : new BlockPos[]{ahead.up(), ahead}) {
                if (naturalLeaf(world, villager, cell) && Retina.visibleFace(world, villager, cell, Retina.BLOCK_REACH) != null) {
                    return cell.toImmutable();
                }
            }
            if (!Mining.passable(world, ahead) || !Mining.passable(world, ahead.up())) {
                return null; // something else stops it first: not leaves' doing
            }
        }
        return null;
    }

    /** A block worth spending on bridges, pillars and walls: a full solid cube that does not fall and is not precious. */
    public static boolean isBuildingBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem) || stack.isEmpty()) {
            return false;
        }
        Block block = blockItem.getBlock();
        BlockState state = block.getDefaultState();
        if (state.hasBlockEntity() || block instanceof FallingBlock || block == Blocks.TNT) {
            return false;
        }
        // the crafting table it carries is gear, like its furnace and chest: never a step, a bridge or a plug
        if (block == Blocks.CRAFTING_TABLE) {
            return false;
        }
        if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.BEACON_BASE_BLOCKS) || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.ICE)) {
            return false;
        }
        if (Mining.oreValue(state) > 0) {
            return false;
        }
        return state.isOpaqueFullCube(net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN)
                && state.isFullCube(net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
    }

    public static int findSlot(SimpleInventory inventory, Predicate<ItemStack> match) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && match.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    /** A building block from the bag, or empty. The stack is the live one in the inventory. */
    public static ItemStack buildingBlock(VillagerEntityMCA villager) {
        int slot = findSlot(villager.getInventory(), Hands::isBuildingBlock);
        return slot < 0 ? ItemStack.EMPTY : villager.getInventory().getStack(slot);
    }

    /** How many building blocks the bag holds in all. */
    public static int buildingBlockCount(VillagerEntityMCA villager) {
        int count = 0;
        SimpleInventory inventory = villager.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (isBuildingBlock(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * It threw this kind of item away on purpose: {@link #collect} leaves it on the ground until {@code until}
     * (world time), as a player does not pick back up the cobble they just dropped.
     */
    public static void refuse(VillagerEntityMCA villager, Item item, long until) {
        REFUSED.computeIfAbsent(villager, v -> new HashMap<>()).put(item, until);
    }

    /** It wants everything again (it ran short of blocks): every refusal is lifted. */
    public static void unrefuse(VillagerEntityMCA villager) {
        REFUSED.remove(villager);
    }

    /** Whether it threw this kind of item away and does not want it back yet. */
    public static boolean refused(VillagerEntityMCA villager, ItemStack stack, long now) {
        Map<Item, Long> items = REFUSED.get(villager);
        if (items == null) {
            return false;
        }
        Long until = items.get(stack.getItem());
        if (until == null) {
            return false;
        }
        if (until < now) {
            items.remove(stack.getItem());
            return false;
        }
        return true;
    }

    /**
     * Throws the stack in {@code slot} out of the bag the way a player drops an item: it falls at its feet,
     * nobody can pick it up for {@link #TOSS_DELAY} ticks, and the villager itself leaves that kind of item on the
     * ground for {@code refuseTicks}. False when the slot is empty.
     */
    public static boolean toss(ServerWorld world, VillagerEntityMCA villager, int slot, long refuseTicks) {
        ItemStack stack = villager.getInventory().removeStack(slot);
        if (stack.isEmpty()) {
            return false;
        }
        Item kind = stack.getItem();
        ItemEntity dropped = villager.dropStack(stack);
        if (dropped != null) {
            dropped.setPickupDelay(TOSS_DELAY);
        }
        if (refuseTicks > 0) {
            refuse(villager, kind, world.getTime() + refuseTicks);
        }
        villager.swingHand(Hand.MAIN_HAND);
        return true;
    }

    /** The context a player would make by right-clicking that face; yaw and look come from the villager. */
    static final class VillagerPlacementContext extends ItemPlacementContext {
        private final LivingEntity villager;

        VillagerPlacementContext(World world, LivingEntity villager, ItemStack stack, BlockHitResult hit) {
            super(world, null, Hand.MAIN_HAND, stack, hit);
            this.villager = villager;
        }

        @Override
        public Direction getHorizontalPlayerFacing() {
            return villager.getHorizontalFacing();
        }

        @Override
        public float getPlayerYaw() {
            return villager.getHeadYaw();
        }

        @Override
        public Direction getPlayerLookDirection() {
            return Direction.getEntityFacingOrder(villager)[0];
        }

        @Override
        public Direction getVerticalPlayerLookDirection() {
            return villager.getPitch() < 0 ? Direction.UP : Direction.DOWN;
        }

        @Override
        public Direction[] getPlacementDirections() {
            Direction[] directions = Direction.getEntityFacingOrder(villager);
            if (canReplaceExisting()) {
                return directions;
            }
            Direction opposite = getSide().getOpposite();
            int i = 0;
            while (i < directions.length && directions[i] != opposite) {
                i++;
            }
            if (i > 0 && i < directions.length) {
                System.arraycopy(directions, 0, directions, 1, i);
                directions[0] = opposite;
            }
            return directions;
        }
    }
}
