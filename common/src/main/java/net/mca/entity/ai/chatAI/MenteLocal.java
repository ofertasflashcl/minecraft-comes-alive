package net.mca.entity.ai.chatAI;

import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.MoveState;
import net.mca.entity.ai.brain.tasks.chore.ProspectingTask;
import net.mca.entity.ai.body.Cofres;
import net.mca.entity.ai.body.Mining;
import net.mca.entity.ai.sense.Escena;
import net.mca.entity.ai.sense.Lugar;
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
import java.util.Set;
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

    /**
     * Orders, most specific first: "no me sigas" must win over "sigueme", and "sal de la mina" or "deja de minar
     * y sube" (climb out and go home) over "deja de minar" (stop right where it is).
     */
    private static final List<Map.Entry<String, Pattern>> ORDERS = List.of(
            order("eat-break", "(ve|anda|sal|salte|sube|subete|vete|andate)( de (la )?mina| de ahi( abajo)?)? (a|para) comer( algo)?|"
                    + "buscar? comida|come algo|tienes que comer|go (eat|get (some )?food)|get some food|grab (some food|a bite)|eat something"),
            order("come-up", "sal de (la )?mina|salte de (la )?mina|sal de ahi( abajo)?|sal del (hoyo|pozo|tunel)|sal afuera|sube( ya)?|subete|"
                    + "ven (para |pa )?arriba|vuelve (a la superficie|arriba)|deja la mina|deja de (minar|cavar|picar) y (sube|sal|vuelve)|"
                    + "(ya )?puedes (subir|salir)( de (la )?mina)?|te espero (arriba|afuera)|"
                    + "come up|come back up|get out of (the )?(mine|hole|cave)|climb (out|up)"),
            order("move-freely", "no me sigas|deja de seguirme|dejame (solo|sola|tranquilo|tranquila)|anda(te)? libre|puedes irte|ya puedes irte|"
                    + "vete (tranquilo|tranquila)|haz lo tuyo|anda a pasear|stop following|leave me alone|you can go|move freely|go away|do your thing"),
            order("stop-working", "deja de (trabajar|minar|talar|pescar|cosechar|cazar)|para de (trabajar|minar|talar|pescar|cosechar|cazar)|"
                    + "detente|deten el trabajo|basta de trabajar|ya no (mines|trabajes|tales|peques|caces)|"
                    + "no sigas (minando|trabajando|cavando|picando|talando|pescando|cazando)|"
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
                    // a bare "ven" (once its name is taken out: "Inas, ven") is "come to me"
                    + "^((oye|hey|ey|che|hola) )?(ven|vente)( ya| rapido| porfa| por favor)?$|"
                    + "follow me|come with me|come along|come here|tag along|walk with me|accompany me|join me|stick with me|stay (close|with me)|"
                    + "let'?s go together"),
            order("stay-here", "quedate|esperame (aqui|aca|ahi)|espera (aqui|aca|ahi)|no te muevas|stay here|stay there|wait here|wait for me|don'?t move"),
            // a bare "mina" is the order ("mina hierro"), not the noun ("la mina esta oscura", "sal de la mina")
            order("start-mining", "ve a minar|vete a minar|anda a minar|ponte a minar|ve a la mina|vete a la mina|anda a la mina|baja a la mina|ven a la mina|"
                    + "vamos a (la mina|minar)|a minar|minar|(?<!(la|una|esa|esta|tu|mi|su|de|en|al|del) )mina|excava|"
                    // going on, or back down after a break: "sigue bajando", "vuelve a la mina"
                    + "sigue (minando|bajando|cavando|picando)|baja mas|vuelve a (la mina|minar)|"
                    + "go mine|go mining|start mining|mine for|dig for|keep (mining|digging)|go back (down|to the mine|to mining)|"
                    + "(busca|buscame|consigue|consigueme|encuentra|encuentrame|traeme|trae|saca|sacame|find|get me|look for|search for|go get) .*(" + ORES + ")"),
            order("start-chopping", "tala|talar|ve a talar|corta (arboles|madera|troncos|lena)|consigue madera|trae madera|chop|cut (down )?trees|get (some )?wood"),
            order("start-fishing", "pesca|pescar|ve a pescar|go fish(ing)?|start fishing|catch (some )?fish"),
            order("start-harvesting", "cosecha|cosechar|recoge (la cosecha|los cultivos|el trigo)|harvest"),
            order("start-hunting", "caza|cazar|ve a cazar|go hunt(ing)?|start hunting"),
            order("try-go-home", "ve(te)? a (tu )?casa|anda(te)? a (tu )?casa|vuelve a (tu )?casa|regresa a (tu )?casa|go home|head home"),
            order("open-trade-window", "comerciar|tradear|intercambiar|que vendes|que tienes para vender|quiero comprar|let'?s trade|trade|what do you sell")
    );

    /** A start-* order said with a "no" before it: "no vayas a la mina", "nunca mines aqui", "don't go mining". */
    private static final Pattern NEGATED_START = Pattern.compile("(?<![a-z])(no|nunca|ni)( [a-z]+){0,2} (vayas|vaya|vayan|mines|minar|bajes|sigas|"
            + "tales|talar|peques|pescar|caces|cazar|cosech[a-z]*|excaves|a la mina)(?![a-z])"
            + "|(?<![a-z])(don'?t|do not|never) (go )?(mine|mining|chop|fish|hunt|harvest)");

    /** Asking whether it sees something, or where one is: "¿hay ...?", "¿ves ...?", "¿dónde hay ...?", "any ...?". */
    private static final String ASK = "(hay(?! que )|ves|viste|divisas|distingues|donde|any|see|spot|where|there)( [a-z']+){0,3} ";

    /** "see:<kind>": a question about one kind of thing ({@link Escena} kinds), by the words people use for it. */
    private static Map.Entry<String, Pattern> seeing(String kind, String nouns) {
        return order("see:" + kind, ASK + "(" + nouns + ")");
    }

    private static final List<Map.Entry<String, Pattern>> QUESTIONS = List.of(
            order("bye", "adios|chao|chau|nos vemos|hasta luego|hasta pronto|hasta manana|bye|goodbye|good bye|see you|see ya"),
            order("hungry", "tienes hambre|tienes comida|te queda comida|has comido|are you hungry|do you have (any )?food|have you eaten"),
            order("found", "(encontraste|has encontrado|hallaste|conseguiste) (algo|algun|minerales|diamantes|hierro)|que (encontraste|has encontrado|conseguiste)|"
                    + "found anything|what did you find|did you find"),
            order("doing", "que (estas haciendo|haces|andas haciendo)|en que andas|en que estas|what are you doing|what('re| are) you up to"),
            order("bag", "que (tienes|llevas|traes|cargas)|tu inventario|tu mochila|que hay en tu mochila|what do you have|what are you carrying|your inventory|your bag"),
            // "¿hay árboles?", "¿ves agua?", "¿dónde hay hierro?", "any monsters around?"
            seeing(Escena.ARBOL, "arbol|arboles|bosques?|troncos?|madera|trees?|woods?|forests?|logs?"),
            seeing(Escena.AGUA, "agua|rios?|lagos?|mar|laguna|charcos?|water|rivers?|lakes?|sea|ocean|pond"),
            seeing(Escena.LAVA, "lava"),
            seeing(Escena.CUEVA, "cuevas?|cavernas?|grutas?|tuneles?|caves?|caverns?|tunnels?"),
            seeing(Escena.MONSTRUO, "monstruos?|zombis?|zombies?|esqueletos?|aranas?|creepers?|enemigos?|bichos|mobs?|monsters?|enemies|hostiles?|skeletons?|spiders?"),
            seeing(Escena.ANIMAL, "animales|animal|vacas?|ovejas?|cerdos?|chanchos?|gallinas?|pollos?|caballos?|peces|conejos?|animals?|cows?|sheep|pigs?|chickens?|horses?|fish|rabbits?"),
            seeing(Escena.MINERAL, "minerales|mineral|menas?|vetas?|ores?|minerals?|" + ORES),
            seeing(Escena.COFRE, "cofres?|baul|baules|chests?"),
            seeing(Escena.CULTIVO, "cultivos?|sembrados?|huertos?|trigo|crops?|farms?|wheat"),
            seeing(Escena.CONSTRUCCION, "casas?|edificios?|construcciones?|aldea|pueblo|houses?|buildings?|village"),
            seeing(Escena.PERSONA, "personas?|gente|aldeanos?|alguien|jugadores|jugador|people|villagers?|anyone|someone|players?"),
            order("health", "como estas|estas bien|como te sientes|que tal estas|how are you|are you (ok|okay|alright)|you (ok|okay)"),
            order("where", "donde estas|en donde estas|donde te encuentras|donde andas|en que lugar estas|where are you|where you at"),
            order("see", "que ves|que estas viendo|que hay (alrededor|cerca)|what do you see|what can you see|what'?s around|"
                    + "que hay( (por aqui|por aca|aqui|aca|ahi|alla|al frente|enfrente|delante|adelante|detras|atras|a tu (izquierda|derecha|alrededor)))?$|"
                    + "que (miras|estas mirando)|mira( a tu| a su)? alrededor|mira (bien|eso|esto|ahi|alla|aca|al frente|adelante)|^mira$|"
                    + "(echa|echale|da|dale) un vistazo|describe(me)?|describi(r|lo)?|cuentame (que|lo que) ves|como es (el lugar|aqui|aca|esto)|"
                    + "what is around|what'?s (out )?there|what'?s near( you)?|look around|^look$|take a look|have a look|"
                    + "tell me what you see|what are you looking at|what does it look like"),
            order("name", "como te llamas|quien eres|cual es tu nombre|what('s| is) your name|who are you"),
            order("help", "que puedes hacer|que sabes hacer|ayuda|what can you do|help"),
            order("thanks", "gracias|muchas gracias|thanks|thank you|bien hecho|buen trabajo|good job|well done"),
            order("greet", "hola|buenas|buenos dias|buenas tardes|buenas noches|ola|que tal|hey|hi|hello")
    );

    /**
     * What a language model said it will do, in its own words, when it forgot to put the order in its answer.
     * Only clear first-person promises count.
     */
    private static final List<Map.Entry<String, Pattern>> PROMISES = List.of(
            order("follow-player", "te sigo|voy contigo|me voy contigo|te acompano|camino (a tu lado|contigo)|voy detras de ti|"
                    + "i('?ll| will) follow( you)?|following you|coming with you|i('?ll| will) come with you|right behind you|lead the way|"
                    + "i('?ll| will) walk with you"),
            order("stay-here", "me quedo (aqui|aca|quieto|quieta)|aqui te espero|te espero aqui|i('?ll| will) (stay|wait) (here|right here)"),
            order("come-up", "ya subo|salgo de la mina|voy subiendo|coming up|i('?ll| will) come up|i'?m coming up|climbing out"),
            order("try-go-home", "me voy a casa|vuelvo a casa|i'?m heading home|i('?ll| will) go home"),
            order("start-mining", "voy a minar|me voy a minar|bajo a la mina|me pongo a minar|i('?ll| will) go mining|off to the mine|"
                    + "i('?ll| will) start mining"),
            order("start-chopping", "voy a talar|me pongo a talar|i('?ll| will) (go )?chop"),
            order("start-fishing", "voy a pescar|me voy a pescar|i('?ll| will) go fishing")
    );

    /**
     * A promise with one of these just before it is a refusal: "no puedo seguirte", "I can't follow you". ("puedo"
     * alone is a yes: "¡Claro que puedo, te sigo!")
     */
    private static final Set<String> NEGATIONS = Set.of("no", "nunca", "jamas", "ni", "not", "never", "cant", "can't",
            "cannot", "won't", "wont", "don't", "dont");

    /**
     * Said with one of these around it, a promise is for later, not now: "esta noche me voy a casa", "cuando termine
     * te sigo", "I'll go home later". Talk about plans is not an order.
     */
    private static final Pattern LATER = Pattern.compile("(?<![a-z])(esta noche|mas tarde|al rato|(?<!(desde|hasta) )luego|despues|manana|"
            + "cuando|quizas|tal vez|later|tonight|tomorrow|when|maybe)(?![a-z])");

    private static final Pattern SPANISH = Pattern.compile("\\b(el|la|los|las|que|de|por|favor|hola|tu|tus|me|mi|dame|ven|sigueme|quedate|"
            + "como|donde|tienes|estas|gracias|aqui|aca|busca|mina|tala|pesca|guarda|para|con|una?|y|si|no|vamos|oye|porfa|"
            // the short orders said to a miner carry no other Spanish word: "sube ya", "sal de ahi", "sigue bajando"
            + "algo|ya|sube|subete|sal|ahi|arriba|abajo|sigue|baja|bajando|casa|comer|comida|hambre|ahora|bien|mas|muy|pero|eso|dime)\\b");
    private static final Pattern ENGLISH = Pattern.compile("\\b(the|you|your|me|my|please|follow|stay|give|what|where|how|are|is|do|have|"
            + "hello|hi|thanks|thank|here|go|come|stop|mine|find|get|can|let'?s|i|keep|mining|it|to|of|and|up|out|back|now)\\b");

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

    /** "Spanish" or "English" when the words clearly say which, null when they do not (a bare "sube"). */
    @Nullable
    public static String language(String text) {
        String t = clean(text);
        int es = count(SPANISH, t) + (text.matches("(?s).*[ñáéíóúü¿¡].*") ? 2 : 0);
        int en = count(ENGLISH, t);
        if (es > en) {
            return "Spanish";
        }
        return en > es ? "English" : null;
    }

    /**
     * Whether to answer {@code text} in Spanish: what its words say, leaving out the villager's own name (an "Inés"
     * says nothing about the player's language); when they do not say ("ok", "sube"), the game's language, else Spanish.
     */
    public static boolean speaksSpanish(String text, @Nullable String villagerName) {
        String language = language(withoutRawName(text, villagerName));
        if (language != null) {
            return language.equals("Spanish");
        }
        return !(MCA.language != null && MCA.language.startsWith("en"));
    }

    /** {@code text} as it was written (accents kept), without the words of {@code name}. */
    private static String withoutRawName(String text, @Nullable String name) {
        if (name == null || name.isBlank()) {
            return text;
        }
        String out = text;
        for (String part : name.trim().split("\\s+")) {
            if (part.length() >= 2) {
                out = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(part) + "(?![\\p{L}\\p{N}])",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(out).replaceAll(" ");
            }
        }
        return out;
    }

    /** Whether an answer is clearly in the other language than the one the player wrote in. */
    public static boolean clearlyNot(String answer, boolean spanish) {
        String t = clean(answer);
        int es = count(SPANISH, t) + (answer.matches("(?s).*[ñáéíóúü¿¡].*") ? 2 : 0);
        int en = count(ENGLISH, t);
        return spanish ? en >= 3 && es == 0 : es >= 3 && en == 0;
    }

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /**
     * The message without the villager's name, so "Mina, ven aqui" is not read as an order to mine and
     * "Inas sal de la mina" is "sal de la mina". Only the first time the name is said is it taken out:
     * "Rosa, dame la rosa" still asks for the rose.
     */
    public static String withoutName(String msg, @Nullable String villagerName) {
        String text = Acciones.normalize(msg);
        if (villagerName == null || villagerName.isBlank()) {
            return text;
        }
        String name = Acciones.normalize(villagerName).trim().replaceAll("\\s+", " ");
        String stripped = removeFirst(text, name);
        if (stripped.equals(text)) {
            for (String part : name.split(" ")) {
                if (part.length() >= 2) {
                    stripped = removeFirst(stripped, part);
                }
            }
        }
        return stripped.replaceAll("\\s+", " ").trim();
    }

    private static String removeFirst(String text, String word) {
        if (word.isEmpty()) {
            return text;
        }
        Matcher m = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])").matcher(text);
        return m.find() ? text.substring(0, m.start()) + " " + text.substring(m.end()) : text;
    }

    /** Reads what the player said to {@code villagerName}: the order and the language without the name ({@link #speaksSpanish}). */
    public static Intent read(String text, @Nullable String villagerName) {
        Intent intent = read(withoutName(text, villagerName));
        return new Intent(intent.command(), intent.argument(), intent.question(), speaksSpanish(text, villagerName));
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
                if (entry.getKey().startsWith("start-") && NEGATED_START.matcher(t).find()) {
                    continue; // "no vayas a la mina" is not an order to go
                }
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

    /** The order a model's own words promise ("¡Claro, te sigo!"), or null; never one said with a "no" before it. */
    @Nullable
    public static String promise(String said) {
        String t = clean(said);
        for (Map.Entry<String, Pattern> entry : PROMISES) {
            Matcher m = entry.getValue().matcher(t);
            if (m.find()) {
                String[] before = t.substring(0, m.start()).trim().split(" ");
                for (int i = Math.max(0, before.length - 3); i < before.length; i++) {
                    if (NEGATIONS.contains(before[i])) {
                        return null;
                    }
                }
                String[] after = t.substring(m.end()).trim().split(" ");
                String around = String.join(" ", java.util.Arrays.copyOfRange(before, Math.max(0, before.length - 4), before.length))
                        + " | " + String.join(" ", java.util.Arrays.copyOfRange(after, 0, Math.min(3, after.length)));
                if (LATER.matcher(around).find()) {
                    return null;
                }
                return entry.getKey();
            }
        }
        return null;
    }

    /** When nothing better can be said: it heard something, but did not get it. */
    public static String shrug(boolean es) {
        return es ? "¿Mm? Perdona, me distraje. ¿Qué decías?" : "Hm? Sorry, I got distracted. What was that?";
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
        if (q.startsWith("see:")) {
            return seeKind(villager, q.substring(4), es);
        }
        return switch (q) {
            case "doing" -> doing(villager, es);
            case "bag" -> bag(villager, es);
            case "health" -> health(villager, player, es);
            case "where" -> where(villager, es);
            case "see" -> see(villager, es);
            case "name" -> es ? "Me llamo " + villager.getName().getString() + "." : "I'm " + villager.getName().getString() + ".";
            case "help" -> help(es);
            case "bye" -> pick(villager, es ? new String[]{"¡Nos vemos, " + name + "!", "¡Chao! Cuídate.", "Hasta luego, " + name + "."}
                    : new String[]{"See you, " + name + "!", "Bye! Take care.", "Later, " + name + "."});
            case "hungry" -> hungry(villager, es);
            case "found" -> found(villager, es);
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
        boolean mining = villager.getVillagerBrain().getCurrentJob() == Chore.PROSPECT;
        if (outcome == null && "come-up".equals(command)) {
            // only a miner climbs out of a mine
            return es ? "No estoy en ninguna mina ahora mismo." : "I'm not down in a mine right now.";
        }
        if (outcome == null && "eat-break".equals(command)) {
            return es ? "Estoy bien, todavía no tengo hambre." : "I'm fine, I'm not hungry yet.";
        }
        if (outcome == null && !"give-item".equals(command) && !"store-items".equals(command)) {
            return pick(villager, es ? new String[]{"Ya estoy en eso.", "Eso ya lo estoy haciendo."} : new String[]{"Already on it.", "I'm already doing that."});
        }
        return switch (command) {
            case "come-up" -> pick(villager, es ? new String[]{"Voy subiendo, salgo de la mina.", "Vale, dejo de picar y subo."}
                    : new String[]{"Coming up, I'm getting out of the mine.", "Okay, I'll stop digging and come up."});
            case "eat-break" -> es ? "Subo a comer algo y luego vuelvo a la mina." : "I'll go up and eat something, then head back down.";
            case "follow-player" -> pick(villager, es ? new String[]{"¡Vamos! Te sigo.", "Voy contigo, " + name + ".", "Detrás tuyo."}
                    : new String[]{"Let's go! I'm right behind you.", "Coming with you, " + name + "."});
            case "stay-here" -> pick(villager, es ? new String[]{"Me quedo aquí.", "Aquí te espero."} : new String[]{"I'll stay here.", "I'll wait right here."});
            case "move-freely" -> pick(villager, es ? new String[]{"Bueno, ando por ahí.", "Vale, me voy a lo mío."} : new String[]{"Alright, I'll wander around.", "Okay, I'll do my own thing."});
            case "stop-working", "stop" -> pick(villager, es ? new String[]{"Listo, dejo lo que estaba haciendo.", "Vale, paro."} : new String[]{"Okay, I'll stop.", "Fine, stopping."});
            case "start-mining" -> {
                String ore = ProspectingTask.wanted(villager);
                if (outcome.startsWith("You keep")) {
                    // already a miner: it goes on (or back down after a break), it does not start over
                    yield ore == null ? (es ? "Sigo en la mina, picando." : "I'll keep at it down in the mine.")
                            : (es ? "Sigo minando, buscando " + oreName(ore, true) + "." : "I'll keep mining for " + oreName(ore, false) + ".");
                }
                yield ore == null
                        ? (es ? "¡A la mina! Me armo mis herramientas y busco minerales." : "Off to the mine! I'll make my tools and look for ores.")
                        : (es ? "Voy a minar y busco " + oreName(ore, true) + " primero." : "I'll go mining and look for " + oreName(ore, false) + " first.");
            }
            case "start-chopping" -> (es ? "Voy a talar árboles." : "I'll go chop some trees.") + hint(villager, Escena.ARBOL, es);
            case "start-fishing" -> (es ? "Voy a pescar." : "I'll go fishing.") + hint(villager, Escena.AGUA, es);
            case "start-harvesting" -> es ? "Voy a cosechar." : "I'll go harvest.";
            case "start-hunting" -> (es ? "Voy de caza." : "I'll go hunting.") + hint(villager, Escena.ANIMAL, es);
            case "start-escort" -> es ? "Te escolto." : "I'll escort you.";
            case "try-go-home" -> mining ? (es ? "Dejo de picar y vuelvo a casa." : "I'll stop digging and head home.")
                    : outcome.startsWith("You have no home") || villager.getResidency().getHome().isEmpty()
                    ? (es ? "No tengo casa a la que volver." : "I don't have a home to go to.")
                    : (es ? "Me voy a casa." : "Heading home.");
            case "wear-armor" -> es ? "Me pongo la armadura." : "Putting my armor on.";
            case "remove-armor" -> es ? "Me quito la armadura." : "Taking my armor off.";
            case "open-trade-window" -> es ? "Mira lo que tengo." : "Here's what I've got.";
            case "give-item" -> gave(villager, given, es);
            case "store-items" -> stored == null ? (es ? "No tengo nada que guardar." : "I've got nothing to store.") : switch (stored) {
                case WALKING -> es ? "Voy a guardar mis cosas en mi cofre." : "I'll put my things in my chest.";
                case PLACED -> es ? "Pongo mi cofre aquí y guardo mis cosas." : "I'll set my chest down here and store my things.";
                default -> es ? "No tengo ningún cofre cerca, así que me quedo con mis cosas." : "I have no chest nearby, so I'll keep my things.";
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

    /**
     * Where it is, from its sense of place ({@link Lugar}): outside, under a roof or down in the rock and how deep,
     * with its coordinates; a house roof is never "underground".
     */
    private static String where(VillagerEntityMCA villager, boolean es) {
        BlockPos p = villager.getBlockPos();
        String coords = p.getX() + ", " + p.getY() + ", " + p.getZ();
        Lugar lugar = Vistazo.lugar(villager);
        if (lugar != null) {
            int depth = Vistazo.profundidad(villager);
            return switch (lugar) {
                case AFUERA -> depth > 8
                        ? (es ? "Estoy afuera, pero en un hoyo, unos " + depth + " bloques bajo el suelo, en " + coords + "."
                        : "I'm outside, but down in a pit, about " + depth + " blocks below the ground, at " + coords + ".")
                        : (es ? "Estoy afuera, en " + coords + "." : "I'm outside, at " + coords + ".");
                case BAJO_TECHO -> es ? "Estoy bajo techo, en " + coords + "." : "I'm indoors, under a roof, at " + coords + ".";
                case BAJO_TIERRA -> depth > 0
                        ? (es ? "Estoy bajo tierra, unos " + depth + " bloques bajo la superficie, en " + coords + "."
                        : "I'm underground, about " + depth + " blocks below the surface, at " + coords + ".")
                        : (es ? "Estoy bajo tierra, en " + coords + "." : "I'm underground, at " + coords + ".");
            };
        }
        boolean under = !villager.getWorld().isSkyVisible(p.up()) && p.getY() < villager.getWorld().getSeaLevel() - 4;
        return es ? "Estoy en " + coords + (under ? ", bajo tierra." : ".") : "I'm at " + coords + (under ? ", underground." : ".");
    }

    /** What it sees: the scene in its own words ({@link Escena#describe(boolean)}), else the beings and ores its eyes saw. */
    private static String see(VillagerEntityMCA villager, boolean es) {
        Escena scene = Vistazo.escena(villager);
        if (scene != null) {
            try {
                String said = scene.describe(es);
                if (said != null && !said.isBlank()) {
                    return said.trim();
                }
            } catch (RuntimeException e) {
                MCA.LOGGER.warn("[Tropeles] {} no supo contar lo que ve: {}", villager.getName().getString(), e.toString());
            }
        }
        return sightings(villager, es);
    }

    /** "¿Hay árboles?": where the nearest one is and how many, or that it saw none ({@link Escena#describe(String, boolean)}). */
    private static String seeKind(VillagerEntityMCA villager, String kind, boolean es) {
        Escena scene = Vistazo.escena(villager);
        if (scene != null) {
            try {
                String said = scene.describe(kind, es);
                if (said != null && !said.isBlank()) {
                    return said.trim();
                }
            } catch (RuntimeException e) {
                MCA.LOGGER.warn("[Tropeles] {} no supo contar si ve {}: {}", villager.getName().getString(), kind, e.toString());
            }
        }
        if (Escena.MINERAL.equals(kind) || Escena.ANIMAL.equals(kind) || Escena.MONSTRUO.equals(kind)) {
            return sightings(villager, es);
        }
        return es ? "No sabría decirte, todavía no me he fijado bien." : "I couldn't say, I haven't had a good look yet.";
    }

    /**
     * What it saw of a kind, said after an order when it fits ("Voy a talar árboles. Veo un roble a la izquierda..."):
     * only when it really saw one not far off, otherwise nothing.
     */
    private static String hint(VillagerEntityMCA villager, String kind, boolean es) {
        Escena scene = Vistazo.escena(villager);
        if (scene == null) {
            return "";
        }
        try {
            Escena.Cosa nearest = scene.nearest(kind);
            if (nearest == null || nearest.distancia() > 48) {
                return "";
            }
            String said = scene.describe(kind, es);
            return said == null || said.isBlank() || said.length() > 140 ? "" : " " + said.trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The beings and ores its eyes saw lately, from the raw sightings (when no scene could be made). */
    private static String sightings(VillagerEntityMCA villager, boolean es) {
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
        return es ? "Puedo seguirte, quedarme aquí, ir a minar (dime qué buscar) y subir cuando me llames, talar, pescar, cosechar, cazar, darte cosas o guardarlas en mi cofre."
                : "I can follow you, stay here, go mining (tell me what to look for) and come up when you call, chop trees, fish, harvest, hunt, hand you things or store them in my chest.";
    }

    /** The food in its own bag. */
    private static String hungry(VillagerEntityMCA villager, boolean es) {
        int meals = 0;
        ItemStack kind = ItemStack.EMPTY;
        SimpleInventory inv = villager.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.isFood()) {
                meals += stack.getCount();
                if (kind.isEmpty() || stack.getCount() > kind.getCount()) {
                    kind = stack;
                }
            }
        }
        if (meals == 0) {
            return villager.getVillagerBrain().getCurrentJob() == Chore.PROSPECT
                    ? (es ? "No me queda nada de comer; cuando me dé hambre subo a buscar comida." : "I've got no food left; when I get hungry I'll go up for some.")
                    : (es ? "No llevo nada de comer encima." : "I'm not carrying any food.");
        }
        return es ? "Llevo " + amount(meals, kind, true) + " para comer, estoy bien." : "I've got " + amount(meals, kind, false) + " to eat, I'm fine.";
    }

    /** The ore it carries, and the ore its eyes saw lately. */
    private static String found(VillagerEntityMCA villager, boolean es) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, ItemStack> kinds = new LinkedHashMap<>();
        SimpleInventory inv = villager.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            if (id.startsWith("raw_") || id.endsWith("_ingot") || id.equals("coal") || id.equals("diamond") || id.equals("emerald")
                    || id.equals("lapis_lazuli") || id.equals("redstone") || id.equals("quartz") || id.equals("ancient_debris")) {
                counts.merge(id, stack.getCount(), Integer::sum);
                kinds.putIfAbsent(id, stack);
            }
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((id, n) -> parts.add(amount(n, kinds.get(id), es)));
        String carried = parts.isEmpty() ? (es ? "Todavía no he sacado nada bueno." : "I haven't dug up anything good yet.")
                : (es ? "Llevo " : "I've got ") + join(parts.subList(0, Math.min(6, parts.size())), es) + ".";
        Escena scene = Vistazo.escena(villager);
        if (scene != null) {
            try {
                // the ores it saw, with where they are; nothing more when it saw none
                return scene.nearest(Escena.MINERAL) == null ? carried : carried + " " + scene.describe(Escena.MINERAL, es).trim();
            } catch (RuntimeException e) {
                // the raw sightings below
            }
        }
        String seen = sightings(villager, es);
        return seen.startsWith(es ? "Veo " : "I can see ") ? carried + " " + seen : carried;
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
