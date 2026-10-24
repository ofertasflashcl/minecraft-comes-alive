package net.mca.entity.ai.chatAI.modules;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.MoveState;
import net.mca.entity.ai.body.Mining;
import net.mca.entity.ai.body.Progreso;
import net.mca.entity.ai.brain.tasks.chore.ProspectingTask;
import net.mca.entity.ai.chatAI.Vistazo;
import net.mca.entity.ai.mente.Recuerdos;
import net.mca.entity.ai.mente.Voluntad;
import net.mca.entity.ai.sense.Escena;
import net.mca.entity.ai.sense.Lugar;
import net.mca.entity.ai.sense.Obras;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.Tacto;
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
 * than its eyes saw), so a language model talks about the world it is really in. The surroundings come from the
 * scene it understands ({@link Escena#forModel()}: place, depth, light, weather, every thing with its direction and
 * distance, and what it did not see); only when that cannot be made, from the raw sightings as before.
 */
public class PerceptionModule {
    /** The scene for a model, cut after a sentence past this many characters (about 300 tokens). */
    private static final int MAX_SCENE = 1200;

    public static void apply(List<String> input, VillagerEntityMCA villager, ServerPlayerEntity player) {
        World world = villager.getWorld();
        long now = world.getTime();
        VisualMemory memory = Retina.memory(villager);

        // what it is up to
        Chore job = villager.getVillagerBrain().getCurrentJob();
        if (job != Chore.NONE) {
            input.add(job == Chore.PROSPECT ? "$villager is connected: it plays on its own like a player (gear, wood, mining, food, home). "
                    : "$villager is working: " + job.name().toLowerCase(Locale.ROOT) + ". ");
            String ore = ProspectingTask.wanted(villager);
            if (job == Chore.PROSPECT && ore != null) {
                input.add("$villager was asked to look for " + ore.replace('_', ' ') + " first. ");
            }
        }
        String doing = memory.doing();
        if (doing != null && !doing.isBlank() && now - memory.doingTick() < 1200) {
            input.add("What $villager is doing at this moment (written in Spanish): " + doing + ". ");
        } else if (job == Chore.NONE) {
            input.add("$villager has no work to do right now. ");
        }
        MoveState move = villager.getVillagerBrain().getMoveState();
        boolean connected = Voluntad.conectado(villager);
        if (connected) {
            // connected: it lives on its own, and its will says what it is set on and why
            input.add("Your current mode and what you are thinking: "
                    + Voluntad.forModel(villager, player.getName().getString()) + " ");
        } else if (move == MoveState.FOLLOW) {
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
        input.add("Your exact position: x=" + feet.getX() + ", y=" + feet.getY() + ", z=" + feet.getZ() + " in the "
                + world.getRegistryKey().getValue().getPath().replace('_', ' ') + ". When asked where you are or for your coordinates, "
                + "copy these three numbers exactly, as X Y Z (\"" + feet.getX() + " " + feet.getY() + " " + feet.getZ() + "\"); never round or change them. ");
        Escena scene = Vistazo.escena(villager);
        if (scene == null) {
            // no scene: where it is from its sense of place alone, never "underground" for a house roof
            Lugar lugar = Vistazo.lugar(villager);
            if (lugar == Lugar.BAJO_TIERRA) {
                input.add("$villager is underground, about " + Vistazo.profundidad(villager) + " blocks below the land (y=" + feet.getY() + "). ");
            } else if (lugar == Lugar.BAJO_TECHO) {
                input.add("$villager is indoors or under a roof, at ground level. ");
            } else if (lugar == Lugar.AFUERA) {
                input.add("$villager is outside, under the open sky. ");
            }
        }
        if (villager.isSubmergedInWater()) {
            input.add("$villager is under water. ");
        } else if (villager.isTouchingWater()) {
            input.add("$villager is standing in water. ");
        }

        // what its body touches: the ceiling over its head and how high, the walls at its head, the floor ({@link Tacto})
        try {
            String felt = Tacto.describeOverhead(villager, true);
            if (!felt.isBlank()) {
                input.add("What $villager feels right around its body, over its head and at its sides (in Spanish): " + felt + " ");
            }
        } catch (RuntimeException e) {
            net.mca.MCA.LOGGER.warn("[Tropeles] {} no pudo sentir lo que tiene encima: {}", villager.getName().getString(), e.toString());
        }

        // what it remembers: the last things that happened to it, the places it knows ({@link Recuerdos})
        try {
            Recuerdos recuerdos = Recuerdos.of(villager);
            String remembered = recuerdos == null ? "" : recuerdos.forModel(now, 8);
            if (!remembered.isBlank()) {
                input.add(shorten(remembered) + "When asked what you did, which way you came up, what $player told you or where your "
                        + "chest, home or mine is, answer from these memories and their exact numbers; never invent others. ");
            }
        } catch (RuntimeException e) {
            net.mca.MCA.LOGGER.warn("[Tropeles] los recuerdos de {} no se pudieron contar: {}", villager.getName().getString(), e.toString());
        }

        // hands and bag
        ItemStack hand = villager.getMainHandStack();
        if (!hand.isEmpty()) {
            input.add("$villager holds " + hand.getName().getString() + ". ");
        }
        Map<String, Integer> bag = new LinkedHashMap<>();
        int food = 0;
        SimpleInventory inv = villager.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                bag.merge(stack.getName().getString(), stack.getCount(), Integer::sum);
                if (stack.isFood()) {
                    food += stack.getCount();
                }
            }
        }
        if (job == Chore.PROSPECT) {
            input.add(food == 0 ? "$villager has no food left; when hungry it must climb out to find some. "
                    : "$villager has " + food + " food to eat in the bag. ");
            input.add(trip(villager, inv));
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
        if (scene != null) {
            String text = null;
            try {
                text = scene.forModel();
            } catch (RuntimeException e) {
                net.mca.MCA.LOGGER.warn("[Tropeles] la escena de {} no se pudo contar: {}", villager.getName().getString(), e.toString());
            }
            if (text != null && !text.isBlank()) {
                input.add("What $villager sees and knows of the surroundings: " + shorten(text.replace('\n', ' ').trim()) + " ");
                return;
            }
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

    /**
     * The mining trip in one line: room left in the bag, pickaxes and how worn the best one is, how deep it is.
     * What it would weigh before heading back, so the model's words fit what it really does.
     */
    private static String trip(VillagerEntityMCA villager, SimpleInventory inv) {
        int free = 0;
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isEmpty()) {
                free++;
            }
        }
        StringBuilder line = new StringBuilder("Mining trip: ").append(free).append(" of ").append(inv.size()).append(" bag slots free");
        try {
            int picks = Progreso.countPicks(villager);
            line.append(", ").append(picks).append(picks == 1 ? " pickaxe" : " pickaxes");
            if (picks > 0 && Progreso.worn(Progreso.bestPickStack(villager))) {
                line.append(" (the best one is about to break)");
            }
        } catch (RuntimeException ignored) {
            // the pickaxes are left out
        }
        Lugar lugar = Vistazo.lugar(villager);
        int depth = Vistazo.profundidad(villager);
        if (lugar == Lugar.BAJO_TIERRA && depth > 0) {
            line.append(", ").append(depth).append(" blocks below the surface");
        } else if (lugar != null) {
            line.append(", ").append(lugar == Lugar.AFUERA ? "outside" : "under a roof");
        }
        return line.append(". ").toString();
    }

    /** At most {@link #MAX_SCENE} characters, cut after a sentence. */
    private static String shorten(String text) {
        if (text.length() <= MAX_SCENE) {
            return text;
        }
        int cut = text.lastIndexOf(". ", MAX_SCENE);
        return cut > 200 ? text.substring(0, cut + 1) : text.substring(0, MAX_SCENE) + "...";
    }
}
