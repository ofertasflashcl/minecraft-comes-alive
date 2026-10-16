package net.mca.entity.ai.chatAI;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a villager does with its hands when asked in words: hand something over the way a player throws an item
 * to a friend. The item is named in plain words, in Spanish or English ("dame 3 lingotes de hierro",
 * "iron_ingot 3"), and matched against what is really in its bag; it never hands over what it does not have.
 */
public final class Acciones {
    /** How far a thrown item reaches a player. */
    public static final double HAND_OVER = 8.0;

    private static final Map<String, String> WORDS = Map.ofEntries(
            Map.entry("hierro", "iron"), Map.entry("diamante", "diamond"), Map.entry("diamantes", "diamond"),
            Map.entry("carbon", "coal"), Map.entry("oro", "gold"), Map.entry("cobre", "copper"),
            Map.entry("esmeralda", "emerald"), Map.entry("esmeraldas", "emerald"), Map.entry("lapislazuli", "lapis"),
            Map.entry("piedra", "cobblestone"), Map.entry("piedras", "cobblestone"), Map.entry("roca", "cobblestone"),
            Map.entry("rocas", "cobblestone"), Map.entry("adoquin", "cobblestone"), Map.entry("tierra", "dirt"),
            Map.entry("madera", "log"), Map.entry("tronco", "log"), Map.entry("troncos", "log"),
            Map.entry("tabla", "planks"), Map.entry("tablas", "planks"), Map.entry("tablones", "planks"),
            Map.entry("palo", "stick"), Map.entry("palos", "stick"), Map.entry("antorcha", "torch"),
            Map.entry("antorchas", "torch"), Map.entry("pan", "bread"), Map.entry("panes", "bread"),
            Map.entry("manzana", "apple"), Map.entry("manzanas", "apple"), Map.entry("pescado", "cod"),
            Map.entry("pico", "pickaxe"), Map.entry("picota", "pickaxe"), Map.entry("espada", "sword"),
            Map.entry("hacha", "axe"), Map.entry("pala", "shovel"), Map.entry("azada", "hoe"), Map.entry("arco", "bow"),
            Map.entry("flecha", "arrow"), Map.entry("flechas", "arrow"), Map.entry("cofre", "chest"),
            Map.entry("horno", "furnace"), Map.entry("mesa", "crafting_table"), Map.entry("lingote", "ingot"),
            Map.entry("lingotes", "ingot"), Map.entry("crudo", "raw"), Map.entry("cruda", "raw"),
            Map.entry("mineral", "ore"), Map.entry("semilla", "seeds"), Map.entry("semillas", "seeds"),
            Map.entry("trigo", "wheat"), Map.entry("zanahoria", "carrot"), Map.entry("zanahorias", "carrot"),
            Map.entry("papa", "potato"), Map.entry("papas", "potato"), Map.entry("patata", "potato"),
            Map.entry("patatas", "potato"), Map.entry("hueso", "bone"), Map.entry("huesos", "bone"),
            Map.entry("hilo", "string"), Map.entry("cuerda", "string"), Map.entry("polvora", "gunpowder"),
            Map.entry("lana", "wool"), Map.entry("cuero", "leather"), Map.entry("casco", "helmet"),
            Map.entry("peto", "chestplate"), Map.entry("pechera", "chestplate"), Map.entry("pantalones", "leggings"),
            Map.entry("botas", "boots"), Map.entry("escudo", "shield"), Map.entry("libro", "book"),
            Map.entry("papel", "paper"), Map.entry("vidrio", "glass"), Map.entry("arena", "sand"),
            Map.entry("grava", "gravel"), Map.entry("cana", "sugar_cane"), Map.entry("azucar", "sugar"),
            Map.entry("huevo", "egg"), Map.entry("huevos", "egg"), Map.entry("leche", "milk"), Map.entry("cubo", "bucket"),
            Map.entry("balde", "bucket"), Map.entry("cuarzo", "quartz")
    );

    private static final Set<String> FILLER = Set.of(
            "un", "una", "uno", "unos", "unas", "el", "la", "los", "las", "de", "del", "al", "tu", "tus", "me", "mi", "mis",
            "dame", "pasame", "regalame", "entregame", "por", "favor", "porfa", "porfavor", "que", "tienes", "todo", "toda",
            "todos", "todas", "algo", "a", "an", "the", "some", "your", "give", "please", "all", "of", "my", "y", "and",
            "con", "en", "para", "lo", "le", "puedes", "podrias", "quiero"
    );

    private static final Map<String, Integer> NUMBERS = Map.ofEntries(
            Map.entry("un", 1), Map.entry("una", 1), Map.entry("uno", 1), Map.entry("one", 1), Map.entry("a", 1),
            Map.entry("an", 1), Map.entry("dos", 2), Map.entry("two", 2), Map.entry("tres", 3), Map.entry("three", 3),
            Map.entry("cuatro", 4), Map.entry("four", 4), Map.entry("cinco", 5), Map.entry("five", 5),
            Map.entry("seis", 6), Map.entry("six", 6), Map.entry("diez", 10), Map.entry("ten", 10)
    );

    private Acciones() {
    }

    public static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** What was asked for, read from plain words: the item words and how many (-1 for "as much as there is"). */
    public record Request(List<String> words, int count, boolean food) {
    }

    public static Request read(String text) {
        String clean = normalize(text).replaceAll("[^a-z0-9_ ]", " ");
        List<String> words = new ArrayList<>();
        int count = -1;
        boolean food = false;
        for (String raw : clean.split("\\s+")) {
            if (raw.isEmpty()) {
                continue;
            }
            if (raw.matches("\\d+")) {
                count = Math.max(1, Math.min(64, Integer.parseInt(raw)));
                continue;
            }
            if (count < 0 && NUMBERS.containsKey(raw)) {
                count = NUMBERS.get(raw);
            }
            if (raw.equals("comida") || raw.equals("food") || raw.equals("comer") || raw.equals("alimento")) {
                food = true;
                continue;
            }
            if (FILLER.contains(raw)) {
                continue;
            }
            for (String part : raw.split("_")) {
                if (!part.isEmpty()) {
                    words.add(WORDS.getOrDefault(part, part.endsWith("s") && part.length() > 3 && !WORDS.containsKey(part) ? part.substring(0, part.length() - 1) : part));
                }
            }
        }
        return new Request(words, count, food);
    }

    /** The bag slot that best fits the request, or -1. */
    public static int find(SimpleInventory bag, Request request) {
        int best = -1;
        int bestScore = 0;
        int bestCount = 0;
        for (int i = 0; i < bag.size(); i++) {
            ItemStack stack = bag.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            int score = 0;
            if (request.food() && stack.isFood()) {
                score += 3;
            }
            Set<String> names = names(stack);
            for (String word : request.words()) {
                if (names.contains(word)) {
                    score += 2;
                } else if (word.length() > 3 && names.stream().anyMatch(n -> n.startsWith(word) || word.startsWith(n) && n.length() > 3)) {
                    score += 1;
                }
            }
            // everything asked for has to be there: "iron ingot" is not "iron pickaxe"
            if (!request.words().isEmpty() && request.words().stream().anyMatch(w -> !names.contains(w)
                    && names.stream().noneMatch(n -> n.startsWith(w) || w.startsWith(n) && n.length() > 3))) {
                continue;
            }
            if (score > bestScore || score == bestScore && score > 0 && stack.getCount() > bestCount) {
                best = i;
                bestScore = score;
                bestCount = stack.getCount();
            }
        }
        return bestScore > 0 ? best : -1;
    }

    private static Set<String> names(ItemStack stack) {
        Set<String> names = new HashSet<>();
        for (String part : Registries.ITEM.getId(stack.getItem()).getPath().split("_")) {
            names.add(part);
        }
        for (String part : normalize(stack.getName().getString()).split("[^a-z0-9]+")) {
            if (!part.isEmpty()) {
                names.add(part);
            }
        }
        return names;
    }

    /** How handing something over went. */
    public enum Status { GAVE, NONE, FAR }

    /** What it handed over (or was asked for), for whoever puts it into words. */
    public record Given(Status status, ItemStack item, int count, String asked, double distance) {
        /** In plain English, for a language model to tell the player in its own words. */
        public String english(String playerName) {
            return switch (status) {
                case GAVE -> "You just threw " + count + " " + item.getName().getString() + " to " + playerName + ".";
                case NONE -> "You do not have any \"" + asked + "\" in your bag, so you gave nothing.";
                case FAR -> playerName + " is " + (int) distance + " blocks away, too far to throw it; ask them to come closer.";
            };
        }
    }

    /** "give-item": throws the asked-for item to the player, or says why not. */
    public static String give(ServerPlayerEntity player, VillagerEntityMCA villager, String argument) {
        return hand(player, villager, argument).english(player.getName().getString());
    }

    /** Hands over what was asked for when it has it and the player is within a throw. */
    public static Given hand(ServerPlayerEntity player, VillagerEntityMCA villager, String argument) {
        Request request = read(argument);
        SimpleInventory bag = villager.getInventory();
        int slot = find(bag, request);
        // what it holds in its hand counts too
        if (slot < 0) {
            ItemStack hand = villager.getMainHandStack();
            if (!hand.isEmpty()) {
                SimpleInventory held = new SimpleInventory(hand.copy());
                if (find(held, request) == 0) {
                    villager.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
                    ItemStack rest = bag.addStack(hand);
                    if (!rest.isEmpty()) {
                        villager.setStackInHand(Hand.MAIN_HAND, rest);
                    }
                    slot = find(bag, request);
                }
            }
        }
        double distance = player.getWorld() == villager.getWorld() ? Math.sqrt(player.squaredDistanceTo(villager)) : Double.MAX_VALUE / 4;
        if (slot < 0 || !(villager.getWorld() instanceof ServerWorld world)) {
            return new Given(Status.NONE, ItemStack.EMPTY, 0, argument.isBlank() ? "eso" : argument.trim(), distance);
        }
        ItemStack stack = bag.getStack(slot);
        int amount = request.count() < 0 ? stack.getCount() : Math.min(request.count(), stack.getCount());
        if (distance > HAND_OVER) {
            return new Given(Status.FAR, stack.copyWithCount(amount), amount, argument.trim(), distance);
        }
        ItemStack gift = stack.split(amount);
        bag.markDirty();
        throwTo(world, villager, player, gift);
        return new Given(Status.GAVE, gift.copy(), amount, argument.trim(), distance);
    }

    /** Says a line to a player who can hear it (the in-game checks use players without a connection). */
    public static void say(ServerPlayerEntity player, VillagerEntityMCA villager, String key, Object... params) {
        if (player.networkHandler != null) {
            villager.sendChatMessage(player, key, params);
        }
    }

    /** Throws {@code stack} toward {@code player} the way a player drops an item at a friend's feet. */
    public static ItemEntity throwTo(ServerWorld world, VillagerEntityMCA villager, ServerPlayerEntity player, ItemStack stack) {
        Vec3d from = villager.getEyePos().subtract(0, 0.3, 0);
        Vec3d toward = player.getPos().add(0, 0.5, 0).subtract(from);
        double distance = Math.max(0.5, toward.length());
        Vec3d dir = toward.normalize();
        double speed = Math.min(0.5, 0.12 + distance * 0.05);
        ItemEntity item = new ItemEntity(world, from.x, from.y, from.z, stack);
        item.setVelocity(dir.x * speed, dir.y * speed + 0.15, dir.z * speed);
        item.setPickupDelay(10);
        world.spawnEntity(item);
        net.mca.entity.ai.body.Hands.aim(villager, player.getEyePos());
        villager.swingHand(Hand.MAIN_HAND);
        world.playSound(null, villager.getBlockPos(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.NEUTRAL, 0.4F, 0.8F);
        return item;
    }
}
