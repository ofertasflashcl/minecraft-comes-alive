package net.mca.entity.ai.mente;

import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.body.Hands;
import net.mca.entity.ai.body.Motor;
import net.mca.entity.ai.body.Postura;
import net.mca.entity.ai.chatAI.MenteLocal;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "¡Para un momento!": the instant interruption. A stop word in chat reaches the villager on the server thread, the
 * very tick it arrives, before any mind (local or online) has thought about it and before the answers it still owed
 * from earlier messages: whatever it was doing (digging, walking, chopping, climbing), the legs and hands let go at
 * once, it crouches like a player holding shift, turns its head to the one who spoke and asks what is going on.
 * <p>
 * Answers it was still working on from before the stop are dropped, and the orders a model chose in them are not
 * carried out ({@link #despues}): what was said before "para" no longer counts, as with a person. The pause lasts
 * until the player says something else to it (an order, "tranquilo, sigue", a question: the next message is the
 * explanation), it gets hurt, the player goes away, or {@link #TICKS} pass; then it stands up and carries on where it
 * left off, its job and its mine untouched.
 * <p>
 * A connected villager's will ({@link Voluntad}, mode ALERTA) owns its pause; this class only adds the instant
 * delivery, the forced crouch and the dropping of stale answers. Everyone else gets the pause kept here.
 */
public final class Interrupcion {
    /** The longest a pause waits for an explanation: 30 seconds. */
    public static final int TICKS = 600;
    /** Beyond this distance the one who stopped it has gone away. */
    private static final double LEJOS = 48.0;
    /** Crouch reason in {@link Postura}. */
    private static final String AGACHE = "pausa";

    /** A pause: who stopped it, the world time it ends at and the last world time it was renewed. */
    private record Pausa(UUID quien, String nombre, long hasta, boolean es) {
    }

    private static final Map<UUID, Pausa> PAUSAS = new ConcurrentHashMap<>();
    /** Per villager: the wall clock (ms) of its last interruption, read by the chat threads. */
    private static final Map<UUID, Long> ULTIMA = new ConcurrentHashMap<>();
    /** The wall clock (ms) a chat thread's turn started at, so a model's late order can be checked against a stop. */
    private static final ThreadLocal<Long> TURNO = new ThreadLocal<>();

    private Interrupcion() {
    }

    /**
     * A chat message just arrived for {@code villager} (server thread). When it is a stop ("para", "espera un
     * momento", "detente", "quieto", "cuidado", "alto", "wait", "hold on"...) the villager stops this very tick, says
     * its question at once and true is returned: the caller then does not start a slower answer. Any other message
     * to a villager on pause ends the pause first and is answered as usual (false).
     */
    public static boolean oir(ServerPlayerEntity player, VillagerEntityMCA villager, String msg) {
        UUID id = villager.getUuid();
        String name = villager.getName().getString();
        MenteLocal.Intent read;
        try {
            read = MenteLocal.read(msg, name);
        } catch (RuntimeException e) {
            return false;
        }
        String cmd = read.command();
        boolean stop = ("alert".equals(cmd) || "stop".equals(cmd)) && read.question() == null;
        if (!stop) {
            if (PAUSAS.containsKey(id) && !Voluntad.conectado(villager)) {
                // the explanation: whatever it is, the pause is over and the message is answered as usual
                soltar(villager, null);
            }
            return false;
        }
        boolean es = MenteLocal.speaksSpanish(msg, name, player.getUuid());
        detener(player, villager, es);
        String line = Voluntad.pregunta(villager, es);
        net.mca.entity.ai.chatAI.OpenAIChatAI.remember(id, player.getUuid(), msg, line);
        Recuerdos.anotar(villager, "charla", player.getName().getString() + " me dijo «" + msg + "» y me detuve en seco; le pregunté «" + line + "»",
                villager.getBlockPos());
        net.mca.entity.ai.chatAI.ChatAI.deliver(player, villager, line);
        MCA.LOGGER.info("[Tropeles] {} se detiene al instante por «{}» de {} y pregunta «{}»", name, msg, player.getName().getString(), line);
        return true;
    }

    /**
     * Stops it dead now (server thread), from the chat or from a model's "alert": legs and hands let go, it crouches,
     * turns to {@code player}, and the answers still being worked out from before are dropped. Returns what its will
     * made of it for a model (a connected villager's alert), else null.
     */
    @Nullable
    public static String detener(PlayerEntity player, VillagerEntityMCA villager, boolean es) {
        UUID id = villager.getUuid();
        ULTIMA.put(id, System.currentTimeMillis());
        String how = null;
        if (Voluntad.conectado(villager)) {
            how = Voluntad.alerta(villager, player, es);
        } else {
            PAUSAS.put(id, new Pausa(player.getUuid(), player.getName().getString(), villager.getWorld().getTime() + TICKS, es));
        }
        quieto(villager, player);
        Postura.agachar(villager, AGACHE, true, true);
        Recuerdos.anotar(villager, "plan", player.getName().getString() + " me pidió que parara: me quedo quieto y pregunto qué pasa", villager.getBlockPos());
        return how;
    }

    /** Ends a pause (server thread): it stands up and goes back to what it was doing; {@code line} is said if given. */
    public static void soltar(VillagerEntityMCA villager, @Nullable String line) {
        Pausa p = PAUSAS.remove(villager.getUuid());
        Postura.agachar(villager, AGACHE, false);
        if (p != null && line != null && villager.getWorld() instanceof ServerWorld world) {
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(p.quien());
            if (player != null) {
                net.mca.entity.ai.chatAI.ChatAI.deliver(player, villager, line);
            }
        }
    }

    /** On pause right now (its own or its will's alert): the job's tick leaves the body alone. */
    public static boolean pausado(VillagerEntityMCA villager) {
        return PAUSAS.containsKey(villager.getUuid())
                || Voluntad.conectado(villager) && Voluntad.of(villager).modo() == Voluntad.Modo.ALERTA;
    }

    /** Every server tick of the villager: keeps a pause still, crouched and looking, until it ends. */
    public static void tick(ServerWorld world, VillagerEntityMCA villager) {
        UUID id = villager.getUuid();
        if (Voluntad.conectado(villager)) {
            // its will owns the alert; the crouch held for it goes when the alert does
            if (Voluntad.of(villager).modo() != Voluntad.Modo.ALERTA && Postura.agachado(villager, AGACHE)) {
                Postura.agachar(villager, AGACHE, false);
            } else if (Voluntad.of(villager).modo() == Voluntad.Modo.ALERTA) {
                PAUSAS.remove(id);
            }
            return;
        }
        Pausa p = PAUSAS.get(id);
        if (p == null) {
            if (Postura.agachado(villager, AGACHE)) {
                Postura.agachar(villager, AGACHE, false);
            }
            return;
        }
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(p.quien());
        long now = world.getTime();
        if (now >= p.hasta() || player == null || player.getWorld() != world || player.distanceTo(villager) > LEJOS
                || villager.hurtTime > 0 || villager.isSleeping()) {
            soltar(villager, villager.hurtTime > 0 || villager.isSleeping() ? null : now >= p.hasta() ? sigo(villager, p.es()) : null);
            return;
        }
        quieto(villager, player);
    }

    /**
     * Whether the chat turn running on this thread started before the villager's last interruption: its answer and
     * any order a model chose in it come from before "para" and are dropped. {@code receivedMs} is the turn's start;
     * pass 0 to use the one set with {@link #turno}.
     */
    public static boolean despues(UUID villager, long receivedMs) {
        long start = receivedMs > 0 ? receivedMs : TURNO.get() == null ? 0 : TURNO.get();
        Long last = ULTIMA.get(villager);
        return start > 0 && last != null && last > start;
    }

    /** Marks the chat turn this thread works on ({@code null} clears it). */
    public static void turno(@Nullable Long receivedMs) {
        if (receivedMs == null) {
            TURNO.remove();
        } else {
            TURNO.set(receivedMs);
        }
    }

    /** Legs and hands off, head on the one who spoke. */
    private static void quieto(VillagerEntityMCA villager, PlayerEntity player) {
        Motor.standStill(villager);
        villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        villager.getBrain().forget(MemoryModuleType.LOOK_TARGET);
        Hands.aim(villager, player.getEyePos());
        villager.getLookControl().lookAt(player, 30.0F, 30.0F);
    }

    private static String sigo(VillagerEntityMCA villager, boolean es) {
        return es ? "Bueno, como no me dices nada, sigo con lo mío." : "Well, since you're not saying anything, I'll get back to it.";
    }
}
