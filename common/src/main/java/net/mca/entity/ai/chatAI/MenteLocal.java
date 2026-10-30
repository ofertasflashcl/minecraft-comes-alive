package net.mca.entity.ai.chatAI;

import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.MoveState;
import net.mca.entity.ai.brain.tasks.chore.ProspectingTask;
import net.mca.entity.ai.body.Cofres;
import net.mca.entity.ai.body.Mining;
import net.mca.entity.ai.mente.Recuerdos;
import net.mca.entity.ai.mente.Voluntad;
import net.mca.entity.ai.sense.Ahora;
import net.mca.entity.ai.sense.Escena;
import net.mca.entity.ai.sense.Lugar;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.Tacto;
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
     * Words that stop a connected villager dead: "cuidado", "detente", "quieto", "shh", "espera" (not "espera aquí",
     * which is waiting, nor "te espera"), "stop", "wait" (not "wait here"), "careful", "watch out", "hold on",
     * "freeze". Read on the message without punctuation.
     */
    private static final String ALERT = "cuidado|ojo con|ojo,? (hay|que)|detente|detenganse|detengase|deten(?= ahi| ya|$)|quieto|quieta|quietos|quietas|"
            + "shh+|chis|chito|silencio|"
            + "(?<!te )(?<!me )(?<!lo )(?<!la )(?<!se )(?<!le )(?<!os )(?<!que )(?<!nos )(?<!les )espera(?! (aqui|aca|ahi|alli|alla))|esperen|"
            + "stop(?! (following|working|mining|chopping|fishing|harvesting|hunting|talking))|wait(?! (here|there|for))|"
            + "careful|watch out|look out|hold on|hold up|hang on|freeze|halt";

    /** The orders a connected villager does itself as an errand, and what each is about ({@link Voluntad#dicho}). */
    /** Things it can make, as said in a "hazme ..." order. */
    private static final String COSAS = "mesas?|palos?|palitos|tablas?|tablones?|cofres?|baul|baules|antorchas?|hornos?|pan|panes|picos?|picota|"
            + "espadas?|hachas?|palas?|azadas?|azadon|canas?|escudos?|arcos?|escaleras?|petos?|pecheras?|armadura|cascos?|pantalones|grebas|botas|"
            + "crafting table|workbench|sticks?|planks?|chests?|torch(es)?|furnaces?|bread|pickaxes?|swords?|axes?|shovels?|hoes?|fishing rod|"
            + "shields?|bows?|ladders?|chestplates?|helmets?|leggings|boots";

    private static final Map<String, String> ERRANDS = Map.of("start-chopping", "TALAR", "start-fishing", "PESCAR", "start-harvesting", "COSECHAR",
            "start-hunting", "CAZAR", "craft-item", "HACER", "stop-working", "PARAR", "stop", "PARAR");

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
                    + "vete (tranquilo|tranquila)|haz lo tuyo|haz tus cosas|vuelve a lo tuyo|vuelve a tus cosas|sigue con lo tuyo|anda a lo tuyo|eres libre|"
                    + "anda a pasear|stop following|leave me alone|you can go|move freely|go away|do your thing|you'?re free|"
                    + "go back to (your (own )?(thing|stuff|business)|what you were doing)"),
            order("stop-working", "deja de (trabajar|minar|talar|pescar|cosechar|cazar)|para de (trabajar|minar|talar|pescar|cosechar|cazar)|"
                    + "deten el trabajo|basta de trabajar|ya no (mines|trabajes|tales|peques|caces)|"
                    + "no sigas (minando|trabajando|cavando|picando|talando|pescando|cazando)|"
                    + "stop (working|mining|chopping|fishing|harvesting|hunting)|quit (working|mining)"),
            // "¡cuidado!", "¡para!", "detente", "watch out": stop dead and ask what is going on (the bare "para" and "alto"
            // are read apart, with their punctuation: {@link #PARA}, {@link #ALTO})
            order("alert", ALERT),
            order("remove-armor", "quitate (la |tu )?armadura|sacate (la |tu )?armadura|take off (your |the )?armou?r|remove (your |the )?armou?r"),
            order("wear-armor", "ponte (la |tu )?armadura|equipate|vistete (con )?(la |tu )?armadura|wear (your |the )?armou?r|put on (your |the )?armou?r"),
            order("give-item", "dame|pasame|regalame|entregame|tirame|lanzame|prestame|me das|me pasas|me regalas|me prestas|"
                    + "give me|hand me|can i have|toss me|throw me|pass me"),
            // "hazme un cofre", "hazte una espada de piedra", "make me a pickaxe": made by its own hands at a table
            order("craft-item", "(haz|hazme|hazte|haceme|hacete|hacer|fabrica|fabricame|fabricate|fabricar|craftea|crafteame|crafteate|craftear|"
                    + "construye|construyeme|construyete|prepara|preparame|preparate|make|craft)(?![a-z]).{0,40}(?<![a-z])(" + COSAS + ")"),
            order("store-items", "guarda (tus|las|todas tus|todo|lo que)|guardalo|guardalas|guardalos|deja tus cosas|ve a guardar|anda a guardar|"
                    + "store (your|it|everything|the)|put (your|it|everything|the) .*chest|deposit"),
            order("follow-player", "sigueme|seguime|siguenos|seguirme|ven conmigo|vente conmigo|vienes conmigo|venir conmigo|vamos juntos|vamonos|"
                    + "vamos a (explorar|caminar|pasear|dar una vuelta)|acompaname|acompanarme|acompaneme|me acompanas|me acompanarias|"
                    + "quedate conmigo|camina conmigo|no te (alejes|separes|despegues) de mi|pegate a mi|ven (aca|aqui|para aca|pa ca|para aqui)|"
                    // a bare "ven" (once its name is taken out: "Inas, ven") is "come to me"
                    + "^((oye|hey|ey|che|hola) )?(ven|vente)( ya| rapido| porfa| por favor)?$|"
                    + "follow me|come with me|come along|come here|tag along|walk with me|accompany me|join me|stick with me|stay (close|with me)|"
                    + "let'?s go together"),
            order("stay-here", "quedate|esperame( (aqui|aca|ahi))?|espera (aqui|aca|ahi)|no te muevas|stay here|stay there|wait here|wait for me|don'?t move"),
            // a bare "mina" is the order ("mina hierro"), not the noun ("la mina esta oscura", "sal de la mina")
            order("start-mining", "ve a minar|vete a minar|anda a minar|ponte a minar|ve a la mina|vete a la mina|anda a la mina|baja a la mina|ven a la mina|"
                    + "vamos a (la mina|minar)|a minar|minar|(?<!(la|una|esa|esta|tu|mi|su|de|en|al|del) )mina|excava|"
                    // going on, or back down after a break: "sigue bajando", "vuelve a la mina"
                    + "sigue (minando|bajando|cavando|picando)|baja mas|vuelve a (la mina|minar)|"
                    + "go mine|go mining|start mining|mine for|dig for|keep (mining|digging)|go back (down|to the mine|to mining)|"
                    + "(busca|buscame|consigue|consigueme|encuentra|encuentrame|traeme|trae|saca|sacame|find|get me|look for|search for|go get) .*(" + ORES + ")"),
            order("start-chopping", "tala|talar|talame|ve a talar|corta (arboles|madera|troncos|lena|un arbol|unos arboles)|cortame|"
                    + "(consigue|consigueme|trae|traeme|traigame|busca|buscame|junta|juntame|recoge|recogeme|saca|sacame|necesito|necesitamos|"
                    + "get|bring|fetch|gather|collect)(?![a-z]).{0,24}(?<![a-z])(madera|maderas|troncos?|lena|wood|logs?)|"
                    + "chop|cut (down )?trees|get (some )?wood"),
            order("start-fishing", "pesca|pescar|pescame|ve a pescar|go fish(ing)?|start fishing|catch (some )?fish|"
                    + "(consigue|consigueme|trae|traeme|saca|sacame)(?![a-z]).{0,16}(?<![a-z])(pescados?|peces|pez|fish)"),
            order("start-harvesting", "cosecha|cosechar|cosechame|recoge (la cosecha|los cultivos|el trigo|las zanahorias|las papas|las patatas)|harvest|"
                    + "(consigue|consigueme|trae|traeme|recoge|recogeme)(?![a-z]).{0,16}(?<![a-z])(trigo|zanahorias?|papas|patatas|remolachas?)"),
            order("start-hunting", "caza|cazar|cazame|ve a cazar|go hunt(ing)?|start hunting|"
                    + "mata (una|un|unas|unos|algunas|algunos|dos|tres|\\d+)? ?(vacas?|cerdos?|chanchos?|ovejas?|gallinas?|pollos?|conejos?|animales?)|"
                    + "(consigue|consigueme|trae|traeme|busca|buscame)(?![a-z]).{0,16}(?<![a-z])(carne|cuero|plumas|lana)"),
            order("try-go-home", "ve(te)? a (tu )?casa|anda(te)? a (tu )?casa|vuelve a (tu )?casa|regresa a (tu )?casa|go home|head home"),
            order("open-trade-window", "comerciar|tradear|intercambiar|que vendes|que tienes para vender|quiero comprar|let'?s trade|trade|what do you sell")
    );

    /**
     * "Para" as the verb (stop!), read with its punctuation: alone, at the end, before a comma or a "!", or with a
     * word that goes with the order ("para ya", "para ahí", "para po"). Never the preposition: "para que", "para
     * ti", "para el", "ven para acá", "sube para comer".
     */
    private static final Pattern PARA = Pattern.compile("(?<![a-z])(?<!ven )(?<!vente )(?<!anda )(?<!vamos )(?<!voy )(?<!ir )(?<!vengo )(?<!sube )"
            + "(?<!baja )(?<!mira )(?<!corre )(?<!salta )(?<!camina )(?<!vuelve )(?<!esto )(?<!eso )(?<!es )(?<!era )(?<!sirve )para"
            + "(?=\\s*$|\\s*[,.!;:?\u00a1\u00bf\u2026]|\\s+(ya|ahi|alli|un poco|un momento|un segundo|un toque|porfa|por favor|po|weon|wn|hombre|loco|altiro|oye|hey|ey)(?![a-z]))");

    /** "¡Alto!", "alto ahí", "oye, alto": the order, not the adjective ("es muy alto", "un árbol alto"). */
    private static final Pattern ALTO = Pattern.compile("(^|[,.!;:\u00a1\u00bf]\\s*|(?<![a-z])(oye|ey|hey|che|ya|ahora)\\s+)alto"
            + "(?=\\s*$|\\s*[,.!;:?\u2026]|\\s+(ahi|ya|alli)(?![a-z]))");

    /**
     * "Tranquilo, sigue", "falsa alarma", "dale", "it's fine": to a villager stopped by an alert, it was nothing and
     * it carries on ({@link #calma}).
     */
    private static final Pattern CALM = Pattern.compile("(?<![a-z])(tranquilo|tranquila|tranqui|sigue|sigamos|continua|continuemos|dale|"
            + "falsa alarma|no es nada|no era nada|nada|todo bien|esta bien|ok|okay|vale|go on|carry on|keep going|it'?s fine|its fine|"
            + "it'?s ok|all clear|false alarm|never ?mind|nothing)(?![a-z])");

    /** Whether {@code text} tells a villager on alert that it was nothing. */
    public static boolean calma(String text) {
        return CALM.matcher(clean(text)).find();
    }

    /**
     * Asking for its exact position: "dame tus coordenadas", "¿en qué coordenadas estás?", "coords", "your
     * coordinates". Read before the orders, so "dame tus coordenadas" is not a hand-over.
     */
    private static final Pattern COORDS = order("coords", "coordenadas?|coords?|cords|coordinates?|"
            + "(tu|su) (ubicacion|posicion|localizacion)( exacta)?|(ubicacion|posicion) exacta|en que (posicion|ubicacion) (estas|te encuentras)|"
            + "donde (estas|te encuentras) exactamente|exactamente donde estas|"
            + "your (exact )?(position|location)|exact (position|location)|where exactly are you|xyz").getValue();

    /** Going back, said as one: home, up, out; "volver a la mina" or "go back down" is the opposite and does not count. */
    private static final String BACK_ES = "(volver|volvamos|regresar|regresemos|regreses|vuelvas|subir|subas|salir|salgas|devolverte|devolverse|"
            + "irte a (tu )?casa|ir a (tu )?casa|irse a casa|vayas a (tu )?casa|volver a (tu )?casa|regresar a (tu )?casa)"
            + "(?! a (la )?min| a trabajar| a cavar| a picar| abajo| mas abajo)";
    private static final String BACK_EN = "((go|head|come|get) (back|home)|return( home)?|call it a day|turn back)(?! down| to (the )?min| to work| to digging)";

    /**
     * Advice to head back, not an order: "sería mejor volver", "deberías subir", "¿no crees que deberías volver?",
     * "¿por qué no vuelves?", "mejor vuelve", "you should go back", "maybe head home", "better head back". The
     * villager weighs it itself ({@link ProspectingTask#juzgarVuelta}); a plain "sube" or "vuelve a casa" is still
     * an order. Read before the orders: "you should go home" holds the order "go home".
     */
    private static final Pattern SUGGEST_RETURN = order("consider-return",
            "(seria|sera|es|creo que es|no seria|quizas seria|tal vez seria) mejor (que )?" + BACK_ES + "|"
                    + "(deberias|deberiamos|tendrias que|conviene|convendria|te conviene|te convendria|ya es hora de|es hora de|seria bueno) (que )?" + BACK_ES + "|"
                    + "por que no (vuelves|regresas|subes|sales|te devuelves|te vas a (tu )?casa|vas a (tu )?casa|volvemos)|"
                    + "y si (vuelves|regresas|subes|sales|te vas a (tu )?casa|volvemos)|"
                    + "mejor (vuelve|vuelvete|volvamos|regresa|regresemos|sube|subete|sal|salte|vete a (tu )?casa|anda a (tu )?casa|vamonos)(?! a (la )?min| abajo)|"
                    + "(you|we) (should|ought to|might want to|may want to|better|had better) " + BACK_EN + "|"
                    + "(you'?d|you had|we'?d) better " + BACK_EN + "|better " + BACK_EN + "|maybe " + BACK_EN + "|"
                    + "maybe (you|we) should " + BACK_EN + "|shouldn'?t you " + BACK_EN + "|why don'?t (you|we) " + BACK_EN + "|"
                    + "(it'?s|its|it is) (time|about time) to " + BACK_EN + "|how about (going|heading|coming) (back|home)|"
                    + "(it )?(might|would) be (better|wise|smart) to " + BACK_EN).getValue();

    /**
     * The advice turned around: "no deberías volver", "mejor no subas", "you shouldn't go back". Asked as a question
     * ("¿no deberías volver?", "shouldn't you go back?") it is the advice again, so this only counts without a "?".
     */
    private static final Pattern NOT_RETURN = Pattern.compile("(?<![a-z])((no|nunca) (deberias|seria mejor|es mejor|conviene|te conviene|tienes que)|"
            + "mejor no|shouldn'?t|should not|better not|(don'?t|do not|never) (go|head|come|return))(?![a-z])");

    /** A start-* order said with a "no" before it: "no vayas a la mina", "nunca mines aqui", "don't go mining". */
    /** What a player may ask it to make ({@link net.mca.entity.ai.body.Progreso#queHacer}). */

    private static final Pattern NEGATED_START = Pattern.compile("(?<![a-z])(no|nunca|ni)( [a-z]+){0,2} (vayas|vaya|vayan|mines|minar|bajes|sigas|"
            + "tales|talar|peques|pescar|caces|cazar|cosech[a-z]*|excaves|a la mina|hagas|hagan|fabriques|craftees|construyas|mates)(?![a-z])"
            + "|(?<![a-z])(don'?t|do not|never) (go )?(mine|mining|chop|fish|hunt|harvest|make|craft)");

    /** Asking whether it sees something, or where one is: "¿hay ...?", "¿ves ...?", "¿dónde hay ...?", "any ...?". */
    private static final String ASK = "(hay(?! que )|ves|viste|divisas|distingues|donde|any|see|spot|where|there)( [a-z']+){0,3} ";

    /** "see:<kind>": a question about one kind of thing ({@link Escena} kinds), by the words people use for it. */
    private static Map.Entry<String, Pattern> seeing(String kind, String nouns) {
        return order("see:" + kind, ASK + "(" + nouns + ")");
    }

    private static final List<Map.Entry<String, Pattern>> QUESTIONS = List.of(
            order("bye", "adios|chao|chau|nos vemos|hasta luego|hasta pronto|hasta manana|bye|goodbye|good bye|see you|see ya"),
            // what it remembers ({@link Recuerdos}): before "¿dónde hay cofres?" and "¿qué haces?"
            order("told", "que te (dije|pedi|conte|mande|ordene)|(recuerdas|te acuerdas( de)?) (lo )?que te (dije|pedi)|"
                    + "what did i (say|tell you|ask( you)?)|do you remember what i (said|told you|asked)"),
            order("climbed", "por donde (subiste|saliste|bajaste|viniste|entraste|llegaste|pasaste)|como (subiste|saliste|llegaste)|"
                    + "por que camino (subiste|saliste|bajaste|viniste)|how did you (get|come|climb) (up|out|here)|which way did you (come|go|climb)|"
                    + "the way you came"),
            // its own mind ({@link net.mca.entity.ai.mente.Conciencia}): what it thinks, and why it did what it did
            order("thinking", "en (que|q) (piensas|estas pensando|pensabas|andas pensando)|(que|q) (piensas|estas pensando|pasa por tu cabeza)|"
                    + "what are you thinking( about)?|what('s| is) on your mind|what do you think|what were you thinking"),
            order("why", "(por que|porque|xq|pq) (vas|fuiste|haces|hiciste|cavas|cavaste|picas|picaste|subes|subiste|bajas|bajaste|"
                    + "estas|te fuiste|volviste|vuelves|dejaste|paraste|usas|usaste|no (usas|usaste|subes|subiste|vas|vienes|tomas|tomaste|vuelves|seguiste))|"
                    + "why (are|did|do|didn't|don't|were|would) you"),
            order("place:cofre", "donde (esta|estan|tienes|dejaste|pusiste|queda|guardaste) (tu|tus|el|los|mi) (cofre|cofres|baul|cosas)|"
                    + "donde guardas (tus cosas|todo)|where('s| is| are) your (chest|chests|stuff)|where did you (put|leave) your (chest|stuff)"),
            order("place:casa", "donde (esta|queda|es) tu casa|donde vives|donde duermes|where('s| is) your (home|house|bed)|where do you live"),
            order("place:mina", "donde (esta|queda) tu mina|donde (estas minando|minas|cavas)|donde esta la mina|where('s| is) (your|the) mine|"
                    + "where (are you|do you) (mine|mining|dig|digging)"),
            order("overhead", "que (tienes|hay) (sobre|encima de|arriba de) (ti|tu cabeza|tuyo)|que hay (encima|arriba)( de ti)?|"
                    + "hay algo (sobre|encima de) (ti|tu cabeza)|tienes (algo|un techo|techo) (encima|sobre (ti|la cabeza))|que bloques? (tienes|hay) (encima|arriba|sobre ti)|"
                    + "what('s| is) (above|over) (you|your head)|anything (above|over) (you|your head)|what blocks? (are )?(above|over) you"),
            order("did", "que (hiciste|has hecho|estuviste haciendo|hacias|has estado haciendo)|que te paso|"
                    + "(recuerdas|te acuerdas)( de)? (algo|lo que hiciste|que hiciste)|"
                    + "what (did you do|have you (done|been doing)|were you doing|happened)|do you remember"),
            order("hungry", "tienes hambre|tienes comida|te queda comida|has comido|are you hungry|do you have (any )?food|have you eaten"),
            order("found", "(encontraste|has encontrado|hallaste|conseguiste) (algo|algun|minerales|diamantes|hierro)|que (encontraste|has encontrado|conseguiste)|"
                    + "found anything|what did you find|did you find"),
            order("doing", "que (estas haciendo|haces|andas haciendo)|en que andas|en que estas|what are you doing|what('re| are) you up to"),
            order("bag", "que (tienes|llevas|traes|cargas)|tu inventario|tu mochila|que hay en tu mochila|what do you have|what are you carrying|your inventory|your bag"),
            // what it hears, and how light it is where it stands ({@link Ahora})
            order("hear", "que (oyes|escuchas|estas (oyendo|escuchando))|(oyes|escuchas|oiste|escuchaste) (algo|eso|un ruido|ruidos|pasos)|"
                    + "hay (algun )?ruido|what (do you|can you) hear|do you hear|did you hear|hear anything|any (noise|sound)s?"),
            order("light", "(esta|es) (muy )?oscuro|hay (poca |suficiente |buena )?luz|ves bien|necesitas (una )?antorchas?|hace falta (una )?antorcha|"
                    + "is it dark|how (dark|bright) is it|can you see well|do you need (a )?torch(es)?|is there (enough )?light"),
            seeing(Escena.ESCALERA, "escaleras?|subidas?|escalones|stairs?|staircases?|steps"),
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
            + "algo|ya|sube|subete|sal|ahi|arriba|abajo|sigue|baja|bajando|casa|comer|comida|hambre|ahora|bien|mas|muy|pero|eso|dime|"
            // the short warnings and the "go on" after one
            + "cuidado|detente|espera|esperame|quieto|quieta|alto|tranquilo|tranquila|dale|nada|silencio|falsa|alarma|"
            // the chat's own Spanish, Chilean above all: "q onda", "wena", "po", "weon", "cachai", "xd"
            + "q|ke|k|onda|wena|wenas|weno|wenaa|po|pue|pos|weon|weona|wn|hueon|huevon|cachai|cachay|cachaste|xd|xdd|oe|bacan|bkn|"
            + "cuatico|wea|weas|altiro|harto|pucha|chuta|fome|filete|sipo|nopo|ya po|hermano|compa)\\b");
    private static final Pattern ENGLISH = Pattern.compile("\\b(the|you|your|me|my|please|follow|stay|give|what|where|how|are|is|do|have|"
            + "hello|hi|thanks|thank|here|go|come|stop|mine|find|get|can|let'?s|i|keep|mining|it|to|of|and|up|out|back|now|"
            + "careful|wait|watch|hold|freeze|fine|carry|on)\\b");

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

    /** Per player, the language they last wrote clearly in: true for Spanish. Kept while the server runs. */
    private static final Map<java.util.UUID, Boolean> PLAYER_SPANISH = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * As {@link #speaksSpanish(String, String)}, remembering per player the last language their words clearly said:
     * a message that does not say ("ok", "sube", "jeggor") is answered in that one, before the game's.
     */
    public static boolean speaksSpanish(String text, @Nullable String villagerName, @Nullable java.util.UUID player) {
        String language = language(withoutRawName(text, villagerName));
        if (language != null) {
            boolean es = language.equals("Spanish");
            if (player != null) {
                PLAYER_SPANISH.put(player, es);
            }
            return es;
        }
        Boolean known = player == null ? null : PLAYER_SPANISH.get(player);
        return known != null ? known : !(MCA.language != null && MCA.language.startsWith("en"));
    }

    /** The language this player last wrote clearly in (true: Spanish), or null when not known yet. */
    @Nullable
    public static Boolean playerSpanish(java.util.UUID player) {
        return PLAYER_SPANISH.get(player);
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
            // one word of the name ("Rosa" of "Rosa Mente") is the name only when it is said as one: never after an
            // article, a possessive or a verb that makes it a plain word ("tu escalera", "está oscuro", "la mina")
            for (String part : name.split(" ")) {
                if (part.length() >= 2) {
                    stripped = removeAddressed(stripped, part);
                }
            }
        }
        return stripped.replaceAll("\\s+", " ").trim();
    }

    /** Words after which a name's word is just a word of the sentence, not someone being called. */
    private static final Pattern NOT_A_NAME_BEFORE = Pattern.compile("(?<![\\p{L}\\p{N}])(tu|tus|mi|mis|su|sus|el|la|los|las|un|una|unos|unas|"
            + "esta|estas|este|es|muy|tan|que|de|del|al|en|hay|ves|the|your|my|his|her|a|an|is|it's|its|very|so|of|any|some)\\s*$");

    private static String removeAddressed(String text, String word) {
        if (word.isEmpty()) {
            return text;
        }
        Matcher m = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])").matcher(text);
        while (m.find()) {
            String before = text.substring(0, m.start()).replaceAll("[^\\p{L}\\p{N}' ]", " ").trim();
            if (!NOT_A_NAME_BEFORE.matcher(before).find()) {
                return text.substring(0, m.start()) + " " + text.substring(m.end());
            }
        }
        return text;
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
        // "dame tus coordenadas" asks where it is, it is no hand-over
        if (COORDS.matcher(t).find()) {
            return new Intent(null, "", "coords", spanish);
        }
        // "sería mejor volver" is advice it weighs, not the order "vuelve a casa"
        if (SUGGEST_RETURN.matcher(t).find() && (text.contains("?") || text.contains("¿") || !NOT_RETURN.matcher(t).find())) {
            return new Intent("consider-return", "", null, spanish);
        }
        // the message as written, accents gone but its punctuation kept: "para," is not "para que"
        String marked = Acciones.normalize(text).trim();
        for (Map.Entry<String, Pattern> entry : ORDERS) {
            Matcher m = entry.getValue().matcher(t);
            if ("alert".equals(entry.getKey()) && !m.find() && (PARA.matcher(marked).find() || ALTO.matcher(marked).find())) {
                return new Intent("alert", "", null, spanish);
            }
            m.reset();
            if (m.find()) {
                if ((entry.getKey().startsWith("start-") || entry.getKey().equals("craft-item")) && NEGATED_START.matcher(t).find()) {
                    continue; // "no vayas a la mina" is not an order to go
                }
                String argument = "";
                if (entry.getKey().equals("give-item")) {
                    argument = t.substring(m.end()).trim();
                } else if (entry.getKey().equals("start-mining")) {
                    Matcher ore = Pattern.compile("(?<![a-z])(" + ORES + ")(?![a-z])").matcher(t);
                    argument = ore.find() ? ore.group(1) : "";
                } else if (ERRANDS.containsKey(entry.getKey())) {
                    argument = t; // how many, of what, and whether to bring it: the errand reads it all ({@link net.mca.entity.ai.mente.Encargo})
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
        if (q.startsWith("place:")) {
            return placeOf(villager, q.substring(6), es);
        }
        return switch (q) {
            case "thinking" -> thinking(villager, es);
            case "why" -> why(villager, es);
            case "doing" -> doing(villager, es);
            case "did" -> did(villager, es);
            case "overhead" -> overhead(villager, es);
            case "climbed" -> climbed(villager, es);
            case "told" -> told(villager, player, es);
            case "bag" -> bag(villager, es);
            case "health" -> health(villager, player, es);
            case "where" -> where(villager, es);
            case "coords" -> coords(villager, es);
            case "see" -> see(villager, es);
            case "hear" -> hear(villager, es);
            case "light" -> light(villager, es);
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
        String errand = command == null ? null : ERRANDS.get(command);
        if (errand != null && Voluntad.conectado(villager)) {
            // a connected villager took it on itself (or said why it cannot): those are its words
            String own = Voluntad.dicho(villager, errand, es);
            if (own != null) {
                return own;
            }
        }
        if ("consider-return".equals(command)) {
            // what it made of the advice, with its reasons ({@link TriggerCommandInfos#considerReturn})
            TriggerCommandInfos.Vuelta vuelta = TriggerCommandInfos.lastVuelta(villager);
            if (vuelta != null) {
                return es ? vuelta.es() : vuelta.en();
            }
            return es ? "Mm, déjame pensarlo." : "Hm, let me think about it.";
        }
        if (outcome == null && "come-up".equals(command)) {
            // only a miner climbs out of a mine
            return es ? "No estoy en ninguna mina ahora mismo." : "I'm not down in a mine right now.";
        }
        if (outcome == null && "eat-break".equals(command)) {
            return es ? "Estoy bien, todavía no tengo hambre." : "I'm fine, I'm not hungry yet.";
        }
        if (outcome == null && "carry-on".equals(command)) {
            return es ? "Si no estaba parado por nada." : "I wasn't stopped for anything.";
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
            case "alert" -> Voluntad.pregunta(villager, es);
            case "carry-on" -> Voluntad.sigoLinea(villager, es);
            case "free" -> pick(villager, es ? new String[]{"Bueno, vuelvo a lo mío.", "Vale, sigo con mis cosas."} : new String[]{"Alright, back to my own thing.", "Okay, I'll get on with my stuff."});
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
        if (Voluntad.conectado(villager) && Voluntad.of(villager).modo() != Voluntad.Modo.LIBRE) {
            // following, waiting or stopped by a warning: what its will is set on, in its own words
            return switch (Voluntad.of(villager).modo()) {
                case SIGUIENDO -> es ? "Te sigo y te cuido la espalda." : "I'm following you and watching your back.";
                case ESPERANDO -> es ? "Aquí, esperándote como me pediste." : "Waiting here for you, like you asked.";
                case CONOCIENDO -> es ? "Conociéndote. ¿Cómo te llamas?" : "Getting to know you. What's your name?";
                case ENCARGO -> {
                    String on = Voluntad.haciendo(villager, es);
                    yield on != null ? on : es ? "Terminando lo que me pediste." : "Finishing what you asked.";
                }
                default -> es ? "Estoy quieto, esperando que me digas qué pasa." : "Standing still, waiting for you to tell me what's going on.";
            };
        }
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

    // ------------------------------------------------------------------ what it remembers

    /** "¿En qué piensas?": its last thought, else what its will is set on, else what it is doing. */
    private static String thinking(VillagerEntityMCA villager, boolean es) {
        String thought = net.mca.entity.ai.mente.Conciencia.ultimoPensamiento(villager, 24000);
        if (thought != null) {
            return es ? "Pensaba: " + thought : "I was thinking (in my words): " + thought;
        }
        if (Voluntad.conectado(villager)) {
            String now = Voluntad.of(villager).pensando();
            if (now != null && !now.isBlank()) {
                return es ? "Ahora mismo: " + now + "." : "Right now (in my words): " + now + ".";
            }
        }
        return doing(villager, es);
    }

    /** "¿Por qué haces eso?": its real last decisions and their reasons, never one made up. */
    private static String why(VillagerEntityMCA villager, boolean es) {
        Recuerdos r = Recuerdos.of(villager);
        List<Recuerdos.Hecho> last = r == null ? List.of() : r.hechos("plan|camino|atasco", 2);
        if (last.isEmpty()) {
            String thought = net.mca.entity.ai.mente.Conciencia.ultimoPensamiento(villager, 6000);
            if (thought != null) {
                return es ? "Lo que pensaba: " + thought : "What I was thinking (in my words): " + thought;
            }
            return es ? "La verdad, no sé bien por qué; no recuerdo haberlo decidido por algo." : "Honestly, I'm not sure why; I don't remember deciding it for a reason.";
        }
        long now = villager.getWorld().getTime();
        List<String> parts = new ArrayList<>();
        for (Recuerdos.Hecho h : last) {
            parts.add(Recuerdos.hace(now, h.t(), es) + " " + lower(h.texto()));
        }
        return (es ? "Por lo que recuerdo: " : "From what I remember (in my words): ") + String.join("; ", parts) + ".";
    }

    /** "¿Qué hiciste?": the last few things that happened to it, from its diary ({@link Recuerdos}). */
    private static String did(VillagerEntityMCA villager, boolean es) {
        Recuerdos r = Recuerdos.of(villager);
        List<Recuerdos.Hecho> last = r == null ? List.of() : r.hechos("viaje|cofre|hallazgo|pelea|oficio|camino|plan|atasco", 4);
        if (last.isEmpty()) {
            return (es ? "No me ha pasado nada que valga la pena contar. " : "Nothing worth telling has happened to me. ") + doing(villager, es);
        }
        long now = villager.getWorld().getTime();
        List<String> parts = new ArrayList<>();
        for (Recuerdos.Hecho h : last) {
            parts.add(Recuerdos.hace(now, h.t(), es) + " " + lower(h.texto()));
        }
        return (es ? "Lo último: " : "Lately (in my words): ") + String.join("; ", parts) + ".";
    }

    /** "¿Por dónde subiste?": the way it last came up or went down, as it remembers it. */
    private static String climbed(VillagerEntityMCA villager, boolean es) {
        Recuerdos r = Recuerdos.of(villager);
        if (r == null) {
            return es ? "No me acuerdo." : "I don't remember.";
        }
        long now = villager.getWorld().getTime();
        List<Recuerdos.Hecho> ways = r.hechos("camino", 1);
        List<Recuerdos.Hecho> out = r.hechos("viaje", 3);
        StringBuilder s = new StringBuilder();
        if (!ways.isEmpty()) {
            Recuerdos.Hecho h = ways.get(0);
            s.append(es ? "Me acuerdo bien: " : "I remember: ").append(Recuerdos.hace(now, h.t(), es)).append(" ").append(lower(h.texto())).append(".");
        }
        for (int i = out.size() - 1; i >= 0; i--) {
            Recuerdos.Hecho h = out.get(i);
            if (h.texto().startsWith("salí")) {
                s.append(s.length() == 0 ? "" : " ").append(es ? "" : "And ").append(Recuerdos.hace(now, h.t(), es)).append(" ").append(lower(h.texto())).append(".");
                break;
            }
        }
        BlockPos door = r.sitio("mina");
        if (door != null) {
            s.append(es ? " La boca de mi mina está en " : " The mouth of my mine is at ").append(door.getX()).append(" ").append(door.getY()).append(" ")
                    .append(door.getZ()).append(".");
        }
        if (s.length() == 0) {
            return es ? "Todavía no he subido ni bajado por ningún lado que recuerde." : "I haven't climbed in or out anywhere I remember yet.";
        }
        if (r.size() > 0) {
            s.append(es ? " Recuerdo " + r.size() + " sitios por donde caminé, así que sé volver por ahí." : " I remember " + r.size() + " spots I walked on, so I know my way back.");
        }
        return s.toString().trim();
    }

    /** "¿Qué te dije?": what this player said to it lately, as it remembers. */
    private static String told(VillagerEntityMCA villager, ServerPlayerEntity player, boolean es) {
        Recuerdos r = Recuerdos.of(villager);
        String who = player.getName().getString();
        List<String> said = new ArrayList<>();
        if (r != null) {
            long now = villager.getWorld().getTime();
            for (Recuerdos.Hecho h : r.hechos("charla", 40)) {
                String prefix = who + " me dijo «";
                if (h.texto().startsWith(prefix)) {
                    int end = h.texto().indexOf("»", prefix.length());
                    said.add(Recuerdos.hace(now, h.t(), es) + ": «" + h.texto().substring(prefix.length(), end < 0 ? h.texto().length() : end) + "»");
                }
            }
        }
        if (said.isEmpty()) {
            return es ? "No me acuerdo de que me hayas dicho nada antes." : "I don't remember you telling me anything before.";
        }
        List<String> last = said.subList(Math.max(0, said.size() - 3), said.size());
        return (es ? "Me dijiste " : "You told me ") + String.join("; ", last) + ".";
    }

    /** "¿Dónde está tu cofre / tu casa / tu mina?": the place it remembers by that name, with its exact coordinates. */
    private static String placeOf(VillagerEntityMCA villager, String what, boolean es) {
        Recuerdos r = Recuerdos.of(villager);
        BlockPos pos = null;
        if (r != null) {
            pos = r.sitio(what);
            if (pos == null && what.equals("casa")) {
                pos = r.sitio("campamento");
            }
            if (pos == null && what.equals("mina")) {
                pos = r.sitio("frente de trabajo");
            }
        }
        if (pos == null && what.equals("casa")) {
            var home = villager.getResidency().getHome();
            if (home.isPresent() && home.get().getDimension() == villager.getWorld().getRegistryKey()) {
                pos = home.get().getPos();
            }
        }
        String[] name = switch (what) {
            case "cofre" -> new String[]{"Mi cofre", "My chest"};
            case "casa" -> new String[]{"Mi casa", "My home"};
            default -> new String[]{"Mi mina", "My mine"};
        };
        if (pos == null) {
            return es ? "Todavía no tengo " + (what.equals("cofre") ? "cofre" : what.equals("casa") ? "casa" : "mina") + " que recuerde."
                    : "I don't have a " + (what.equals("cofre") ? "chest" : what.equals("casa") ? "home" : "mine") + " that I remember yet.";
        }
        int d = (int) Math.round(Math.sqrt(villager.getBlockPos().getSquaredDistance(pos)));
        String at = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        String extra = "";
        if (what.equals("mina") && r != null && r.sitio("frente de trabajo") != null && !r.sitio("frente de trabajo").equals(pos)) {
            BlockPos face = r.sitio("frente de trabajo");
            extra = es ? "; estaba picando en " + face.getX() + " " + face.getY() + " " + face.getZ() : "; I was digging at " + face.getX() + " " + face.getY() + " " + face.getZ();
        }
        return es ? name[0] + " está en " + at + ", a unos " + d + " bloques de aquí" + extra + "."
                : name[1] + " is at " + at + ", about " + d + " blocks from here" + extra + ".";
    }

    /** "¿Qué tienes encima?": what its body feels over its head and around it ({@link Tacto}). */
    private static String overhead(VillagerEntityMCA villager, boolean es) {
        try {
            String felt = Tacto.describeOverhead(villager, es);
            if (!felt.isBlank()) {
                return felt;
            }
        } catch (RuntimeException e) {
            // said plainly below
        }
        return es ? "No sé bien, no alcanzo a sentirlo." : "I can't really tell.";
    }

    private static String lower(String text) {
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
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

    /** Its exact block position, "X Y Z" as the game's own coordinates show them: "123 64 -45". */
    public static String coordinates(VillagerEntityMCA villager) {
        BlockPos p = villager.getBlockPos();
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    /**
     * Whether {@code line} gives the villager's exact coordinates: its three numbers, signs included, each written
     * as a whole number somewhere in it ("123 64 -45", "x=123, y=64, z=-45").
     */
    public static boolean hasCoordinates(String line, BlockPos p) {
        Set<Integer> numbers = new java.util.HashSet<>();
        Matcher m = Pattern.compile("(?<![\\d.,])(-|−)?\\s?(\\d{1,8})(?![\\d]|[.,]\\d)").matcher(line);
        while (m.find()) {
            try {
                int n = Integer.parseInt(m.group(2));
                numbers.add(m.group(1) != null ? -n : n);
            } catch (NumberFormatException ignored) {
                // too long to be a coordinate
            }
        }
        return numbers.contains(p.getX()) && numbers.contains(p.getY()) && numbers.contains(p.getZ());
    }

    /** " en el Nether" / " in the Nether" outside the overworld, else nothing. */
    private static String dimension(VillagerEntityMCA villager, boolean es) {
        String path = villager.getWorld().getRegistryKey().getValue().getPath();
        return switch (path) {
            case "overworld" -> "";
            case "the_nether" -> es ? " en el Nether" : " in the Nether";
            case "the_end" -> es ? " en el End" : " in the End";
            default -> es ? " en " + path.replace('_', ' ') : " in " + path.replace('_', ' ');
        };
    }

    /**
     * Where it is in words, from its sense of place ({@link Lugar}): outside, under a roof or down in the rock and
     * how deep; a house roof is never "underground". "afuera" / "outside".
     */
    private static String place(VillagerEntityMCA villager, boolean es) {
        Lugar lugar = Vistazo.lugar(villager);
        if (lugar != null) {
            int depth = Vistazo.profundidad(villager);
            return switch (lugar) {
                case AFUERA -> depth > 8
                        ? (es ? "afuera, pero en un hoyo, unos " + depth + " bloques bajo el suelo" : "outside, but down in a pit, about " + depth + " blocks below the ground")
                        : (es ? "afuera" : "outside");
                case BAJO_TECHO -> es ? "bajo techo" : "indoors, under a roof";
                case BAJO_TIERRA -> depth > 0
                        ? (es ? "bajo tierra, unos " + depth + " bloques bajo la superficie" : "underground, about " + depth + " blocks below the surface")
                        : (es ? "bajo tierra" : "underground");
            };
        }
        BlockPos p = villager.getBlockPos();
        boolean under = !villager.getWorld().isSkyVisible(p.up()) && p.getY() < villager.getWorld().getSeaLevel() - 4;
        return under ? (es ? "bajo tierra" : "underground") : "";
    }

    /** Where it is: its place in words and its exact coordinates, "Estoy bajo techo, en 123 64 -45." */
    private static String where(VillagerEntityMCA villager, boolean es) {
        String place = place(villager, es);
        String at = coordinates(villager) + dimension(villager, es);
        if (place.isEmpty()) {
            return es ? "Estoy en " + at + "." : "I'm at " + at + ".";
        }
        return es ? "Estoy " + place + ", en " + at + "." : "I'm " + place + ", at " + at + ".";
    }

    /** "Dame tus coordenadas": the exact numbers first, then where that is. */
    private static String coords(VillagerEntityMCA villager, boolean es) {
        String place = place(villager, es);
        String at = coordinates(villager);
        return es ? "Mis coordenadas: " + at + " (X Y Z)" + dimension(villager, true) + (place.isEmpty() ? "." : "; estoy " + place + ".")
                : "My coordinates: " + at + " (X Y Z)" + dimension(villager, false) + (place.isEmpty() ? "." : "; I'm " + place + ".");
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

    /** What it hears: the beings it hears and does not see (where, coming closer or not), or that all is quiet ({@link Ahora}). */
    private static String hear(VillagerEntityMCA villager, boolean es) {
        Ahora ahora = Vistazo.ahora(villager);
        if (ahora == null) {
            return es ? "No sabría decirte." : "I couldn't say.";
        }
        List<String> heard = new ArrayList<>();
        for (Ahora.Ser s : ahora.seres()) {
            if (s.oido()) {
                String what = es ? (s.clase() == Ahora.Clase.JUGADOR || s.clase() == Ahora.Clase.ALDEANO && !"aldeano".equals(s.nombre())
                        ? "a " + s.nombre() : (s.nombre().endsWith("a") ? "una " : "un ") + s.nombre())
                        : (s.clase() == Ahora.Clase.JUGADOR || s.clase() == Ahora.Clase.ALDEANO ? s.name() : "a " + s.name());
                heard.add(what + " " + (es ? s.direccion() : s.direction()) + (s.acercandose() ? (es ? " que se acerca" : ", coming closer") : "")
                        + (s.visto() ? "" : (es ? " (no lo veo)" : " (I can't see it)")));
                if (heard.size() >= 3) {
                    break;
                }
            }
        }
        if (heard.isEmpty()) {
            return es ? "No oigo nada raro, está tranquilo." : "I don't hear anything odd, it's quiet.";
        }
        return (es ? "Oigo " : "I hear ") + String.join(es ? ", " : ", ", heard) + ".";
    }

    /** How light it is where it stands, and whether it would want a torch ({@link Ahora.Luz}). */
    private static String light(VillagerEntityMCA villager, boolean es) {
        Ahora ahora = Vistazo.ahora(villager);
        if (ahora == null) {
            return es ? "No sabría decirte." : "I couldn't say.";
        }
        Ahora.Luz luz = ahora.luz();
        String said = luz.palabra(es);
        said = Character.toUpperCase(said.charAt(0)) + said.substring(1) + (es ? " (luz " + luz.nivel() + " de 15)" : " (light " + luz.nivel() + " of 15)");
        if (luz.monstruos()) {
            said += es ? "; tan oscuro que pueden salir monstruos" : "; dark enough for monsters to spawn";
        }
        if (luz.antorcha()) {
            said += es ? ", aquí pondría una antorcha" : ", I'd put a torch here";
        }
        return said + ".";
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
        return es ? "Puedo seguirte, quedarme aquí, detenerme si me dices «cuidado», ir a minar (dime qué buscar) y subir cuando me llames, talar, pescar, cosechar, cazar, darte cosas o guardarlas en mi cofre, y decirte mis coordenadas exactas."
                : "I can follow you, stay here, stop dead when you say \"careful\", go mining (tell me what to look for) and come up when you call, chop trees, fish, harvest, hunt, hand you things or store them in my chest, and tell you my exact coordinates.";
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
