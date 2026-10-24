package net.mca.server;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.mca.Config;
import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.VillagerLike;
import net.mca.entity.ai.Chore;
import net.mca.mixin.MixinPlayerListS2CPacketAccessor;
import net.mca.util.WorldUtils;
import net.minecraft.command.EntitySelectorReader;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.LiteralTextContent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.PersistentState;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A villager doing the PROSPECT chore is listed like an online player: a Tab entry (a fake, unsigned
 * PlayerListS2CPacket entry keyed by the villager's own entity UUID, so the spectator menu teleports to it), yellow
 * joined/left lines and its death line, "{@code <Inas> ...}" chat (Messenger, ChatAI), its name for /tp and other
 * commands (MixinEntitySelector) and a chunk ticket, so it keeps working when nobody is near, as a player would.
 * Where each one is gets saved with the world: after a restart its chunk is loaded again, so a villager whose saved
 * job is still PROSPECT comes back online by itself instead of waiting, frozen, for someone to walk by.
 * Its body and senses are untouched: this only changes how the server presents it. Server thread only.
 */
public final class ProspectorPresence {
    private static final int REFRESH_TICKS = 20;
    /** A listed villager whose chunk unloaded leaves after this long unseen. */
    private static final int GRACE_TICKS = 600;
    /** Level 30: 3x3 chunks where entities tick, 7x7 loaded, like a player standing there. */
    private static final int TICKET_RADIUS = 3;
    /** Expires on its own: refreshed every REFRESH_TICKS while it prospects, gone 5 s after it stops or moves on. */
    private static final ChunkTicketType<UUID> TICKET = ChunkTicketType.create("mca_prospector", UUID::compareTo, 100);
    private static final EnumSet<PlayerListS2CPacket.Action> ADD_ACTIONS = EnumSet.of(
            PlayerListS2CPacket.Action.ADD_PLAYER, PlayerListS2CPacket.Action.UPDATE_GAME_MODE,
            PlayerListS2CPacket.Action.UPDATE_LISTED, PlayerListS2CPacket.Action.UPDATE_LATENCY,
            PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME);
    /** The same job line again this soon (real time) is not said at all: at 20x a chore talks 20x as often. */
    private static final long REPEAT_DROP_MS = 2_000L;
    /** The same job line again this soon goes only to the one it is addressed to, not to everyone. */
    private static final long REPEAT_PRIVATE_MS = 10_000L;
    /** A name no player has, resolved once per server start to check that the selector hook is live. */
    private static final String PROBE_NAME = "mca_sonda_tp";
    /** After a start, how long a villager saved as prospecting may take to load and show up before it is forgotten. */
    private static final int RETURN_WAIT_TICKS = 1200;

    public enum Route { EVERYONE, PRIVATE, DROP }

    private static final Map<UUID, Listing> LISTED = new LinkedHashMap<>();
    private static final Map<String, Long> LAST_LINE = new HashMap<>();
    /** The listed profile names, rebuilt on the server thread after every change: the console may read it from its own. */
    private static volatile List<String> names = List.of();
    private static volatile WeakReference<MinecraftServer> owner = new WeakReference<>(null);
    /** The bound server's saved spots, and the tick it was bound at (start of the wait for the saved ones). */
    private static Spots spots = new Spots();
    private static int boundAt;
    private static boolean probeSeen;
    /** Set whenever the console-completion hook (MixinServerCommandSource) asks for the names: it is live. */
    private static volatile boolean namesAsked;

    private static final class Listing {
        final UUID uuid;
        String fullName;
        String profileName;
        int lastSeen;

        Listing(UUID uuid, String fullName, String profileName, int lastSeen) {
            this.uuid = uuid;
            this.fullName = fullName;
            this.profileName = profileName;
            this.lastSeen = lastSeen;
        }
    }

    /** Where a listed villager was last seen: its world's id and its chunk. */
    private record Spot(String world, long chunk) {
    }

    /** The spots of the listed villagers, saved with the overworld ("mca_prospectors"). */
    private static final class Spots extends PersistentState {
        final Map<UUID, Spot> byVillager = new LinkedHashMap<>();

        Spots() {
        }

        Spots(NbtCompound nbt) {
            NbtList list = nbt.getList("spots", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                NbtCompound entry = list.getCompound(i);
                if (entry.containsUuid("villager")) {
                    byVillager.put(entry.getUuid("villager"), new Spot(entry.getString("world"), entry.getLong("chunk")));
                }
            }
        }

        void put(UUID villager, Spot spot) {
            if (!spot.equals(byVillager.put(villager, spot))) {
                markDirty();
            }
        }

        void remove(UUID villager) {
            if (byVillager.remove(villager) != null) {
                markDirty();
            }
        }

        @Override
        public NbtCompound writeNbt(NbtCompound nbt) {
            NbtList list = new NbtList();
            byVillager.forEach((villager, spot) -> {
                NbtCompound entry = new NbtCompound();
                entry.putUuid("villager", villager);
                entry.putString("world", spot.world());
                entry.putLong("chunk", spot.chunk());
                list.add(entry);
            });
            nbt.put("spots", list);
            return nbt;
        }
    }

    private ProspectorPresence() {
    }

    /** Every server tick, from {@link ServerInteractionManager#tick(MinecraftServer)}. */
    public static void tick(MinecraftServer server) {
        if (server.getTicks() % REFRESH_TICKS == 0) {
            refreshNow(server);
        }
    }

    /**
     * Lists the villagers that prospect now, renames the ones a real player's name collides with, drops the rest,
     * and keeps the chunks of every prospector loaded. The two are switched apart: prospectorLoadsChunks keeps a
     * prospector working when nobody is near even with prospectorListedAsPlayer off.
     */
    public static void refreshNow(MinecraftServer server) {
        bind(server);
        boolean enabled = Config.getInstance().prospectorListedAsPlayer;
        boolean loadsChunks = Config.getInstance().prospectorLoadsChunks;
        int now = server.getTicks();
        Map<UUID, VillagerEntityMCA> prospecting = new LinkedHashMap<>();
        Set<UUID> loadedButIdle = new HashSet<>();
        for (ServerWorld world : server.getWorlds()) {
            for (Entity e : world.iterateEntities()) {
                if (e instanceof VillagerEntityMCA v) {
                    if (v.isAlive() && v.getVillagerBrain().getCurrentJob() == Chore.PROSPECT) {
                        prospecting.put(v.getUuid(), v);
                    } else if (LISTED.containsKey(v.getUuid()) || spots.byVillager.containsKey(v.getUuid())) {
                        loadedButIdle.add(v.getUuid());
                    }
                }
            }
        }
        // the ones shown in Tab: every prospector, or none when the listing is switched off
        Map<UUID, VillagerEntityMCA> shown = enabled ? prospecting : Map.of();
        Set<UUID> savedBefore = new HashSet<>(spots.byVillager.keySet());

        List<Listing> gone = new ArrayList<>();
        for (Listing l : LISTED.values()) {
            if (shown.containsKey(l.uuid)) {
                l.lastSeen = now;
            } else if (!enabled || loadedButIdle.contains(l.uuid) || now - l.lastSeen > GRACE_TICKS || now < l.lastSeen) {
                gone.add(l);
            }
        }
        for (Listing l : gone) {
            leave(server, l, true);
        }

        if (loadsChunks) {
            for (VillagerEntityMCA v : prospecting.values()) {
                spots.put(v.getUuid(), new Spot(v.getWorld().getRegistryKey().getValue().toString(), v.getChunkPos().toLong()));
            }
        }
        returnSaved(server, loadsChunks, now, prospecting.keySet(), loadedButIdle);

        // real players always keep their name
        Set<String> taken = new HashSet<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            taken.add(p.getGameProfile().getName().toLowerCase(Locale.ROOT));
        }
        List<Listing> renamed = new ArrayList<>();
        for (Listing l : LISTED.values()) {
            VillagerEntityMCA v = shown.get(l.uuid);
            String full = v == null ? l.fullName : fullName(v);
            if (!full.equals(l.fullName) || taken.contains(l.profileName.toLowerCase(Locale.ROOT))) {
                renamed.add(l);
            } else {
                taken.add(l.profileName.toLowerCase(Locale.ROOT));
            }
        }
        for (Listing l : renamed) {
            VillagerEntityMCA v = shown.get(l.uuid);
            if (v != null) {
                l.fullName = fullName(v);
            }
            l.profileName = pickProfileName(l.fullName, taken);
            taken.add(l.profileName.toLowerCase(Locale.ROOT));
            // the client keys entries by UUID and ignores a second ADD (putIfAbsent): remove, then add again
            server.getPlayerManager().sendToAll(new PlayerRemoveS2CPacket(List.of(l.uuid)));
            server.getPlayerManager().sendToAll(addPacket(List.of(l)));
        }

        for (VillagerEntityMCA v : shown.values()) {
            if (!LISTED.containsKey(v.getUuid())) {
                String full = fullName(v);
                Listing l = new Listing(v.getUuid(), full, pickProfileName(full, taken), now);
                taken.add(l.profileName.toLowerCase(Locale.ROOT));
                LISTED.put(l.uuid, l);
                server.getPlayerManager().sendToAll(addPacket(List.of(l)));
                server.getPlayerManager().broadcast(Text.translatable("multiplayer.player.joined", nameText(l))
                        .formatted(Formatting.YELLOW), false);
                if (savedBefore.contains(l.uuid)) {
                    MCA.LOGGER.info("[Tropeles] {} vuelve a su mina tras cargar el mundo: en Tab como '{}'", full, l.profileName);
                } else {
                    MCA.LOGGER.info("[Tropeles] {} sale a buscar minerales: en Tab como '{}'", full, l.profileName);
                }
            }
        }

        if (loadsChunks) {
            for (VillagerEntityMCA v : prospecting.values()) {
                ServerWorld world = (ServerWorld) v.getWorld();
                world.getChunkManager().addTicket(TICKET, v.getChunkPos(), TICKET_RADIUS, v.getUuid());
                // a world with no player in it stops ticking its entities 300 ticks after the last one left (its
                // idle timeout), ticket or not: a prospector keeps its world awake as a player standing there would,
                // so it goes on mining while the one who sent it is in the Nether
                world.resetIdleTimeout();
            }
        }
        snapshotNames();
    }

    /** From ServerInteractionManager.onPlayerJoin, after vanilla sent the real player list: the villagers too. */
    public static void onPlayerJoin(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null || owner.get() != server || LISTED.isEmpty() || player.networkHandler == null) {
            return;
        }
        try {
            String joining = player.getGameProfile().getName();
            for (Listing l : LISTED.values()) {
                if (l.profileName.equalsIgnoreCase(joining)) {
                    // a real player always keeps their name: the villager is renamed before anyone sees two of them
                    refreshNow(server);
                    break;
                }
            }
            player.networkHandler.sendPacket(addPacket(LISTED.values()));
        } catch (RuntimeException e) {
            // a joining player must never be kicked over how the villagers are shown
            MCA.LOGGER.error("[Tropeles] no se pudo mandar la lista de aldeanos en Tab a {}", player.getName().getString(), e);
        }
    }

    /**
     * From VillagerEntityMCA.onDeath, server side, after super.onDeath and only when it really died. The message was
     * read before super.onDeath (that clears the damage record). Like a player: the death line, then it leaves.
     */
    public static void onDeath(VillagerEntityMCA villager, Text deathMessage) {
        MinecraftServer server = villager.getServer();
        if (server == null || owner.get() != server) {
            return;
        }
        Listing l = LISTED.get(villager.getUuid());
        if (l == null) {
            return;
        }
        try {
            if (villager.getWorld().getGameRules().getBoolean(GameRules.SHOW_DEATH_MESSAGES)) {
                server.getPlayerManager().broadcast(deathMessage, false);
            }
            leave(server, l, true);
        } catch (RuntimeException e) {
            // the death itself (drops, family, village) goes on whatever happens here
            LISTED.remove(l.uuid);
            spots.remove(l.uuid);
            MCA.LOGGER.error("[Tropeles] no se pudo anunciar la muerte de {}", l.fullName, e);
        }
        snapshotNames();
    }

    /**
     * The spots saved by an earlier run whose villager is not back yet: their chunks are loaded so it can come back
     * by itself. A spot is dropped once its villager turns out to have stopped, or never shows up, and all of them
     * when prospectors do not load chunks.
     */
    private static void returnSaved(MinecraftServer server, boolean loadsChunks, int now, Set<UUID> prospecting, Set<UUID> loadedButIdle) {
        List<UUID> stale = new ArrayList<>();
        for (Map.Entry<UUID, Spot> e : spots.byVillager.entrySet()) {
            UUID id = e.getKey();
            if (loadsChunks && (prospecting.contains(id) || LISTED.containsKey(id))) {
                continue;
            }
            ServerWorld world = worldNamed(server, e.getValue().world());
            if (!loadsChunks || world == null || loadedButIdle.contains(id) || now - boundAt > RETURN_WAIT_TICKS) {
                stale.add(id);
            } else {
                world.getChunkManager().addTicket(TICKET, new ChunkPos(e.getValue().chunk()), TICKET_RADIUS, id);
                world.resetIdleTimeout();
            }
        }
        for (UUID id : stale) {
            spots.remove(id);
        }
    }

    @Nullable
    private static ServerWorld worldNamed(MinecraftServer server, String id) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(id)) {
                return world;
            }
        }
        return null;
    }

    private static void leave(MinecraftServer server, Listing l, boolean announce) {
        LISTED.remove(l.uuid);
        spots.remove(l.uuid);
        String prefix = l.uuid + "|";
        LAST_LINE.keySet().removeIf(k -> k.startsWith(prefix));
        server.getPlayerManager().sendToAll(new PlayerRemoveS2CPacket(List.of(l.uuid)));
        if (announce) {
            server.getPlayerManager().broadcast(Text.translatable("multiplayer.player.left", nameText(l))
                    .formatted(Formatting.YELLOW), false);
        }
    }

    /** A new server (another world loaded in single player) starts with nobody listed. */
    private static void bind(MinecraftServer server) {
        if (owner.get() != server) {
            LISTED.clear();
            LAST_LINE.clear();
            names = List.of();
            owner = new WeakReference<>(server);
            boundAt = server.getTicks();
            try {
                spots = WorldUtils.loadData(server.getOverworld(), Spots::new, w -> new Spots(), "mca_prospectors");
            } catch (RuntimeException e) {
                // a broken file only costs the return after a restart
                MCA.LOGGER.error("[Tropeles] no se pudo leer dónde estaban los aldeanos mineros", e);
                spots = new Spots();
            }
            if (!spots.byVillager.isEmpty()) {
                MCA.LOGGER.info("[Tropeles] {} aldeano(s) estaban buscando minerales al cerrar: se carga su zona para que vuelvan",
                        spots.byVillager.size());
            }
            selfCheck(server);
        }
    }

    private static void snapshotNames() {
        List<String> now = new ArrayList<>(LISTED.size());
        for (Listing l : LISTED.values()) {
            now.add(l.profileName);
        }
        names = List.copyOf(now);
    }

    /**
     * Once per server start: resolves a name no player has through a real selector, which loads EntitySelector
     * (and so applies its mixin) and tells whether the name hook is live, and builds an empty Tab packet, which
     * applies the PlayerListS2CPacket accessor, and asks the server's own name completion, which goes through
     * MixinServerCommandSource. A broken mixin then shows in the first second, not at the first /tp.
     */
    private static void selfCheck(MinecraftServer server) {
        probeSeen = false;
        namesAsked = false;
        try {
            new EntitySelectorReader(new StringReader(PROBE_NAME)).read().getEntities(server.getCommandSource());
        } catch (CommandSyntaxException | RuntimeException e) {
            MCA.LOGGER.warn("[Tropeles] la sonda de /tp por nombre no se pudo leer: {}", e.toString());
        }
        addPacket(List.of());
        try {
            server.getCommandSource().getPlayerNames();
        } catch (RuntimeException e) {
            MCA.LOGGER.warn("[Tropeles] la sonda de nombres de la consola falló: {}", e.toString());
        }
        if (!namesAsked) {
            MCA.LOGGER.info("[Tropeles] el gancho de ServerCommandSource no se aplicó: la consola no completará los"
                    + " nombres de los aldeanos en Tab (los jugadores sí)");
        }
        if (probeSeen) {
            MCA.LOGGER.info("[Tropeles] los aldeanos que buscan minerales salen en Tab; /tp por su nombre listo");
        } else {
            MCA.LOGGER.warn("[Tropeles] el gancho de EntitySelector no se aplicó: a un aldeano en Tab solo se llega"
                    + " por el menú de espectador o por su UUID");
        }
    }

    public static boolean isListed(Entity entity) {
        MinecraftServer server = entity.getServer();
        return server != null && owner.get() == server && LISTED.containsKey(entity.getUuid());
    }

    /**
     * The listed villager a typed name means: its profile name (ignoring case), then its full name without accents,
     * then a unique first word. Null when nobody, or more than one, fits.
     */
    @Nullable
    public static Entity findByName(MinecraftServer server, String typed) {
        if (PROBE_NAME.equals(typed)) {
            probeSeen = true;
            return null;
        }
        if (owner.get() != server || LISTED.isEmpty() || typed == null || typed.isBlank()) {
            return null;
        }
        String key = normalize(typed);
        Listing hit = null;
        for (Listing l : LISTED.values()) {
            if (l.profileName.equalsIgnoreCase(typed)) {
                hit = l;
                break;
            }
        }
        if (hit == null) {
            for (Listing l : LISTED.values()) {
                if (normalize(l.fullName).equals(key)) {
                    hit = l;
                    break;
                }
            }
        }
        if (hit == null) {
            for (Listing l : LISTED.values()) {
                if (normalize(l.fullName.split("\\s+", 2)[0]).equals(key)) {
                    if (hit != null) {
                        return null; // two "Ines": the player has to say which
                    }
                    hit = l;
                }
            }
        }
        return hit == null ? null : find(server, hit.uuid);
    }

    /** The names the listed villagers go by in Tab (immutable; safe from any thread, e.g. the console's completer). */
    public static Collection<String> profileNames(MinecraftServer server) {
        namesAsked = true;
        return owner.get() == server ? names : List.of();
    }

    /** The Tab entry this villager is shown with, or null when it is not listed. */
    @Nullable
    public static PlayerListS2CPacket tabPacket(Entity villager) {
        Listing l = isListed(villager) ? LISTED.get(villager.getUuid()) : null;
        return l == null ? null : addPacket(List.of(l));
    }

    /** The name it goes by in Tab and for /tp (at most 16 of [A-Za-z0-9_]), or null when it is not listed. */
    @Nullable
    public static String profileName(Entity villager) {
        Listing l = isListed(villager) ? LISTED.get(villager.getUuid()) : null;
        return l == null ? null : l.profileName;
    }

    @Nullable
    private static Entity find(MinecraftServer server, UUID uuid) {
        for (ServerWorld world : server.getWorlds()) {
            Entity e = world.getEntity(uuid);
            if (e != null && !e.isRemoved()) {
                return e;
            }
        }
        return null;
    }

    /** "{@code <Inas> }" like vanilla chat.type.text; the line itself is sent as a system message, never signed. */
    public static MutableText chatPrefix(Entity villager) {
        Listing l = LISTED.get(villager.getUuid());
        MutableText name = l == null ? Text.literal(villager.getName().getString()) : nameText(l);
        return Text.literal("<").append(name).append("> ");
    }

    /**
     * Where a line addressed to someone goes. A chat answer (literal text) is said to everyone, like player chat. A
     * job report ("chore.*") goes to everyone too, but the same report again within 10 s (real time) only to the one
     * it is addressed to, and within 2 s not at all. Replies to the interaction menu stay with whoever clicked.
     * A job report call records its time: call it once per line said.
     */
    public static Route route(Entity villager, Text message) {
        if (message.getContent() instanceof LiteralTextContent) {
            return Route.EVERYONE;
        }
        String key = message.getContent() instanceof TranslatableTextContent t ? t.getKey() : "";
        // MCA keys read "#G..#E..#P..#T<dialogue type>.chore.mining.orenotify"
        int at = key.indexOf("chore.");
        if (at < 0) {
            return Route.PRIVATE;
        }
        String id = villager.getUuid() + "|" + key.substring(at);
        long now = Util.getMeasuringTimeMs();
        Long last = LAST_LINE.get(id);
        if (last != null && now - last < REPEAT_DROP_MS) {
            return Route.DROP;
        }
        LAST_LINE.put(id, now);
        return last != null && now - last < REPEAT_PRIVATE_MS ? Route.PRIVATE : Route.EVERYONE;
    }

    /**
     * A job line for the one who sent the villager, found by UUID on the whole server (another dimension too).
     * Silent when that player is offline: never addressed to a stranger, whose name and gender the line would use.
     */
    public static void sayToAssigner(VillagerEntityMCA villager, String key, Object... params) {
        MinecraftServer server = villager.getServer();
        if (server == null) {
            return;
        }
        Optional<UUID> id = villager.getVillagerBrain().getJobAssignerUuid();
        ServerPlayerEntity target = id.isPresent() ? server.getPlayerManager().getPlayer(id.get()) : null;
        if (target != null && target.networkHandler != null) {
            villager.sendChatMessage(target, key, params);
        }
    }

    /** VILLAGER_NAME, not getDisplayName(): that one adds " (Prospecting)". */
    private static String fullName(VillagerEntityMCA v) {
        String name = v.getTrackedValue(VillagerLike.VILLAGER_NAME);
        if (name == null || name.isBlank()) {
            name = v.getName().getString();
        }
        return name.trim();
    }

    /** The name as players see it in chat: a click puts "/tp @s Inas" in the chat box, shift-click starts an answer. */
    private static MutableText nameText(Listing l) {
        String go = "/tp @s " + l.profileName;
        String bring = "/tp " + l.profileName + " @s";
        MutableText tip = Text.literal(l.fullName + "\n")
                .append(Text.literal("Clic: " + go + "\nTraerlo: " + bring + "\nMayús+clic: responder").formatted(Formatting.GRAY));
        return Text.literal(l.fullName).styled(s -> s
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, tip))
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, go))
                .withInsertion(l.profileName + ", "));
    }

    /** The full name and a pick in Tab, so a villager is told apart from a real player at a glance. */
    private static Text tabName(Listing l) {
        return Text.literal(l.fullName).append(Text.literal(" ⛏").formatted(Formatting.GRAY));
    }

    private static PlayerListS2CPacket addPacket(Collection<Listing> listings) {
        List<PlayerListS2CPacket.Entry> entries = new ArrayList<>(listings.size());
        for (Listing l : listings) {
            // (profileId, profile, listed, latency, gameMode, displayName, chatSession): no session, nothing signed
            entries.add(new PlayerListS2CPacket.Entry(l.uuid, new GameProfile(l.uuid, l.profileName),
                    true, 0, GameMode.SURVIVAL, tabName(l), null));
        }
        // a fresh EnumSet per packet: in single player the packet object itself is handed to the client thread
        PlayerListS2CPacket packet = new PlayerListS2CPacket(EnumSet.copyOf(ADD_ACTIONS), List.of());
        ((MixinPlayerListS2CPacketAccessor) packet).mca$setEntries(entries);
        return packet;
    }

    /** First word, accents stripped, [A-Za-z0-9_], at most 16 (the packet refuses longer), "Aldeano" when nothing is left. */
    static String pickProfileName(String fullName, Set<String> takenLowercase) {
        String first = fullName.isBlank() ? "" : fullName.trim().split("\\s+", 2)[0];
        String base = Normalizer.normalize(first, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replaceAll("[^A-Za-z0-9_]", "");
        if (base.isEmpty()) {
            base = "Aldeano";
        }
        if (base.length() > 16) {
            base = base.substring(0, 16);
        }
        String name = base;
        for (int i = 2; takenLowercase.contains(name.toLowerCase(Locale.ROOT)); i++) {
            String suffix = Integer.toString(i);
            name = base.substring(0, Math.min(base.length(), 16 - suffix.length())) + suffix;
        }
        return name;
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
