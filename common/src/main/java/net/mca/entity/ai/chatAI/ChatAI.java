package net.mca.entity.ai.chatAI;

import net.mca.Config;
import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.server.ProspectorPresence;
import net.mca.util.WorldUtils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.jetbrains.annotations.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.mca.entity.VillagerLike.VILLAGER_NAME;

/**
 * Talking to villagers in chat, the way players talk to each other on a server. Each message is routed on the
 * server thread to the villager it is for (its name, the one looked at, the one already talking, the only one
 * around, or the player's own worker), answered on that villager's own worker lane (one message after the
 * other, within a time budget, with a watchdog), and said back at once as "{@code <Inas> ...}" to the one who
 * asked and to the players near either of them. Before it answers it looks around ({@link Vistazo#fresh}), on the
 * server thread like everything that reads the world; only the minds online are asked from the lanes. A villager always answers: when the minds online are slow or
 * down, its own mind ({@link MenteLocal}) does. A villager at work is never steered by chat: it does not walk
 * over or turn its head, so its hands and eyes stay on the job.
 */
public class ChatAI {
    /** Max range to find a villager in */
    private static final int VILLAGER_SEARCH_RANGE = 32;

    /** How far the villager a player looks at can be, like a crosshair: far enough to reach a miner down a shaft. */
    private static final double LOOK_RANGE = 48;
    /** A looked-at villager this close is talked to even with other players around. */
    private static final double CLOSE_LOOK = 6;
    /** A conversation goes on without names while the villager is this close (or wherever, when it began far away). */
    private static final double NEAR_RANGE = 32;
    /** "The only villager around": the only one this close that the player can see. */
    private static final double ALONE_RANGE = 16;
    /** Another player this close means the chat may be for them: then only names and close looks count. */
    private static final double OTHER_PLAYERS_RANGE = 32;
    /** A name said this close counts anywhere in the message; farther away it must be said to someone ("Inas, ..."). */
    private static final double NAME_RANGE = 64;
    /** How long a conversation goes on without names, alone and with others around (real time). */
    private static final long CONVERSATION_MS = 180_000;
    private static final long CROWDED_CONVERSATION_MS = 45_000;
    /** An answer never shows before the player's own line. */
    private static final long MIN_REPLY_DELAY_MS = 300;
    /** More waiting messages than this for one villager are answered by its own mind alone, never dropped. */
    private static final int MAX_PENDING = 2;
    /** The watchdog answers this long after the longest a mind may take. */
    private static final long GRACE_MS = 8_000;
    /** "Inas está pensando…" is shown again this often while it thinks. */
    private static final long THINKING_REPEAT_MS = 2_500;

    /** Map of villager UUIDs to strategies (i.e. managed by InworldAI or GPT3) */
    private static final Map<UUID, ChatAIStrategy> strategies = new ConcurrentHashMap<>();

    /**
     * Current conversation of player. <p>
     * A player can max. have 1 conversation at all times.
     */
    private static final Map<UUID, OpenConversation> currentConversations = new ConcurrentHashMap<>();

    private static final AtomicInteger THREADS = new AtomicInteger();
    /** The chat's own threads, so a slow mind never holds up the game or anything else. */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
        Thread thread = new Thread(r, "MCA-Chat-" + THREADS.incrementAndGet());
        thread.setDaemon(true);
        thread.setContextClassLoader(ChatAI.class.getClassLoader());
        return thread;
    });
    /** Each villager's lane: its messages are answered one after the other, in order. */
    private static final Map<UUID, CompletableFuture<Void>> LANES = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicInteger> PENDING = new ConcurrentHashMap<>();
    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    /** Who a message is for, and how that was decided (for the log). */
    public record Addressee(VillagerEntityMCA villager, String how) {
    }

    /**
     * One message on its way to an answer, shared by the worker, the watchdog and the server thread: whoever
     * says the answer first claims {@link #said}, so exactly one line is said.
     */
    private static final class Turn {
        final MinecraftServer server;
        final ServerPlayerEntity speaker;
        final UUID speakerId;
        final VillagerEntityMCA villager;
        final UUID villagerId;
        final String villagerName;
        final String msg;
        final long received;
        final long hardMs;
        final boolean es;
        final AtomicBoolean said = new AtomicBoolean();
        volatile boolean shown;
        /** The villager's own answer, once it read the world (and carried out any order). */
        volatile String fallback;

        Turn(MinecraftServer server, ServerPlayerEntity speaker, VillagerEntityMCA villager, String msg, long received, long hardMs) {
            this.server = server;
            this.speaker = speaker;
            this.speakerId = speaker.getUuid();
            this.villager = villager;
            this.villagerId = villager.getUuid();
            this.villagerName = villager.getName().getString();
            this.msg = msg;
            this.received = received;
            this.hardMs = hardMs;
            this.es = MenteLocal.speaksSpanish(msg, villagerName);
        }
    }

    /**
     * Gets an answer for a specific message for a villager from a player with the villager-specific chat strategy
     * @param player ServerPlayerEntity of the player
     * @param villager VillagerEntityMCA of the villager
     * @param msg Message in question
     * @return the answer; never empty. Slow: never call it on the server thread.
     */
    public static Optional<String> answer(ServerPlayerEntity player, VillagerEntityMCA villager, String msg) {
        return answer(player, villager, msg, System.currentTimeMillis() + Math.max(1_000L, Config.getInstance().tropelesChatBudgetMs), null);
    }

    /** As above, with the online minds done by {@code deadline}; {@code early} gets the villager's own answer once ready. */
    static Optional<String> answer(ServerPlayerEntity player, VillagerEntityMCA villager, String msg, long deadline, @Nullable Consumer<String> early) {
        ChatAIStrategy strategy = computeStrategyIfAbsent(villager.getUuid());
        if (strategy instanceof OpenAIChatAI open) {
            return open.answer(player, villager, msg, deadline, early);
        }
        Optional<String> said = Optional.empty();
        try {
            said = strategy.answer(player, villager, msg);
        } catch (RuntimeException e) {
            MCA.LOGGER.error("[Tropeles] InworldAI falló para {}", villager.getName().getString(), e);
        }
        if (said.isPresent() && !said.get().isBlank()) {
            return said;
        }
        // InworldAI said nothing (an error, or a trigger with no words): the usual minds answer
        return new OpenAIChatAI().answer(player, villager, msg, deadline, early);
    }

    /**
     * Searches Config for a map entry for UUID, uses Inworld with said entry if found, else GPT3 (default)
     * @param villagerID UUID of villager
     * @return Object implementing the ChatAIStrategy interface
     */
    private static ChatAIStrategy computeStrategyIfAbsent(UUID villagerID) {
        return strategies.computeIfAbsent(villagerID, v -> {
            String inworldResourceName = Config.getInstance().inworldAIResourceNames.getOrDefault(v, "");
            return inworldResourceName.isEmpty() ? new OpenAIChatAI() : new InworldAI(inworldResourceName);
        });
    }

    /**
     * Clears the strategy for a specific villager
     * @param villagerID UUID of the villager
     */
    public static void clearStrategy(UUID villagerID) {
        strategies.remove(villagerID);
    }

    // ------------------------------------------------------------------ a player said something in chat

    /**
     * A player wrote {@code msg} in chat (server thread). Finds the villager it is for and starts its answer;
     * every decision is logged, also when no villager is listening.
     */
    public static void onPlayerChat(ServerPlayerEntity speaker, String msg, long receivedMs) {
        try {
            MinecraftServer server = speaker.getServer();
            if (server == null || msg == null || msg.isBlank()) {
                return;
            }
            Config config = Config.getInstance();
            if (LOGGED.compareAndSet(false, true)) {
                List<String> minds = new ArrayList<>();
                Cerebros.chain(speaker.getName().getString()).forEach(m -> minds.add(m.name()));
                MCA.LOGGER.info("[Tropeles] charla con aldeanos activa: mentes {}, {} ms en línea, {} ms en este equipo, sin decir el nombre: {}",
                        minds, config.tropelesChatBudgetMs, config.tropelesChatLocalBudgetMs, config.tropelesChatImplicit);
            }
            Optional<Addressee> to = pickAddressee(speaker, msg);
            if (to.isEmpty()) {
                MCA.LOGGER.info("[Tropeles] nadie escucha a {}: «{}»", speaker.getName().getString(), msg);
                return;
            }
            VillagerEntityMCA villager = to.get().villager();
            MCA.LOGGER.info("[Tropeles] {} → {} ({}): «{}»", speaker.getName().getString(), villager.getName().getString(), to.get().how(), msg);
            // "¡para!": it stops this very tick and asks, ahead of every mind and of the answers it still owed
            if (net.mca.entity.ai.mente.Interrupcion.oir(speaker, villager, msg)) {
                currentConversations.put(speaker.getUuid(), new OpenConversation(villager.getUuid(), System.currentTimeMillis(),
                        villager.getWorld() != speaker.getWorld() || villager.distanceTo(speaker) > NEAR_RANGE));
                return;
            }
            // fresh eyes before it answers (server thread): what it says about its surroundings is what it just saw
            Vistazo.fresh(villager);

            boolean remote = villager.getWorld() != speaker.getWorld() || villager.distanceTo(speaker) > NEAR_RANGE;
            currentConversations.put(speaker.getUuid(), new OpenConversation(villager.getUuid(), System.currentTimeMillis(), remote));

            long budget = Math.max(1_000L, config.tropelesChatBudgetMs);
            long hard = Math.max(budget, config.tropelesChatLocalBudgetMs) + GRACE_MS;
            Turn turn = new Turn(server, speaker, villager, msg, receivedMs, hard);

            AtomicInteger pending = PENDING.computeIfAbsent(turn.villagerId, k -> new AtomicInteger());
            boolean backlog = pending.incrementAndGet() > MAX_PENDING;
            if (backlog) {
                MCA.LOGGER.info("[Tropeles] {} tiene {} mensajes esperando: contesta su propia mente", turn.villagerName, pending.get());
            }
            // the in-game checks keep the chat offline on their thread; the lane carries it over
            boolean offline = backlog || Cerebros.OFFLINE.get();

            thinking(turn, Math.max(0, config.tropelesChatThinkingMs));
            long watchdog = Math.max(1_000L, receivedMs + hard - System.currentTimeMillis());
            Cerebros.CLOCK.schedule(() -> server.execute(() -> rescue(turn)), watchdog, TimeUnit.MILLISECONDS);
            lane(turn.villagerId, () -> work(turn, receivedMs + budget, offline, pending));
        } catch (Throwable t) {
            MCA.LOGGER.error("[Tropeles] la charla de {} falló al empezar", speaker.getName().getString(), t);
        }
    }

    /** Runs {@code job} after the villager's earlier messages, on the chat's own threads. */
    private static void lane(UUID villager, Runnable job) {
        CompletableFuture<Void> next = LANES.compute(villager, (k, prev) -> (prev == null ? CompletableFuture.<Void>completedFuture(null)
                : prev.handle((a, b) -> (Void) null)).thenRunAsync(job, POOL));
        // outside compute(): a future already done would run this at once, inside the map's own update
        next.whenComplete((a, b) -> {
            if (b != null) {
                MCA.LOGGER.error("[Tropeles] charla", b);
            }
            LANES.remove(villager, next);
        });
    }

    /** The answer, worked out on a chat thread, then said on the server thread. */
    private static void work(Turn turn, long deadline, boolean offline, AtomicInteger pending) {
        String line = null;
        try {
            if (offline) {
                Cerebros.OFFLINE.set(true);
            }
            net.mca.entity.ai.mente.Interrupcion.turno(turn.received);
            if (!turn.said.get() && !net.mca.entity.ai.mente.Interrupcion.despues(turn.villagerId, turn.received)) {
                line = answer(turn.speaker, turn.villager, turn.msg, deadline, local -> turn.fallback = local).orElse(null);
            }
        } catch (Throwable t) {
            MCA.LOGGER.error("[Tropeles] la charla con {} falló", turn.villagerName, t);
        } finally {
            net.mca.entity.ai.mente.Interrupcion.turno(null);
            Cerebros.OFFLINE.set(false);
            pending.decrementAndGet();
        }
        if (turn.said.get()) {
            if (line != null) {
                MCA.LOGGER.info("[Tropeles] {} pensó demasiado; ya había contestado, no dice «{}»", turn.villagerName, line);
            }
            return;
        }
        if (line == null || line.isBlank()) {
            line = turn.fallback;
        }
        if (line == null || line.isBlank()) {
            try {
                line = OpenAIChatAI.lastResort(turn.server, turn.speaker, turn.villager, turn.msg);
            } catch (Throwable t) {
                MCA.LOGGER.error("[Tropeles] ni la mente local de {} pudo contestar", turn.villagerName, t);
                line = MenteLocal.shrug(turn.es);
            }
        }
        long wait = MIN_REPLY_DELAY_MS - (System.currentTimeMillis() - turn.received);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String said = line;
        if (turn.server.isRunning()) {
            turn.server.execute(() -> say(turn, said));
        }
    }

    /** The watchdog: the minds took far too long, so the villager's own mind answers now (server thread). */
    private static void rescue(Turn turn) {
        if (!turn.server.isOnThread() || turn.said.get()) {
            return;
        }
        String line = turn.fallback;
        if (line == null || line.isBlank()) {
            line = OpenAIChatAI.lastResort(turn.server, turn.speaker, turn.villager, turn.msg);
        }
        MCA.LOGGER.warn("[Tropeles] {} tardó más de {} s en pensar: contesta su propia mente", turn.villagerName, turn.hardMs / 1000);
        say(turn, line);
    }

    /** Says the answer once (server thread): the first of the worker and the watchdog wins. */
    private static void say(Turn turn, String line) {
        if (!turn.server.isOnThread() || !turn.said.compareAndSet(false, true)) {
            return;
        }
        if (net.mca.entity.ai.mente.Interrupcion.despues(turn.villagerId, turn.received)) {
            // stopped with "¡para!" after this was asked: what it was about to say no longer fits
            MCA.LOGGER.info("[Tropeles] {} se detuvo después de «{}»; no dice «{}»", turn.villagerName, turn.msg, line);
            return;
        }
        OpenAIChatAI.remember(turn.villagerId, turn.speakerId, turn.msg, line);
        // what was said and what it answered is a memory of its own too ({@link net.mca.entity.ai.mente.Recuerdos})
        net.mca.entity.ai.mente.Recuerdos.anotar(turn.villager, "charla", turn.speaker.getName().getString() + " me dijo «" + turn.msg
                + "»; le contesté «" + line + "»", turn.villager.getBlockPos());
        deliver(turn.server, turn.speakerId, turn.speaker, turn.villagerId, turn.villager, line, turn.shown);
    }

    /** "Inas está pensando…" above the hotbar of the one who asked, while it thinks (server thread). */
    private static void thinking(Turn turn, long delay) {
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, turn.server).execute(() -> {
            if (!turn.server.isOnThread() || turn.said.get() || System.currentTimeMillis() - turn.received > turn.hardMs) {
                return;
            }
            ServerPlayerEntity player = turn.server.getPlayerManager().getPlayer(turn.speakerId);
            if (player != null && player.networkHandler != null) {
                player.sendMessage(Text.literal(turn.villagerName + (turn.es ? " está pensando…" : " is thinking…"))
                        .formatted(Formatting.GRAY, Formatting.ITALIC), true);
                turn.shown = true;
            }
            thinking(turn, THINKING_REPEAT_MS);
        });
    }

    // ------------------------------------------------------------------ who it is for

    /**
     * Searches the villager this message is for: compatibility name for {@link #pickAddressee}.
     * @param player The player in the conversation
     * @param msg The message
     * @return {@code Optional.Empty} if no valid villager was found, Optional containing the VillagerEntityMCA object otherwise
     */
    public static Optional<VillagerEntityMCA> getVillagerForConversation(ServerPlayerEntity player, String msg) {
        return pickAddressee(player, msg).map(Addressee::villager);
    }

    /**
     * Who {@code msg} is for, on the server thread, in this order:
     * <ol>
     * <li>"nombre": a villager named in it, anywhere when said to it ("Inas, sube") or with its whole name,
     * otherwise within {@value #NAME_RANGE} blocks (longest name, then the one addressed, looked at, talking, closest),
     * unless it is only mentioned in passing in a message that names another online player;</li>
     * <li>nobody, when it names another online player;</li>
     * <li>"mirada": the villager under the player's crosshair, up to {@value #LOOK_RANGE} blocks (down a shaft too);</li>
     * <li>"conversación": the villager it was talking with lately;</li>
     * <li>"único cerca": the only grown villager the player sees within {@value #ALONE_RANGE} blocks;</li>
     * <li>"su trabajador": the only villager working for this player.</li>
     * </ol>
     * With other players around, the implicit ones count only from close by: the chat may be for them.
     */
    public static Optional<Addressee> pickAddressee(ServerPlayerEntity player, String msg) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return Optional.empty();
        }
        String text = normalizeString(msg);
        boolean implicit = Config.getInstance().tropelesChatImplicit;
        long now = System.currentTimeMillis();
        boolean alone = alone(server, player);

        VillagerEntityMCA looked = lookedAt(player);
        OpenConversation conversation = currentConversations.get(player.getUuid());
        long window = alone ? CONVERSATION_MS : CROWDED_CONVERSATION_MS;
        UUID partner = conversation != null && now - conversation.lastMs() < window ? conversation.villagerUUID() : null;

        // 1. by name, and the player's own workers on the way
        Named best = null;
        List<VillagerEntityMCA> workers = new ArrayList<>();
        for (ServerWorld world : server.getWorlds()) {
            boolean here = world == player.getWorld();
            for (Entity entity : world.iterateEntities()) {
                if (!(entity instanceof VillagerEntityMCA villager) || !villager.isAlive()) {
                    continue;
                }
                double d2 = here ? villager.squaredDistanceTo(player) : Double.MAX_VALUE;
                // by UUID: a miner keeps working for its player while that player is in another dimension
                if (villager.getVillagerBrain().getCurrentJob() != Chore.NONE
                        && villager.getVillagerBrain().getJobAssignerUuid().filter(player.getUuid()::equals).isPresent()) {
                    workers.add(villager);
                }
                Named named = named(text, villager, d2);
                if (named != null && (best == null || named.better(best, looked, partner))) {
                    best = named;
                }
            }
        }
        // the name a listed prospector goes by in Tab, said to it: "@Inas", "Inas2, sube"
        for (String word : msg.split("[\\s,.;:!?¿¡\"()]+")) {
            boolean mention = word.startsWith("@");
            String typed = mention ? word.substring(1) : word;
            if (typed.length() < 3) {
                continue;
            }
            Entity listed = ProspectorPresence.findByName(server, typed);
            if (!(listed instanceof VillagerEntityMCA villager) || !villager.isAlive()) {
                continue;
            }
            String profile = ProspectorPresence.profileName(villager);
            String plain = normalizeString(typed);
            int at = wordAt(text, plain);
            if (!mention && (profile == null || !typed.equalsIgnoreCase(profile) || at < 0 || !vocative(text, at, plain.length()))) {
                continue;
            }
            double d2 = villager.getWorld() == player.getWorld() ? villager.squaredDistanceTo(player) : Double.MAX_VALUE;
            Named named = new Named(villager, typed.length(), Math.max(0, at), true, d2);
            if (best == null || named.better(best, looked, partner)) {
                best = named;
            }
        }
        // 2. "Pedro, ven" is for Pedro, and so is "Pedro, Inas ya viene": a villager only named in passing yields
        // to a player named in the same message; one said to ("Inas, dile a Pedro que...") does not
        boolean forPlayer = false;
        for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
            String name = normalizeString(other.getName().getString());
            if (other != player && name.length() >= 2 && wordAt(text, name) >= 0) {
                forPlayer = true;
                break;
            }
        }
        if (best != null && (best.vocative || !forPlayer)) {
            return Optional.of(new Addressee(best.villager, "nombre"));
        }
        if (forPlayer) {
            return Optional.empty();
        }
        if (!implicit) {
            return Optional.empty();
        }

        // 3. the one the player is looking at
        if (looked != null && (alone || looked.distanceTo(player) <= CLOSE_LOOK)) {
            return Optional.of(new Addressee(looked, "mirada"));
        }

        // 4. the one it was just talking with
        if (partner != null) {
            for (ServerWorld world : server.getWorlds()) {
                Entity entity = world.getEntity(partner);
                if (entity instanceof VillagerEntityMCA villager && villager.isAlive()
                        && (conversation.remote() || world == player.getWorld() && villager.distanceTo(player) < NEAR_RANGE)) {
                    return Optional.of(new Addressee(villager, "conversación"));
                }
            }
        }
        if (!alone) {
            return Optional.empty();
        }

        // 5. the only one around
        VillagerEntityMCA only = null;
        int seen = 0;
        for (VillagerEntityMCA villager : WorldUtils.getCloseEntities(player.getWorld(), player, ALONE_RANGE, VillagerEntityMCA.class)) {
            if (villager.isAlive() && !villager.isBaby() && villager.distanceTo(player) <= ALONE_RANGE && player.canSee(villager)) {
                only = villager;
                seen++;
            }
        }
        if (seen == 1) {
            return Optional.of(new Addressee(only, "único cerca"));
        }

        // 6. the one working for this player ("sube" typed while looking away from the shaft)
        if (seen == 0 && workers.size() == 1) {
            return Optional.of(new Addressee(workers.get(0), "su trabajador"));
        }
        return Optional.empty();
    }

    /** A villager named in a message: how long the name was, where, whether said to it, and how far it is. */
    private record Named(VillagerEntityMCA villager, int length, int at, boolean vocative, double d2) {
        boolean better(Named other, @Nullable VillagerEntityMCA looked, @Nullable UUID partner) {
            if (length != other.length) {
                return length > other.length;
            }
            if (vocative != other.vocative) {
                return vocative;
            }
            if (at != other.at) {
                return at < other.at;
            }
            if ((villager == looked) != (other.villager == looked)) {
                return villager == looked;
            }
            boolean mine = villager.getUuid().equals(partner);
            if (mine != other.villager.getUuid().equals(partner)) {
                return mine;
            }
            if (villager.isBaby() != other.villager.isBaby()) {
                return !villager.isBaby();
            }
            return d2 < other.d2;
        }
    }

    /**
     * Whether {@code text} names {@code villager}: its whole name anywhere, as long as it is two words or said to
     * it or close by; or, within {@value #NAME_RANGE} blocks, the first name of a longer one.
     */
    @Nullable
    private static Named named(String text, VillagerEntityMCA villager, double d2) {
        String full = normalizeString(villager.getTrackedValue(VILLAGER_NAME)).trim().replaceAll("\\s+", " ");
        if (full.length() < 2) {
            return null;
        }
        boolean near = d2 < NAME_RANGE * NAME_RANGE;
        int at = wordAt(text, full);
        if (at >= 0) {
            boolean vocative = vocative(text, at, full.length());
            // a one-word name that is also a word ("Rosa", "Mina") counts from afar only when said to it
            if (full.indexOf(' ') > 0 || vocative || near) {
                return new Named(villager, full.length(), at, vocative, d2);
            }
            return null;
        }
        int space = full.indexOf(' ');
        if (near && space >= 2) {
            String first = full.substring(0, space);
            at = wordAt(text, first);
            if (at >= 0) {
                return new Named(villager, first.length(), at, vocative(text, at, first.length()), d2);
            }
        }
        return null;
    }

    /** Where {@code name} is said in {@code text} as whole words, or -1. */
    private static int wordAt(String text, String name) {
        Matcher m = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}])").matcher(text);
        return m.find() ? m.start() : -1;
    }

    private static final Pattern GREETING = Pattern.compile("(hola|oye|ey|hey|che|buenas|hello|hi|ok|okay|bueno|ya|y|eh|holi|ola)( (hola|oye|hey))?");

    /** Whether the name at {@code at} is said to someone: first (after a greeting), last, or set off by a comma. */
    private static boolean vocative(String text, int at, int length) {
        String before = text.substring(0, at).replaceAll("[^\\p{L}\\p{N} ,]", " ").trim();
        String after = text.substring(Math.min(text.length(), at + length)).trim();
        if (before.isEmpty() || before.endsWith(",") || GREETING.matcher(before.replace(",", " ").replaceAll("\\s+", " ").trim()).matches()) {
            return true;
        }
        return after.isEmpty() || after.matches("[,!?.:;¿¡].*");
    }

    /** No other player (not a spectator) is near enough for the chat to be meant for them. */
    private static boolean alone(MinecraftServer server, ServerPlayerEntity player) {
        for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
            if (other != player && !other.isSpectator() && other.getWorld() == player.getWorld()
                    && other.squaredDistanceTo(player) < OTHER_PLAYERS_RANGE * OTHER_PLAYERS_RANGE) {
                return false;
            }
        }
        return true;
    }

    /**
     * The villager under the player's crosshair, as the game finds what a player looks at: the first body the
     * line of sight reaches before any block, up to {@value #LOOK_RANGE} blocks; a little leeway when far.
     */
    @Nullable
    private static VillagerEntityMCA lookedAt(ServerPlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVec(1.0F).multiply(LOOK_RANGE));
        BlockHitResult block = player.getWorld().raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        double reach = block.getType() == HitResult.Type.MISS ? LOOK_RANGE * LOOK_RANGE : eye.squaredDistanceTo(block.getPos());
        VillagerEntityMCA best = null;
        double bestD2 = Double.MAX_VALUE;
        for (VillagerEntityMCA villager : WorldUtils.getCloseEntities(player.getWorld(), player, LOOK_RANGE, VillagerEntityMCA.class)) {
            if (!villager.isAlive()) {
                continue;
            }
            Box box = villager.getBoundingBox();
            double leeway = 0.3 + 0.015 * Math.sqrt(eye.squaredDistanceTo(box.getCenter()));
            Optional<Vec3d> hit = box.expand(leeway).raycast(eye, end);
            if (hit.isEmpty()) {
                continue;
            }
            double d2 = eye.squaredDistanceTo(hit.get());
            if (d2 <= reach + 0.5 && d2 < bestD2) {
                best = villager;
                bestD2 = d2;
            }
        }
        return best;
    }

    /**
     * The villager whose full name (first and last, accents ignored) appears in the message as whole words, in any
     * loaded world. With several, the longest name wins, then the one closest to {@code near}.
     */
    public static Optional<VillagerEntityMCA> findByFullName(MinecraftServer server, String msg, @Nullable Entity near) {
        String normalizedMsg = normalizeString(msg);
        VillagerEntityMCA best = null;
        int bestLength = 0;
        double bestDistance = Double.MAX_VALUE;
        for (ServerWorld world : server.getWorlds()) {
            for (Entity entity : world.iterateEntities()) {
                if (!(entity instanceof VillagerEntityMCA villager) || !villager.isAlive()) {
                    continue;
                }
                String name = normalizeString(villager.getTrackedValue(VILLAGER_NAME)).trim();
                if (name.isEmpty() || !Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}])").matcher(normalizedMsg).find()) {
                    continue;
                }
                double distance = near != null && near.getWorld() == world ? villager.squaredDistanceTo(near) : Double.MAX_VALUE / 2;
                if (name.length() > bestLength || name.length() == bestLength && distance < bestDistance) {
                    best = villager;
                    bestLength = name.length();
                    bestDistance = distance;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    // ------------------------------------------------------------------ saying it

    /** Says the answer now, as {@link #deliver(MinecraftServer, UUID, ServerPlayerEntity, UUID, VillagerEntityMCA, String, boolean)} (server thread). */
    public static void deliver(ServerPlayerEntity player, VillagerEntityMCA villager, String answer) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            deliver(server, player.getUuid(), player, villager.getUuid(), villager, answer, false);
        }
    }

    /**
     * Says the answer in chat like a player, "{@code <Inas> ...}", on the server thread: to the one who asked,
     * wherever they are, and to the players within {@link Config#tropelesChatHearRange} of either of them (to
     * everyone when the villager is listed as an online player). Both are looked up again by UUID, since a
     * respawn or a chunk reload makes new objects. An idle villager close by turns to the one who asked; one at
     * work never does.
     */
    public static void deliver(MinecraftServer server, UUID speakerId, ServerPlayerEntity fallbackSpeaker, UUID villagerId,
                               VillagerEntityMCA fallbackVillager, String answer, boolean clearBar) {
        if (!server.isOnThread()) {
            return;
        }
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(speakerId);
        ServerPlayerEntity speaker = online != null ? online : fallbackSpeaker;
        VillagerEntityMCA villager = fallbackVillager;
        for (ServerWorld world : server.getWorlds()) {
            if (world.getEntity(villagerId) instanceof VillagerEntityMCA found && found.isAlive()) {
                villager = found;
                break;
            }
        }
        boolean here = !villager.isRemoved();
        if (clearBar && speaker.networkHandler != null && !speaker.isRemoved()) {
            speaker.sendMessage(Text.empty(), true);
        }

        MutableText words = Text.literal(answer);
        boolean everyone = here && ProspectorPresence.isListed(villager)
                && ProspectorPresence.route(villager, words) == ProspectorPresence.Route.EVERYONE;
        List<ServerPlayerEntity> hearers = new ArrayList<>();
        // the one who asked, unless they left meanwhile (a respawned one was found again above)
        if (!speaker.isRemoved()) {
            hearers.add(speaker);
        }
        int hear = Config.getInstance().tropelesChatHearRange;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p == speaker || hearers.contains(p)) {
                continue;
            }
            if (everyone || hear < 0 || here && near(p, villager, hear) || near(p, speaker, hear)) {
                hearers.add(p);
            }
        }

        MutableText said = villager.transformMessage(words);
        for (ServerPlayerEntity p : hearers) {
            boolean faint = !everyone && p != speaker && p.getWorld() == villager.getWorld() && p.distanceTo(villager) > 10;
            villager.sayInChat(said.copy().formatted(faint ? Formatting.GRAY : Formatting.WHITE), p);
        }
        if (here) {
            villager.playSpeechEffect();
            // an idle villager turns to the one it answers; one at work keeps its head on the job
            if (villager.getWorld() == speaker.getWorld() && villager.distanceTo(speaker) < 16
                    && villager.getVillagerBrain().getCurrentJob() == Chore.NONE && !villager.isSleeping() && villager.canSee(speaker)) {
                villager.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(speaker, true), 60L);
                villager.getLookControl().lookAt(speaker, 30.0F, 30.0F);
            }
        }
        UUID id = villagerId;
        currentConversations.computeIfPresent(speakerId, (k, c) -> c.villagerUUID().equals(id) ? new OpenConversation(id, System.currentTimeMillis(), c.remote()) : c);
        MCA.LOGGER.info("[Tropeles] {} → {}{}: «{}»", villager.getName().getString(), speaker.getName().getString(),
                everyone ? " (y todo el servidor)" : hearers.size() > 1 ? " (y " + (hearers.size() - 1) + " más)" : "", answer);
    }

    private static boolean near(ServerPlayerEntity player, Entity to, int range) {
        return player.getWorld() == to.getWorld() && player.squaredDistanceTo(to) <= (double) range * range;
    }

    /**
     * Scans the local area in a {@value #VILLAGER_SEARCH_RANGE} block range of the player for a villager with searchName. <p>
     * searchName is {@link #normalizeString normalized}.
     * @param player ServerPlayerEntity object of the reference player
     * @param searchName Name of the villager
     * @return Optional containing the VillagerEntityMCA of the first villager with the matching name, empty Optional otherwise
     */
    public static Optional<VillagerEntityMCA> findVillagerInArea(ServerPlayerEntity player, String searchName) {
        List<VillagerEntityMCA> entities = WorldUtils.getCloseEntities(player.getWorld(), player, VILLAGER_SEARCH_RANGE, VillagerEntityMCA.class);

        // Get specific villager
        String normalizedSearchName = normalizeString(searchName);

        // Go through list, look for first match for name
        for (VillagerEntityMCA villager : entities) {
            String villagerName = normalizeString(villager.getTrackedValue(VILLAGER_NAME));
            if (normalizedSearchName.equals(villagerName)) {
                return Optional.of(villager);
            }
        }
        return Optional.empty();
    }

    /**
     * Normalizes the String according to NFD and removes any accents, umlauts, etc.
     * @param string The String to be normalized
     * @see <a href="https://unicode.org/reports/tr15/#Examples">Unicode Normalization Forms</a>
     */
    private static String normalizeString(String string) {
        return Normalizer.normalize(string, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Information needed to manage an open conversation.
     * @param villagerUUID UUID of the villager the conversation is with
     * @param lastMs When it was last talked to or last answered (wall clock, so a sped-up world does not shorten it)
     * @param remote Whether it began far away (by name), so it goes on wherever the two are
     */
    private record OpenConversation(UUID villagerUUID, long lastMs, boolean remote) {}

}
