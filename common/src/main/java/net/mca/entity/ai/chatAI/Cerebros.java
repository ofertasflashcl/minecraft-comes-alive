package net.mca.entity.ai.chatAI;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mca.Config;
import net.mca.MCA;
import net.minecraft.util.Pair;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.Nullable;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The minds a villager can think with, asked in turn until one answers. All of them are free: a model running
 * on your own computer (Player2 or Ollama) when there is one, MCA's own service, then public services that need
 * no key. A mind that fails rests a while so the next message does not wait on it again; a local one that is
 * not running is skipped after a quick check. When no mind answers, the caller falls back on the villager's own
 * offline mind ({@link MenteLocal}), so a villager always answers.
 */
public final class Cerebros {
    public static final String MCA_ENDPOINT = "https://api.conczin.net/v1/mca/chat";
    private static final String USER_AGENT = "MCA-Tropeles/7.6.28 (Minecraft villager chat)";

    /** No mind takes longer than this in all, so a player is never left waiting. */
    private static final long DEADLINE_MS = 45_000;

    /**
     * One mind: where to ask, with which model, and how. {@code inHouse} is MCA's own service, which adds the
     * villager persona itself.
     */
    public record Mind(String name, String url, String model, String token, boolean local, boolean inHouse,
                       Map<String, String> headers, @Nullable String health) {
    }

    /** What a mind said, raw, and how long it took. */
    public record Reply(String mind, String content, long millis) {
    }

    /** For the in-game checks: on this thread, never go online, the offline mind answers. */
    public static final ThreadLocal<Boolean> OFFLINE = ThreadLocal.withInitial(() -> false);

    private static final Map<String, Long> RESTING = new ConcurrentHashMap<>();
    private static final Map<String, Long> LOCAL_DOWN = new ConcurrentHashMap<>();
    private static final Map<String, Long> LOCAL_UP = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LAST = new ConcurrentHashMap<>();
    private static volatile String ollamaModel = "";

    private Cerebros() {
    }

    /** The mind that last answered for this villager, or "mente local". */
    public static String last(UUID villager) {
        return LAST.getOrDefault(villager, "mente local");
    }

    static void answeredOffline(UUID villager) {
        LAST.put(villager, "mente local");
    }

    private static String slash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    private static boolean isLocal(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("://127.0.0.1") || u.contains("://localhost") || u.contains("://0.0.0.0") || u.contains("://192.168.");
    }

    /** The minds to ask, in order, from the config. */
    public static List<Mind> chain(String playerName) {
        Config config = Config.getInstance();
        List<Mind> minds = new ArrayList<>();
        Set<String> urls = new java.util.HashSet<>();
        List<String> order = config.tropelesMinds == null || config.tropelesMinds.isEmpty()
                ? List.of("custom", "player2", "ollama", "conczin", "llm7", "chat", "pollinations") : config.tropelesMinds;
        for (String raw : order) {
            String name = raw == null ? "" : raw.toLowerCase(Locale.ROOT).trim();
            Mind mind = switch (name) {
                case "custom" -> {
                    String endpoint = config.villagerChatAIEndpoint == null ? "" : config.villagerChatAIEndpoint.trim();
                    if (endpoint.isEmpty() || endpoint.contains("conczin.net")) {
                        yield null;
                    }
                    yield new Mind("propia", endpoint, config.villagerChatAIModel, config.villagerChatAIToken, isLocal(endpoint), false,
                            endpoint.startsWith(slash(config.player2Url)) ? Map.of("player2-game-key", "minecraft-comes-alive-reborn") : Map.of(), null);
                }
                case "player2" -> new Mind("player2", slash(config.player2Url) + "v1/chat/completions", "default", "", true, false,
                        Map.of("player2-game-key", "minecraft-comes-alive-reborn"), slash(config.player2Url) + "v1/health");
                case "ollama" -> new Mind("ollama", slash(config.tropelesOllamaUrl) + "v1/chat/completions", config.tropelesOllamaModel, "", true, false,
                        Map.of(), slash(config.tropelesOllamaUrl) + "api/tags");
                case "conczin", "mca" -> new Mind("mca", MCA_ENDPOINT, config.villagerChatAIEndpoint != null && config.villagerChatAIEndpoint.contains("conczin.net")
                        ? config.villagerChatAIModel : "default", playerName, false, true, Map.of(), null);
                case "llm7" -> new Mind("llm7", "https://api.llm7.io/v1/chat/completions", "default", "unused", false, false, Map.of(), null);
                case "chat", "ch.at", "chat.at" -> new Mind("ch.at", "https://ch.at/v1/chat/completions", "gpt-4o", "", false, false, Map.of(), null);
                case "pollinations" -> new Mind("pollinations", "https://text.pollinations.ai/openai", "openai", "", false, false, Map.of(), null);
                default -> null;
            };
            if (mind != null && urls.add(mind.url())) {
                minds.add(mind);
            }
        }
        return minds;
    }

    // ------------------------------------------------------------------ asking

    /**
     * Asks the minds in turn and returns the first real answer, or null when none answered.
     *
     * @param system  the system prompt for each mind (MCA's own service adds its persona itself)
     * @param history past turns as (role, text), oldest first
     */
    @Nullable
    public static Reply ask(List<Mind> minds, Function<Mind, String> system, List<Pair<String, String>> history, String msg,
                            String playerName, String villagerName, @Nullable UUID villager) {
        if (OFFLINE.get()) {
            return null;
        }
        long start = System.currentTimeMillis();
        for (Mind mind : minds) {
            long now = System.currentTimeMillis();
            if (now - start > DEADLINE_MS) {
                break;
            }
            if (RESTING.getOrDefault(mind.name(), 0L) > now) {
                continue;
            }
            if (mind.local() && !running(mind)) {
                continue;
            }
            Reply reply = once(mind, system.apply(mind), history, msg, playerName, villagerName).reply();
            if (reply != null) {
                if (villager != null) {
                    LAST.put(villager, mind.name());
                }
                return reply;
            }
        }
        return null;
    }

    private record Try(@Nullable Reply reply, String error) {
    }

    /** One try at one mind; no reply and a rest when it does not answer. */
    private static Try once(Mind mind, String system, List<Pair<String, String>> history, String msg, String playerName, String villagerName) {
        long start = System.currentTimeMillis();
        String model = mind.model();
        if (mind.name().equals("ollama") && (model == null || model.isBlank())) {
            model = ollamaModel;
            if (model.isBlank()) {
                rest(mind, 60_000, "no tiene modelos instalados");
                return new Try(null, "no tiene modelos instalados");
            }
        }
        String body = body(mind, model == null || model.isBlank() ? "default" : model, system, history, msg, playerName, villagerName);
        Http http = post(mind, body);
        long millis = System.currentTimeMillis() - start;
        if (http.code() / 100 == 2) {
            String content = content(http.body());
            if (content != null && !content.isBlank()) {
                return new Try(new Reply(mind.name(), content, millis), "");
            }
        }
        String error = http.code() / 100 == 2 ? "respuesta vacía" : error(http);
        if (error.contains("limit") || http.code() == 429) {
            rest(mind, 20 * 60_000, error);
        } else if (http.code() == 401 || http.code() == 403 || http.code() == 404) {
            rest(mind, 10 * 60_000, error);
        } else {
            rest(mind, 60_000, error);
        }
        return new Try(null, error);
    }

    private static void rest(Mind mind, long millis, String why) {
        RESTING.put(mind.name(), System.currentTimeMillis() + millis);
        MCA.LOGGER.info("[Tropeles] la mente {} no respondió ({}), descansa {} s", mind.name(), why, millis / 1000);
    }

    /** A local mind answers a quick health check; the result is kept a little while. */
    private static boolean running(Mind mind) {
        long now = System.currentTimeMillis();
        if (LOCAL_UP.getOrDefault(mind.name(), 0L) > now) {
            return true;
        }
        if (LOCAL_DOWN.getOrDefault(mind.name(), 0L) > now) {
            return false;
        }
        if (mind.health() == null) {
            return true;
        }
        Http http = get(mind.health(), mind.headers(), 600, 2000);
        boolean up = http.code() / 100 == 2;
        if (up && mind.name().equals("ollama")) {
            try {
                JsonArray models = JsonParser.parseString(http.body()).getAsJsonObject().getAsJsonArray("models");
                ollamaModel = models != null && !models.isEmpty() ? models.get(0).getAsJsonObject().get("name").getAsString() : "";
            } catch (RuntimeException e) {
                ollamaModel = "";
            }
        }
        if (up) {
            LOCAL_UP.put(mind.name(), now + 30_000);
        } else {
            LOCAL_DOWN.put(mind.name(), now + 60_000);
        }
        return up;
    }

    /** The request body: plain OpenAI-style chat. Names only go to MCA's own service, the others may refuse them. */
    static String body(Mind mind, String model, String system, List<Pair<String, String>> history, String msg, String playerName, String villagerName) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        JsonArray messages = new JsonArray();
        messages.add(message("system", system, null));
        for (Pair<String, String> turn : history) {
            boolean user = turn.getLeft().equals("user");
            messages.add(message(turn.getLeft(), turn.getRight(), mind.inHouse() ? (user ? playerName : villagerName) : null));
        }
        messages.add(message("user", msg, mind.inHouse() ? playerName : null));
        root.add("messages", messages);
        if (!mind.inHouse()) {
            root.addProperty("temperature", 0.8);
            root.addProperty("stream", false);
        }
        return root.toString();
    }

    private static JsonObject message(String role, String content, @Nullable String name) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        if (name != null) {
            m.addProperty("name", name);
        }
        m.addProperty("content", content);
        return m;
    }

    /** The text of an answer, whatever shape it came in: OpenAI choices, Ollama's own, or a bare response. */
    @Nullable
    static String content(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonObject message = null;
            if (root.has("choices") && root.get("choices").isJsonArray() && !root.getAsJsonArray("choices").isEmpty()) {
                JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (choice.has("message") && choice.get("message").isJsonObject()) {
                    message = choice.getAsJsonObject("message");
                } else if (choice.has("text") && choice.get("text").isJsonPrimitive()) {
                    return choice.get("text").getAsString();
                }
            } else if (root.has("message") && root.get("message").isJsonObject()) {
                message = root.getAsJsonObject("message");
            } else if (root.has("response") && root.get("response").isJsonPrimitive()) {
                return root.get("response").getAsString();
            }
            if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
                return null;
            }
            JsonElement content = message.get("content");
            if (content.isJsonPrimitive()) {
                return content.getAsString();
            }
            if (content.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement part : content.getAsJsonArray()) {
                    if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                        sb.append(part.getAsJsonObject().get("text").getAsString());
                    }
                }
                return sb.toString();
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static String error(Http http) {
        String text = http.body() == null ? "" : http.body();
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            if (root.has("error")) {
                JsonElement error = root.get("error");
                if (error.isJsonPrimitive()) {
                    return "HTTP " + http.code() + " " + error.getAsString();
                }
                if (error.isJsonObject()) {
                    JsonObject e = error.getAsJsonObject();
                    String message = e.has("message") && e.get("message").isJsonPrimitive() ? e.get("message").getAsString()
                            : e.has("code") && e.get("code").isJsonPrimitive() ? e.get("code").getAsString() : error.toString();
                    return "HTTP " + http.code() + " " + message;
                }
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        String shortText = text.replace('\n', ' ');
        return "HTTP " + http.code() + " " + (shortText.length() > 160 ? shortText.substring(0, 160) : shortText);
    }

    // ------------------------------------------------------------------ http

    private record Http(int code, String body) {
    }

    private static Http post(Mind mind, String body) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(mind.url()).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(mind.local() ? 1500 : 5000);
            con.setReadTimeout(mind.local() ? 90_000 : 30_000);
            con.setRequestProperty("Accept-Charset", StandardCharsets.UTF_8.toString());
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("User-Agent", USER_AGENT);
            if (mind.token() != null && !mind.token().isEmpty()) {
                con.setRequestProperty("Authorization", "Bearer " + mind.token());
            }
            for (Map.Entry<String, String> header : mind.headers().entrySet()) {
                con.setRequestProperty(header.getKey(), header.getValue());
            }
            con.setDoOutput(true);
            try (DataOutputStream out = new DataOutputStream(con.getOutputStream())) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            return read(con);
        } catch (Exception e) {
            return new Http(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Http get(String url, Map<String, String> headers, int connect, int read) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(connect);
            con.setReadTimeout(read);
            con.setRequestProperty("User-Agent", USER_AGENT);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                con.setRequestProperty(header.getKey(), header.getValue());
            }
            return read(con);
        } catch (Exception e) {
            return new Http(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Http read(HttpURLConnection con) throws java.io.IOException {
        int code = con.getResponseCode();
        InputStream stream = code >= 400 ? con.getErrorStream() : con.getInputStream();
        String text = stream == null ? "" : IOUtils.toString(stream, StandardCharsets.UTF_8);
        return new Http(code, text);
    }

    // ------------------------------------------------------------------ for the in-game checks

    /**
     * Asks every mind once, ignoring rests, and says how each did: "name: ok 812 ms «text»" or "name: no (why)".
     * Slow; never call it on the server thread.
     */
    public static Map<String, String> probe(String playerName, String system, String msg) {
        Map<String, String> results = new LinkedHashMap<>();
        for (Mind mind : chain(playerName)) {
            if (mind.local()) {
                LOCAL_UP.remove(mind.name());
                LOCAL_DOWN.remove(mind.name());
                if (!running(mind)) {
                    results.put(mind.name(), "no (no está corriendo en este equipo)");
                    continue;
                }
            }
            RESTING.remove(mind.name());
            Try attempt = once(mind, system, List.of(), msg, playerName, "Aldeano");
            if (attempt.reply() != null) {
                String text = attempt.reply().content().replace('\n', ' ');
                results.put(mind.name(), "ok " + attempt.reply().millis() + " ms «" + (text.length() > 140 ? text.substring(0, 140) : text) + "»");
            } else {
                results.put(mind.name(), "no (" + attempt.error() + ")");
            }
            RESTING.remove(mind.name());
        }
        return results;
    }
}
