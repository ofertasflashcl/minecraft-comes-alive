package net.mca.entity.ai.mente;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.body.Progreso;
import net.mca.entity.ai.chatAI.MenteLocal;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import org.jetbrains.annotations.Nullable;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Something a player asked a connected villager to do for them, the way one friend asks another in the game: "tala un
 * poco", "tráeme 10 troncos", "cosecha el trigo", "caza algo", "pesca un rato", "hazme un cofre". It does it with its own
 * hands and eyes ({@link net.mca.entity.ai.brain.tasks.chore.ProspectingTask#encargo}), still connected, and then goes
 * back to what it was doing for them ({@link Voluntad}): MCA's own chores would take its connection away. What it got
 * is what came into its bag of that kind; it hands it over when it was asked to bring it, otherwise it offers it.
 */
public final class Encargo {
    public enum Tipo {
        TALAR("talar", "chopping trees", 10, 192, 12000),
        COSECHAR("cosechar", "harvesting the ripe crops", 64, 256, 4800),
        CAZAR("cazar", "hunting animals", 2, 6, 6000),
        PESCAR("pescar", "fishing", 3, 24, 12000),
        HACER("hacer", "crafting", 1, 64, 9000);

        public final String palabra;
        public final String english;
        /** How many when the words do not say ("tala un poco"). */
        final int poco;
        final int max;
        /** Ticks it keeps at it before it comes back with what it has. */
        final int limite;

        Tipo(String palabra, String english, int poco, int max, int limite) {
            this.palabra = palabra;
            this.english = english;
            this.poco = poco;
            this.max = max;
            this.limite = limite;
        }

        /** The errand a chore of MCA's menu stands for, for a connected villager (escorting is following). */
        @Nullable
        public static Tipo of(Chore chore) {
            return switch (chore) {
                case CHOP -> TALAR;
                case HARVEST -> COSECHAR;
                case HUNT -> CAZAR;
                case FISH -> PESCAR;
                default -> null;
            };
        }
    }

    /** How it ended (or that it goes on). */
    public enum Fin {SIGUE, LISTO, NADA, LLENO, NOCHE, FALTA, HAMBRE, TIEMPO}

    public final Tipo tipo;
    public final int cuanto;
    public final boolean traer;
    /** What to make ({@link Tipo#HACER}), or null. */
    @Nullable
    public final Item cosa;
    /** The kind of animal to hunt ("caza una vaca"), or null for any it may hunt. */
    @Nullable
    public final Class<?> presa;
    public final UUID para;
    public final String paraNombre;
    /** What it got so far, counted by the task. */
    public int hecho;
    /** The task's own count when it started: logs in the bag, crops harvested, animals hunted, catches, things made. */
    public int base;
    /** The bag (and hands) when it started: what came in since is what it got. */
    private final Map<Item, Integer> antes;

    private Encargo(Tipo tipo, int cuanto, boolean traer, @Nullable Item cosa, @Nullable Class<?> presa, PlayerEntity para, String paraNombre,
                    Map<Item, Integer> antes) {
        this.tipo = tipo;
        this.cuanto = cuanto;
        this.traer = traer;
        this.cosa = cosa;
        this.presa = presa;
        this.para = para.getUuid();
        this.paraNombre = paraNombre;
        this.antes = antes;
    }

    /**
     * The errand from the words that asked for it (the whole message, or a model's "12 traer"); null for something to
     * make that it does not know how to make.
     */
    @Nullable
    static Encargo leer(Tipo tipo, String words, VillagerEntityMCA villager, PlayerEntity player, String name) {
        String t = llano(words == null ? "" : words);
        Item cosa = null;
        int poco = tipo.poco;
        if (tipo == Tipo.HACER) {
            cosa = Progreso.queHacer(t, villager);
            if (cosa == null) {
                return null;
            }
            poco = cosa == Items.TORCH || cosa == Items.STICK || cosa == Items.OAK_PLANKS ? 8 : 1;
        }
        int n = cuantos(t, poco, tipo.max);
        if (tipo == Tipo.TALAR && ARBOL.matcher(t).find() && !TRONCO.matcher(t).find() && n != poco) {
            n = Math.min(tipo.max, n * 5); // "tala dos árboles": about five logs a tree
        }
        boolean traer = tipo == Tipo.HACER ? !PARA_SI.matcher(t).find() : PARA_EL.matcher(t).find();
        Class<?> presa = tipo == Tipo.CAZAR ? presa(t) : null;
        return new Encargo(tipo, n, traer, cosa, presa, player, name, bolsa(villager));
    }

    /** The animal the words name ("caza una vaca", "mata dos gallinas"), or null. */
    @Nullable
    private static Class<?> presa(String t) {
        if (t.matches(".*(?<![a-z])(vacas?|res|reses|cows?)(?![a-z]).*")) {
            return net.minecraft.entity.passive.CowEntity.class;
        }
        if (t.matches(".*(?<![a-z])(cerdos?|chanchos?|puercos?|cochinos?|pigs?)(?![a-z]).*")) {
            return net.minecraft.entity.passive.PigEntity.class;
        }
        if (t.matches(".*(?<![a-z])(ovejas?|sheep)(?![a-z]).*")) {
            return net.minecraft.entity.passive.SheepEntity.class;
        }
        if (t.matches(".*(?<![a-z])(gallinas?|pollos?|chickens?)(?![a-z]).*")) {
            return net.minecraft.entity.passive.ChickenEntity.class;
        }
        if (t.matches(".*(?<![a-z])(conejos?|rabbits?)(?![a-z]).*")) {
            return net.minecraft.entity.passive.RabbitEntity.class;
        }
        return null;
    }

    // ------------------------------------------------------------------ the words

    private static final Pattern STACKS = Pattern.compile("(?<![a-z])(\\d+|dos|tres|cuatro|cinco|two|three|four|five) (stacks?|pilas?|estacks?)(?![a-z])");
    private static final Pattern MEDIO = Pattern.compile("(?<![a-z])(media pila|medio stack|medio estack|half a stack)(?![a-z])");
    private static final Pattern STACK = Pattern.compile("(?<![a-z])(un|una|a) (stack|pila|estack)(?![a-z])");
    private static final Pattern DIGITOS = Pattern.compile("(?<![0-9])(\\d{1,4})(?![0-9])");
    private static final Pattern PAR = Pattern.compile("(?<![a-z])(un par|un parcito|a couple|a pair)(?![a-z])");
    private static final Pattern MUCHO = Pattern.compile("(?<![a-z])(mucho|mucha|muchos|muchas|harto|harta|hartos|hartas|bastante|bastantes|"
            + "un monton|montones|caleta|a lot|lots|plenty|loads)(?![a-z])");
    private static final Pattern UNO = Pattern.compile("(?<![a-z])(un|una|uno|one|an?)(?![a-z])");
    private static final Pattern POCO = Pattern.compile("(?<![a-z])(un poco|unos pocos|unas pocas|un poquito|algo|algunos|algunas|unos|unas|"
            + "a few|some|a bit)(?![a-z])");
    private static final Map<String, Integer> NUMEROS = numeros();
    private static final Pattern ARBOL = Pattern.compile("(?<![a-z])(arbol|arboles|trees?)(?![a-z])");
    private static final Pattern TRONCO = Pattern.compile("(?<![a-z])(tronco|troncos|logs?)(?![a-z])");
    /** "Tráeme", "dame", "para mí", "bring me", or a model's "traer": it hands over what it gets. */
    private static final Pattern PARA_EL = Pattern.compile("(?<![a-z])(traeme|traigame|traenos|traemelo|traemelos|traemela|traemelas|trae|dame|damelo|"
            + "damelos|damela|damelas|pasame|consigueme|consiguenos|buscame|juntame|recogeme|cortame|sacame|cazame|pescame|cosechame|"
            + "para mi|pa mi|para nosotros|traer|entregar|entregame|bring|fetch|give me|get me|for me)(?![a-z])");
    /** "Hazte una espada", "para ti": what it makes is for itself. */
    private static final Pattern PARA_SI = Pattern.compile("(?<![a-z])(hazte|hacete|fabricate|craftate|crafteate|construyete|preparate|para ti|pa ti|"
            + "tuyo|tuya|tuyos|tuyas|para vos|yourself|for you|quedatela|quedatelo)(?![a-z])");

    /** How many the words ask for: a number, "un stack", "mucha", "un poco"; {@code poco} when they do not say. */
    static int cuantos(String t, int poco, int max) {
        int n = -1;
        Matcher m;
        if ((m = STACKS.matcher(t)).find()) {
            n = 64 * numero(m.group(1));
        } else if (MEDIO.matcher(t).find()) {
            n = 32;
        } else if (STACK.matcher(t).find()) {
            n = 64;
        } else if ((m = DIGITOS.matcher(t)).find()) {
            n = Integer.parseInt(m.group(1));
        } else {
            for (String word : t.split("[^a-z]+")) {
                Integer v = NUMEROS.get(word);
                if (v != null) {
                    n = v;
                    break;
                }
            }
            if (n < 0 && PAR.matcher(t).find()) {
                n = 2;
            } else if (n < 0 && MUCHO.matcher(t).find()) {
                n = poco * 3;
            } else if (n < 0 && POCO.matcher(t).find()) {
                n = poco;
            } else if (n < 0 && UNO.matcher(t).find()) {
                n = 1;
            }
        }
        return Math.max(1, Math.min(max, n < 0 ? poco : n));
    }

    private static int numero(String word) {
        Integer v = NUMEROS.get(word);
        return v != null ? v : word.matches("\\d+") ? Integer.parseInt(word) : 1;
    }

    private static Map<String, Integer> numeros() {
        Map<String, Integer> m = new LinkedHashMap<>();
        String[] es = {"dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce", "trece", "catorce", "quince",
                "dieciseis", "diecisiete", "dieciocho", "diecinueve", "veinte"};
        for (int i = 0; i < es.length; i++) {
            m.put(es[i], i + 2);
        }
        String[] en = {"two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
                "sixteen", "seventeen", "eighteen", "nineteen", "twenty"};
        for (int i = 0; i < en.length; i++) {
            m.put(en[i], i + 2);
        }
        m.put("treinta", 30);
        m.put("thirty", 30);
        m.put("cuarenta", 40);
        m.put("forty", 40);
        m.put("cincuenta", 50);
        m.put("fifty", 50);
        m.put("cien", 100);
        m.put("hundred", 100);
        return m;
    }

    /** Lower case without accents, for the patterns. */
    static String llano(String text) {
        String n = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "").replace('ñ', 'n');
    }

    // ------------------------------------------------------------------ what it got

    /** Everything it carries, in the bag, in its hands and on its body: item -> count. */
    static Map<Item, Integer> bolsa(VillagerEntityMCA villager) {
        Map<Item, Integer> all = new LinkedHashMap<>();
        SimpleInventory inv = villager.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) {
                all.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = villager.getEquippedStack(slot);
            if (!s.isEmpty() && !shares(inv, s)) {
                all.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        return all;
    }

    /** A stack worn or held that is the very stack of a bag slot (MCA's hands may hold one): counted once. */
    private static boolean shares(SimpleInventory inv, ItemStack stack) {
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i) == stack) {
                return true;
            }
        }
        return false;
    }

    /** Whether a thing it got belongs to this errand (what it hands over). */
    boolean deEste(ItemStack stack) {
        return switch (tipo) {
            case TALAR -> stack.isIn(ItemTags.LOGS);
            case COSECHAR -> stack.isOf(Items.WHEAT) || stack.isOf(Items.CARROT) || stack.isOf(Items.POTATO) || stack.isOf(Items.BEETROOT);
            case CAZAR -> stack.isFood() && !stack.isOf(Items.BREAD) || stack.isOf(Items.LEATHER) || stack.isOf(Items.FEATHER)
                    || stack.isOf(Items.RABBIT_HIDE) || stack.isOf(Items.RABBIT_FOOT) || stack.isIn(ItemTags.WOOL);
            case PESCAR -> !(stack.getItem() instanceof FishingRodItem);
            case HACER -> cosa == Items.OAK_PLANKS ? stack.isIn(ItemTags.PLANKS) : stack.isOf(cosa);
        };
    }

    /** What it got since it started that belongs to this errand, never more than it carries now: item -> count. */
    Map<Item, Integer> ganado(VillagerEntityMCA villager) {
        Map<Item, Integer> now = bolsa(villager);
        Map<Item, Integer> got = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : now.entrySet()) {
            int more = e.getValue() - antes.getOrDefault(e.getKey(), 0);
            if (more > 0 && deEste(new ItemStack(e.getKey()))) {
                got.put(e.getKey(), more);
            }
        }
        return got;
    }

    /** "10 troncos de roble, 2 de abedul" / "10 oak logs": what it got, in words. */
    static String palabras(Map<Item, Integer> cosas, boolean es) {
        StringBuilder sb = new StringBuilder();
        int k = 0;
        for (Map.Entry<Item, Integer> e : cosas.entrySet()) {
            if (k++ > 0) {
                sb.append(k == cosas.size() ? (es ? " y " : " and ") : ", ");
            }
            sb.append(MenteLocal.amount(e.getValue(), new ItemStack(e.getKey()), es));
        }
        return sb.toString();
    }

    /** What it is asked for, in words: "10 troncos", "lo que esté maduro", "un cofre". */
    String pedido(boolean es) {
        return switch (tipo) {
            case TALAR -> es ? cuanto + " troncos" : cuanto + " logs";
            case COSECHAR -> cuanto >= tipo.poco ? (es ? "lo que esté maduro" : "whatever is ripe") : (es ? cuanto + " cultivos" : cuanto + " crops");
            case CAZAR -> es ? (cuanto == 1 ? "un animal" : cuanto + " animales") : (cuanto == 1 ? "an animal" : cuanto + " animals");
            case PESCAR -> es ? (cuanto == 1 ? "un pescado" : cuanto + " pescados") : (cuanto == 1 ? "a fish" : cuanto + " fish");
            case HACER -> MenteLocal.amount(cuanto, new ItemStack(cosa), es);
        };
    }

    /** How far along it is: "4 de 10 troncos". */
    String avance(boolean es) {
        return switch (tipo) {
            case TALAR -> hecho + (es ? " de " : " of ") + cuanto + (es ? " troncos" : " logs");
            case COSECHAR -> hecho + (es ? " cosechados" : " harvested");
            case CAZAR -> hecho + (es ? " de " : " of ") + cuanto + (es ? " animales" : " animals");
            case PESCAR -> hecho + (es ? " de " : " of ") + cuanto + (es ? " pescas" : " catches");
            case HACER -> hecho + (es ? " de " : " of ") + cuanto + " " + new ItemStack(cosa).getName().getString();
        };
    }

    /** The first thing it says, taking the errand on (Spanish, English). */
    String[] empiezo() {
        String n = paraNombre;
        return switch (tipo) {
            case TALAR -> new String[]{"¡Claro, " + n + "! Voy a talar " + pedido(true) + (traer ? " y te los traigo." : "."),
                    "Sure, " + n + "! I'll chop " + pedido(false) + (traer ? " and bring them to you." : ".")};
            case COSECHAR -> new String[]{"Vale, cosecho " + pedido(true) + " y vuelvo a sembrar.", "Okay, I'll harvest " + pedido(false) + " and sow it again."};
            case CAZAR -> new String[]{"Voy de caza, " + n + ": " + pedido(true) + (traer ? ", y te traigo lo que saque." : "."),
                    "Going hunting, " + n + ": " + pedido(false) + (traer ? ", and I'll bring you what I get." : ".")};
            case PESCAR -> new String[]{"Voy a pescar un rato" + (traer ? " y te traigo lo que saque." : "."),
                    "I'll go fishing for a bit" + (traer ? " and bring you what I catch." : ".")};
            case HACER -> new String[]{(traer ? "¡Va! Te hago " : "Me hago ") + pedido(true) + ".",
                    (traer ? "On it! I'll make you " : "I'll make myself ") + pedido(false) + "."};
        };
    }

    /** For a language model: the errand, in plain English. */
    String english() {
        return tipo.english + " for " + paraNombre + " (asked for " + pedido(false) + "; so far " + avance(false) + ")"
                + (traer ? ", to hand it over to them" : "");
    }

    /** For its thoughts and the log: "talo árboles para Benja: 4 de 10 troncos". */
    String pensando() {
        return switch (tipo) {
            case TALAR -> "talo árboles para " + paraNombre;
            case COSECHAR -> "cosecho para " + paraNombre;
            case CAZAR -> "cazo para " + paraNombre;
            case PESCAR -> "pesco para " + paraNombre;
            case HACER -> "hago " + pedido(true) + " para " + (traer ? paraNombre : "mí");
        } + ": " + avance(true);
    }
}
