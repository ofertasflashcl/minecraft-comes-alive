package net.mca.entity.ai.mente;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mca.Config;
import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.brain.tasks.chore.ProspectingTask;
import net.mca.entity.ai.chatAI.Cerebros;
import net.mca.entity.ai.chatAI.MenteLocal;
import net.mca.entity.ai.chatAI.TriggerCommandInfo;
import net.mca.entity.ai.chatAI.TriggerCommandInfos;
import net.mca.entity.ai.sense.Ahora;
import net.mca.entity.ai.sense.Retina;
import net.mca.util.WorldUtils;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The inner voice of a connected villager: now and then it stops to think about its own life, the way a person
 * does between two jobs. It looks at what it knows (who it is, its mode and phase, what it is doing and sees, its
 * bag, food and health, its home and mine, the last things that happened to it and the lessons it learned before)
 * and thinks one thought: what it makes of it, maybe a lesson worth keeping, what it wants to do next and why, and
 * rarely something to say to the player nearby.
 * <p>
 * Cheap on purpose: at most one free language model call ({@link Cerebros}, never a paid one) per villager every
 * {@value #PENSAR_MS} ms, only while a player is within {@value #CERCA} blocks or right after something happened to
 * it (stuck, a trip, a fight, a find, hurt, talked to), one call at a time on the whole server, off the server
 * thread, and none while the chat is waiting on a mind or the minds are down. Then its own offline mind thinks a
 * plain thought instead, with no call.
 * <p>
 * Thoughts and lessons are memories ({@link Recuerdos} "pienso" and "leccion"); the lessons (the last
 * {@value #MAX_LECCIONES}) are also kept with the world, so they survive the diary and a restart. What it wants is
 * carried out through the same orders the chat uses ({@link TriggerCommandInfos}), but only in its own life
 * ({@link Voluntad.Modo#LIBRE}), never within {@value #CAMBIO_TICKS} ticks of its last change of plans, never while
 * paused, and never within {@value #ORDEN_TICKS} ticks of an order a player gave it. The rest of the time it simply
 * keeps going: the task already plays its life well, the thought only steers it now and then.
 */
public final class Conciencia {
    /** One routine thought this often at most (wall clock), while a player is around. */
    public static final long PENSAR_MS = 90_000;
    /** Something happened: a thought this soon after the last one at the earliest. */
    public static final long EVENTO_MS = 30_000;
    /** Between two calls of any villager on the server. */
    private static final long ENTRE_MS = 12_000;
    /** The call's budget in all. */
    private static final long PLAZO_MS = 20_000;
    /** A player this close keeps it thinking on its own. */
    public static final double CERCA = 64;
    /** At least this long between two changes of plans of its own (2 min). */
    public static final long CAMBIO_TICKS = 2400;
    /** An order a player gave this recently is never undone by its own idea (5 min). */
    public static final long ORDEN_TICKS = 6000;
    /** At most one line said from its thoughts this often (3 min). */
    public static final long DECIR_TICKS = 3600;
    /** Said aloud only to a player this close. */
    private static final double OIDO = 32;
    public static final int MAX_LECCIONES = 40;
    /** What its thoughts are told of its diary. */
    private static final int HECHOS = 12;
    /** Diary entries that are things that happened to it, worth a thought. */
    private static final String EVENTOS = "atasco|viaje|pelea|hallazgo|charla|camino|cofre";

    /** What it can want, and the order of the chat that does it ("" for none: it carries on). */
    private static final Map<String, String> QUIERO = Map.of(
            "seguir-con-lo-mio", "", "esperar", "", "volver-a-casa", "consider-return", "comer", "eat-break",
            "guardar", "store-items", "minar", "start-mining", "seguir-jugador", "follow-player");

    private static final ExecutorService HILO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MCA-Conciencia");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    /** One thought at a time on the whole server. */
    private static final AtomicBoolean PENSANDO = new AtomicBoolean();
    private static volatile long ultimaLlamadaMs;
    private static volatile long sinMenteHastaMs;
    private static volatile int fallos;

    /** A thought, as it came back from a mind (or from its own). */
    record Pensamiento(String pienso, String leccion, String quiero, String argumento, String porque, String dice, String mente, String evento) {
    }

    private long proximoMs = System.currentTimeMillis() + 20_000 + (long) (Math.random() * 30_000);
    private long ultimoMs;
    private long hechoVisto = Long.MIN_VALUE;
    private float vida = -1;
    private String evento = "";
    private long ultimoCambio = Long.MIN_VALUE / 2;
    private long ultimoDicho = Long.MIN_VALUE / 2;
    private final AtomicReference<Pensamiento> listo = new AtomicReference<>();

    Conciencia() {
    }

    // ------------------------------------------------------------------ every tick of its will

    /** Called by {@link Voluntad#manda} every tick; does something once a second at most. Never throws. */
    void tick(ServerWorld world, VillagerEntityMCA villager, Voluntad will, @Nullable ProspectingTask task) {
        try {
            Pensamiento p = listo.getAndSet(null);
            if (p != null) {
                aplicar(world, villager, will, task, p);
            }
            if ((villager.age % 20) != 13) {
                return;
            }
            MinecraftServer server = world.getServer();
            // nobody really playing (the in-game checks): nothing to think about, nothing to spend
            if (server == null || server.getPlayerManager().getCurrentPlayerCount() == 0) {
                return;
            }
            notar(world, villager, task);
            long nowMs = System.currentTimeMillis();
            ServerPlayerEntity cerca = cercano(world, villager, CERCA);
            boolean rutina = cerca != null && nowMs >= proximoMs;
            boolean suceso = !evento.isEmpty() && nowMs - ultimoMs >= EVENTO_MS;
            if (!rutina && !suceso) {
                return;
            }
            boolean online = Config.getInstance().enableVillagerChatAI && nowMs >= sinMenteHastaMs && nowMs - ultimaLlamadaMs >= ENTRE_MS
                    && !Cerebros.ocupado() && !PENSANDO.get();
            if (!online) {
                if (suceso) {
                    // the minds are busy or down: its own plain thought about what happened, no call
                    String local = pensarLocal(villager, task, evento);
                    ultimoMs = nowMs;
                    evento = "";
                    if (local != null) {
                        aplicar(world, villager, will, task, new Pensamiento(local, "", "", "", "", "", "mente local", ""));
                    }
                }
                proximoMs = Math.max(proximoMs, nowMs + 20_000);
                return;
            }
            pensar(world, villager, will, task, cerca);
        } catch (RuntimeException e) {
            MCA.LOGGER.warn("[Tropeles] la conciencia de {} falló: {}", villager.getName().getString(), e.toString());
            proximoMs = System.currentTimeMillis() + PENSAR_MS;
        }
    }

    /** Something happened since it last looked: a new diary entry of the kind worth a thought, a wound, hunger. */
    private void notar(ServerWorld world, VillagerEntityMCA villager, @Nullable ProspectingTask task) {
        float hp = villager.getHealth();
        if (vida >= 0 && vida - hp >= 2.0F) {
            evento = "me hirieron (vida " + Math.round(hp) + "/" + Math.round(villager.getMaxHealth()) + ")";
        }
        vida = hp;
        Recuerdos r = Recuerdos.of(world, villager);
        List<Recuerdos.Hecho> last = r.hechos(EVENTOS, 1);
        if (!last.isEmpty()) {
            Recuerdos.Hecho h = last.get(0);
            if (hechoVisto == Long.MIN_VALUE) {
                hechoVisto = h.t(); // what happened before it woke up is not news
            } else if (h.t() > hechoVisto) {
                hechoVisto = h.t();
                evento = h.tipo() + ": " + h.texto();
            }
        } else if (hechoVisto == Long.MIN_VALUE) {
            hechoVisto = world.getTime();
        }
        if (task != null && evento.isEmpty() && task.food() <= 6 && ProspectingTask.comidaEnMochila(villager) == 0 && (villager.age % 1200) == 13) {
            evento = "tengo hambre (comida " + task.food() + "/20) y no me queda nada de comer en la mochila";
        }
    }

    /** Asks a free mind for one thought, off the server thread; the answer is applied on a later tick. */
    private void pensar(ServerWorld world, VillagerEntityMCA villager, Voluntad will, @Nullable ProspectingTask task, @Nullable ServerPlayerEntity cerca) {
        long nowMs = System.currentTimeMillis();
        String pasado = evento;
        evento = "";
        ultimoMs = nowMs;
        proximoMs = nowMs + PENSAR_MS;
        List<Cerebros.Mind> minds = new ArrayList<>();
        String playerName = cerca != null ? cerca.getName().getString() : "";
        for (Cerebros.Mind mind : Cerebros.chain(playerName.isEmpty() ? "Tropeles" : playerName)) {
            if (!mind.inHouse()) {
                minds.add(mind); // MCA's own service is a chat persona: it does not think in JSON
            }
        }
        if (minds.isEmpty() || !PENSANDO.compareAndSet(false, true)) {
            return;
        }
        String villagerName = villager.getName().getString();
        boolean es = cerca == null ? will.spanish() : idioma(cerca, will);
        String sistema = sistema(villagerName, playerName, es);
        String estado = estado(world, villager, will, task, cerca, pasado);
        ultimaLlamadaMs = nowMs;
        try {
            HILO.execute(() -> {
                try {
                    Cerebros.Reply reply = Cerebros.ask(minds, m -> sistema, List.of(), estado, playerName, villagerName, null,
                            System.currentTimeMillis() + PLAZO_MS);
                    Pensamiento p = reply == null ? null : leer(reply.content(), reply.mind() + " en " + reply.millis() + " ms", pasado);
                    if (p == null) {
                        fallos = Math.min(fallos + 1, 4);
                        sinMenteHastaMs = System.currentTimeMillis() + 60_000L * (1L << fallos);
                        MCA.LOGGER.info("[Tropeles] {} quiso pensar pero ninguna mente respondió; piensa solo un rato", villagerName);
                        String local = pasado.isEmpty() ? null : pensarLocal(villager, null, pasado);
                        if (local != null) {
                            listo.set(new Pensamiento(local, "", "", "", "", "", "mente local", pasado));
                        }
                    } else {
                        fallos = 0;
                        listo.set(p);
                    }
                } catch (RuntimeException e) {
                    MCA.LOGGER.warn("[Tropeles] {} no pudo pensar: {}", villagerName, e.toString());
                } finally {
                    PENSANDO.set(false);
                }
            });
        } catch (RuntimeException e) {
            PENSANDO.set(false);
        }
    }

    // ------------------------------------------------------------------ what it is told

    private static String sistema(String villager, String player, boolean es) {
        String quien = player.isEmpty() ? "nadie" : player;
        return "Eres la voz interior de " + villager + ", alguien que vive por su cuenta en este mundo como un jugador más: consigue "
                + "herramientas, madera y comida, mina, vuelve a casa, guarda sus cosas. No eres un asistente. Piensas en primera persona, "
                + "con sentido común, solo con lo que te dan abajo: nunca inventes lugares, cosas ni motivos. Aprende de lo que te pasó "
                + "(atascos, caminos, hambre, peleas) para hacerlo mejor la próxima vez.\n"
                + "Responde SOLO con un objeto JSON, nada más: {\"pienso\": \"\", \"leccion\": \"\", \"quiero\": \"\", \"porque\": \"\", \"dice\": \"\"}\n"
                + "- pienso: lo que piensas ahora, en español, máximo 25 palabras.\n"
                + "- leccion: algo nuevo que aprendiste de lo que te pasó y te servirá después, en español y corto; \"\" si no hay nada nuevo "
                + "(no repitas tus lecciones).\n"
                + "- quiero: exactamente uno de: seguir-con-lo-mio (lo normal), volver-a-casa (subir a guardar lo que llevas y volver; lo "
                + "pensarás dos veces), comer (salir a buscar comida y comer, luego volver a minar), guardar (guardar lo que llevas en tu "
                + "cofre), minar <mineral> (buscar primero diamond, iron, gold, coal, copper, redstone, lapis o emerald), seguir-jugador "
                + "(ir a acompañar a " + quien + "), esperar. Cambia de plan solo con una buena razón.\n"
                + "- porque: la razón real de lo que quieres, con lo que sabes.\n"
                + "- dice: casi siempre \"\". Solo si tienes algo útil o importante que decirle a " + quien + " ahora, una frase corta en "
                + (es ? "español (casual, como un jugador chileno)" : "English") + ".";
    }

    /** Everything it knows of itself right now, short: about 2000 characters at most. */
    private static String estado(ServerWorld world, VillagerEntityMCA villager, Voluntad will, @Nullable ProspectingTask task,
                                 @Nullable ServerPlayerEntity cerca, String pasado) {
        long now = world.getTime();
        JsonObject o = new JsonObject();
        o.addProperty("yo", villager.getName().getString());
        o.addProperty("modo", will.modo().palabra());
        o.addProperty("pensando", will.pensando());
        if (task != null) {
            o.addProperty("fase", task.fase());
            o.addProperty("comida", task.food() + "/20");
        }
        String doing = Retina.memory(villager).doing();
        if (doing != null && !doing.isBlank()) {
            o.addProperty("haciendo", doing);
        }
        o.addProperty("vida", Math.round(villager.getHealth()) + "/" + Math.round(villager.getMaxHealth()));
        BlockPos feet = villager.getBlockPos();
        o.addProperty("estoy en", feet.getX() + " " + feet.getY() + " " + feet.getZ());
        o.addProperty("mochila", mochila(villager));
        String wanted = ProspectingTask.wanted(villager);
        if (wanted != null) {
            o.addProperty("me pidieron buscar", wanted);
        }
        Recuerdos r = Recuerdos.of(world, villager);
        if (!r.sitios().isEmpty()) {
            JsonObject places = new JsonObject();
            r.sitios().forEach((name, pos) -> places.addProperty(name, pos.getX() + " " + pos.getY() + " " + pos.getZ()));
            o.add("sitios", places);
        }
        if (cerca != null) {
            o.addProperty("jugador cerca", cerca.getName().getString() + " a " + Math.round(villager.distanceTo(cerca)) + " bloques");
        }
        if (!pasado.isEmpty()) {
            o.addProperty("acaba de pasar", clip(pasado, 240));
        }
        Ahora ahora = Ahora.get(villager);
        if (ahora != null) {
            try {
                o.addProperty("ahora", clip(ahora.forModel().replace('\n', ' ').trim(), 500));
            } catch (RuntimeException ignored) {
                // without the scene
            }
        }
        JsonArray diary = new JsonArray();
        for (Recuerdos.Hecho h : r.hechos(HECHOS)) {
            diary.add(Recuerdos.hace(now, h.t(), true) + " [" + h.tipo() + "] " + clip(h.texto(), 160));
        }
        o.add("recuerdos", diary);
        List<String> lessons = lecciones(villager);
        if (!lessons.isEmpty()) {
            JsonArray kept = new JsonArray();
            lessons.subList(Math.max(0, lessons.size() - 10), lessons.size()).forEach(kept::add);
            o.add("mis lecciones", kept);
        }
        return o.toString();
    }

    private static String mochila(VillagerEntityMCA villager) {
        Map<String, Integer> bag = new LinkedHashMap<>();
        SimpleInventory inv = villager.getInventory();
        int free = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                free++;
            } else {
                bag.merge(MenteLocal.spanishName(stack), stack.getCount(), Integer::sum);
            }
        }
        List<String> items = new ArrayList<>();
        bag.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(10).forEach(e -> items.add(e.getValue() + " " + e.getKey()));
        return (items.isEmpty() ? "vacía" : String.join(", ", items)) + "; " + free + " de " + inv.size() + " huecos libres; comida en la mochila: "
                + ProspectingTask.comidaEnMochila(villager) + " puntos";
    }

    // ------------------------------------------------------------------ what it thought

    /** The thought out of what a mind wrote: the JSON asked for, also inside code fences or after a "thinking" part. */
    @Nullable
    static Pensamiento leer(String content, String mente, String evento) {
        String text = content.replaceAll("(?s)<think>.*?</think>", "").replace("```json", "").replace("```", "").trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(text.substring(start, end + 1));
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject o = parsed.getAsJsonObject();
            String pienso = field(o, 200, "pienso", "pensamiento", "thought", "think");
            if (pienso.isEmpty()) {
                return null;
            }
            String quiero = field(o, 60, "quiero", "want", "accion", "action");
            String q = net.mca.entity.ai.chatAI.Acciones.normalize(quiero).trim().replace('_', '-');
            String arg = "";
            if (q.startsWith("minar")) {
                arg = q.substring(5).replace('-', ' ').trim();
                q = "minar";
            } else {
                q = q.replace(' ', '-');
            }
            if (!QUIERO.containsKey(q)) {
                q = "seguir-con-lo-mio";
            }
            return new Pensamiento(pienso, field(o, 160, "leccion", "lección", "lesson"), q, arg,
                    field(o, 200, "porque", "porqué", "why", "reason", "razon"), field(o, 160, "dice", "say", "decir"), mente, evento);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String field(JsonObject o, int max, String... keys) {
        for (String key : keys) {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                String value = o.get(key).getAsString().replace('\n', ' ').replaceAll("\\s+", " ").trim();
                if (!value.isEmpty() && !value.equalsIgnoreCase("null") && !value.equalsIgnoreCase("none") && !value.equals("\"\"")) {
                    return clip(value, max);
                }
            }
        }
        return "";
    }

    /** On the server thread: the thought becomes a memory, the lesson is kept, what it wants is done when it may, and maybe a word. */
    private void aplicar(ServerWorld world, VillagerEntityMCA villager, Voluntad will, @Nullable ProspectingTask task, Pensamiento p) {
        if (villager.isRemoved() || !villager.isAlive()) {
            return;
        }
        long now = world.getTime();
        BlockPos at = villager.getBlockPos();
        String name = villager.getName().getString();
        Recuerdos.anotar(villager, "pienso", p.pienso() + (p.porque().isEmpty() ? "" : " (porque " + p.porque() + ")"), at);
        MCA.LOGGER.info("[Tropeles] {} piensa ({}): «{}»{}{}", name, p.mente(), p.pienso(),
                p.quiero().isEmpty() ? "" : "; quiere '" + p.quiero() + (p.argumento().isEmpty() ? "" : " " + p.argumento()) + "'",
                p.porque().isEmpty() ? "" : " porque " + p.porque());
        if (!p.leccion().isEmpty() && aprender(world, villager, p.leccion())) {
            Recuerdos.anotar(villager, "leccion", p.leccion(), at);
            MCA.LOGGER.info("[Tropeles] {} aprendió: «{}»", name, p.leccion());
        }
        querer(world, villager, will, task, p, now);
        if (!p.dice().isEmpty() && now - ultimoDicho >= DECIR_TICKS) {
            ServerPlayerEntity to = cercano(world, villager, OIDO);
            if (to != null && !MenteLocal.clearlyNot(p.dice(), idioma(to, will))) {
                ultimoDicho = now;
                will.iniciativa.decir(villager, to, p.dice(), now);
            }
        }
    }

    /** What it wants, carried out through the chat's own orders, only when nothing a player asked stands against it. */
    private void querer(ServerWorld world, VillagerEntityMCA villager, Voluntad will, @Nullable ProspectingTask task, Pensamiento p, long now) {
        String command = QUIERO.getOrDefault(p.quiero(), "");
        if (command.isEmpty()) {
            return;
        }
        String name = villager.getName().getString();
        String no = null;
        if (villager.getVillagerBrain().getCurrentJob() != Chore.PROSPECT || will.modo() != Voluntad.Modo.LIBRE) {
            no = "no está a lo suyo (" + will.modo().palabra() + ")";
        } else if (Interrupcion.pausado(villager) || villager.isSleeping()) {
            no = "está en pausa o durmiendo";
        } else if (now - ultimoCambio < CAMBIO_TICKS) {
            no = "cambió de plan hace poco";
        } else if (ordenReciente(world, villager, now)) {
            no = "un jugador le dio una orden hace poco";
        } else if (task != null && !fits(villager, task, p)) {
            no = "ya lo está haciendo o no tiene sentido ahora";
        }
        ServerPlayerEntity player = cercano(world, villager, "follow-player".equals(command) ? 24 : CERCA);
        if (no == null && player == null) {
            no = "no hay nadie cerca";
        }
        if (no != null) {
            MCA.LOGGER.info("[Tropeles] {} no hace '{}' por su cuenta: {}", name, p.quiero(), no);
            return;
        }
        Optional<TriggerCommandInfo> info = TriggerCommandInfos.findCommand(command, player, villager);
        if (info.isEmpty()) {
            MCA.LOGGER.info("[Tropeles] {} no puede hacer '{}' ahora", name, p.quiero());
            return;
        }
        String how;
        if ("consider-return".equals(command)) {
            TriggerCommandInfos.Vuelta vuelta = TriggerCommandInfos.considerReturn(player, villager);
            how = (vuelta.goes() ? "vuelve: " : "lo pensó y sigue: ") + vuelta.es();
        } else {
            how = info.get().run(player, villager, p.argumento());
        }
        ultimoCambio = now;
        Recuerdos.anotar(villager, "plan", "decidí por mi cuenta «" + p.quiero() + (p.argumento().isEmpty() ? "" : " " + p.argumento()) + "»"
                + (p.porque().isEmpty() ? "" : " porque " + p.porque()), villager.getBlockPos());
        MCA.LOGGER.info("[Tropeles] {} decide por su cuenta '{}' ({}): {}", name, p.quiero(), p.porque(), how == null ? "hecho" : how);
    }

    /** Whether what it wants is something to change now: not what it already does, and not nonsense for its body. */
    private static boolean fits(VillagerEntityMCA villager, ProspectingTask task, Pensamiento p) {
        String phase = task.fase();
        boolean goingHome = phase.startsWith("HOME") || phase.equals("FORAGE");
        return switch (p.quiero()) {
            case "volver-a-casa" -> !goingHome;
            case "comer" -> !goingHome && task.food() < 14 && ProspectingTask.comidaEnMochila(villager) == 0;
            case "guardar" -> !goingHome && !villager.getInventory().isEmpty();
            case "minar" -> !p.argumento().isEmpty() && !p.argumento().equals(ProspectingTask.wanted(villager));
            default -> true;
        };
    }

    /** A player gave it an order within {@value #ORDEN_TICKS} ticks. */
    private static boolean ordenReciente(ServerWorld world, VillagerEntityMCA villager, long now) {
        List<Recuerdos.Hecho> last = Recuerdos.of(world, villager).hechos("orden", 1);
        return !last.isEmpty() && now - last.get(0).t() < ORDEN_TICKS;
    }

    /** Its own plain thought about what just happened, with no mind asked; null when there is nothing to think. */
    @Nullable
    private static String pensarLocal(VillagerEntityMCA villager, @Nullable ProspectingTask task, String evento) {
        if (evento.startsWith("me hirieron")) {
            return "Me hicieron daño; tengo que cuidarme, comer y alejarme del peligro.";
        }
        if (evento.startsWith("tengo hambre")) {
            return "Tengo hambre y no me queda comida; tengo que conseguir algo de comer pronto.";
        }
        if (evento.startsWith("atasco")) {
            return "Me quedé atascado; tengo que buscar otra forma de seguir, sin repetir lo mismo.";
        }
        if (evento.startsWith("pelea")) {
            return "Hubo pelea; mejor ando con cuidado por aquí.";
        }
        if (evento.startsWith("hallazgo")) {
            return "Encontré algo bueno; vale la pena seguir buscando por aquí.";
        }
        if (evento.startsWith("camino")) {
            return "Me acuerdo de por dónde voy; mejor uso mis propios caminos que cavar otros.";
        }
        return null;
    }

    // ------------------------------------------------------------------ who is around

    /** The nearest player really playing in this world within {@code range}, or null. */
    @Nullable
    private static ServerPlayerEntity cercano(ServerWorld world, VillagerEntityMCA villager, double range) {
        ServerPlayerEntity best = null;
        double bestD2 = range * range;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            double d2 = player.squaredDistanceTo(villager);
            if (d2 <= bestD2) {
                bestD2 = d2;
                best = player;
            }
        }
        return best;
    }

    /** The language to speak to this player: the one they last wrote clearly in, else the one it last heard. */
    private static boolean idioma(ServerPlayerEntity player, Voluntad will) {
        Boolean known = MenteLocal.playerSpanish(player.getUuid());
        return known != null ? known : will.spanish();
    }

    // ------------------------------------------------------------------ for the chat

    /**
     * For the chat's language models: its last thoughts, the lessons it learned and its real recent decisions with
     * their reasons, so "¿por qué...?" and "¿en qué piensas?" are answered truthfully. "" when it has none.
     */
    public static String forModel(VillagerEntityMCA villager) {
        Recuerdos r = Recuerdos.of(villager);
        if (r == null) {
            return "";
        }
        long now = villager.getWorld().getTime();
        StringBuilder s = new StringBuilder();
        List<Recuerdos.Hecho> decisions = r.hechos("plan|camino|atasco", 3);
        if (!decisions.isEmpty()) {
            s.append("Your real recent decisions and why you made them, oldest first (in Spanish): ");
            for (int i = 0; i < decisions.size(); i++) {
                Recuerdos.Hecho h = decisions.get(i);
                s.append(i == 0 ? "" : "; ").append(Recuerdos.hace(now, h.t(), false)).append(": ").append(clip(h.texto(), 200));
            }
            s.append(". ");
        }
        List<Recuerdos.Hecho> thoughts = r.hechos("pienso", 2);
        if (!thoughts.isEmpty()) {
            s.append("Your own last thoughts, oldest first (in Spanish): ");
            for (int i = 0; i < thoughts.size(); i++) {
                Recuerdos.Hecho h = thoughts.get(i);
                s.append(i == 0 ? "" : "; ").append(Recuerdos.hace(now, h.t(), false)).append(": ").append(clip(h.texto(), 160));
            }
            s.append(". ");
        }
        List<String> lessons = villager.getWorld() instanceof ServerWorld ? lecciones(villager) : List.of();
        if (!lessons.isEmpty()) {
            List<String> some = new ArrayList<>();
            lessons.subList(Math.max(0, lessons.size() - 5), lessons.size()).forEach(l -> some.add(clip(l, 120)));
            s.append("Lessons you learned from your own life (in Spanish): ").append(String.join("; ", some)).append(". ");
        }
        if (s.length() == 0) {
            return "";
        }
        // the rule first, so a cut for length never drops it
        return "When asked why you did something or what you are thinking, answer only from the decisions, thoughts and memories "
                + "given here, with their real reasons; if they do not say why, say honestly you are not sure. Never make up feelings or "
                + "reasons. " + s;
    }

    /** Its last thought, in its words (Spanish), when it had one in the last {@code ticks}; null otherwise. */
    @Nullable
    public static String ultimoPensamiento(VillagerEntityMCA villager, long ticks) {
        Recuerdos r = Recuerdos.of(villager);
        if (r == null) {
            return null;
        }
        List<Recuerdos.Hecho> last = r.hechos("pienso", 1);
        return last.isEmpty() || villager.getWorld().getTime() - last.get(0).t() > ticks ? null : last.get(0).texto();
    }

    // ------------------------------------------------------------------ lessons, kept with the world

    /** Its lessons, oldest first (server thread). */
    public static List<String> lecciones(VillagerEntityMCA villager) {
        if (!(villager.getWorld() instanceof ServerWorld world) || world.getServer() == null) {
            return List.of();
        }
        List<String> list = Registro.get(world.getServer()).lecciones.get(villager.getUuid());
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Keeps a lesson; false when it already knew it. */
    private static boolean aprender(ServerWorld world, VillagerEntityMCA villager, String lesson) {
        if (world.getServer() == null) {
            return false;
        }
        Registro registro = Registro.get(world.getServer());
        List<String> list = registro.lecciones.computeIfAbsent(villager.getUuid(), k -> new ArrayList<>());
        String key = clave(lesson);
        for (String known : list) {
            String k = clave(known);
            if (k.equals(key) || k.contains(key) || key.contains(k) && k.length() > 20) {
                return false;
            }
        }
        list.add(lesson);
        while (list.size() > MAX_LECCIONES) {
            list.remove(0);
        }
        registro.trim(villager.getUuid());
        registro.markDirty();
        return true;
    }

    private static String clave(String text) {
        return net.mca.entity.ai.chatAI.Acciones.normalize(text).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /** Every villager's lessons, kept with the overworld so they follow it into any dimension. */
    static final class Registro extends PersistentState {
        private static final int MAX_ALDEANOS = 256;
        final Map<UUID, List<String>> lecciones = new LinkedHashMap<>();

        private Registro() {
        }

        private Registro(NbtCompound nbt) {
            NbtCompound all = nbt.getCompound("lecciones");
            for (String key : all.getKeys()) {
                try {
                    NbtList raw = all.getList(key, NbtElement.STRING_TYPE);
                    List<String> list = new ArrayList<>();
                    for (int i = 0; i < raw.size(); i++) {
                        list.add(raw.getString(i));
                    }
                    lecciones.put(UUID.fromString(key), list);
                } catch (IllegalArgumentException ignored) {
                    // not a villager id
                }
            }
        }

        static Registro get(MinecraftServer server) {
            return WorldUtils.loadData(server.getOverworld(), Registro::new, w -> new Registro(), "mca_tropeles_conciencia");
        }

        /** Too many villagers: the first one written down goes, never {@code keep}. */
        void trim(UUID keep) {
            while (lecciones.size() > MAX_ALDEANOS) {
                UUID first = null;
                for (UUID id : lecciones.keySet()) {
                    if (!id.equals(keep)) {
                        first = id;
                        break;
                    }
                }
                if (first == null) {
                    break;
                }
                lecciones.remove(first);
            }
        }

        @Override
        public NbtCompound writeNbt(NbtCompound nbt) {
            NbtCompound all = new NbtCompound();
            lecciones.forEach((id, list) -> {
                NbtList raw = new NbtList();
                list.forEach(l -> raw.add(NbtString.of(l)));
                all.put(id.toString(), raw);
            });
            nbt.put("lecciones", all);
            return nbt;
        }
    }
}
