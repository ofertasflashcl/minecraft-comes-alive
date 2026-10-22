package net.mca.entity.ai.chatAI;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mca.Config;
import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.body.Cofres;
import net.mca.entity.ai.chatAI.modules.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Pair;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Villager chat. Every message is first read by the villager's own mind ({@link MenteLocal}): a clear order is
 * carried out at once, on the server thread, whatever happens online, and the villager's own answer is ready
 * right then. Then the first free language model that is up ({@link Cerebros}) says the answer in the villager's
 * voice, knowing who it is, what it sees, carries and just did, and the last things it said to this player; it
 * may also pick an order the local mind did not recognise, and an order it promises in words is carried out too.
 * What it says about an order is always what really happened. When no model answers in time, the local mind's
 * answer is said, so the villager always talks back and always obeys.
 */
public class OpenAIChatAI implements ChatAIStrategy {
    /** How much of a conversation with one player a villager keeps: rough tokens, and turns. */
    private static final int MAX_MEMORY = 600;
    private static final int MAX_TURNS = 12;
    /** A conversation untouched this long is forgotten (real time: a sped-up world does not shorten it). */
    private static final long MAX_MEMORY_MS = 30 * 60_000L;
    /** The longest line a villager says in chat. */
    private static final int MAX_LINE = 280;

    /** Villager -> player -> what they said to each other, oldest first. */
    private static final Map<UUID, Map<UUID, Talk>> memory = new HashMap<>();

    /** Orders a model may write in other ways ("follow_player", "follow"). */
    private static final Map<String, String> COMMAND_ALIASES = Map.ofEntries(
            Map.entry("follow", "follow-player"), Map.entry("follow-me", "follow-player"), Map.entry("stay", "stay-here"),
            Map.entry("wait", "stay-here"), Map.entry("move", "move-freely"), Map.entry("wander", "move-freely"),
            Map.entry("mine", "start-mining"), Map.entry("mining", "start-mining"), Map.entry("go-mining", "start-mining"),
            Map.entry("chop", "start-chopping"), Map.entry("fish", "start-fishing"), Map.entry("harvest", "start-harvesting"),
            Map.entry("hunt", "start-hunting"), Map.entry("escort", "start-escort"), Map.entry("go-home", "try-go-home"),
            Map.entry("home", "try-go-home"), Map.entry("stop", "stop-working"), Map.entry("give", "give-item"),
            Map.entry("store", "store-items"), Map.entry("trade", "open-trade-window"), Map.entry("climb-out", "come-up"),
            Map.entry("eat", "eat-break")
    );

    public static String translate(String phrase) {
        return phrase.replace("_", " ").toLowerCase(Locale.ROOT).replace("mca.", "");
    }

    /** What a mind answered: the words, and maybe an order with its argument. */
    public record StructuredResponse(@Nullable String message, String optionalCommand, String argument) {
    }

    /**
     * Everything read from the world, on the server thread, before any mind is asked. {@code local} is the
     * villager's own answer, true to whatever order was just carried out; {@code done} tells whether that order
     * really took effect (not refused, not already under way, not impossible).
     */
    private record Prepared(String playerName, String villagerName, List<Pair<String, String>> history,
                            MenteLocal.Intent intent, @Nullable String outcome, boolean refused, boolean done, String open,
                            String inHouse, List<Cerebros.Mind> minds, String local, @Nullable String image) {
    }

    /**
     * How an order a model chose went: done, or why not in the villager's words, what was handed or stored, and the
     * order's own account of it ({@code how}, plain English, when it gave one).
     */
    private record Acted(boolean done, @Nullable String failLine, @Nullable Acciones.Given given, @Nullable Cofres.Store stored,
                         @Nullable String how) {
    }

    /** One conversation between a villager and one player. */
    private static final class Talk {
        final List<Pair<String, String>> turns = new ArrayList<>();
        long lastMs;
    }

    public static String verify(String encodedURL) {
        try {
            // receive
            HttpURLConnection con = (HttpURLConnection) (new URL(encodedURL)).openConnection();
            con.setRequestProperty("Accept-Charset", StandardCharsets.UTF_8.toString());
            InputStream response = con.getInputStream();
            String body = IOUtils.toString(response, StandardCharsets.UTF_8);

            // parse json
            JsonObject map = JsonParser.parseString(body).getAsJsonObject();
            return map.has("answer") ? map.get("answer").getAsString().trim().replace("\n", " ") : "";
        } catch (Exception e) {
            MCA.LOGGER.error(e);
            return "error";
        }
    }

    /** Answers within the chat budget ({@link Config#tropelesChatBudgetMs}); never empty. Slow: never on the server thread with minds online. */
    public Optional<String> answer(ServerPlayerEntity player, VillagerEntityMCA villager, String msg) {
        return answer(player, villager, msg, System.currentTimeMillis() + Math.max(1_000L, Config.getInstance().tropelesChatBudgetMs), null);
    }

    /**
     * Answers with the online minds done by {@code deadline} (wall clock, ms). {@code early}, when given, gets the
     * villager's own answer as soon as the world was read (and any order carried out), so the caller can say it
     * if the minds take too long; the caller then remembers what was really said ({@link #remember}). Never empty.
     */
    public Optional<String> answer(ServerPlayerEntity player, VillagerEntityMCA villager, String msg, long deadline, @Nullable Consumer<String> early) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return Optional.of(MenteLocal.shrug(MenteLocal.spanish(msg)));
        }
        // the in-game checks keep this thread offline; the world is read on the server thread, so it is told
        boolean offline = Cerebros.OFFLINE.get();
        Prepared prep = onServer(server, () -> prepare(player, villager, msg, offline));
        if (prep == null) {
            String line = lastResort(server, player, villager, msg);
            if (early == null) {
                remember(villager.getUuid(), player.getUuid(), msg, line);
            }
            return Optional.of(line);
        }
        if (early != null) {
            early.accept(prep.local());
        }

        String line = null;
        if (prep.intent().order() && !prep.refused() && !prep.done()) {
            // an order it could not carry out, or was already doing: its own words say exactly why, and no mind
            // may dress that up as a yes
            MCA.LOGGER.info("[Tropeles] {} no pudo con la orden '{}': contesta su propia mente", prep.villagerName(), prep.intent().command());
        } else {
            try {
                Cerebros.Reply reply = Cerebros.ask(prep.minds(), mind -> mind.inHouse() ? prep.inHouse() : prep.open(),
                        prep.history(), msg, prep.playerName(), prep.villagerName(), villager.getUuid(), deadline, prep.image());
                if (reply != null) {
                    StructuredResponse said = structured(reply.content(), prep.villagerName());
                    line = speak(server, player, villager, prep, said);
                    MCA.LOGGER.info("[Tropeles] {} pensó con {} en {} ms (orden '{}'){}", prep.villagerName(), reply.mind(), reply.millis(),
                            said.optionalCommand(), reply.saw() ? ", mirando la foto de lo que ve" : "");
                }
            } catch (RuntimeException e) {
                MCA.LOGGER.warn("[Tropeles] las mentes en línea fallaron, contesta la mente local", e);
            }
        }

        if (line == null || line.isBlank()) {
            Cerebros.answeredOffline(villager.getUuid());
            line = prep.local();
        }
        if (line == null || line.isBlank()) {
            line = MenteLocal.shrug(prep.intent().spanish());
        }
        line = clip(line);
        if (early == null) {
            remember(villager.getUuid(), player.getUuid(), msg, line);
        }
        return Optional.of(line);
    }

    /**
     * The words to say from what a model answered, keeping them true: an order it chose or promised in words is
     * carried out, one that failed is told as it really went, and words that say the opposite of what happened
     * (agreeing to an order it refused, or in the wrong language) give way to the villager's own answer.
     */
    @Nullable
    private static String speak(MinecraftServer server, ServerPlayerEntity player, VillagerEntityMCA villager, Prepared prep, StructuredResponse said) {
        boolean es = prep.intent().spanish();
        String message = said.message() == null ? null : clean(said.message());
        if (message != null && message.isBlank()) {
            message = null;
        }
        if (message != null && MenteLocal.clearlyNot(message, es)) {
            MCA.LOGGER.info("[Tropeles] {} contestó en otro idioma, habla su propia mente: «{}»", prep.villagerName(), message);
            message = null;
        }
        if (prep.refused() || prep.intent().order()) {
            // the order was already handled here: the model only finds the words, and may not claim another one
            // ("¡Te sigo!" after a refusal, or "me quedo aquí" when it really went mining)
            String promised = message == null ? null : MenteLocal.promise(message);
            if (promised != null && (prep.refused() || !sameOrder(promised, prep.intent().command()))) {
                MCA.LOGGER.info("[Tropeles] {} prometió '{}' pero hizo '{}': habla su propia mente", prep.villagerName(), promised,
                        prep.refused() ? "nada" : prep.intent().command());
                return prep.local();
            }
            return message;
        }

        String command = command(said.optionalCommand());
        if (command.isEmpty() && message != null) {
            // a promise in words is a command: "¡Claro, te sigo!" with no order still follows
            String promised = MenteLocal.promise(message);
            if (promised != null) {
                MCA.LOGGER.info("[Tropeles] {} prometió '{}' sin ponerlo como orden; lo hace", prep.villagerName(), promised);
                command = promised;
            }
        }
        if (command.isEmpty()) {
            return message;
        }
        String chosen = command;
        Acted acted = onServer(server, () -> act(player, villager, chosen, said.argument(), es));
        if (acted == null) {
            return message;
        }
        if (acted.failLine() != null) {
            return acted.failLine(); // it could not: say so, never what the model hoped
        }
        if (message != null) {
            String words = MenteLocal.promise(message);
            if (words == null || sameOrder(words, chosen)) {
                return message;
            }
            // "¡Te sigo!" with stay-here as the order: say what it really did
            MCA.LOGGER.info("[Tropeles] {} dijo que haría '{}' pero la orden fue '{}': habla su propia mente", prep.villagerName(), words, chosen);
        }
        String outcome = acted.how() != null ? acted.how() : acted.done() ? "done" : null;
        MenteLocal.Intent intent = new MenteLocal.Intent(chosen, said.argument(), null, es);
        return onServer(server, () -> MenteLocal.answer(player, villager, intent, outcome, acted.given(), acted.stored(), false));
    }

    /** Whether words promising {@code promised} fit the order really carried out: climbing out of a mine is going home. */
    private static boolean sameOrder(String promised, @Nullable String done) {
        if (promised.equals(done)) {
            return true;
        }
        Set<String> home = Set.of("come-up", "try-go-home", "eat-break");
        return home.contains(promised) && done != null && home.contains(done);
    }

    /** The order's name as the command list spells it, or "" when it is none of them. */
    private static String command(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String name = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        name = COMMAND_ALIASES.getOrDefault(name, name);
        for (TriggerCommandInfo info : TriggerCommandInfos.triggerCommands) {
            if (info.command.equals(name)) {
                return name;
            }
        }
        return "";
    }

    /**
     * When nothing else could answer: the villager's own words to a question, or a plain "say again" to an order,
     * so it never confirms one it may not have carried out.
     */
    public static String lastResort(MinecraftServer server, ServerPlayerEntity player, VillagerEntityMCA villager, String msg) {
        String line = onServer(server, () -> {
            Vistazo.fresh(villager);
            MenteLocal.Intent intent = MenteLocal.read(msg, villager.getName().getString());
            return intent.order() ? MenteLocal.shrug(intent.spanish()) : MenteLocal.answer(player, villager, intent, null, null, null, false);
        });
        return line == null || line.isBlank() ? MenteLocal.shrug(MenteLocal.spanish(msg)) : clip(line);
    }

    /**
     * Runs {@code task} on the server thread, now when already on it, and waits for it; null when it failed.
     * Never call this with minds online from the server thread itself: it would block the game.
     */
    @Nullable
    private static <T> T onServer(MinecraftServer server, Supplier<T> task) {
        try {
            if (server.isOnThread()) {
                return task.get();
            }
            if (!server.isRunning()) {
                // shutting down: a stopped server's submit() runs the task right here, off its thread, while the
                // worlds are being saved; nobody is left to answer anyway
                return null;
            }
            return server.submit(task).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            MCA.LOGGER.error("[Tropeles] no se pudo leer el mundo para la charla", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ on the server thread

    private static Prepared prepare(ServerPlayerEntity player, VillagerEntityMCA villager, String msg, boolean offline) {
        Config config = Config.getInstance();
        // fresh eyes first (at most every few seconds): an idle villager has just looked around before it answers
        Vistazo.fresh(villager);
        String playerName = player.getName().getString();
        String villagerName = villager.getName().getString();
        List<Pair<String, String>> history = history(villager.getUuid(), player.getUuid());

        // the language: what the words say (its own name aside); when they do not say ("ok", "sube"), the game's, else Spanish
        boolean es = MenteLocal.speaksSpanish(msg, villagerName);

        // 1. the villager's own mind reads the message, without its name in it; a clear order is carried out right now
        MenteLocal.Intent read = MenteLocal.read(msg, villagerName);
        MenteLocal.Intent intent = resolve(new MenteLocal.Intent(read.command(), read.argument(), read.question(), es), villager, player);
        boolean refused = false;
        boolean done = false;
        String outcome = null;
        Acciones.Given given = null;
        Cofres.Store stored = null;
        if (intent.order()) {
            try {
                int hearts = villager.getVillagerBrain().getMemoriesForPlayer(player).getHearts();
                if (hearts < -25 && !"open-trade-window".equals(intent.command())) {
                    refused = true;
                } else if ("give-item".equals(intent.command())) {
                    given = Acciones.hand(player, villager, intent.argument());
                    outcome = given.english(playerName);
                    done = given.status() == Acciones.Status.GAVE;
                } else if ("store-items".equals(intent.command())) {
                    if (villager.getInventory().isEmpty()) {
                        outcome = "You have nothing in your bag to store.";
                    } else {
                        stored = Cofres.orderStore(villager, player);
                        outcome = switch (stored) {
                            case WALKING -> "You are walking to your chest to store your things.";
                            case PLACED -> "You set your chest down here and are storing your things.";
                            default -> "You have no chest nearby and none in your bag, so you keep your things.";
                        };
                        done = stored != Cofres.Store.NONE;
                    }
                } else {
                    Optional<TriggerCommandInfo> command = TriggerCommandInfos.findCommand(intent.command(), player, villager);
                    if (command.isPresent()) {
                        String how = command.get().run(player, villager, intent.argument());
                        outcome = how != null ? how : "You did it: " + command.get().description + ".";
                        // "You have no home to go to here": carried out, but nothing happened
                        done = how == null || !how.startsWith("You have no");
                    }
                }
            } catch (RuntimeException e) {
                MCA.LOGGER.error("[Tropeles] {} no pudo cumplir la orden '{}'", villagerName, intent.command(), e);
                outcome = null;
                done = false;
            }
            MCA.LOGGER.info("[Tropeles] {} entendió la orden '{}' ({}): {}", villagerName, intent.command(), intent.argument(),
                    refused ? "se niega" : outcome == null ? "ya lo estaba haciendo" : outcome);
        }

        // its own answer, ready now: said when no model answers in time
        String local;
        try {
            local = MenteLocal.answer(player, villager, intent, outcome, given, stored, refused);
        } catch (RuntimeException e) {
            MCA.LOGGER.error("[Tropeles] la mente local de {} falló", villagerName, e);
            local = MenteLocal.shrug(es);
        }
        if (local == null || local.isBlank()) {
            local = MenteLocal.shrug(es);
        }

        // 2. who it is, and what it knows about the player and the world around it
        StringBuilder who = new StringBuilder();
        module(who, "personalidad", () -> {
            List<String> input = new LinkedList<>();
            PersonalityModule.apply(input, villager, player);
            TraitsModule.apply(input, villager, player);
            return input;
        });
        module(who, "relación", () -> {
            List<String> input = new LinkedList<>();
            RelationModule.apply(input, villager, player);
            PlayerModule.apply(input, villager, player);
            return input;
        });
        StringBuilder world = new StringBuilder();
        module(world, "aldea", () -> {
            List<String> input = new LinkedList<>();
            VillageModule.apply(input, villager, player);
            EnvironmentModule.apply(input, villager, player);
            return input;
        });
        module(world, "percepción", () -> {
            List<String> input = new LinkedList<>();
            PerceptionModule.apply(input, villager, player);
            return input;
        });
        if (villager.getWorld() != player.getWorld()) {
            world.append("$player is in another dimension and is talking to you from afar; you cannot see them. ");
        } else if (villager.distanceTo(player) > 16) {
            world.append("$player is about ").append((int) villager.distanceTo(player)).append(" blocks away and is talking to you from afar. ");
        } else {
            world.append("$player is right here with you, ").append(Math.max(1, (int) villager.distanceTo(player))).append(" blocks away. ");
        }
        world.append(SIGHT);
        String context = ("\nWho you are: " + who + "\nWhere you are and what you see: " + world)
                .replace("$player", playerName).replace("$villager", villagerName);

        String speech = "\nWrite your message in " + (es ? "Spanish" : "English") + ", the language " + playerName + " writes in"
                + (es ? " (a natural, casual Spanish like a Chilean player's is fine)" : "") + ". "
                + "One or two short sentences, at most about 25 words, like a line in a game chat. ";

        StringBuilder orders = new StringBuilder("\n\n");
        if (refused) {
            orders.append(playerName).append(" just asked you to do something, but you refuse: you dislike them. Say no in your own words. Use no command.\n");
        } else if (intent.order()) {
            orders.append("You have already done what ").append(playerName).append(" just asked. ").append(outcome)
                    .append(" Tell them in your own words, matching what really happened. Use no command.\n");
        }
        List<TriggerCommandInfo> valid = intent.order() || refused ? List.of() : TriggerCommandInfos.triggerCommands.stream()
                .filter(c -> c.isActive == null || c.isActive.test(player, villager))
                .toList();
        orders.append("Reply with exactly one JSON object and nothing else: {\"message\": \"what you say\", \"optionalCommand\": \"\", \"argument\": \"\"}\n");
        if (!valid.isEmpty()) {
            orders.append("These are the things you can actually do. When ").append(playerName)
                    .append(" asks you for one of them or clearly wants it, do it gladly: put it in optionalCommand (a new request replaces an older order). Otherwise leave it empty:\n");
            for (TriggerCommandInfo command : valid) {
                orders.append("  * ").append(command.command).append(": ").append(command.description);
                if (command.argument != null) {
                    orders.append(" (argument: ").append(command.argument).append(")");
                }
                orders.append("\n");
            }
            orders.append("If your message says you will do one of these things, optionalCommand must hold it, or you will not really do it. ")
                    .append("Never claim to do, have or give something that is not true or not on this list.\n");
            valid.stream().filter(c -> c.command.equals("follow-player")).findFirst().ifPresent(c ->
                    orders.append("Example: ").append(playerName).append(" writes \"¿vienes conmigo al río?\" and you answer ")
                            .append("{\"message\": \"¡Claro, vamos!\", \"optionalCommand\": \"follow-player\", \"argument\": \"\"}\n"));
        }

        // MCA's own service adds the persona itself; the others get one
        StringBuilder inHouse = new StringBuilder();
        inHouse.append("[world_id:").append(player.getServerWorld().getSeed()).append("]");
        inHouse.append("[player_id:").append(player.getUuid()).append("]");
        inHouse.append("[character_id:").append(villager.getUuid()).append("]");
        if (config.villagerChatAIUseLongTermMemory) {
            inHouse.append("[use_memory:true]");
        }
        if (config.villagerChatAIUseSharedLongTermMemory) {
            inHouse.append("[shared_memory:true]");
        }
        if (!config.villagerChatAISystemPrompt.isEmpty()) {
            inHouse.append(config.villagerChatAISystemPrompt).append("\n");
        }
        inHouse.append(context).append(speech).append(orders);

        String persona = !config.villagerChatAISystemPrompt.isEmpty() ? config.villagerChatAISystemPrompt
                : "You are " + villagerName + ", a person who lives in this world, fully immersed in it. You are not an assistant, "
                + "you never mention AI, models or games, and you do not know you are in one. You chat with " + playerName
                + " the way another player would in the game chat: natural, in character, with your own personality and mood; "
                + "you can be warm, funny, sarcastic or blunt. No emojis, no lists, no narrating actions between asterisks. "
                + "You only know what you have seen, carry and done yourself: never invent items, places or deeds, and when you "
                + "do not know something, say so. Keep to what was said before in this conversation.";
        List<Cerebros.Mind> minds = Cerebros.chain(playerName);
        // a question about what it sees: a picture of its view for the minds that can look at one
        String image = !offline && Vistazo.aboutSight(intent) && Cerebros.anyCanLook(minds) ? Vistazo.foto(villager) : null;
        String open = persona + "\n" + context + speech + orders;

        return new Prepared(playerName, villagerName, history, intent, outcome, refused, done, open, inHouse.toString(),
                minds, local, image);
    }

    /**
     * How to talk about the surroundings: only what the scene above lists, with its directions, and an honest "I
     * don't see any" for the rest. About 60 tokens: free models have small contexts.
     */
    private static final String SIGHT = "\nWhen you talk about your surroundings, mention only what is listed above, with its direction "
            + "as given (left, right, ahead, behind: in Spanish izquierda, derecha, delante, detrás) and its rough distance. "
            + "If asked about something not listed, say honestly that you do not see any from here. ";

    /** Adds what one module knows; a module that fails is logged and left out, the rest still count. */
    private static void module(StringBuilder into, String name, Supplier<List<String>> module) {
        try {
            for (String s : module.get()) {
                into.append(s);
            }
        } catch (RuntimeException e) {
            MCA.LOGGER.warn("[Tropeles] el módulo de charla '{}' falló", name, e);
        }
    }

    /**
     * What a short order means for this villager: a bare "stop" stops the work when working, otherwise it stands
     * still; "sube" to one that is not mining but somewhere below the player is "come up here, to me".
     */
    private static MenteLocal.Intent resolve(MenteLocal.Intent intent, VillagerEntityMCA villager, ServerPlayerEntity player) {
        if ("come-up".equals(intent.command()) && villager.getVillagerBrain().getCurrentJob() != Chore.PROSPECT
                && villager.getWorld() == player.getWorld() && villager.getY() < player.getY() - 1.5) {
            return new MenteLocal.Intent("follow-player", "", null, intent.spanish());
        }
        if (!"stop".equals(intent.command())) {
            return intent;
        }
        String command = villager.getVillagerBrain().getCurrentJob() != Chore.NONE ? "stop-working" : "stay-here";
        return new MenteLocal.Intent(command, "", null, intent.spanish());
    }

    /**
     * Carries out an order a language model chose. Not done with no reason to give means it was already doing
     * it (or it is not possible right now); a reason is the villager's own words for why not.
     */
    private static Acted act(ServerPlayerEntity player, VillagerEntityMCA villager, String name, String argument, boolean spanish) {
        try {
            if (name.equals("give-item")) {
                Acciones.Given given = Acciones.hand(player, villager, argument);
                MCA.LOGGER.info("[Tropeles] {} da '{}': {}", villager.getName().getString(), argument, given.status());
                boolean gave = given.status() == Acciones.Status.GAVE;
                return new Acted(gave, gave ? null : MenteLocal.answer(player, villager, new MenteLocal.Intent("give-item", argument, null, spanish), "", given, null, false),
                        given, null, null);
            }
            if (name.equals("store-items")) {
                if (villager.getInventory().isEmpty()) {
                    return new Acted(false, MenteLocal.answer(player, villager, new MenteLocal.Intent("store-items", "", null, spanish), "", null, null, false), null, null, null);
                }
                Cofres.Store stored = Cofres.orderStore(villager, player);
                boolean done = stored != Cofres.Store.NONE;
                return new Acted(done, done ? null : MenteLocal.answer(player, villager, new MenteLocal.Intent("store-items", "", null, spanish), "", null, stored, false),
                        null, stored, null);
            }
            Optional<TriggerCommandInfo> command = TriggerCommandInfos.findCommand(name, player, villager);
            if (command.isPresent()) {
                String how = command.get().run(player, villager, argument);
                MCA.LOGGER.info("[Tropeles] {} hace '{}' ({}): {}", villager.getName().getString(), name, argument, how == null ? "hecho" : how);
                if (how != null && how.startsWith("You have no")) {
                    // carried out, but nothing came of it ("no home to go to here"): never let the model say it went
                    return new Acted(false, MenteLocal.answer(player, villager, new MenteLocal.Intent(name, argument, null, spanish), how, null, null, false),
                            null, null, how);
                }
                return new Acted(true, null, null, null, how);
            }
            MCA.LOGGER.info("[Tropeles] {} no hace '{}': ya lo estaba haciendo o no puede ahora", villager.getName().getString(), name);
            if (name.equals("come-up") || name.equals("eat-break")) {
                // only a miner climbs out of a mine: "¡Ya subo!" from one that is not in any would be a lie
                return new Acted(false, MenteLocal.answer(player, villager, new MenteLocal.Intent(name, "", null, spanish), null, null, null, false), null, null, null);
            }
            return new Acted(false, null, null, null, null);
        } catch (RuntimeException e) {
            MCA.LOGGER.error("[Tropeles] {} no pudo hacer '{}'", villager.getName().getString(), name, e);
            return new Acted(false, MenteLocal.shrug(spanish), null, null, null);
        }
    }

    // ------------------------------------------------------------------ the conversation so far

    /** What this villager and this player said to each other lately, oldest first; forgotten after a long silence. */
    private static List<Pair<String, String>> history(UUID villager, UUID player) {
        synchronized (memory) {
            Map<UUID, Talk> talks = memory.get(villager);
            Talk talk = talks == null ? null : talks.get(player);
            if (talk == null) {
                return new ArrayList<>();
            }
            if (System.currentTimeMillis() - talk.lastMs > MAX_MEMORY_MS) {
                talks.remove(player);
                return new ArrayList<>();
            }
            List<Pair<String, String>> past = talk.turns;
            while (!past.isEmpty() && (past.size() > MAX_TURNS || past.stream().mapToInt(v -> v.getRight().length() / 4).sum() > MAX_MEMORY)) {
                past.remove(0);
            }
            return new ArrayList<>(past);
        }
    }

    /** Keeps one exchange, what the player said and what the villager really answered. */
    public static void remember(UUID villager, UUID player, String msg, String line) {
        synchronized (memory) {
            Talk talk = memory.computeIfAbsent(villager, key -> new HashMap<>()).computeIfAbsent(player, key -> new Talk());
            talk.turns.add(new Pair<>("user", msg));
            talk.turns.add(new Pair<>("assistant", line));
            talk.lastMs = System.currentTimeMillis();
        }
    }

    // ------------------------------------------------------------------ reading what a mind said

    /**
     * The words and the order out of whatever a model wrote: the JSON asked for, JSON with other key names,
     * JSON inside code fences or after a "thinking" part, or just plain words.
     */
    public static StructuredResponse structured(String content, String villagerName) {
        String text = content.replaceAll("(?s)<think>.*?</think>", "").replace("```json", "").replace("```", "").trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonElement parsed = JsonParser.parseString(text.substring(start, end + 1));
                if (parsed.isJsonObject()) {
                    JsonObject o = parsed.getAsJsonObject();
                    String message = field(o, "message", "reply", "text", "answer", "say", "response");
                    String command = field(o, "optionalCommand", "command", "action", "optional_command");
                    String argument = field(o, "argument", "arg", "item", "ore", "target");
                    if (message != null || command != null) {
                        return new StructuredResponse(tidy(message, villagerName), command == null ? "" : command.trim(), argument == null ? "" : argument.trim());
                    }
                }
            } catch (RuntimeException ignored) {
                // not JSON after all: plain words
            }
        }
        return new StructuredResponse(tidy(text, villagerName), "", "");
    }

    @Nullable
    private static String field(JsonObject o, String... keys) {
        for (String key : keys) {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                String value = o.get(key).getAsString();
                if (!value.isBlank() && !value.equalsIgnoreCase("null") && !value.equalsIgnoreCase("none")) {
                    return value;
                }
            }
        }
        return null;
    }

    @Nullable
    static String tidy(@Nullable String answer, String villagerName) {
        if (answer == null) {
            return null;
        }
        String text = answer.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        // "<Name> hello", the way its lines show in chat
        text = text.replaceFirst("^<[^<>]{1,40}>\\s*", "");
        // "Name: hello" or "**Name**: hello"
        String plain = text.replace("*", "");
        if (plain.regionMatches(true, 0, villagerName + ":", 0, villagerName.length() + 1)) {
            text = plain.substring(villagerName.length() + 1).trim();
        }
        if (text.length() > 1 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.length() > 400) {
            int cut = Math.max(text.lastIndexOf(". ", 400), Math.max(text.lastIndexOf("! ", 400), text.lastIndexOf("? ", 400)));
            text = cut > 40 ? text.substring(0, cut + 1) : text.substring(0, 400);
        }
        return text;
    }

    /** A model's line made fit for chat: no acted-out "*smiles*", no emojis the game font cannot draw. */
    private static String clean(String line) {
        return line.replaceAll("\\*[^*]{1,60}\\*", " ")
                .replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]", "")
                .replaceAll("\\s+", " ").trim();
    }

    /** At most {@link #MAX_LINE} characters, cut after a sentence when it can be. */
    private static String clip(String line) {
        String text = line.trim();
        if (text.length() <= MAX_LINE) {
            return text;
        }
        int cut = Math.max(text.lastIndexOf(". ", MAX_LINE), Math.max(text.lastIndexOf("! ", MAX_LINE), text.lastIndexOf("? ", MAX_LINE)));
        return cut > 40 ? text.substring(0, cut + 1) : text.substring(0, MAX_LINE - 1) + "…";
    }

    /** For the in-game checks: a fresh start, as if it never talked to anyone. */
    public static void forget(UUID villager) {
        synchronized (memory) {
            memory.remove(villager);
        }
    }

}
