package net.mca.entity.ai.mente;

import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Memories;
import net.mca.entity.ai.body.CombatMind;
import net.mca.entity.ai.body.Hands;
import net.mca.entity.ai.body.Mining;
import net.mca.entity.ai.body.Motor;
import net.mca.entity.ai.body.Postura;
import net.mca.entity.ai.brain.tasks.chore.ProspectingTask;
import net.mca.entity.ai.chatAI.ChatAI;
import net.mca.entity.ai.chatAI.MenteLocal;
import net.mca.entity.ai.chatAI.OpenAIChatAI;
import net.mca.entity.ai.relationship.Gender;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.mca.server.world.data.PlayerSaveData;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The companion a villager becomes for the one who connects it ("Conectar" in its menu), as a person meeting someone:
 * <ul>
 * <li>the meeting: it turns to the player, looks them over from the feet up to the face, says hello and asks their
 * name; while it waits for the answer it takes in the place, only looking about, ducking down now and then like a
 * player holding shift, its eyes on what is around and back on the player;</li>
 * <li>the bond: the name and how the player identifies (their own words, else the game's setting) are kept per
 * player with its other memories of them; the time at their side, a meal when it was starving and their answers make
 * hearts, and the hearts and what the two agree on make the stage: known, friend, best friend, compadre or comadre,
 * partner (only with mutual attraction, as the game's own romance has it);</li>
 * <li>hunger at the player's side: it says so and stops, goes through its bag (head down, crouched, the hand
 * rummaging), makes bread when it can, and else asks the player for something to eat, picking up what is thrown to it;
 * a "¿qué pasa?" meanwhile gets the truth.</li>
 * </ul>
 * All of it is deterministic and instant (server thread): no language model has to answer for the meeting to happen;
 * the models get the bond in their prompt ({@link #forModel}) for everything else they say.
 */
public final class Companero {
    public enum Etapa {
        DESCONOCIDO, CONOCIDO, AMIGO, MEJOR_AMIGO, COMPADRE, PAREJA
    }

    /** Flags of {@link Memories#getLazos()}. */
    public static final int COMPADRE = 1;
    public static final int PAREJA = 2;
    public static final int AMIGO_HEARTS = 25;
    public static final int MEJOR_HEARTS = 60;
    public static final int PAREJA_HEARTS = 75;
    /** Ticks at the player's side for a heart: two minutes together. */
    private static final int TOGETHER_HEART = 2400;
    /** Within this many blocks it is at the player's side. */
    private static final double TOGETHER = 16.0;

    // the meeting, in ticks since the connection
    private static final int TURN = 10;
    private static final int SCAN = 52;
    private static final int GREET = 58;
    private static final int ASK_AGAIN = 760;
    private static final int GIVE_UP = 1500;
    /** After the name (or a greeting of someone it knew) this long, it sets off at their side. */
    private static final int AFTER = 50;
    private static final String CROUCH = "conociendo";

    // hunger at the player's side
    /** Food at or under this with nothing in the bag: it stops and sees to it. */
    private static final int HUNGRY = 12;
    private static final int RUMMAGE_FROM = 20;
    private static final int RUMMAGE_TO = 100;
    /** How long it waits for something to eat from the player before it goes and finds it itself. */
    private static final int WAIT_FOOD = 900;
    private static final int HUNGER_PAUSE = 3600;
    private static final String RUMMAGE = "mochila";

    private static final class Encuentro {
        final UUID player;
        boolean greeted;
        boolean knew;
        long told = -1;
        int asked;
        long nextLook;
        long peekUntil;
        long nextPeek;
        @Nullable
        Vec3d look;

        Encuentro(UUID player) {
            this.player = player;
        }
    }

    private static final class Hambre {
        final long since;
        boolean asked;
        boolean leave;
        boolean decided;

        Hambre(long since) {
            this.since = since;
        }
    }

    private static final Map<UUID, Encuentro> ENCUENTROS = new ConcurrentHashMap<>();
    private static final Map<UUID, Hambre> HAMBRE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> HAMBRE_NEXT = new ConcurrentHashMap<>();
    /** Ticks together not yet written into the memories (they are saved a heart at a time). */
    private static final Map<UUID, Long> JUNTOS = new ConcurrentHashMap<>();

    private static final Pattern LLAMO = Pattern.compile("(?:me llamo|mi nombre es|my name is|my name's)\\s+([\\p{L}][\\p{L}\\p{N}_'-]{1,15})");
    private static final Pattern SOY = Pattern.compile("(?:^|[\\s,.!¡¿])(?:soy|ll[aá]mame|dime|i'm|i am|im|call me)\\s+([\\p{L}][\\p{L}\\p{N}_'-]{1,15})");
    private static final Pattern SOLO = Pattern.compile("^[\\s¡!¿.,]*(?:(?:hola|holi|wena|buenas|hey|hi|hello|ola)[\\s,!.]+)?([\\p{L}][\\p{L}\\p{N}_'-]{1,15})[\\s.!,]*$");
    private static final Pattern HOMBRE = Pattern.compile("\\b(?:soy|i'?m|i am)\\s+(?:un\\s+|a\\s+)?(?:hombre|chico|nino|varon|caballero|cabro|man|boy|guy|male|dude)\\b");
    private static final Pattern MUJER = Pattern.compile("\\b(?:soy|i'?m|i am)\\s+(?:una\\s+|a\\s+)?(?:mujer|chica|nina|dama|senorita|cabra|woman|girl|female|lady)\\b");
    private static final Pattern NEUTRO = Pattern.compile("no binari[oae]|non-?binary|\\benby\\b|\\belle\\b|they/them");
    private static final Pattern PIDE_PAREJA = Pattern.compile("(?:quieres|queris|quieri|te gustaria|aceptas|puedes)\\s+(?:ser\\s+)?(?:mi|mí)\\s+(?:pareja|novi[oa]|polol[oa]|espos[oa]|amor|enamorad[oa])"
            + "|pololeemos|seamos (?:pareja|novios|pololos)|(?:be|become) my (?:girlfriend|boyfriend|partner|wife|husband)|go out with me");
    private static final Pattern PIDE_COMPADRE = Pattern.compile("(?:quieres|queris|quieri|aceptas|puedes)\\s+(?:ser\\s+)?(?:mi\\s+)?(?:compadre|comadre)|seamos (?:compadres|comadres)|be my (?:buddy|compadre)");
    private static final Pattern PIDE_AMIGO = Pattern.compile("(?:quieres|queris|quieri|aceptas|puedes)\\s+(?:ser\\s+)?(?:mi\\s+)?(?:amig[oa]|amigue|companer[oa])|seamos amig[oa]s|be my friend|be friends");
    private static final Pattern QUE_SOMOS = Pattern.compile("que somos|que soy (?:yo )?para ti|somos amig[oa]s|me quieres|me tienes cari|what are we|are we friends|do you like me");
    private static final Pattern MI_NOMBRE = Pattern.compile("como me llamo|sabes (?:como me llamo|mi nombre)|te acuerdas de mi(?: nombre)?|recuerdas mi nombre|what'?s my name|do you (?:know|remember) my name");
    private static final Pattern QUE_PASA = Pattern.compile("que (?:te )?(?:pasa|paso|sucede|ocurre)|estas bien|por que (?:te )?(?:paras|paraste|detienes|detuviste|esperas)|what'?s (?:wrong|up|going on)|are you (?:ok|okay|alright)|what happened");
    private static final Pattern NO_TENGO = Pattern.compile("^(?:no|nop|nope|nada)\\b|no tengo|no traigo|no me queda|no hay|i don'?t have|no food|sorry,? no");
    /** Words after "soy", "dime", "I'm"... that are never a name. */
    private static final Set<String> NO_NOMBRE = Set.of("un", "una", "el", "la", "los", "las", "tu", "su", "mi", "de", "del", "muy", "bien", "mal",
            "nuevo", "nueva", "hombre", "mujer", "chico", "chica", "nino", "nina", "varon", "yo", "no", "si", "que", "como", "donde", "cuando", "tan",
            "mas", "amigo", "amiga", "compadre", "comadre", "pareja", "novio", "novia", "pololo", "polola", "gay", "lesbiana", "trans", "bi", "hetero",
            "feliz", "alguien", "quien", "tuyo", "tuya", "ella", "el", "a", "an", "the", "not", "fine", "good", "ok", "okay", "here", "back", "sorry",
            "going", "hungry", "tired", "your", "so", "just", "male", "female", "man", "woman", "boy", "girl", "guy", "humano", "jugador", "player",
            "aqui", "aca", "listo", "lista", "chileno", "chilena", "hola", "holi", "wena", "buenas", "hey", "hi", "hello", "ola", "gracias", "thanks",
            "vale", "dale", "ya", "sigueme", "ven", "para", "espera", "vamos", "oye", "mira", "algo", "eso", "esto", "nada", "todo", "otra", "otro",
            "ahora", "lo", "le", "te", "me", "se", "es", "porfa", "por", "cabro", "cabra", "dude", "lady", "enby", "elle", "minero", "minera", "hambre");

    private Companero() {
    }

    // ------------------------------------------------------------------ the bond

    private static Memories mem(VillagerEntityMCA villager, PlayerEntity player) {
        return villager.getVillagerBrain().getMemoriesForPlayer(player);
    }

    /** The name it calls the player: the one they told it, else the one the game shows. */
    public static String nombre(VillagerEntityMCA villager, PlayerEntity player) {
        String told = mem(villager, player).getNombre();
        return told.isBlank() ? player.getName().getString() : told;
    }

    /** How the player identifies: their own words first, else their game setting ('m', 'f' or 'n'). */
    public static char genero(VillagerEntityMCA villager, PlayerEntity player) {
        String told = mem(villager, player).getGenero();
        if (!told.isEmpty()) {
            return told.charAt(0);
        }
        if (player instanceof ServerPlayerEntity sp) {
            Gender g = PlayerSaveData.get(sp).getGender();
            return g == Gender.FEMALE ? 'f' : g == Gender.MALE ? 'm' : 'n';
        }
        return 'n';
    }

    public static Etapa etapa(VillagerEntityMCA villager, PlayerEntity player) {
        return etapa(mem(villager, player));
    }

    private static Etapa etapa(Memories m) {
        if ((m.getLazos() & PAREJA) != 0) {
            return Etapa.PAREJA;
        }
        if ((m.getLazos() & COMPADRE) != 0) {
            return Etapa.COMPADRE;
        }
        if (m.getHearts() >= MEJOR_HEARTS) {
            return Etapa.MEJOR_AMIGO;
        }
        if (m.getHearts() >= AMIGO_HEARTS) {
            return Etapa.AMIGO;
        }
        return m.getNombre().isBlank() ? Etapa.DESCONOCIDO : Etapa.CONOCIDO;
    }

    /** "amigo", "mejor amiga", "comadre"...: the word for the stage, for how the player identifies. */
    public static String palabra(Etapa etapa, char g, boolean es) {
        if (!es) {
            return switch (etapa) {
                case DESCONOCIDO -> "stranger";
                case CONOCIDO -> "acquaintance";
                case AMIGO -> "friend";
                case MEJOR_AMIGO -> "best friend";
                case COMPADRE -> g == 'f' ? "comadre" : "compadre";
                case PAREJA -> g == 'f' ? "girlfriend" : g == 'm' ? "boyfriend" : "partner";
            };
        }
        String o = g == 'f' ? "a" : g == 'm' ? "o" : "e";
        return switch (etapa) {
            case DESCONOCIDO -> "desconocid" + o;
            case CONOCIDO -> "conocid" + o;
            case AMIGO -> "amig" + o;
            case MEJOR_AMIGO -> "mejor amig" + o;
            case COMPADRE -> g == 'f' ? "comadre" : "compadre";
            case PAREJA -> g == 'f' ? "polola" : g == 'm' ? "pololo" : "pareja";
        };
    }

    private static void heart(VillagerEntityMCA villager, PlayerEntity player, int n) {
        Memories m = mem(villager, player);
        m.setHearts(MathHelper.clamp(m.getHearts() + n, -100, 100));
    }

    /**
     * Every second at the player's side (connected): time together makes hearts, and a new stage of the bond is said
     * once, when it comes, the way a friend would let it slip.
     */
    public static void juntos(ServerWorld world, VillagerEntityMCA villager, PlayerEntity player) {
        if (villager.squaredDistanceTo(player) > TOGETHER * TOGETHER) {
            return;
        }
        UUID key = clave(villager, player);
        long t = JUNTOS.merge(key, 20L, Long::sum);
        if (t >= TOGETHER_HEART) {
            JUNTOS.remove(key);
            Memories m = mem(villager, player);
            m.addJuntos(t);
            m.setHearts(Math.min(100, m.getHearts() + 1));
            anunciar(world, villager, player);
        }
    }

    private static UUID clave(VillagerEntityMCA villager, PlayerEntity player) {
        return new UUID(villager.getUuid().getMostSignificantBits() ^ player.getUuid().getLeastSignificantBits(),
                villager.getUuid().getLeastSignificantBits() ^ player.getUuid().getMostSignificantBits());
    }

    /** A stage it reached and has not said yet: it says it now. */
    private static void anunciar(ServerWorld world, VillagerEntityMCA villager, PlayerEntity player) {
        Memories m = mem(villager, player);
        Etapa now = etapa(m);
        if (now.ordinal() <= m.getEtapa() || now.ordinal() < Etapa.AMIGO.ordinal()) {
            return;
        }
        m.setEtapa(now.ordinal());
        boolean es = Voluntad.of(villager).spanish();
        String n = nombre(villager, player);
        char g = genero(villager, player);
        String line = switch (now) {
            case AMIGO -> es ? "Oye, " + n + "… me caes muy bien. Creo que ya somos " + (g == 'f' ? "amigas" : "amigos") + "."
                    : "Hey, " + n + "… I really like you. I think we're friends now.";
            case MEJOR_AMIGO -> es ? n + ", ¿sabes? Eres mi " + palabra(Etapa.MEJOR_AMIGO, g, true) + "." : n + ", you know what? You're my best friend.";
            default -> null;
        };
        if (line != null) {
            decir(villager, player, line, "le digo que ahora es mi " + palabra(now, g, true));
        }
    }

    // ------------------------------------------------------------------ the meeting

    /** The player just connected it from its menu: the meeting starts (its will is set to meet them). */
    public static void empezar(VillagerEntityMCA villager, PlayerEntity player) {
        ENCUENTROS.put(villager.getUuid(), new Encuentro(player.getUuid()));
        MCA.LOGGER.info("[Tropeles] {} conoce a {}: lo mira de pies a cabeza", villager.getName().getString(), player.getName().getString());
    }

    /** The meeting is over or cut short: it stands up straight and lets go of its looks. */
    public static void terminar(VillagerEntityMCA villager) {
        ENCUENTROS.remove(villager.getUuid());
        Postura.agachar(villager, CROUCH, false);
    }

    /**
     * One tick of the meeting, {@code t} ticks after the connection: true when it is over and it sets off at the
     * player's side.
     */
    public static boolean conocer(ServerWorld world, VillagerEntityMCA villager, PlayerEntity player, long t) {
        Encuentro e = ENCUENTROS.computeIfAbsent(villager.getUuid(), k -> new Encuentro(player.getUuid()));
        long now = world.getTime();
        Motor.standStill(villager);
        villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        if ((villager.age & 1) == 0) {
            Retina.look(world, villager); // it takes in what it looks at: the player, the place
        }
        if (t < TURN) {
            face(villager, player.getEyePos());
            return false;
        }
        if (t < SCAN) {
            // from the boots up to the eyes, slowly: sizing them up
            double f = (t - TURN) / (double) (SCAN - TURN);
            Vec3d at = player.getPos().add(0.0, player.getStandingEyeHeight() * f * f * (3 - 2 * f), 0.0);
            face(villager, at);
            return false;
        }
        boolean es = Voluntad.of(villager).spanish();
        String villagerName = villager.getName().getString();
        if (!e.greeted && t >= GREET) {
            e.greeted = true;
            Memories m = mem(villager, player);
            e.knew = !m.getNombre().isBlank();
            face(villager, player.getEyePos());
            String line;
            if (e.knew) {
                line = saludoDeNuevo(villager, player, es);
            } else {
                int pick = (int) Math.floorMod(villager.getUuid().getLeastSignificantBits() + now, 3L);
                line = es ? switch (pick) {
                    case 0 -> "¡Hola! ¿Cómo te llamas?";
                    case 1 -> "¡Hola! Yo soy " + villagerName + ". ¿Y tú, cómo te llamas?";
                    default -> "Hola… no te había visto antes. ¿Cómo te llamas?";
                } : switch (pick) {
                    case 0 -> "Hi! What's your name?";
                    case 1 -> "Hi! I'm " + villagerName + ". And you, what's your name?";
                    default -> "Hello… I haven't seen you before. What's your name?";
                };
            }
            decir(villager, player, line, e.knew ? "lo saludo, ya lo conocía" : "le pregunto su nombre");
            e.nextLook = now + 30;
            e.nextPeek = now + 60;
            return false;
        }
        if (!e.greeted) {
            face(villager, player.getEyePos());
            return false;
        }
        if (e.knew && t >= GREET + AFTER) {
            terminar(villager);
            return true;
        }
        if (e.told >= 0 && now - e.told >= AFTER) {
            decir(villager, player, es ? "Bueno, ¿qué hacemos? Yo voy contigo." : "So, what do we do? I'm coming with you.", "me voy con él");
            terminar(villager);
            return true;
        }
        if (e.told < 0 && e.asked == 0 && t >= ASK_AGAIN) {
            e.asked = 1;
            face(villager, player.getEyePos());
            decir(villager, player, es ? "¿Y? ¿No me vas a decir cómo te llamas?" : "So? Aren't you going to tell me your name?", "le pregunto otra vez su nombre");
        }
        if (e.told < 0 && t >= GIVE_UP) {
            String n = player.getName().getString();
            decir(villager, player, es ? "Bueno, te diré " + n + " entonces. Vamos, te acompaño." : "Well, I'll call you " + n + " then. Come on, I'll go with you.",
                    "no me dijo su nombre; le digo " + n);
            terminar(villager);
            return true;
        }
        lookAbout(world, villager, player, e, now);
        return false;
    }

    private static String saludoDeNuevo(VillagerEntityMCA villager, PlayerEntity player, boolean es) {
        String n = nombre(villager, player);
        char g = genero(villager, player);
        Etapa st = etapa(villager, player);
        if (!es) {
            return switch (st) {
                case PAREJA -> n + ", my love! I was waiting for you.";
                case COMPADRE -> "My dear " + n + "! What are we up to?";
                case MEJOR_AMIGO -> n + "! My best friend. What are we doing today?";
                case AMIGO -> "Hey, " + n + "! Good to see you, friend.";
                default -> "Hi again, " + n + "!";
            };
        }
        return switch (st) {
            case PAREJA -> "¡" + n + ", mi amor! Te estaba esperando.";
            case COMPADRE -> "¡" + (g == 'f' ? "Comadre " : "Compadre ") + n + "! ¿En qué andamos?";
            case MEJOR_AMIGO -> "¡" + n + ", mi " + palabra(Etapa.MEJOR_AMIGO, g, true) + "! ¿Qué hacemos hoy?";
            case AMIGO -> "¡" + n + "! Qué bueno verte, " + palabra(Etapa.AMIGO, g, true) + ".";
            default -> "¡Hola de nuevo, " + n + "!";
        };
    }

    /**
     * Waiting for the answer: only the eyes and the knees move. It looks about at what is there (something it saw move,
     * the ground, the trees, the sky line), comes back to the player now and then, and ducks down for a moment like a
     * player tapping shift to peer at something.
     */
    private static void lookAbout(ServerWorld world, VillagerEntityMCA villager, PlayerEntity player, Encuentro e, long now) {
        if (now >= e.nextLook || e.look == null) {
            int pick = villager.getRandom().nextInt(5);
            Vec3d eye = villager.getEyePos();
            if (pick <= 1) {
                e.look = player.getEyePos();
            } else if (pick == 2) {
                List<VisualMemory.SeenEntity> seen = Retina.memory(villager).entities(now, 200);
                VisualMemory.SeenEntity some = seen.isEmpty() ? null : seen.get(villager.getRandom().nextInt(seen.size()));
                e.look = some != null && some.pos != null && some.pos.squaredDistanceTo(eye) > 4.0 ? some.pos.add(0.0, 0.8, 0.0) : null;
            }
            if (e.look == null || pick >= 3) {
                double yaw = villager.getRandom().nextDouble() * Math.PI * 2.0;
                double pitch = -0.35 + villager.getRandom().nextDouble() * 0.75; // a little above the horizon to the ground nearby
                double d = 6.0 + villager.getRandom().nextDouble() * 8.0;
                e.look = eye.add(Math.cos(yaw) * d, -Math.sin(pitch) * d * 0.5, Math.sin(yaw) * d);
            }
            e.nextLook = now + 28 + villager.getRandom().nextInt(30);
        }
        face(villager, e.look);
        if (now >= e.nextPeek && now >= e.peekUntil) {
            e.peekUntil = now + 12 + villager.getRandom().nextInt(14);
            e.nextPeek = e.peekUntil + 70 + villager.getRandom().nextInt(80);
            Postura.agachar(villager, CROUCH, true);
        } else if (now >= e.peekUntil && Postura.agachado(villager, CROUCH)) {
            Postura.agachar(villager, CROUCH, false);
        }
    }

    private static void face(VillagerEntityMCA villager, Vec3d at) {
        villager.getLookControl().lookAt(at.x, at.y, at.z, 12.0F, 12.0F);
        Hands.aim(villager, at);
        double dx = at.x - villager.getX();
        double dz = at.z - villager.getZ();
        if (dx * dx + dz * dz > 0.09) {
            float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
            villager.setBodyYaw(villager.getBodyYaw() + MathHelper.clamp(MathHelper.wrapDegrees(yaw - villager.getBodyYaw()), -9.0F, 9.0F));
        }
    }

    // ------------------------------------------------------------------ hunger at the player's side

    /** What hunger asks of the will this tick. */
    public enum Pide {
        /** Nothing: it follows as usual. */
        NADA,
        /** It is standing still seeing to it (the legs wait). */
        QUIETO,
        /** It goes and finds food itself, and comes back after. */
        BUSCAR
    }

    /**
     * Hunger while it goes with the player: the first time it runs out of food (nothing in the bag and {@code food} at
     * {@value #HUNGRY} or under), it says so and stops, goes through its bag, makes bread when it has the wheat and a
     * table, else asks the player for something to eat and picks up what they throw it; with nothing given it goes
     * and finds food itself.
     */
    public static Pide hambre(ServerWorld world, VillagerEntityMCA villager, PlayerEntity player, ProspectingTask task) {
        UUID id = villager.getUuid();
        long now = world.getTime();
        int food = task.food();
        boolean bag = ProspectingTask.comidaEnMochila(villager) > 0;
        Hambre h = HAMBRE.get(id);
        if (h == null) {
            boolean starving = food <= 6;
            if (food > HUNGRY || bag || CombatMind.busy(villager) || villager.isSleeping() || now < HAMBRE_NEXT.getOrDefault(id, 0L) && !starving) {
                return Pide.NADA;
            }
            h = new Hambre(now);
            HAMBRE.put(id, h);
            boolean es = Voluntad.of(villager).spanish();
            decir(villager, player, es ? "Uf… espera un poco, me está dando hambre." : "Ugh… hold on a second, I'm getting hungry.",
                    "me dio hambre (comida " + food + " de 20) y no tengo nada en la mochila");
            Motor.standStill(villager);
            return Pide.QUIETO;
        }
        boolean es = Voluntad.of(villager).spanish();
        long t = now - h.since;
        if (bag || food > HUNGRY + 4) {
            // it ate (the player gave it something, or it found it): thanks, and back to the way
            HAMBRE.remove(id);
            HAMBRE_NEXT.put(id, now + HUNGER_PAUSE);
            Postura.agachar(villager, RUMMAGE, false);
            if (h.asked) {
                heart(villager, player, 4);
                decir(villager, player, es ? "¡Gracias, " + nombre(villager, player) + "! Me salvaste, te debo una."
                        : "Thanks, " + nombre(villager, player) + "! You saved me, I owe you one.", "me dio de comer cuando no tenía nada");
                anunciar(world, villager, player);
            }
            return Pide.NADA;
        }
        if (h.leave) {
            HAMBRE.remove(id);
            HAMBRE_NEXT.put(id, now + HUNGER_PAUSE);
            decir(villager, player, es ? "Bueno, voy a buscar algo de comer aquí cerca y vuelvo contigo." : "Alright, I'll go find something to eat nearby and come back to you.",
                    "nadie tenía comida: la busco yo y vuelvo");
            return Pide.BUSCAR;
        }
        if (t < RUMMAGE_FROM) {
            Motor.standStill(villager);
            face(villager, player.getEyePos());
            return Pide.QUIETO;
        }
        if (t < RUMMAGE_TO) {
            // the bag: head down at its hands, crouched, the hand going through what it carries
            Motor.standStill(villager);
            villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
            if (t == RUMMAGE_FROM) {
                MCA.LOGGER.info("[Tropeles] {} revisa su mochila buscando algo de comer", villager.getName().getString());
            }
            float yaw = villager.getBodyYaw() * (float) (Math.PI / 180.0);
            Vec3d hands = villager.getPos().add(-MathHelper.sin(yaw) * 0.45, 0.55, MathHelper.cos(yaw) * 0.45);
            villager.getLookControl().lookAt(hands.x, hands.y, hands.z, 20.0F, 40.0F);
            Hands.aim(villager, hands);
            Postura.agachar(villager, RUMMAGE, true);
            if ((t % 16) == 0) {
                villager.swingHand((t % 32) == 0 ? Hand.MAIN_HAND : Hand.OFF_HAND);
            }
            return Pide.QUIETO;
        }
        if (!h.decided) {
            h.decided = true;
            Postura.agachar(villager, RUMMAGE, false);
            face(villager, player.getEyePos());
            if (task.hacerPan(world)) {
                task.comerAhora(world);
                HAMBRE.remove(id);
                HAMBRE_NEXT.put(id, now + HUNGER_PAUSE);
                decir(villager, player, es ? "Tenía trigo: me hice un pan. ¡Listo, ya podemos seguir!" : "I had wheat: I made myself some bread. Done, let's keep going!",
                        "revisé mi mochila: tenía trigo y me hice pan");
                return Pide.NADA;
            }
            h.asked = true;
            String n = nombre(villager, player);
            decir(villager, player, es ? "Revisé mi mochila y no tengo nada para comer… ¿Tienes algo de comer, " + n + "?"
                    : "I went through my bag and I have nothing to eat… Do you have something to eat, " + n + "?", "revisé mi mochila: nada para comer; le pido");
            return Pide.NADA;
        }
        // waiting for a meal: it goes along, and what is thrown to it, it picks up
        if ((t % 10) == 0) {
            Hands.collect(world, villager, Mining.pies(villager), 3.0, ProspectingTask::comestible);
        }
        if (t > RUMMAGE_TO + WAIT_FOOD && food <= 8) {
            h.leave = true;
        }
        return Pide.NADA;
    }

    /** Hungry right now with nothing to eat (an episode under way): what "¿qué pasa?" is about. */
    private static boolean hambriento(VillagerEntityMCA villager) {
        return HAMBRE.containsKey(villager.getUuid());
    }

    // ------------------------------------------------------------------ what the player says

    /**
     * A message from {@code player} to this connected villager (server thread, the tick it arrives): its name and how
     * they identify, a question about the bond or their name, an offer to be friends, compadres or a couple, and "¿qué
     * pasa?" while it is hungry are answered at once, here. True when it answered (no model is asked then).
     */
    public static boolean oir(ServerPlayerEntity player, VillagerEntityMCA villager, String msg) {
        if (!Voluntad.conectado(villager) || msg == null || msg.isBlank()) {
            return false;
        }
        String villagerName = villager.getName().getString();
        boolean es = MenteLocal.speaksSpanish(msg, villagerName, player.getUuid());
        String low = llano(msg);
        String own = llano(villagerName);
        Memories m = mem(villager, player);
        Encuentro e = ENCUENTROS.get(villager.getUuid());
        boolean waitingName = e != null && e.greeted && e.told < 0 && !e.knew;

        String told = null;
        Matcher lm = LLAMO.matcher(low);
        if (lm.find() && !NO_NOMBRE.contains(lm.group(1)) && !lm.group(1).equals(own)) {
            told = original(msg, lm.group(1));
        } else {
            Matcher sm = SOY.matcher(low);
            while (sm.find()) {
                String word = sm.group(1);
                String orig = original(msg, word);
                if (!NO_NOMBRE.contains(word) && !word.equals(own) && (waitingName || Character.isUpperCase(orig.charAt(0)))) {
                    told = orig;
                    break;
                }
            }
            if (told == null && waitingName) {
                Matcher one = SOLO.matcher(low);
                if (one.matches() && !NO_NOMBRE.contains(one.group(1)) && !one.group(1).equals(own)) {
                    told = original(msg, one.group(1));
                }
            }
        }
        char gender = HOMBRE.matcher(low).find() ? 'm' : MUJER.matcher(low).find() ? 'f' : NEUTRO.matcher(low).find() ? 'n' : 0;

        StringBuilder answer = new StringBuilder();
        String why = null;
        if (told != null) {
            told = Character.toUpperCase(told.charAt(0)) + told.substring(1);
            boolean first = m.getNombre().isBlank();
            boolean same = told.equalsIgnoreCase(m.getNombre());
            m.setNombre(told);
            if (first) {
                heart(villager, player, 5);
            }
            if (e != null) {
                // told before it even asked: no question then, it goes straight to "mucho gusto"
                e.told = villager.getWorld().getTime();
                e.greeted = true;
            }
            answer.append(es ? (same ? "Sí, ya lo sé, " + told + ". " : first ? "¡Mucho gusto, " + told + "! Yo soy " + villagerName + ". " : "Ah, " + told + ". Te diré así entonces. ")
                    : (same ? "Yes, I know, " + told + ". " : first ? "Nice to meet you, " + told + "! I'm " + villagerName + ". " : "Oh, " + told + ". I'll call you that then. "));
            why = "se presentó: se llama " + told;
        }
        if (gender != 0 && !String.valueOf(gender).equals(m.getGenero())) {
            m.setGenero(String.valueOf(gender));
            if (answer.isEmpty()) {
                answer.append(es ? "Entendido, " + nombre(villager, player) + ". " : "Got it, " + nombre(villager, player) + ". ");
            }
            why = (why == null ? "" : why + "; ") + "me dijo cómo se identifica (" + gender + ")";
        }
        if (answer.isEmpty()) {
            String reply = bondQuestion(player, villager, low, es);
            if (reply == null) {
                return false;
            }
            answer.append(reply);
            why = "le respondí sobre nosotros";
        }
        String line = answer.toString().trim();
        OpenAIChatAI.remember(villager.getUuid(), player.getUuid(), msg, line);
        Recuerdos.anotar(villager, "charla", player.getName().getString() + " me dijo «" + msg + "»; le dije «" + line + "»", Mining.pies(villager));
        Voluntad.oyo(villager, player, es);
        ChatAI.deliver(player, villager, line);
        MCA.LOGGER.info("[Tropeles] {} a {}: «{}» ({})", villagerName, player.getName().getString(), line, why);
        return true;
    }

    /** The answers about the two of them, about their name, or about its hunger; null when the message is none of those. */
    @Nullable
    private static String bondQuestion(ServerPlayerEntity player, VillagerEntityMCA villager, String low, boolean es) {
        Memories m = mem(villager, player);
        String n = nombre(villager, player);
        char g = genero(villager, player);
        Etapa st = etapa(m);
        Hambre h = HAMBRE.get(villager.getUuid());
        if (h != null && h.asked && NO_TENGO.matcher(low).find()) {
            h.leave = true;
            return es ? "No te preocupes, " + n + ". Ya me las arreglo." : "Don't worry, " + n + ". I'll manage.";
        }
        if (QUE_PASA.matcher(low).find() && hambriento(villager)) {
            return h != null && h.asked ? (es ? "Me hacía falta comida y no tengo nada… ¿tienes algo de comer?" : "I needed food and I have nothing… do you have something to eat?")
                    : (es ? "Me hacía falta comida. Déjame revisar mi mochila, a ver si puedo hacerme algo." : "I needed food. Let me check my bag, maybe I can make something.");
        }
        if (MI_NOMBRE.matcher(low).find()) {
            return m.getNombre().isBlank() ? (es ? "No me lo has dicho… ¿cómo te llamas?" : "You haven't told me… what's your name?")
                    : (es ? "Claro que sí: te llamas " + n + "." : "Of course: your name is " + n + ".");
        }
        if (PIDE_PAREJA.matcher(low).find()) {
            boolean attracted = villager.canBeAttractedTo(PlayerSaveData.get(player));
            boolean taken = villager.getRelationships().getPartnerUUID().filter(p -> !p.equals(player.getUuid())).isPresent();
            if (st == Etapa.PAREJA) {
                return es ? "Pero si ya somos pareja, tontito." : "But we're already together, silly.";
            }
            if (taken) {
                return es ? "Ay, " + n + "… ya tengo a alguien. Pero te quiero mucho igual." : "Oh, " + n + "… I already have someone. But I care about you a lot.";
            }
            if (!attracted) {
                return es ? "Te quiero mucho, " + n + ", pero como " + palabra(st.ordinal() >= Etapa.AMIGO.ordinal() ? st : Etapa.AMIGO, g, true) + "." :
                        "I care about you a lot, " + n + ", but as a friend.";
            }
            if (m.getHearts() < PAREJA_HEARTS) {
                heart(villager, player, 1);
                return es ? "Me caes muy bien, " + n + "… pero vamos con calma. Conozcámonos más." : "I really like you, " + n + "… but let's take it slow. Let's get to know each other.";
            }
            m.setLazos(m.getLazos() | PAREJA);
            m.setEtapa(Etapa.PAREJA.ordinal());
            heart(villager, player, 5);
            return es ? "¿En serio?… Sí. Sí quiero, " + n + "." : "Really?… Yes. Yes, I do, " + n + ".";
        }
        if (PIDE_COMPADRE.matcher(low).find()) {
            if (st == Etapa.COMPADRE || st == Etapa.PAREJA) {
                return es ? "¡Si ya lo somos!" : "We already are!";
            }
            if (m.getHearts() < MEJOR_HEARTS) {
                return es ? "Compadre se gana con el tiempo, " + n + ". Vamos bien, eso sí." : "That takes time, " + n + ". We're doing well, though.";
            }
            m.setLazos(m.getLazos() | COMPADRE);
            m.setEtapa(Etapa.COMPADRE.ordinal());
            heart(villager, player, 3);
            return es ? "¡Claro que sí, " + (g == 'f' ? "comadre" : "compadre") + "! Para lo que necesites." : "Of course, buddy! Whatever you need.";
        }
        if (PIDE_AMIGO.matcher(low).find()) {
            if (st.ordinal() >= Etapa.AMIGO.ordinal()) {
                return es ? "¡Pero si ya somos " + (g == 'f' ? "amigas" : "amigos") + ", " + n + "!" : "But we're already friends, " + n + "!";
            }
            if (m.getHearts() >= 10 || !m.getNombre().isBlank()) {
                m.setHearts(Math.max(m.getHearts(), AMIGO_HEARTS));
                m.setEtapa(Etapa.AMIGO.ordinal());
                return es ? "¡Claro que sí! Amigos, entonces." : "Of course! Friends, then.";
            }
            heart(villager, player, 2);
            return es ? "Recién nos conocemos… pero me caes bien. ¿Cómo te llamas?" : "We've only just met… but I like you. What's your name?";
        }
        if (QUE_SOMOS.matcher(low).find()) {
            return es ? switch (st) {
                case PAREJA -> "Somos pareja, " + n + ". Eres mi " + palabra(st, g, true) + ".";
                case COMPADRE -> "Somos " + (g == 'f' ? "comadres" : "compadres") + ", " + n + ". Para lo que sea.";
                case MEJOR_AMIGO -> "Eres mi " + palabra(st, g, true) + ", " + n + ".";
                case AMIGO -> "Somos " + (g == 'f' ? "amigas" : "amigos") + ", " + n + ". Y vamos bien.";
                case CONOCIDO -> "Nos estamos conociendo, " + n + ". Me caes bien.";
                case DESCONOCIDO -> "Todavía no sé ni cómo te llamas.";
            } : switch (st) {
                case PAREJA -> "We're together, " + n + ".";
                case COMPADRE, MEJOR_AMIGO -> "You're my best friend, " + n + ".";
                case AMIGO -> "We're friends, " + n + ".";
                case CONOCIDO -> "We're getting to know each other, " + n + ".";
                case DESCONOCIDO -> "I don't even know your name yet.";
            };
        }
        return null;
    }

    /** For a language model: who this player is to it, by name, how they identify and the stage of the bond. */
    public static String forModel(VillagerEntityMCA villager, PlayerEntity player) {
        Memories m = mem(villager, player);
        char g = genero(villager, player);
        Etapa st = etapa(m);
        String who = g == 'f' ? "a woman" : g == 'm' ? "a man" : "non-binary";
        String told = m.getNombre();
        String name = told.isBlank() ? "You do not know this player's name yet (the game shows " + player.getName().getString()
                + "); if it fits, ask it. " : "This player told you their name: " + told + "; call them " + told + ". ";
        String bond = switch (st) {
            case DESCONOCIDO, CONOCIDO -> "You are just getting to know each other";
            case AMIGO -> "They are your friend";
            case MEJOR_AMIGO -> "They are your best friend";
            case COMPADRE -> "They are your compadre/comadre: a brother or sister you chose";
            case PAREJA -> "They are your partner, the one you love: be warm and affectionate with them";
        };
        return name + "They are " + who + " (use the right gendered words for them). " + bond + " (hearts " + m.getHearts()
                + " of 100). Talk to them with the familiarity of that bond, like a real companion. ";
    }

    // ------------------------------------------------------------------ speech

    private static void decir(VillagerEntityMCA villager, PlayerEntity player, String line, String why) {
        Recuerdos.anotar(villager, "charla", "le dije a " + player.getName().getString() + " «" + line + "» (" + why + ")", Mining.pies(villager));
        if (player instanceof ServerPlayerEntity sp) {
            OpenAIChatAI.remember(villager.getUuid(), sp.getUuid(), "…", line);
            ChatAI.deliver(sp, villager, line);
        }
        MCA.LOGGER.info("[Tropeles] {} dice a {}: «{}» ({})", villager.getName().getString(), player.getName().getString(), line, why);
    }

    /** Lower case without accents, for the patterns. */
    private static String llano(String text) {
        String n = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "").replace('ñ', 'n');
    }

    /** The word as the player wrote it (its capital letters, its accents), found again in the original message. */
    private static String original(String msg, String plain) {
        for (String word : msg.split("[^\\p{L}\\p{N}_'-]+")) {
            if (llano(word).equals(plain)) {
                return word;
            }
        }
        return plain;
    }
}
