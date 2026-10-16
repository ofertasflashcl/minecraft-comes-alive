package net.mca.entity.ai.brain.tasks.chore;

import net.mca.entity.ai.sense.Obras;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonSyntaxException;
import net.mca.Config;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.TaskUtils;
import net.mca.util.InventoryUtils;
import net.mca.util.RegistryHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChoppingTask extends AbstractChoreTask {
    private int chopTicks, targetTreeTicks;
    private BlockPos targetTree;

    public ChoppingTask() {
        super(ImmutableMap.of(MemoryModuleType.LOOK_TARGET, MemoryModuleState.VALUE_ABSENT, MemoryModuleType.WALK_TARGET, MemoryModuleState.VALUE_ABSENT));
    }

    @Override
    protected boolean shouldRun(ServerWorld world, VillagerEntityMCA villager) {
        return villager.getVillagerBrain().getCurrentJob() == Chore.CHOP && super.shouldRun(world, villager);
    }

    @Override
    protected boolean shouldKeepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        return shouldRun(world, villager);
    }

    @Override
    protected void finishRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        ItemStack stack = villager.getStackInHand(villager.getDominantHand());
        if (!stack.isEmpty()) {
            villager.setStackInHand(villager.getDominantHand(), ItemStack.EMPTY);
        }
    }

    @Override
    protected void run(ServerWorld world, VillagerEntityMCA villager, long time) {
        super.run(world, villager, time);

        if (!villager.hasStackEquipped(villager.getDominantSlot())) {
            int i = InventoryUtils.getFirstSlotContainingItem(villager.getInventory(), stack -> stack.getItem() instanceof AxeItem);
            if (i == -1) {
                abandonJobWithMessage("chore.chopping.noaxe");
            } else {
                villager.setStackInHand(villager.getDominantHand(), villager.getInventory().getStack(i));
            }
        }
    }

    @Override
    protected void keepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        if (this.villager == null) this.villager = villager;

        if (!InventoryUtils.contains(villager.getInventory(), AxeItem.class) && !villager.hasStackEquipped(villager.getDominantSlot())) {
            abandonJobWithMessage("chore.chopping.noaxe");
        } else if (!villager.hasStackEquipped(villager.getDominantSlot())) {
            int i = InventoryUtils.getFirstSlotContainingItem(villager.getInventory(), stack -> stack.getItem() instanceof AxeItem);
            ItemStack stack = villager.getInventory().getStack(i);
            villager.setStackInHand(villager.getDominantHand(), stack);
        }

        if (targetTree == null) {
            List<BlockPos> nearbyLogs = TaskUtils.getNearbyBlocks(villager.getBlockPos(), world, (blockState -> blockState.isIn(BlockTags.LOGS)), 15, 5);
            List<BlockPos> nearbyTrees = new ArrayList<>();

            // valid "trees" are logs on the ground with leaves around them
            nearbyLogs.stream()
                    .filter(log -> isTreeStartLog(world, log))
                    .forEach(nearbyTrees::add);
            targetTree = TaskUtils.getNearestPoint(villager.getBlockPos(), nearbyTrees);

            if (targetTree != null) {
                ItemStack stack = villager.getStackInHand(villager.getDominantHand());
                BlockPos pos = targetTree;
                BlockState state;
                while ((state = world.getBlockState(pos)).isIn(BlockTags.LOGS)) {
                    targetTreeTicks += getTicksFor(state, 60) / stack.getMiningSpeedMultiplier(state);
                    pos = pos.add(0, 1, 0);
                }
            }

            failedTicks = FAILED_COOLDOWN;
            return;
        }

        villager.moveTowards(targetTree);

        BlockState state = world.getBlockState(targetTree);
        if (state.isIn(BlockTags.LOGS)) {
            villager.swingHand(villager.getDominantHand());
            chopTicks++;

            // cut down a tree every few seconds, dependent on config + the mining speed multiplier
            if (chopTicks >= targetTreeTicks) {
                chopTicks = 0;

                destroyTree(world, targetTree);
            }
        } else {
            targetTree = null;
            targetTreeTicks = 0;
        }
        super.keepRunning(world, villager, time);
    }

    /**
     * Returns trues if origin is bottom point of tree.
     */
    private boolean isTreeStartLog(ServerWorld world, BlockPos origin) {
        if (!world.getBlockState(origin).isIn(BlockTags.LOGS)) {
            return false;
        }

        // ensure we're looking at a valid tree before continuing
        if (!isValidTree(world, origin.down())) {
            return false;
        }

        // a trunk the world grew, not the log wall of somebody's cabin
        if (!Obras.tree(world, origin) || Obras.inVillageBuilding(world, origin)) {
            return false;
        }

        // check upside continues and valid leaves exist.
        BlockPos.Mutable posUp = origin.mutableCopy(); // copy as mutable for reduce resources
        for (int y = 0; y < Config.getInstance().maxTreeHeight; y++) {
            BlockState up = world.getBlockState(posUp.setY(posUp.getY() + 1)); // use set directly instead of "pos_up.move(Direction.UP)" (set is faster)
            if (up.isIn(BlockTags.LOGS)) {continue;} else return up.isIn(BlockTags.LEAVES);
        }
        return false;
    }

    private void destroyTree(ServerWorld world, BlockPos origin) {
        ItemStack stack = villager.getStackInHand(villager.getDominantHand());
        BlockPos pos = origin;
        BlockState state;

        while ((state = world.getBlockState(pos)).isIn(BlockTags.LOGS)) {
            if (Obras.mayBreak(world, villager, pos) && world.breakBlock(pos, false, villager)) {
                Obras.broken(world, pos);
                pos = pos.add(0, 1, 0);
                villager.getInventory().addStack(new ItemStack(state.getBlock(), 1));
                stack.damage(1, villager, e -> e.sendEquipmentBreakStatus(e.getDominantSlot()));
            } else {
                break;
            }
        }
    }

    private boolean isValidTree(ServerWorld world, Vec3d pos) {
        return isValidTree(world, BlockPos.ofFloored(pos));
    }

    private boolean isValidTree(ServerWorld world, BlockPos pos) {
        // Similar logic to WanderOrTeleportToTargetTask#isAreaSafe
        final BlockState state = world.getBlockState(pos);
        final Identifier stateId = Registries.BLOCK.getId(state.getBlock());
        for (String blockId : Config.getInstance().validTreeSources) {
            if (blockId.equals(stateId.toString())) {
                return true;
            } else if (blockId.charAt(0) == '#') {
                Identifier identifier = new Identifier(blockId.substring(1));
                TagKey<Block> tag = TagKey.of(RegistryKeys.BLOCK, identifier);
                if (tag != null && !RegistryHelper.isTagEmpty(tag)) {
                    if (state.isIn(tag)) {
                        return true;
                    }
                } else {
                    throw new JsonSyntaxException("Unknown block tag in validTreeSources '" + identifier + "'");
                }
            }
        }
        return false;
    }

    private int getTicksFor(BlockState state, int fallback) {
        // Similar logic to WanderOrTeleportToTargetTask#isAreaSafe
        final Map<String, Integer> sources = Config.getInstance().maxTreeTicks;
        final Identifier stateId = Registries.BLOCK.getId(state.getBlock());
        for (String blockId : sources.keySet()) {
            if (blockId.equals(stateId.toString())) {
                return sources.get(blockId);
            } else if (blockId.charAt(0) == '#') {
                Identifier identifier = new Identifier(blockId.substring(1));
                TagKey<Block> tag = TagKey.of(RegistryKeys.BLOCK, identifier);
                if (tag != null && !RegistryHelper.isTagEmpty(tag)) {
                    if (state.isIn(tag)) {
                        return sources.get(blockId);
                    }
                } else {
                    throw new JsonSyntaxException("Unknown block tag in maxTreeTicks '" + identifier + "'");
                }
            }
        }
        return fallback;
    }
}
