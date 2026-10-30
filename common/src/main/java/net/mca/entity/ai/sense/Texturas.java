package net.mca.entity.ai.sense;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mca.MCA;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.biome.Biome;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real block textures for the pictures of the eye ({@link Pantalla}, {@link Foto}), read from the game's own
 * assets when the server runs inside the client (singleplayer, LAN): the client jar is on the class path there. Nothing
 * is shipped with the mod. On a dedicated server there are no assets: {@link #pincel} gives null and every picture is
 * drawn exactly as before, with map colours.
 * <p>
 * Per block state, lazily and only for the states actually seen: {@code blockstates/<id>.json} (the variant that
 * matches the state, or the multipart parts that apply), the model JSON along its {@code parent} chain, the texture
 * map with its {@code #references}, and per face the texture of the model element that shows on that side (its UV,
 * face rotation and tint index; the model's x/y rotation undone), or a guess from the texture names when the model
 * has no elements. Each PNG is decoded once (the first frame of an animated strip), brought to 16x16 ARGB with four
 * smaller mip levels down to its average colour. Sampling is a few array lookups. Biome tints (grass, foliage,
 * water) come from the biome at the block. Only pictures use it: the mind never does.
 */
public final class Texturas {
    public static final int SIZE = 16;
    static final int LEVELS = 5;
    /** Past this many block states seen, new ones keep their map colour (memory stays bounded). */
    static final int MAX_STATES = 16384;
    static final int MAX_TEXTURES = 4096;
    /** An opaque texel from here up. */
    public static final int OPAQUE = 160;
    /** A texel under this alpha is a hole. */
    public static final int HOLE = 24;

    static final int TINT_NONE = 0;
    static final int TINT_GRASS = 1;
    static final int TINT_FOLIAGE = 2;
    static final int TINT_WATER = 3;
    static final int TINT_FIXED = 4;

    private static final int GRASS_DEFAULT = 0x91BD59;
    private static final int FOLIAGE_DEFAULT = 0x77AB2F;
    private static final int WATER_DEFAULT = 0x3F76E4;

    private static final Object LOCK = new Object();
    /** 0 not looked yet, 1 found, -1 not there (dedicated server) */
    private static volatile int found;
    @Nullable
    private static volatile ClassLoader loader;
    private static volatile boolean broken;
    private static boolean loggedError;

    private static final Caras NONE = new Caras();
    private static final Tex NO_TEX = new Tex(new int[][]{new int[1]});
    private static final JsonObject NO_JSON = new JsonObject();
    private static final ConcurrentHashMap<BlockState, Caras> STATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, JsonObject> JSON = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Tex> TEXTURES = new ConcurrentHashMap<>();

    private Texturas() {
    }

    // ---------------------------------------------------------------- whether there are textures at all

    /** The brush of one picture (its biome cache), or null when there are no textures: a dedicated server. */
    @Nullable
    public static Pincel pincel(ServerWorld world) {
        if (broken || world.getServer() == null || world.getServer().isDedicated() || !available()) {
            return null;
        }
        return new Pincel(world);
    }

    static boolean available() {
        int f = found;
        if (f == 0) {
            synchronized (LOCK) {
                if (found == 0) {
                    ClassLoader l = null;
                    try {
                        l = probe();
                    } catch (Throwable e) {
                        l = null;
                    }
                    loader = l;
                    found = l != null ? 1 : -1;
                    if (l != null) {
                        MCA.LOGGER.info("[Tropeles] el ojo ve con las texturas del juego");
                    }
                }
                f = found;
            }
        }
        return f > 0;
    }

    @Nullable
    private static ClassLoader probe() {
        ClassLoader[] candidates = {Thread.currentThread().getContextClassLoader(), Blocks.class.getClassLoader(),
                Texturas.class.getClassLoader(), ClassLoader.getSystemClassLoader()};
        for (ClassLoader c : candidates) {
            if (c == null) {
                continue;
            }
            try (InputStream states = c.getResourceAsStream("assets/minecraft/blockstates/stone.json")) {
                if (states == null) {
                    continue;
                }
                try (InputStream png = c.getResourceAsStream("assets/minecraft/textures/block/stone.png")) {
                    if (png != null) {
                        return c;
                    }
                }
            } catch (Exception | LinkageError ignored) {
                // not this one
            }
        }
        return null;
    }

    private static void failed(Throwable e) {
        if (!loggedError) {
            loggedError = true;
            MCA.LOGGER.warn("[Tropeles] el ojo no pudo leer una textura: {}", e.toString());
        }
        if (e instanceof LinkageError) {
            broken = true; // something of the image or JSON side is missing here: the map colours for good
        }
    }

    // ---------------------------------------------------------------- what is cached

    /** One texture: 16x16 ARGB and its mip levels (8, 4, 2, 1), colour weighted by alpha. */
    static final class Tex {
        final int[][] mip;

        Tex(int[][] mip) {
            this.mip = mip;
        }

        int average() {
            return mip[mip.length - 1][0];
        }
    }

    /** One face of a block state as the picture draws it. */
    public static final class Cara {
        final Tex tex;
        @Nullable
        final Tex over;
        final int tint;
        final int overTint;
        final int fixed;
        /** the face the natural coordinates are taken on (the model's, when the model is turned without uv lock) */
        final Direction face;
        /** world point to model point (around the centre), null when not turned or uv-locked */
        @Nullable
        final int[] m;
        final boolean lock;
        /** the natural rectangle of the element on this face, and its uv rectangle (0..16) */
        final float n0, n1, n2, n3;
        final float u0, v0, u1, v1;
        final int rot;

        Cara(Tex tex, @Nullable Tex over, int tint, int overTint, int fixed, Direction face, @Nullable int[] m, boolean lock,
             float[] n, float[] uv, int rot) {
            this.tex = tex;
            this.over = over;
            this.tint = tint;
            this.overTint = overTint;
            this.fixed = fixed;
            this.face = face;
            this.m = m;
            this.lock = lock;
            this.n0 = n[0];
            this.n1 = n[1];
            this.n2 = n[2];
            this.n3 = n[3];
            this.u0 = uv[0];
            this.v0 = uv[1];
            this.u1 = uv[2];
            this.v1 = uv[3];
            this.rot = rot;
        }
    }

    /** The six faces of a block state, its shape for a ray (the model's elements, in the world), and its cross. */
    static final class Caras {
        final Cara[] face = new Cara[6];
        /** min x, y, z, max x, y, z in 0..1 */
        double[] box = {0, 0, 0, 1, 1, 1};
        boolean full = true;
        @Nullable
        Cara cross;
    }

    @Nullable
    static Caras caras(BlockState s) {
        Caras c = STATES.get(s);
        if (c != null) {
            return c == NONE ? null : c;
        }
        if (broken || loader == null || STATES.size() >= MAX_STATES) {
            return null;
        }
        try {
            c = build(s);
        } catch (Throwable e) {
            failed(e);
            c = null;
        }
        STATES.put(s, c == null ? NONE : c);
        return c;
    }

    // ---------------------------------------------------------------- reading the assets

    @Nullable
    private static InputStream open(String path) {
        ClassLoader l = loader;
        return l == null ? null : l.getResourceAsStream(path);
    }

    @Nullable
    private static JsonObject json(String path) {
        JsonObject o = JSON.get(path);
        if (o != null) {
            return o == NO_JSON ? null : o;
        }
        o = null;
        try (InputStream in = open(path)) {
            if (in != null) {
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonElement e = JsonParser.parseReader(r);
                    o = e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
                }
            }
        } catch (Exception e) {
            o = null;
        }
        JSON.put(path, o == null ? NO_JSON : o);
        return o;
    }

    @Nullable
    private static Tex texture(String id) {
        String[] r = split(id);
        String path = "assets/" + r[0] + "/textures/" + r[1] + ".png";
        Tex t = TEXTURES.get(path);
        if (t != null) {
            return t == NO_TEX ? null : t;
        }
        if (TEXTURES.size() >= MAX_TEXTURES) {
            return null;
        }
        t = null;
        try (InputStream in = open(path)) {
            if (in != null) {
                BufferedImage img = ImageIO.read(in);
                if (img != null) {
                    t = decode(img);
                }
            }
        } catch (Exception e) {
            t = null;
        }
        TEXTURES.put(path, t == null ? NO_TEX : t);
        return t;
    }

    /** 16x16 ARGB from the image's first square (an animated strip's first frame), box-filtered or repeated. */
    private static Tex decode(BufferedImage img) {
        int side = Math.min(img.getWidth(), img.getHeight());
        int[] base = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (side <= SIZE) {
                    base[y * SIZE + x] = img.getRGB(x * side / SIZE, y * side / SIZE);
                    continue;
                }
                int x0 = x * side / SIZE;
                int x1 = Math.max(x0 + 1, (x + 1) * side / SIZE);
                int y0 = y * side / SIZE;
                int y1 = Math.max(y0 + 1, (y + 1) * side / SIZE);
                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int c = img.getRGB(xx, yy);
                        int ca = c >>> 24;
                        a += ca;
                        r += ((c >> 16) & 255) * ca;
                        g += ((c >> 8) & 255) * ca;
                        b += (c & 255) * ca;
                        n++;
                    }
                }
                base[y * SIZE + x] = pack(a, r, g, b, n);
            }
        }
        int[][] mip = new int[LEVELS][];
        mip[0] = base;
        for (int k = 1; k < LEVELS; k++) {
            int size = SIZE >> k;
            int[] up = mip[k - 1];
            int upSize = size * 2;
            int[] level = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    long a = 0, r = 0, g = 0, b = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            int c = up[(y * 2 + dy) * upSize + x * 2 + dx];
                            int ca = c >>> 24;
                            a += ca;
                            r += ((c >> 16) & 255) * ca;
                            g += ((c >> 8) & 255) * ca;
                            b += (c & 255) * ca;
                        }
                    }
                    level[y * size + x] = pack(a, r, g, b, 4);
                }
            }
            mip[k] = level;
        }
        return new Tex(mip);
    }

    private static int pack(long a, long r, long g, long b, int n) {
        if (a <= 0) {
            return 0;
        }
        int alpha = (int) Math.min(255, a / n);
        return (alpha << 24) | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a);
    }

    /** "ns:path" (or "path", the game's own) into {ns, path}. */
    private static String[] split(String id) {
        int c = id.indexOf(':');
        return c < 0 ? new String[]{"minecraft", id} : new String[]{id.substring(0, c), id.substring(c + 1)};
    }

    // ---------------------------------------------------------------- block state -> models -> faces

    private record Variant(String model, int x, int y, boolean uvlock) {
    }

    private record Model(Map<String, String> textures, @Nullable JsonArray elements) {
    }

    @Nullable
    private static Caras build(BlockState s) {
        if (s.isAir() || s.getRenderType() == BlockRenderType.INVISIBLE) {
            return null;
        }
        Identifier id = Registries.BLOCK.getId(s.getBlock());
        JsonObject states = json("assets/" + id.getNamespace() + "/blockstates/" + id.getPath() + ".json");
        if (states == null) {
            return null;
        }
        Map<String, String> props = properties(s);
        List<Variant> parts = variants(states, props);
        if (parts.isEmpty()) {
            return null;
        }
        List<Model> models = new ArrayList<>(parts.size());
        for (Variant v : parts) {
            models.add(model(v.model()));
        }
        int tint = tintOf(s);
        int fixed = fixedTint(s, props);
        Caras out = new Caras();

        // a cross (flowers, grass, saplings): only turned planes
        Model first = models.get(0);
        if (first.elements() != null && isCross(first.elements())) {
            JsonObject e = first.elements().get(0).getAsJsonObject();
            JsonObject faces = e.getAsJsonObject("faces");
            JsonObject f = faces == null ? null : firstFace(faces);
            Tex tex = f == null ? null : texture(first.textures(), str(f, "texture"));
            if (tex != null) {
                float[] uv = uv(f, new float[]{0, 0, 16, 16});
                int t = f.has("tintindex") && f.get("tintindex").getAsInt() >= 0 ? tint : TINT_NONE;
                Cara c = new Cara(tex, null, t, TINT_NONE, fixed, Direction.NORTH, null, false, new float[]{0, 0, 16, 16}, uv, 0);
                out.cross = c;
                for (int k = 0; k < 6; k++) {
                    out.face[k] = c;
                }
                return out;
            }
        }

        // the shape a ray meets: every element of every part, in the world
        double[] box = null;
        for (int p = 0; p < parts.size(); p++) {
            JsonArray elements = models.get(p).elements();
            if (elements == null) {
                continue;
            }
            int[] toWorld = matrix(parts.get(p), true);
            for (JsonElement je : elements) {
                if (!je.isJsonObject()) {
                    continue;
                }
                JsonObject e = je.getAsJsonObject();
                float[] from = vec(e, "from");
                float[] to = vec(e, "to");
                if (from == null || to == null) {
                    continue;
                }
                double[] a = turn(toWorld, from[0], from[1], from[2]);
                double[] b = turn(toWorld, to[0], to[1], to[2]);
                if (box == null) {
                    box = new double[]{1, 1, 1, 0, 0, 0};
                }
                for (int k = 0; k < 3; k++) {
                    box[k] = Math.min(box[k], Math.min(a[k], b[k]) / 16.0);
                    box[k + 3] = Math.max(box[k + 3], Math.max(a[k], b[k]) / 16.0);
                }
            }
        }
        if (box != null) {
            out.box = box;
            out.full = box[0] <= 1.0E-3 && box[1] <= 1.0E-3 && box[2] <= 1.0E-3 && box[3] >= 1 - 1.0E-3 && box[4] >= 1 - 1.0E-3 && box[5] >= 1 - 1.0E-3;
        }

        boolean any = false;
        for (Direction world : Direction.values()) {
            Cara c = null;
            for (int p = 0; p < parts.size() && c == null; p++) {
                c = elementFace(parts.get(p), models.get(p), world, tint, fixed);
            }
            if (c == null) {
                c = guessFace(parts.get(0), first, world, s, tint, fixed);
            }
            out.face[world.ordinal()] = c;
            any |= c != null;
        }
        return any ? out : null;
    }

    /** The state's properties as the blockstate file names them, from its own printout ("Block{id}[a=b,c=d]"). */
    private static Map<String, String> properties(BlockState s) {
        Map<String, String> out = new HashMap<>();
        String text = s.toString();
        int open = text.indexOf('[');
        int close = text.lastIndexOf(']');
        if (open < 0 || close <= open) {
            return out;
        }
        for (String pair : text.substring(open + 1, close).split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return out;
    }

    private static List<Variant> variants(JsonObject states, Map<String, String> props) {
        List<Variant> out = new ArrayList<>();
        if (states.has("variants") && states.get("variants").isJsonObject()) {
            JsonObject variants = states.getAsJsonObject("variants");
            JsonElement chosen = null;
            for (Map.Entry<String, JsonElement> e : variants.entrySet()) {
                if (chosen == null) {
                    chosen = e.getValue(); // the first, when none matches
                }
                if (matches(e.getKey(), props)) {
                    chosen = e.getValue();
                    break;
                }
            }
            Variant v = chosen == null ? null : variant(chosen);
            if (v != null) {
                out.add(v);
            }
        } else if (states.has("multipart") && states.get("multipart").isJsonArray()) {
            for (JsonElement part : states.getAsJsonArray("multipart")) {
                if (!part.isJsonObject()) {
                    continue;
                }
                JsonObject o = part.getAsJsonObject();
                JsonElement when = o.get("when");
                if (when == null || test(when, props)) {
                    Variant v = o.has("apply") ? variant(o.get("apply")) : null;
                    if (v != null) {
                        out.add(v);
                    }
                }
            }
        }
        return out;
    }

    private static boolean matches(String key, Map<String, String> props) {
        if (key.isEmpty() || key.equals("normal")) {
            return true;
        }
        for (String pair : key.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !pair.substring(eq + 1).equals(props.get(pair.substring(0, eq)))) {
                return false;
            }
        }
        return true;
    }

    private static boolean test(JsonElement when, Map<String, String> props) {
        if (!when.isJsonObject()) {
            return true;
        }
        JsonObject o = when.getAsJsonObject();
        if (o.has("OR") && o.get("OR").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("OR")) {
                if (test(e, props)) {
                    return true;
                }
            }
            return false;
        }
        if (o.has("AND") && o.get("AND").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("AND")) {
                if (!test(e, props)) {
                    return false;
                }
            }
            return true;
        }
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String have = props.get(e.getKey());
            String want = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : "";
            boolean not = want.startsWith("!");
            if (not) {
                want = want.substring(1);
            }
            boolean any = false;
            for (String w : want.split("\\|")) {
                if (w.equals(have)) {
                    any = true;
                    break;
                }
            }
            if (any == not) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static Variant variant(JsonElement e) {
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            return a.size() == 0 ? null : variant(a.get(0)); // weighted ones: the first
        }
        if (!e.isJsonObject()) {
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        String model = str(o, "model");
        if (model == null) {
            return null;
        }
        int x = o.has("x") ? o.get("x").getAsInt() : 0;
        int y = o.has("y") ? o.get("y").getAsInt() : 0;
        boolean lock = o.has("uvlock") && o.get("uvlock").getAsBoolean();
        return new Variant(model, Math.floorMod(x / 90, 4), Math.floorMod(y / 90, 4), lock);
    }

    private static Model model(String id) {
        Map<String, String> textures = new HashMap<>();
        JsonArray elements = null;
        String current = id;
        for (int depth = 0; depth < 24 && current != null; depth++) {
            String[] r = split(current);
            if (r[1].startsWith("builtin/")) {
                break;
            }
            JsonObject o = json("assets/" + r[0] + "/models/" + r[1] + ".json");
            if (o == null) {
                break;
            }
            if (o.has("textures") && o.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("textures").entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        textures.putIfAbsent(e.getKey(), e.getValue().getAsString()); // the child's own first
                    }
                }
            }
            if (elements == null && o.has("elements") && o.get("elements").isJsonArray()) {
                elements = o.getAsJsonArray("elements");
            }
            current = str(o, "parent");
        }
        return new Model(textures, elements);
    }

    @Nullable
    private static Tex texture(Map<String, String> textures, @Nullable String ref) {
        for (int depth = 0; ref != null && ref.startsWith("#") && depth < 16; depth++) {
            ref = textures.get(ref.substring(1));
        }
        return ref == null || ref.startsWith("#") ? null : texture(ref);
    }

    private static boolean isCross(JsonArray elements) {
        if (elements.size() == 0) {
            return false;
        }
        for (JsonElement je : elements) {
            if (!je.isJsonObject() || !turned(je.getAsJsonObject())) {
                return false;
            }
        }
        return true;
    }

    private static boolean turned(JsonObject e) {
        if (!e.has("rotation") || !e.get("rotation").isJsonObject()) {
            return false;
        }
        JsonObject r = e.getAsJsonObject("rotation");
        return r.has("angle") && Math.abs(r.get("angle").getAsFloat()) > 1.0E-3;
    }

    @Nullable
    private static JsonObject firstFace(JsonObject faces) {
        for (String k : new String[]{"north", "south", "east", "west", "up", "down"}) {
            if (faces.has(k) && faces.get(k).isJsonObject()) {
                return faces.getAsJsonObject(k);
            }
        }
        return null;
    }

    /** The face of the element that shows on this side of the block (the outermost one), with an overlay on it. */
    @Nullable
    private static Cara elementFace(Variant v, Model model, Direction world, int tint, int fixed) {
        JsonArray elements = model.elements();
        if (elements == null) {
            return null;
        }
        int[] toModel = matrix(v, false);
        Direction face = rotate(toModel, world);
        String key = face.asString();
        JsonObject best = null;
        JsonObject bestFace = null;
        JsonObject overFace = null;
        double bestOut = -1;
        float[] bestFrom = null;
        float[] bestTo = null;
        for (JsonElement je : elements) {
            if (!je.isJsonObject()) {
                continue;
            }
            JsonObject e = je.getAsJsonObject();
            if (turned(e)) {
                continue;
            }
            JsonObject faces = e.has("faces") && e.get("faces").isJsonObject() ? e.getAsJsonObject("faces") : null;
            if (faces == null || !faces.has(key) || !faces.get(key).isJsonObject()) {
                continue;
            }
            float[] from = vec(e, "from");
            float[] to = vec(e, "to");
            if (from == null || to == null) {
                continue;
            }
            double out = outward(face, from, to);
            if (best != null && Math.abs(out - bestOut) < 1.0E-3 && same(from, bestFrom) && same(to, bestTo)) {
                if (overFace == null) {
                    overFace = faces.getAsJsonObject(key); // the same box again: an overlay (the grass on a grass block's side)
                }
                continue;
            }
            if (out > bestOut + 1.0E-3) {
                best = e;
                bestFace = faces.getAsJsonObject(key);
                bestOut = out;
                bestFrom = from;
                bestTo = to;
                overFace = null;
            }
        }
        if (bestFace == null) {
            return null;
        }
        Tex tex = texture(model.textures(), str(bestFace, "texture"));
        if (tex == null) {
            return null;
        }
        Tex over = overFace == null ? null : texture(model.textures(), str(overFace, "texture"));
        float[] n = natural(face, bestFrom, bestTo);
        float[] uv = uv(bestFace, n);
        int rot = bestFace.has("rotation") ? Math.floorMod(bestFace.get("rotation").getAsInt() / 90, 4) : 0;
        int t = tinted(bestFace) ? tint : TINT_NONE;
        int ot = overFace != null && tinted(overFace) ? tint : TINT_NONE;
        boolean identity = v.x() == 0 && v.y() == 0;
        boolean lock = v.uvlock() && !identity; // turned, but the texture keeps to the world: taken on the world's face
        return new Cara(tex, over, t, ot, fixed, lock ? world : face, identity || lock ? null : toModel, lock, n, uv, rot);
    }

    /** No elements (fluids, entity-drawn blocks): the texture its names suggest for this side. */
    @Nullable
    private static Cara guessFace(Variant v, Model model, Direction world, BlockState s, int tint, int fixed) {
        Map<String, String> tx = model.textures();
        String side = world.asString();
        String[] keys = switch (world) {
            case UP -> new String[]{side, "top", "end", "all", "side", "texture", "particle"};
            case DOWN -> new String[]{side, "bottom", "end", "all", "side", "texture", "particle"};
            default -> new String[]{side, "side", "front", "all", "texture", "cross", "plant", "particle"};
        };
        Tex tex = null;
        for (String k : keys) {
            if (tx.containsKey(k)) {
                tex = texture(tx, "#" + k);
                if (tex != null) {
                    break;
                }
            }
        }
        if (tex == null) {
            for (String k : tx.keySet()) {
                tex = texture(tx, "#" + k);
                if (tex != null) {
                    break;
                }
            }
        }
        if (tex == null) {
            return null;
        }
        int t = tint == TINT_WATER ? TINT_WATER : TINT_NONE; // water still is grey, made blue by the biome
        float[] whole = {0, 0, 16, 16};
        return new Cara(tex, null, t, TINT_NONE, fixed, world, null, true, whole, whole, 0);
    }

    private static boolean tinted(JsonObject face) {
        return face.has("tintindex") && face.get("tintindex").getAsInt() >= 0;
    }

    private static boolean same(float[] a, float[] b) {
        return Math.abs(a[0] - b[0]) < 1.0E-3 && Math.abs(a[1] - b[1]) < 1.0E-3 && Math.abs(a[2] - b[2]) < 1.0E-3;
    }

    /** How far out an element's face reaches on that side (bigger is nearer the block's own face). */
    private static double outward(Direction face, float[] from, float[] to) {
        return switch (face) {
            case DOWN -> 16 - from[1];
            case UP -> to[1];
            case NORTH -> 16 - from[2];
            case SOUTH -> to[2];
            case WEST -> 16 - from[0];
            case EAST -> to[0];
        };
    }

    /** The default uv of an element's face (its natural rectangle), the game's own convention. */
    private static float[] natural(Direction face, float[] f, float[] t) {
        return switch (face) {
            case DOWN -> new float[]{f[0], 16 - t[2], t[0], 16 - f[2]};
            case UP -> new float[]{f[0], f[2], t[0], t[2]};
            case NORTH -> new float[]{16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]};
            case SOUTH -> new float[]{f[0], 16 - t[1], t[0], 16 - f[1]};
            case WEST -> new float[]{f[2], 16 - t[1], t[2], 16 - f[1]};
            case EAST -> new float[]{16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]};
        };
    }

    private static float[] uv(JsonObject face, float[] fallback) {
        if (face.has("uv") && face.get("uv").isJsonArray() && face.getAsJsonArray("uv").size() == 4) {
            JsonArray a = face.getAsJsonArray("uv");
            return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat()};
        }
        return fallback.clone();
    }

    @Nullable
    private static float[] vec(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return null;
        }
        JsonArray a = o.getAsJsonArray(key);
        if (a.size() != 3) {
            return null;
        }
        return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    @Nullable
    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    // ---------------------------------------------------------------- the model's x/y turns

    /**
     * The 3x3 matrix of a variant's turn: model to world ({@code toWorld}: x first, then y, as the game turns it), or
     * world to model. y=90 takes north to east, x=90 takes north down.
     */
    private static int[] matrix(Variant v, boolean toWorld) {
        int[] m = new int[9];
        for (int col = 0; col < 3; col++) {
            int[] e = new int[3];
            e[col] = 1;
            if (toWorld) {
                for (int k = 0; k < v.x(); k++) {
                    e = new int[]{e[0], e[2], -e[1]};
                }
                for (int k = 0; k < v.y(); k++) {
                    e = new int[]{-e[2], e[1], e[0]};
                }
            } else {
                for (int k = 0; k < v.y(); k++) {
                    e = new int[]{e[2], e[1], -e[0]};
                }
                for (int k = 0; k < v.x(); k++) {
                    e = new int[]{e[0], -e[2], e[1]};
                }
            }
            m[col] = e[0];
            m[3 + col] = e[1];
            m[6 + col] = e[2];
        }
        return m;
    }

    private static double[] turn(int[] m, double x, double y, double z) {
        x -= 8;
        y -= 8;
        z -= 8;
        return new double[]{m[0] * x + m[1] * y + m[2] * z + 8, m[3] * x + m[4] * y + m[5] * z + 8, m[6] * x + m[7] * y + m[8] * z + 8};
    }

    private static Direction rotate(int[] m, Direction d) {
        int x = d.getOffsetX();
        int y = d.getOffsetY();
        int z = d.getOffsetZ();
        int rx = m[0] * x + m[1] * y + m[2] * z;
        int ry = m[3] * x + m[4] * y + m[5] * z;
        int rz = m[6] * x + m[7] * y + m[8] * z;
        for (Direction o : Direction.values()) {
            if (o.getOffsetX() == rx && o.getOffsetY() == ry && o.getOffsetZ() == rz) {
                return o;
            }
        }
        return d;
    }

    // ---------------------------------------------------------------- tints

    private static int tintOf(BlockState s) {
        Block b = s.getBlock();
        if (b == Blocks.GRASS_BLOCK || b == Blocks.GRASS || b == Blocks.TALL_GRASS || b == Blocks.FERN || b == Blocks.LARGE_FERN
                || b == Blocks.POTTED_FERN || b == Blocks.SUGAR_CANE) {
            return TINT_GRASS;
        }
        if (b == Blocks.WATER || b == Blocks.WATER_CAULDRON || b == Blocks.BUBBLE_COLUMN) {
            return TINT_WATER;
        }
        if (b == Blocks.SPRUCE_LEAVES || b == Blocks.BIRCH_LEAVES || b == Blocks.MANGROVE_LEAVES || b == Blocks.LILY_PAD
                || b == Blocks.REDSTONE_WIRE || b == Blocks.MELON_STEM || b == Blocks.PUMPKIN_STEM
                || b == Blocks.ATTACHED_MELON_STEM || b == Blocks.ATTACHED_PUMPKIN_STEM) {
            return TINT_FIXED;
        }
        return TINT_FOLIAGE; // leaves, vines, and whatever a mod tints
    }

    private static int fixedTint(BlockState s, Map<String, String> props) {
        Block b = s.getBlock();
        if (b == Blocks.SPRUCE_LEAVES) {
            return 0x619961;
        }
        if (b == Blocks.BIRCH_LEAVES) {
            return 0x80A755;
        }
        if (b == Blocks.MANGROVE_LEAVES) {
            return 0x92C648;
        }
        if (b == Blocks.LILY_PAD) {
            return 0x208030;
        }
        if (b == Blocks.ATTACHED_MELON_STEM || b == Blocks.ATTACHED_PUMPKIN_STEM) {
            return 0xE0C71C;
        }
        if (b == Blocks.MELON_STEM || b == Blocks.PUMPKIN_STEM) {
            int age = number(props.get("age"));
            return (age * 32) << 16 | (255 - age * 8) << 8 | age * 4;
        }
        if (b == Blocks.REDSTONE_WIRE) {
            double f = number(props.get("power")) / 15.0;
            double r = f * 0.6 + (f > 0 ? 0.4 : 0.3);
            double g = Math.max(0, Math.min(1, f * f * 0.7 - 0.5));
            double bl = Math.max(0, Math.min(1, f * f * 0.6 - 0.7));
            return (int) (r * 255) << 16 | (int) (g * 255) << 8 | (int) (bl * 255);
        }
        return 0xFFFFFF;
    }

    private static int number(@Nullable String s) {
        try {
            return s == null ? 0 : Math.max(0, Math.min(15, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- sampling

    /** The mip level for a pixel that covers this many texels. */
    public static int level(double texels) {
        if (texels < 1.5) {
            return 0;
        }
        if (texels < 3) {
            return 1;
        }
        if (texels < 6) {
            return 2;
        }
        if (texels < 12) {
            return 3;
        }
        return 4;
    }

    private static int multiply(int argb, int tint) {
        if (tint == 0xFFFFFF) {
            return argb;
        }
        int r = ((argb >> 16) & 255) * ((tint >> 16) & 255) / 255;
        int g = ((argb >> 8) & 255) * ((tint >> 8) & 255) / 255;
        int b = (argb & 255) * (tint & 255) / 255;
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /**
     * The brush of one picture: the textures and the biome tints of what the rays touch. Not shared between threads;
     * a new one for every picture or tick.
     */
    public static final class Pincel {
        private final ServerWorld world;
        private final BlockPos.Mutable at = new BlockPos.Mutable();
        private long biomeKey = Long.MIN_VALUE;
        private int grass = GRASS_DEFAULT;
        private int foliage = FOLIAGE_DEFAULT;
        private int water = WATER_DEFAULT;
        private boolean biomes = true;
        /** where the last {@link #shape} hit, along the ray, and on which side (null: a cross plane) */
        public double t;
        @Nullable
        public Direction side;

        Pincel(ServerWorld world) {
            this.world = world;
        }

        @Nullable
        public Cara cara(BlockState s, Direction face) {
            Caras c = caras(s);
            return c == null ? null : c.face[face.ordinal()];
        }

        /** Whether the state has textures at all (else the picture keeps its map colour). */
        public boolean textured(BlockState s) {
            return caras(s) != null;
        }

        /** Whether the state is drawn as a cross of planes (flowers, grass, saplings). */
        public boolean cross(BlockState s) {
            Caras c = caras(s);
            return c != null && c.cross != null;
        }

        /** Whether the state's model fills its whole cell. */
        public boolean full(BlockState s) {
            Caras c = caras(s);
            return c == null || c.full;
        }

        /**
         * The colour (0xRRGGBB) the picture paints for this face at a point of the block (0..1 inside it), tinted;
         * a hole of a cutout texture (leaves) shows the leaves deeper in, darker. -1 when the state has no texture.
         */
        public int color(BlockState s, Direction face, int x, int y, int z, double lx, double ly, double lz, int level) {
            Cara c = cara(s, face);
            if (c == null) {
                return -1;
            }
            int argb = texel(c, x, y, z, lx, ly, lz, level);
            boolean fluid = !s.getFluidState().isEmpty() && s.getFluidState().getBlockState().getBlock() == s.getBlock();
            if (!fluid && (argb >>> 24) < 128) {
                int avg = tinted(c.tex.average(), c.tint, c.fixed, x, y, z);
                return Foto.scale(avg & 0xFFFFFF, 0.55);
            }
            return argb & 0xFFFFFF;
        }

        /** The ARGB texel of a face at a point of the block (0..1), tinted, with its overlay. */
        public int texel(Cara c, int x, int y, int z, double lx, double ly, double lz, int level) {
            double px = clamp16(lx * 16);
            double py = clamp16(ly * 16);
            double pz = clamp16(lz * 16);
            if (c.m != null) {
                int[] m = c.m;
                double cx = px - 8;
                double cy = py - 8;
                double cz = pz - 8;
                px = m[0] * cx + m[1] * cy + m[2] * cz + 8;
                py = m[3] * cx + m[4] * cy + m[5] * cz + 8;
                pz = m[6] * cx + m[7] * cy + m[8] * cz + 8;
            }
            double a;
            double b;
            switch (c.face) {
                case DOWN -> {
                    a = px;
                    b = 16 - pz;
                }
                case UP -> {
                    a = px;
                    b = pz;
                }
                case NORTH -> {
                    a = 16 - px;
                    b = 16 - py;
                }
                case SOUTH -> {
                    a = px;
                    b = 16 - py;
                }
                case WEST -> {
                    a = pz;
                    b = 16 - py;
                }
                default -> {
                    a = 16 - pz;
                    b = 16 - py;
                }
            }
            double u;
            double v;
            if (c.lock) {
                u = a;
                v = b;
            } else {
                double fa = c.n2 - c.n0 < 1.0E-4 ? 0.5 : (a - c.n0) / (c.n2 - c.n0);
                double fb = c.n3 - c.n1 < 1.0E-4 ? 0.5 : (b - c.n1) / (c.n3 - c.n1);
                fa = Math.max(0, Math.min(0.9999, fa));
                fb = Math.max(0, Math.min(0.9999, fb));
                double s;
                double t;
                switch (c.rot) {
                    case 1 -> {
                        s = fb;
                        t = 1 - fa;
                    }
                    case 2 -> {
                        s = 1 - fa;
                        t = 1 - fb;
                    }
                    case 3 -> {
                        s = 1 - fb;
                        t = fa;
                    }
                    default -> {
                        s = fa;
                        t = fb;
                    }
                }
                u = c.u0 + s * (c.u1 - c.u0);
                v = c.v0 + t * (c.v1 - c.v0);
            }
            return at(c, u, v, x, y, z, level);
        }

        /** The texel at uv (0..16) of a face. */
        private int at(Cara c, double u, double v, int x, int y, int z, int level) {
            level = Math.max(0, Math.min(LEVELS - 1, level));
            int size = SIZE >> level;
            int ix = Math.max(0, Math.min(size - 1, (int) (u * size / 16.0)));
            int iy = Math.max(0, Math.min(size - 1, (int) (v * size / 16.0)));
            int k = iy * size + ix;
            int argb = tinted(c.tex.mip[level][k], c.tint, c.fixed, x, y, z);
            if (c.over != null) {
                int over = c.over.mip[level][k];
                if ((over >>> 24) >= 128) {
                    argb = (argb & 0xFF000000) | (tinted(over, c.overTint, c.fixed, x, y, z) & 0xFFFFFF);
                }
            }
            return argb;
        }

        /**
         * Where a ray (from the eye, unit direction) meets the model of the block at (x, y, z): the planes of a cross,
         * or the box of its elements. Returns the ARGB texel there (0 when it misses), and leaves the distance in
         * {@link #t} and the side in {@link #side}.
         */
        public int shape(BlockState s, int x, int y, int z, double ex, double ey, double ez, double dx, double dy, double dz, double footprint) {
            t = Double.POSITIVE_INFINITY;
            side = null;
            Caras cs = caras(s);
            if (cs == null) {
                return 0;
            }
            double ox = ex - x;
            double oy = ey - y;
            double oz = ez - z;
            if (cs.cross != null) {
                return cross(cs.cross, x, y, z, ox, oy, oz, dx, dy, dz, footprint);
            }
            double[] b = cs.box;
            double t0 = 0;
            double t1 = Double.POSITIVE_INFINITY;
            int axis = -1;
            double[] o = {ox, oy, oz};
            double[] d = {dx, dy, dz};
            for (int k = 0; k < 3; k++) {
                if (Math.abs(d[k]) < 1.0E-9) {
                    if (o[k] < b[k] || o[k] > b[k + 3]) {
                        return 0;
                    }
                    continue;
                }
                double near = (b[k] - o[k]) / d[k];
                double far = (b[k + 3] - o[k]) / d[k];
                if (near > far) {
                    double sw = near;
                    near = far;
                    far = sw;
                }
                if (near > t0) {
                    t0 = near;
                    axis = k;
                }
                t1 = Math.min(t1, far);
                if (t0 > t1) {
                    return 0;
                }
            }
            if (axis < 0) {
                return 0; // the eye inside it
            }
            Direction face = switch (axis) {
                case 0 -> dx > 0 ? Direction.WEST : Direction.EAST;
                case 1 -> dy > 0 ? Direction.DOWN : Direction.UP;
                default -> dz > 0 ? Direction.NORTH : Direction.SOUTH;
            };
            Cara c = cs.face[face.ordinal()];
            if (c == null) {
                return 0;
            }
            int argb = texel(c, x, y, z, ox + dx * t0, oy + dy * t0, oz + dz * t0, level(t0 * footprint));
            t = t0;
            side = face;
            return argb;
        }

        private int cross(Cara c, int x, int y, int z, double ox, double oy, double oz, double dx, double dy, double dz, double footprint) {
            int best = 0;
            double bestT = Double.POSITIVE_INFINITY;
            for (int plane = 0; plane < 2; plane++) {
                // x = z, and x + z = 1
                double den = plane == 0 ? dx - dz : dx + dz;
                if (Math.abs(den) < 1.0E-9) {
                    continue;
                }
                double tt = plane == 0 ? (oz - ox) / den : (1 - ox - oz) / den;
                if (tt <= 0 || tt >= bestT) {
                    continue;
                }
                double px = ox + dx * tt;
                double py = oy + dy * tt;
                if (px < 0 || px > 1 || py < 0 || py > 1) {
                    continue;
                }
                int argb = at(c, c.u0 + px * (c.u1 - c.u0), c.v0 + (1 - py) * (c.v1 - c.v0), x, y, z, level(tt * footprint));
                if ((argb >>> 24) >= 128) {
                    best = argb;
                    bestT = tt;
                }
            }
            t = bestT;
            side = null;
            return best;
        }

        /** The average colour of the water at a block, tinted by its biome (0xRRGGBB), or -1. */
        public int water(BlockState s, int x, int y, int z) {
            BlockState w = s.getFluidState().isEmpty() ? s : s.getFluidState().getBlockState();
            Cara c = cara(w, Direction.UP);
            return c == null ? -1 : tinted(c.tex.average(), c.tint, c.fixed, x, y, z) & 0xFFFFFF;
        }

        private int tinted(int argb, int kind, int fixed, int x, int y, int z) {
            return switch (kind) {
                case TINT_NONE -> argb;
                case TINT_FIXED -> multiply(argb, fixed);
                default -> {
                    biome(x, y, z);
                    yield multiply(argb, kind == TINT_GRASS ? grass : kind == TINT_WATER ? water : foliage);
                }
            };
        }

        private void biome(int x, int y, int z) {
            if (!biomes) {
                return;
            }
            long key = BlockPos.asLong(x >> 2, y >> 2, z >> 2);
            if (key == biomeKey) {
                return;
            }
            biomeKey = key;
            try {
                Biome b = world.getBiome(at.set(x, y, z)).value();
                grass = b.getGrassColorAt(x + 0.5, z + 0.5);
                foliage = b.getFoliageColor();
                water = b.getWaterColor();
            } catch (RuntimeException | LinkageError e) {
                biomes = false; // the plain tints from now on
                grass = GRASS_DEFAULT;
                foliage = FOLIAGE_DEFAULT;
                water = WATER_DEFAULT;
            }
        }

        private static double clamp16(double v) {
            return v < 0 ? 0 : Math.min(16, v);
        }
    }
}
