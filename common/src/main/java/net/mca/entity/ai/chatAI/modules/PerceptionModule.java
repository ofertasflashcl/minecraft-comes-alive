package net.mca.entity.ai.chatAI.modules;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.MoveState;
import net.mca.entity.ai.body.Mining;
import net.mca.entity.ai.brain.tasks.chore.ProspectingTask;
import net.mca.entity.ai.sense.Obras;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the villager knows about its own situation right now, from its body, its bag and its eyes (never more
 * than its eyes saw), so a language model talks about the world it is really in.
 */
public class PerceptionModule {
    public static void apply(List<String> input, VillagerEntityMCA villager, ServerPlayerEntity player) {
        World world = villager.getWorld();
        long now = world.getTime();
        VisualMemory memory = Retina.memory(villager);

        // what it is up to
        Chore job = villager.getVillagerBrain().getCurrentJob();
        if (job != Chore.NONE) {
            input.add("$villager is working: " + job.name().toLowerCase(Locale.ROOT) + ". ");
            String ore = ProspectingTask.wanted(villager);
            if (job == Chore.PROSPECT && ore != null) {
                input.add("$villager was asked to look for " + ore.replace('_', ' ') + " first. ");
            }
        }
        String doing = memory.doing();
        if (doing != null && !doing.isBlank() && now - memory.doingTick() < 1200) {
            input.add("What $villager is doing at this moment (in Spanish): " + doing + ". ");
        }
        MoveState move = villager.getVillagerBrain().getMoveState();
        if (move == MoveState.FOLLOW) {
            input.add("$villager is following $player. ");
        } else if (move == MoveState.STAY) {
            input.add("$villager is waiting in place until told otherwise. ");
        }

        // its body and where it is
        float hp = villager.getHealth() / villager.getMaxHealth();
        if (hp < 0.35F) {
            input.add("$villager is badly hurt. ");
        } else if (hp < 0.8F) {
            input.add("$villager is a bit hurt. ");
        }
        BlockPos feet = villager.getBlockPos();
        if (!world.isSkyVisible(feet.up()) && feet.getY() < world.getSeaLevel() - 4) {
            input.add("$villager is underground, at depth y=" + feet.getY() + ". ");
        }
        if (villager.isSubmergedInWater()) {
            input.add("$villager is under water. ");
        } else if (villager.isTouchingWater()) {
            input.add("$villager is standing in water. ");
        }

        // hands and bag
        ItemStack hand = villager.getMainHandStack();
        if (!hand.isEmpty()) {
            input.add("$villager holds " + hand.getName().getString() + ". ");
        }
        Map<String, Integer> bag = new LinkedHashMap<>();
        SimpleInventory inv = villager.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                bag.merge(stack.getName().getString(), stack.getCount(), Integer::sum);
            }
        }
        if (bag.isEmpty()) {
            input.add("$villager's bag is empty. ");
        } else {
            List<String> items = new ArrayList<>();
            bag.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12)
                    .forEach(e -> items.add(e.getValue() + " " + e.getKey()));
            input.add("$villager carries: " + String.join(", ", items) + ". ");
        }

        // what its eyes saw lately
        if (player.getWorld() == world && villager.distanceTo(player) < Retina.ENTITY_RANGE) {
            input.add(Retina.canSee(world, villager, player) ? "$villager can see $player right now. " : "$villager cannot see $player right now. ");
        }
        Map<String, Integer> beings = new LinkedHashMap<>();
        for (VisualMemory.SeenEntity seen : memory.entities(now, 100)) {
            if (seen.type == net.minecraft.entity.EntityType.PLAYER) {
                continue;
            }
            String kind = seen.type.getName().getString() + (seen.type.getSpawnGroup() == SpawnGroup.MONSTER ? " (hostile)" : "");
            beings.merge(kind, 1, Integer::sum);
        }
        if (!beings.isEmpty()) {
            List<String> list = new ArrayList<>();
            beings.forEach((kind, n) -> list.add((n > 1 ? n + " " : "") + kind));
            input.add("$villager just saw: " + String.join(", ", list.subList(0, Math.min(8, list.size()))) + ". ");
        }
        Map<String, Integer> things = new LinkedHashMap<>();
        boolean water = false;
        boolean lava = false;
        for (VisualMemory.SeenBlock block : memory.blocks(b -> now - b.lastSeen < 600 && b.state != null && b.distance < 24)) {
            if (Mining.isOre(block.state)) {
                things.merge(block.state.getBlock().getName().getString(), 1, Integer::sum);
            } else if (block.state.getBlock() instanceof net.minecraft.block.ChestBlock) {
                things.merge("chest", 1, Integer::sum);
            } else if (Obras.crop(block.state) && !(block.state.getBlock() instanceof net.minecraft.block.FarmlandBlock)) {
                things.merge("crops", 1, Integer::sum);
            }
            water |= block.state.getFluidState().isIn(FluidTags.WATER);
            lava |= block.state.getFluidState().isIn(FluidTags.LAVA);
        }
        if (!things.isEmpty()) {
            List<String> list = new ArrayList<>();
            things.forEach((thing, n) -> list.add(n + " " + thing));
            input.add("Nearby $villager saw: " + String.join(", ", list.subList(0, Math.min(8, list.size()))) + ". ");
        }
        if (lava) {
            input.add("$villager saw lava close by. ");
        }
        if (water && !villager.isTouchingWater()) {
            input.add("$villager saw water close by. ");
        }

    }
}
