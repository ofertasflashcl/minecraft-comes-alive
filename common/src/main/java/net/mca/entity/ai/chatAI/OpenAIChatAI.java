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
import java.util.function.Supplier;

/**
 * Villager chat. Every message is first read by the villager's own mind ({@link MenteLocal}): a clear order is
 * carried out at once, on the server thread, whatever happens online. Then the first free language model that
 * is up ({@link Cerebros}) says the answer, knowing what the villager sees, carries and just did; it may also
 * pick an order the local mind did not recognise. When no model answers, the local mind answers itself, so the
 * villager always talks back and always obeys.
 */
public class OpenAIChatAI implements ChatAIStrategy {
    private static final int MAX_MEMORY = 500;
    private static final int MAX_MEMORY_TIME = 20 * 60 * 45;

    private static final Map<UUID, List<Pair<String, String>>> memory = new HashMap<>();
    private static final Map<UUID, Long> lastInteractions = new HashMap<>();

    public static String translate(String phrase) {
        return phrase.replace("_", " ").toLowerCase(Locale.ROOT).replace("mca.", "");
    }

    /** What a mind answered: the words, and maybe an order with its argument. */
    public record StructuredResponse(@Nullable String message, String optionalCommand, String argument) {
    }

    /** Everything read from the world, on the server thread, before any mind is asked. */
    private record Prepared(String playerName, String villagerName, List<Pair<String, String>> history,
                            MenteLocal.Intent intent, @Nullable String outcome, @Nullable Acciones.Given given,
                            @Nullable Cofres.Store stored, boolean refused, String open, String inHouse,
                            List<Cerebros.Mind> minds) {
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

    public Optional<String> answer(ServerPlayerEntity player, VillagerEntityMCA villager, String msg) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return Optional.empty();
        }
        Prepared prep = onServer(server, () -> prepare(player, villager, msg));
        if (prep == null) {
            return Optional.empty();
        }

        String line = null;
        try {
            Cerebros.Reply reply = Cerebros.ask(prep.minds(), mind -> mind.inHouse() ? prep.inHouse() : prep.open(),
                    prep.history(), msg, prep.playerName(), prep.villagerName(), villager.getUuid());
            if (reply != null) {
                StructuredResponse said = structured(reply.content(), prep.villagerName());
                if (said.message() != null && !said.message().isBlank()) {
                    line = said.message();
                    MCA.LOGGER.info("[Tropeles] {} pensó con {} en {} ms", prep.villagerName(), reply.mind(), reply.millis());
                    // an order only the language model understood
                    if (!prep.intent().order() && !prep.refused() && !said.optionalCommand().isBlank()) {
                        String extra = onServer(server, () -> act(player, villager, said, prep.intent().spanish()));
                        if (extra != null && !extra.isBlank()) {
                            line = line + " " + extra;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            MCA.LOGGER.warn("[Tropeles] las mentes en línea fallaron, contesta la mente local", e);
        }

        if (line == null) {
            Cerebros.answeredOffline(villager.getUuid());
            line = onServer(server, () -> MenteLocal.answer(player, villager, prep.intent(), prep.outcome(), prep.given(), prep.stored(), prep.refused()));
        }
        if (line != null) {
            remember(villager.getUuid(), msg, line);
        }
        return Optional.ofNullable(line);
    }

    /** Runs {@code task} on the server thread, now when already on it, and waits for it. */
    @Nullable
    private static <T> T onServer(MinecraftServer server, Supplier<T> task) {
        if (server.isOnThread()) {
            return task.get();
        }
        try {
            return server.submit(task).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            MCA.LOGGER.error("[Tropeles] no se pudo leer el mundo para la charla", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ on the server thread

    private static Prepared prepare(ServerPlayerEntity player, VillagerEntityMCA villager, String msg) {
        Config config = Config.getInstance();
        String playerName = player.getName().getString();
        String villagerName = villager.getName().getString();
        List<Pair<String, String>> history = history(villager);

        // 1. the villager's own mind reads the message; a clear order is carried out right now
        MenteLocal.Intent intent = resolve(MenteLocal.read(msg), villager);
        boolean refused = false;
        String outcome = null;
        Acciones.Given given = null;
        Cofres.Store stored = null;
        if (intent.order()) {
            int hearts = villager.getVillagerBrain().getMemoriesForPlayer(player).getHearts();
            if (hearts < -25 && !"open-trade-window".equals(intent.command())) {
                refused = true;
            } else if ("give-item".equals(intent.command())) {
                given = Acciones.hand(player, villager, intent.argument());
                outcome = given.english(playerName);
            } else if ("store-items".equals(intent.command())) {
                if (villager.getInventory().isEmpty()) {
                    outcome = "You have nothing in your bag to store.";
                } else {
                    stored = Cofres.orderStore(villager, player);
                    outcome = switch (stored) {
                        case WALKING -> "You are walking to your chest to store your things.";
                        case PLACED -> "You set your chest down here and are storing your things.";
                        case NONE -> "You have no chest nearby and none in your bag, so you keep your things.";
                    };
                }
            } else {
                Optional<TriggerCommandInfo> command = TriggerCommandInfos.findCommand(intent.command(), player, villager);
                if (command.isPresent()) {
                    String how = command.get().run(player, villager, intent.argument());
                    outcome = how != null ? how : "You did it: " + command.get().description + ".";
                }
            }
            MCA.LOGGER.info("[Tropeles] {} entendió la orden '{}' ({}): {}", villagerName, intent.command(), intent.argument(),
                    refused ? "se niega" : outcome == null ? "ya lo estaba haciendo" : outcome);
        }

        // 2. what it knows about itself, the player and the world around it
        List<String> input = new LinkedList<>();
        PersonalityModule.apply(input, villager, player);
        TraitsModule.apply(input, villager, player);
        RelationModule.apply(input, villager, player);
        VillageModule.apply(input, villager, player);
        EnvironmentModule.apply(input, villager, player);
        PlayerModule.apply(input, villager, player);
        PerceptionModule.apply(input, villager, player);
        StringBuilder context = new StringBuilder();
        for (String s : input) {
            context.append(s.replace("$player", playerName).replace("$villager", villagerName));
        }
        if (villager.getWorld() != player.getWorld()) {
            context.append("\n").append(playerName).append(" is in another dimension and is talking to you from afar, you cannot see them. ");
        } else if (villager.distanceTo(player) > 16) {
            context.append("\n").append(playerName).append(" is about ").append((int) villager.distanceTo(player)).append(" blocks away and is talking to you from afar. ");
        }
        context.append("\nAnswer in the same language ").append(playerName).append(" writes in");
        context.append(MCA.language != null ? " (" + MCA.language + " when unsure). " : " (Spanish when unsure). ");

        StringBuilder orders = new StringBuilder("\n\n");
        if (refused) {
            orders.append(playerName).append(" just asked you to do something, but you refuse: you dislike them. Say no in your own words.\n");
        } else if (intent.order() && outcome == null) {
            orders.append(playerName).append(" just asked you to do something you were already doing. Tell them so in your own words.\n");
        } else if (intent.order()) {
            orders.append("You have already done what ").append(playerName).append(" just asked. ").append(outcome)
                    .append(" Tell them in your own words, matching what really happened. Use no command.\n");
        }
        List<TriggerCommandInfo> valid = intent.order() ? List.of() : TriggerCommandInfos.triggerCommands.stream()
                .filter(c -> c.isActive == null || c.isActive.test(player, villager))
                .toList();
        orders.append("Reply with exactly one JSON object and nothing else: {\"message\": \"what you say\", \"optionalCommand\": \"\", \"argument\": \"\"}\n");
        if (!valid.isEmpty()) {
            orders.append("These are the things you can actually do. Put one in optionalCommand only when ").append(playerName)
                    .append(" asks for it or clearly wants it, otherwise leave it empty:\n");
            for (TriggerCommandInfo command : valid) {
                orders.append("  * ").append(command.command).append(": ").append(command.description);
                if (command.argument != null) {
                    orders.append(" (argument: ").append(command.argument).append(")");
                }
                orders.append("\n");
            }
            orders.append("Never claim to do something that is not on this list.");
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
        inHouse.append(context).append(orders);

        String persona = !config.villagerChatAISystemPrompt.isEmpty() ? config.villagerChatAISystemPrompt
                : "You are " + villagerName + ", a person living in this world, fully immersed in it. You are not an assistant and you do not know you are in a game. "
                + "You talk like a real person in a game chat: one or two short sentences, natural and in character; you can be warm, funny, sarcastic or blunt. "
                + "No emojis, no narrating actions between asterisks, no lists. You only know what you have seen yourself.";
        String open = persona + "\n" + context + orders;

        return new Prepared(playerName, villagerName, history, intent, outcome, given, stored, refused, open, inHouse.toString(),
                Cerebros.chain(playerName));
    }

    /** A bare "stop": stop working when working, otherwise stand still. */
    private static MenteLocal.Intent resolve(MenteLocal.Intent intent, VillagerEntityMCA villager) {
        if (!"stop".equals(intent.command())) {
            return intent;
        }
        String command = villager.getVillagerBrain().getCurrentJob() != Chore.NONE ? "stop-working" : "stay-here";
        return new MenteLocal.Intent(command, "", null, intent.spanish());
    }

    /** Carries out an order a language model chose; says something more only when it could not be done. */
    @Nullable
    private static String act(ServerPlayerEntity player, VillagerEntityMCA villager, StructuredResponse said, boolean spanish) {
        String name = said.optionalCommand().trim();
        if (name.equals("give-item")) {
            Acciones.Given given = Acciones.hand(player, villager, said.argument());
            MCA.LOGGER.info("[Tropeles] {} da '{}': {}", villager.getName().getString(), said.argument(), given.status());
            return given.status() == Acciones.Status.GAVE ? null
                    : MenteLocal.answer(player, villager, new MenteLocal.Intent("give-item", said.argument(), null, spanish), "", given, null, false);
        }
        if (name.equals("store-items")) {
            Cofres.Store stored = Cofres.orderStore(villager, player);
            return stored != Cofres.Store.NONE ? null
                    : MenteLocal.answer(player, villager, new MenteLocal.Intent("store-items", "", null, spanish), "", null, stored, false);
        }
        Optional<TriggerCommandInfo> command = TriggerCommandInfos.findCommand(name, player, villager);
        if (command.isPresent()) {
            MCA.LOGGER.info("[Tropeles] {} hace '{}' ({})", villager.getName().getString(), name, said.argument());
            command.get().run(player, villager, said.argument());
        }
        return null;
    }

    // ------------------------------------------------------------------ the conversation so far

    private static List<Pair<String, String>> history(VillagerEntityMCA villager) {
        synchronized (memory) {
            UUID id = villager.getUuid();
            long time = villager.getWorld().getTime();
            // forgot about last conversation if it's too long ago
            if (time > lastInteractions.getOrDefault(id, 0L) + MAX_MEMORY_TIME) {
                memory.remove(id);
            }
            lastInteractions.put(id, time);
            List<Pair<String, String>> past = memory.computeIfAbsent(id, key -> new LinkedList<>());
            while (!past.isEmpty() && past.stream().mapToInt(v -> v.getRight().length() / 4).sum() > MAX_MEMORY) {
                past.remove(0);
            }
            return new ArrayList<>(past);
        }
    }

    private static void remember(UUID villager, String msg, String line) {
        synchronized (memory) {
            List<Pair<String, String>> past = memory.computeIfAbsent(villager, key -> new LinkedList<>());
            past.add(new Pair<>("user", msg));
            past.add(new Pair<>("assistant", line));
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

    /** For the in-game checks: a fresh start, as if it never talked to anyone. */
    public static void forget(UUID villager) {
        synchronized (memory) {
            memory.remove(villager);
            lastInteractions.remove(villager);
        }
    }

}
