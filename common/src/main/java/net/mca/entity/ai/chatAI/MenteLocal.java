package net.mca.entity.ai.chatAI;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.MoveState;
import net.mca.entity.ai.brain.tasks.chore.ProspectingTask;
import net.mca.entity.ai.body.Cofres;
import net.mca.entity.ai.body.Mining;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The villager's own mind, which needs no internet: it understands plain orders and questions in Spanish and
 * English, carries the orders out the same way the language models do (through the chat commands), and answers
 * from what it really knows: what it carries, what it is doing, what its eyes saw. The language models only add
 * a nicer way of saying things; with all of them down a villager still listens and still obeys.
 */
public final class MenteLocal {
    /** What the player asked for: an order (a chat command and its argument), a question, or nothing clear. */
    public record Intent(@Nullable String command, String argument, @Nullable String question, boolean spanish) {
        public boolean order() {
            return command != null;
        }
    }

    private static final String ORES = "diamantes?|hierro|oro|carbon|cobre|esmeraldas?|lapislazuli|lapis|redstone|cuarzo|netherita|"
            + "diamonds?|iron|gold|coal|copper|emeralds?|quartz|netherite|ancient debris";

    /** Orders, most specific first: "no me sigas" must win over "sigueme". */
    private static final List<Map.Entry<String, Pattern>> ORDERS = List.of(
            order("move-freely", "no me sigas|deja de seguirme|dejame (solo|sola|tranquilo|tranquila)|anda(te)? libre|puedes irte|ya puedes irte|"
                    + "vete (tranquilo|tranquila)|haz lo tuyo|anda a pasear|stop following|leave me alone|you can go|move freely|go away|do your thing"),
            order("stop-working", "deja de (trabajar|minar|talar|pescar|cosechar|cazar)|para de (trabajar|minar|talar|pescar|cosechar|cazar)|"
                    + "detente|deten el trabajo|basta de trabajar|ya no (mines|trabajes|tales|peques|caces)|"
                    + "stop (working|mining|chopping|fishing|harvesting|hunting)|quit (working|mining)"),
            order("remove-armor", "quitate (la |tu )?armadura|sacate (la |tu )?armadura|take off (your |the )?armou?r|remove (your |the )?armou?r"),
            order("wear-armor", "ponte (la |tu )?armadura|equipate|vistete (con )?(la |tu )?armadura|wear (your |the )?armou?r|put on (your |the )?armou?r"),
            order("give-item", "dame|pasame|regalame|entregame|tirame|lanzame|prestame|me das|me pasas|me regalas|me prestas|"
                    + "give me|hand me|can i have|toss me|throw me|pass me"),
            order("store-items", "guarda (tus|las|todas tus|todo|lo que)|guardalo|guardalas|guardalos|deja tus cosas|ve a guardar|anda a guardar|"
                    + "store (your|it|everything|the)|put (your|it|everything|the) .*chest|deposit"),
            order("follow-player", "sigueme|seguime|siguenos|seguirme|ven conmigo|vente conmigo|vienes conmigo|venir conmigo|vamos juntos|vamonos|"
                    + "vamos a (explorar|caminar|pasear|dar una vuelta)|acompaname|acompanarme|acompaneme|me acompanas|me acompanarias|"
                    + "quedate conmigo|camina conmigo|no te (alejes|separes|despegues) de mi|pegate a mi|ven (aca|aqui|para aca|pa ca|para aqui)|"
                    + "follow me|come with me|come along|come here|tag along|walk with me|accompany me|join me|stick with me|stay (close|with me)|"
                    + "let'?s go together"),
            order("stay-here", "quedate|esperame (aqui|aca|ahi)|espera (aqui|aca|ahi)|no te muevas|stay here|stay there|wait here|wait for me|don'?t move"),
            order("start-mining", "ve a minar|vete a minar|anda a minar|ponte a minar|ve a la mina|a minar|mina|minar|excava|"
                    + "go mine|go mining|start mining|mine for|dig for|"
                    + "(busca|buscame|consigue|consigueme|encuentra|encuentrame|traeme|trae|saca|sacame|find|get me|look for|search for|go get) .*(" + ORES + ")"),
            order("start-chopping", "tala|talar|ve a talar|corta (arboles|madera|troncos|lena)|consigue madera|trae madera|chop|cut (down )?trees|get (some )?wood"),
            order("start-fishing", "pesca|pescar|ve a pescar|go fish(ing)?|start fishing|catch (some )?fish"),
            order("start-harvesting", "cosecha|cosechar|recoge (la cosecha|los cultivos|el trigo)|harvest"),
            order("start-hunting", "caza|cazar|ve a cazar|go hunt(ing)?|start hunting"),
            order("try-go-home", "ve(te)? a (tu )?casa|anda(te)? a (tu )?casa|vuelve a (tu )?casa|regresa a (tu )?casa|go home|head home"),
            order("open-trade-window", "comerciar|tradear|intercambiar|que vendes|que tienes para vender|quiero comprar|let'?s trade|trade|what do you sell")
    );

    private static final List<Map.Entry<String, Pattern>> QUESTIONS = List.of(
            order("doing", "que (estas haciendo|haces|andas haciendo)|en que andas|en que estas|what are you doing|what('re| are) you up to"),
            order("bag", "que (tienes|llevas|traes|cargas)|tu inventario|tu mochila|que hay en tu mochila|what do you have|what are you carrying|your inventory|your bag"),
            order("health", "como estas|estas bien|como te sientes|que tal estas|how are you|are you (ok|okay|alright)|you (ok|okay)"),
            order("where", "donde estas|en donde estas|where are you"),
            order("see", "que ves|que estas viendo|que hay (alrededor|cerca)|what do you see|what can you see|what'?s around"),
            order("name", "como te llamas|quien eres|cual es tu nombre|what('s| is) your name|who are you"),
            order("help", "que puedes hacer|que sabes hacer|ayuda|what can you do|help"),
            order("thanks", "gracias|muchas gracias|thanks|thank you|bien hecho|buen trabajo|good job|well done"),
            order("greet", "hola|buenas|buenos dias|buenas tardes|buenas noches|ola|que tal|hey|hi|hello")
    );

    private static final Pattern SPANISH = Pattern.compile("\\b(el|la|los|las|que|de|por|favor|hola|tu|tus|me|mi|dame|ven|sigueme|quedate|"
            + "como|donde|tienes|estas|gracias|aqui|aca|busca|mina|tala|pesca|guarda|para|con|una?|y|si|no|vamos|oye|porfa)\\b");
    private static final Pattern ENGLISH = Pattern.compile("\\b(the|you|your|me|my|please|follow|stay|give|what|where|how|are|is|do|have|"
            + "hello|hi|thanks|thank|here|go|come|stop|mine|find|get|can|let'?s|i)\\b");

    private MenteLocal() {
    }

    private static Map.Entry<String, Pattern> order(String name, String regex) {
        return Map.entry(name, Pattern.compile("(?<![a-z])(" + regex + ")(?![a-z])"));
    }

    private static String clean(String text) {
        return Acciones.normalize(text).replaceAll("[^a-z0-9' ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Whether the player wrote in Spanish (the default when it is not clearly English). */
    public static boolean spanish(String text) {
        String t = clean(text);
        if (text.matches("(?s).*[ñáéíóúü¿¡].*")) {
            return true;
        }
        return count(SPANISH, t) >= count(ENGLISH, t);
    }

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** Reads the message: which order or question it is, if any. */
    public static Intent read(String text) {
        String t = clean(text);
        boolean spanish = spanish(text);
        // a bare "para", "alto" or "stop" is an order to stop whatever it is doing
        if (t.matches("(para|alto|stop|basta|halt|ya basta|para ya)")) {
            return new Intent("stop", "", null, spanish);
        }
        for (Map.Entry<String, Pattern> entry : ORDERS) {
            Matcher m = entry.getValue().matcher(t);
            if (m.find()) {
                String argument = "";
                if (entry.getKey().equals("give-item")) {
                    argument = t.substring(m.end()).trim();
                } else if (entry.getKey().equals("start-mining")) {
                    Matcher ore = Pattern.compile("(?<![a-z])(" + ORES + ")(?![a-z])").matcher(t);
                    argument = ore.find() ? ore.group(1) : "";
                }
                return new Intent(entry.getKey(), argument, null, spanish);
            }
        }
        for (Map.Entry<String, Pattern> entry : QUESTIONS) {
            if (entry.getValue().matcher(t).find()) {
                return new Intent(null, "", entry.getKey(), spanish);
            }
        }
        return new Intent(null, "", null, spanish);
    }

    // ------------------------------------------------------------------ answering, from what it really knows

    /**
     * The words it says back. {@code outcome} is how the order went (null when it was not carried out because it
     * was already doing it), {@code given} the hand-over when it was one, {@code refused} when it will not obey.
     */
    public static String answer(ServerPlayerEntity player, VillagerEntityMCA villager, Intent intent, @Nullable String outcome,
                                @Nullable Acciones.Given given, @Nullable Cofres.Store stored, boolean refused) {
        boolean es = intent.spanish();
        String name = player.getName().getString();
        if (refused) {
            return pick(villager, es ? new String[]{"No pienso hacerte caso.", "¿Y por qué tendría que hacerte caso a ti?", "Ni lo sueñes."}
                    : new String[]{"I'm not doing that for you.", "Why would I listen to you?", "Not a chance."});
        }
        if (intent.command() != null) {
            return order(villager, intent, outcome, given, stored, es, name);
        }
        String q = intent.question() == null ? "" : intent.question();
        return switch (q) {
            case "doing" -> doing(villager, es);
            case "bag" -> bag(villager, es);
            case "health" -> health(villager, player, es);
            case "where" -> where(villager, es);
            case "see" -> see(villager, es);
            case "name" -> es ? "Me llamo " + villager.getName().getString() + "." : "I'm " + villager.getName().getString() + ".";
            case "help" -> help(es);
            case "thanks" -> pick(villager, es ? new String[]{"¡De nada!", "Cuando quieras.", "Para eso estamos."}
                    : new String[]{"You're welcome!", "Any time.", "No problem."});
            case "greet" -> pick(villager, es ? new String[]{"¡Hola, " + name + "!", "¡Buenas, " + name + "! ¿Qué necesitas?", "Hola. " + doing(villager, true)}
                    : new String[]{"Hi, " + name + "!", "Hello, " + name + ". Need anything?", "Hey. " + doing(villager, false)});
            default -> pick(villager, es
                    ? new String[]{"Mm, no te entendí bien. " + help(true), "¿Cómo? No te sigo. " + help(true)}
                    : new String[]{"Hm, I didn't quite get that. " + help(false), "Sorry, what? " + help(false)});
        };
    }

    private static String order(VillagerEntityMCA villager, Intent intent, @Nullable String outcome, @Nullable Acciones.Given given,
                                @Nullable Cofres.Store stored, boolean es, String name) {
        String command = intent.command();
        if (outcome == null && !"give-item".equals(command) && !"store-items".equals(command)) {
            return pick(villager, es ? new String[]{"Ya estoy en eso.", "Eso ya lo estoy haciendo."} : new String[]{"Already on it.", "I'm already doing that."});
        }
        return switch (command) {
            case "follow-player" -> pick(villager, es ? new String[]{"¡Vamos! Te sigo.", "Voy contigo, " + name + ".", "Detrás tuyo."}
                    : new String[]{"Let's go! I'm right behind you.", "Coming with you, " + name + "."});
            case "stay-here" -> pick(villager, es ? new String[]{"Me quedo aquí.", "Aquí te espero."} : new String[]{"I'll stay here.", "I'll wait right here."});
            case "move-freely" -> pick(villager, es ? new String[]{"Bueno, ando por ahí.", "Vale, me voy a lo mío."} : new String[]{"Alright, I'll wander around.", "Okay, I'll do my own thing."});
            case "stop-working", "stop" -> pick(villager, es ? new String[]{"Listo, dejo lo que estaba haciendo.", "Vale, paro."} : new String[]{"Okay, I'll stop.", "Fine, stopping."});
            case "start-mining" -> {
                String ore = ProspectingTask.wanted(villager);
                yield ore == null
                        ? (es ? "¡A la mina! Me armo mis herramientas y busco minerales." : "Off to the mine! I'll make my tools and look for ores.")
                        : (es ? "Voy a minar y busco " + oreName(ore, true) + " primero." : "I'll go mining and look for " + oreName(ore, false) + " first.");
            }
            case "start-chopping" -> es ? "Voy a talar árboles." : "I'll go chop some trees.";
            case "start-fishing" -> es ? "Voy a pescar." : "I'll go fishing.";
            case "start-harvesting" -> es ? "Voy a cosechar." : "I'll go harvest.";
            case "start-hunting" -> es ? "Voy de caza." : "I'll go hunting.";
            case "start-escort" -> es ? "Te escolto." : "I'll escort you.";
            case "try-go-home" -> es ? "Me voy a casa." : "Heading home.";
            case "wear-armor" -> es ? "Me pongo la armadura." : "Putting my armor on.";
            case "remove-armor" -> es ? "Me quito la armadura." : "Taking my armor off.";
            case "open-trade-window" -> es ? "Mira lo que tengo." : "Here's what I've got.";
            case "give-item" -> gave(villager, given, es);
            case "store-items" -> stored == null ? (es ? "No tengo nada que guardar." : "I've got nothing to store.") : switch (stored) {
                case WALKING -> es ? "Voy a guardar mis cosas en mi cofre." : "I'll put my things in my chest.";
                case PLACED -> es ? "Pongo mi cofre aquí y guardo mis cosas." : "I'll set my chest down here and store my things.";
                case NONE -> es ? "No tengo ningún cofre cerca, así que me quedo con mis cosas." : "I have no chest nearby, so I'll keep my things.";
            };
            default -> es ? "Hecho." : "Done.";
        };
    }

    private static String gave(VillagerEntityMCA villager, @Nullable Acciones.Given given, boolean es) {
        if (given == null) {
            return es ? "No tengo nada que darte." : "I've got nothing to give you.";
        }
        return switch (given.status()) {
            case GAVE -> pick(villager, es ? new String[]{"Toma, " + amount(given.count(), given.item(), true) + ".", "Ahí van " + amount(given.count(), given.item(), true) + "."}
                    : new String[]{"Here, " + amount(given.count(), given.item(), false) + ".", "Catch: " + amount(given.count(), given.item(), false) + "."});
            case NONE -> es ? "No tengo " + given.asked() + "." : "I don't have any " + given.asked() + ".";
            case FAR -> es ? "Estás muy lejos, acércate y te lo paso." : "You're too far away, come closer and I'll hand it over.";
        };
    }

    /** What its last line said it was doing, if lately. */
    private static String doing(VillagerEntityMCA villager, boolean es) {
        VisualMemory memory = Retina.memory(villager);
        long now = villager.getWorld().getTime();
        Chore job = villager.getVillagerBrain().getCurrentJob();
        MoveState move = villager.getVillagerBrain().getMoveState();
        String what = memory.doing();
        if (what != null && !what.isBlank() && now - memory.doingTick() < 1200 && job != Chore.NONE) {
            return es ? "Ahora mismo: " + what + "." : "Right now I'm busy: " + what + ".";
        }
        if (job != Chore.NONE) {
            return es ? "Estoy " + switch (job) {
                case PROSPECT -> "minando";
                case CHOP -> "talando árboles";
                case FISH -> "pescando";
                case HARVEST -> "cosechando";
                case HUNT -> "cazando";
                case ESCORT -> "escoltando";
                default -> "trabajando";
            } + "." : "I'm " + job.name().toLowerCase(java.util.Locale.ROOT) + ".";
        }
        if (move == MoveState.FOLLOW) {
            return es ? "Te estoy siguiendo." : "I'm following you.";
        }
        if (move == MoveState.STAY) {
            return es ? "Aquí, esperando." : "Just waiting here.";
        }
        return es ? "Nada en especial, dando unas vueltas." : "Nothing much, just wandering around.";
    }

    private static String bag(VillagerEntityMCA villager, boolean es) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, ItemStack> kinds = new LinkedHashMap<>();
        SimpleInventory inv = villager.getInventory();
        List<ItemStack> all = new ArrayList<>();
        if (!villager.getMainHandStack().isEmpty()) {
            all.add(villager.getMainHandStack());
        }
        for (int i = 0; i < inv.size(); i++) {
            all.add(inv.getStack(i));
        }
        for (ItemStack stack : all) {
            if (stack.isEmpty()) {
                continue;
            }
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            counts.merge(id, stack.getCount(), Integer::sum);
            kinds.putIfAbsent(id, stack);
        }
        if (counts.isEmpty()) {
            return es ? "No llevo nada, tengo la mochila vacía." : "Nothing, my bag is empty.";
        }
        List<String> parts = new ArrayList<>();
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(7)
                .forEach(e -> parts.add(amount(e.getValue(), kinds.get(e.getKey()), es)));
        String list = join(parts, es);
        return (es ? "Llevo " : "I've got ") + list + (counts.size() > 7 ? (es ? " y algunas cosas más." : " and a few more things.") : ".");
    }

    private static String health(VillagerEntityMCA villager, ServerPlayerEntity player, boolean es) {
        float hp = villager.getHealth() / villager.getMaxHealth();
        int hearts = villager.getVillagerBrain().getMemoriesForPlayer(player).getHearts();
        String body = hp < 0.35F ? (es ? "Muy mal herido, necesito curarme." : "Badly hurt, I need to heal.")
                : hp < 0.8F ? (es ? "Un poco herido, pero aguanto." : "A bit hurt, but I'll manage.")
                : (es ? "Bien, gracias." : "Good, thanks.");
        if (hearts < -25) {
            body += es ? " Aunque contigo no tengo ganas de hablar." : " Not that I want to talk to you.";
        }
        return body + " " + doing(villager, es);
    }

    private static String where(VillagerEntityMCA villager, boolean es) {
        BlockPos p = villager.getBlockPos();
        boolean under = !villager.getWorld().isSkyVisible(p.up()) && p.getY() < villager.getWorld().getSeaLevel() - 4;
        String coords = p.getX() + ", " + p.getY() + ", " + p.getZ();
        return es ? "Estoy en " + coords + (under ? ", bajo tierra." : ".") : "I'm at " + coords + (under ? ", underground." : ".");
    }

    private static String see(VillagerEntityMCA villager, boolean es) {
        VisualMemory memory = Retina.memory(villager);
        long now = villager.getWorld().getTime();
        List<String> parts = new ArrayList<>();
        Map<String, Integer> beings = new LinkedHashMap<>();
        for (VisualMemory.SeenEntity seen : memory.entities(now, 100)) {
            String kind = (seen.type.getSpawnGroup() == SpawnGroup.MONSTER ? "!" : "") + seen.type.getName().getString();
            beings.merge(kind, 1, Integer::sum);
        }
        beings.forEach((kind, n) -> parts.add((n > 1 ? n + " " : "") + (kind.startsWith("!") ? kind.substring(1) + (es ? " (¡cuidado!)" : " (watch out!)") : kind)));
        Map<String, Integer> ores = new LinkedHashMap<>();
        for (VisualMemory.SeenBlock block : memory.blocks(b -> now - b.lastSeen < 600 && b.state != null && Mining.isOre(b.state))) {
            ores.merge(block.state.getBlock().getName().getString(), 1, Integer::sum);
        }
        ores.forEach((ore, n) -> parts.add(n + " " + ore));
        if (parts.isEmpty()) {
            return es ? "Nada que valga la pena por aquí." : "Nothing worth mentioning around here.";
        }
        return (es ? "Veo " : "I can see ") + join(parts.subList(0, Math.min(6, parts.size())), es) + ".";
    }

    private static String help(boolean es) {
        return es ? "Puedo seguirte, quedarme aquí, ir a minar (dime qué buscar), talar, pescar, cosechar, cazar, darte cosas o guardarlas en mi cofre."
                : "I can follow you, stay here, go mining (tell me what to look for), chop trees, fish, harvest, hunt, hand you things or store them in my chest.";
    }

    private static String join(List<String> parts, boolean es) {
        if (parts.size() <= 1) {
            return parts.isEmpty() ? "" : parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + (es ? " y " : " and ") + parts.get(parts.size() - 1);
    }

    private static String pick(VillagerEntityMCA villager, String[] lines) {
        return lines[villager.getRandom().nextInt(lines.length)];
    }

    // ------------------------------------------------------------------ names of things in Spanish

    private static final Map<String, String> ES = Map.ofEntries(
            Map.entry("cobblestone", "piedra"), Map.entry("cobbled_deepslate", "pizarra"), Map.entry("stone", "piedra lisa"),
            Map.entry("dirt", "tierra"), Map.entry("gravel", "grava"), Map.entry("sand", "arena"), Map.entry("stick", "palos"),
            Map.entry("coal", "carbón"), Map.entry("charcoal", "carbón vegetal"), Map.entry("raw_iron", "hierro crudo"),
            Map.entry("iron_ingot", "lingotes de hierro"), Map.entry("raw_gold", "oro crudo"), Map.entry("gold_ingot", "lingotes de oro"),
            Map.entry("raw_copper", "cobre crudo"), Map.entry("copper_ingot", "lingotes de cobre"), Map.entry("diamond", "diamantes"),
            Map.entry("emerald", "esmeraldas"), Map.entry("lapis_lazuli", "lapislázuli"), Map.entry("redstone", "redstone"),
            Map.entry("torch", "antorchas"), Map.entry("bread", "pan"), Map.entry("apple", "manzanas"), Map.entry("wheat", "trigo"),
            Map.entry("wheat_seeds", "semillas de trigo"), Map.entry("carrot", "zanahorias"), Map.entry("potato", "papas"),
            Map.entry("baked_potato", "papas asadas"), Map.entry("beef", "carne cruda"), Map.entry("cooked_beef", "filetes"),
            Map.entry("porkchop", "chuletas crudas"), Map.entry("cooked_porkchop", "chuletas"), Map.entry("chicken", "pollo crudo"),
            Map.entry("cooked_chicken", "pollo asado"), Map.entry("cod", "bacalao"), Map.entry("cooked_cod", "bacalao cocido"),
            Map.entry("salmon", "salmón"), Map.entry("cooked_salmon", "salmón cocido"), Map.entry("chest", "cofre"),
            Map.entry("furnace", "horno"), Map.entry("crafting_table", "mesa de trabajo"), Map.entry("bow", "arco"),
            Map.entry("arrow", "flechas"), Map.entry("flint", "pedernal"), Map.entry("bone", "huesos"), Map.entry("string", "hilo"),
            Map.entry("rotten_flesh", "carne podrida"), Map.entry("gunpowder", "pólvora"), Map.entry("leather", "cuero"),
            Map.entry("feather", "plumas"), Map.entry("egg", "huevos"), Map.entry("shield", "escudo"), Map.entry("bucket", "balde"),
            Map.entry("water_bucket", "balde de agua"), Map.entry("andesite", "andesita"), Map.entry("diorite", "diorita"),
            Map.entry("granite", "granito"), Map.entry("tuff", "toba"), Map.entry("quartz", "cuarzo"), Map.entry("ancient_debris", "escombros ancestrales")
    );

    private static final Map<String, String> MATERIAL = Map.of("wooden", "de madera", "stone", "de piedra", "iron", "de hierro",
            "golden", "de oro", "diamond", "de diamante", "netherite", "de netherita", "leather", "de cuero", "chainmail", "de malla");
    private static final Map<String, String> TOOL = Map.of("pickaxe", "pico", "sword", "espada", "axe", "hacha", "shovel", "pala",
            "hoe", "azada", "helmet", "casco", "chestplate", "peto", "leggings", "pantalones", "boots", "botas");

    /** "3 lingotes de hierro", "1 lingote de hierro" / "3 Iron Ingot". */
    public static String amount(int count, ItemStack stack, boolean es) {
        if (!es) {
            return count + " " + stack.getName().getString();
        }
        String name = spanishName(stack);
        if (count == 1) {
            // one of them: "lingotes de hierro" -> "lingote de hierro", "papas asadas" -> "papa asada"
            int of = name.indexOf(" de ");
            String head = of < 0 ? name : name.substring(0, of);
            StringBuilder single = new StringBuilder();
            for (String word : head.split(" ")) {
                single.append(single.length() > 0 ? " " : "").append(word.length() > 3 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word);
            }
            name = single + (of < 0 ? "" : name.substring(of));
        }
        return count + " " + name;
    }

    public static String spanishName(ItemStack stack) {
        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        String direct = ES.get(id);
        if (direct != null) {
            return direct;
        }
        if (id.endsWith("_log") || id.endsWith("_stem")) {
            return "troncos";
        }
        if (id.endsWith("_planks")) {
            return "tablas";
        }
        if (id.endsWith("_sapling")) {
            return "brotes";
        }
        int cut = id.lastIndexOf('_');
        if (cut > 0) {
            String tool = TOOL.get(id.substring(cut + 1));
            String material = MATERIAL.get(id.substring(0, cut));
            if (tool != null && material != null) {
                return tool + " " + material;
            }
        }
        return stack.getName().getString();
    }

    private static String oreName(String ore, boolean es) {
        if (!es) {
            return ore.replace('_', ' ');
        }
        return switch (ore) {
            case "diamond" -> "diamantes";
            case "iron" -> "hierro";
            case "gold" -> "oro";
            case "coal" -> "carbón";
            case "copper" -> "cobre";
            case "emerald" -> "esmeraldas";
            case "lapis" -> "lapislázuli";
            case "quartz" -> "cuarzo";
            case "ancient_debris" -> "escombros ancestrales";
            default -> ore;
        };
    }
}
