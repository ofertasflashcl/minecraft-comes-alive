package net.mca.server;

import net.mca.MCA;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * "Camara rapida": the whole server runs f times as fast (or slow), so a villager's day can be watched in minutes.
 * The tick length lives on the server instance (MixinMinecraftServer): every world load starts
 * at 1x and nothing is saved, so a world never silently comes back sped up. The clients need nothing.
 */
public final class GameSpeed {
    public static final float MIN = 0.1f;
    public static final float MAX = 20.0f;
    /** "/mca-velocidad max": a 1 ms tick, as fast as the machine allows. */
    public static final float SPRINT = 50.0f;

    /** How long one measurement of the real ticks per second lasts. */
    private static final long SAMPLE_MS = 1000L;

    /** Implemented on MinecraftServer by the mixin. */
    public interface Holder {
        float mca$getSpeed();

        void mca$setSpeed(float speed);

        /** False when the tick loop never went through the hook (another mod owns it): the speed cannot change. */
        boolean mca$speedHookLive();
    }

    private static float measuredTps = 20.0f;
    private static long sampleStartMs = -1L;
    private static int sampleStartTick;
    /** The server whose hook was already checked, so the check is logged once per world load. */
    private static WeakReference<MinecraftServer> checked = new WeakReference<>(null);

    private GameSpeed() {
    }

    public static float get(MinecraftServer server) {
        return server == null ? 1.0f : ((Holder) server).mca$getSpeed();
    }

    /** Server thread only. Clamped to MIN..SPRINT; the schedule restarts from now, so there is no catch-up burst. */
    public static void set(MinecraftServer server, float speed) {
        float clamped = Math.max(MIN, Math.min(SPRINT, speed));
        ((Holder) server).mca$setSpeed(clamped);
        sampleStartMs = -1L;
    }

    /** Whether this server's tick loop can be sped up at all (see {@link Holder#mca$speedHookLive()}). */
    public static boolean available(MinecraftServer server) {
        return server != null && ((Holder) server).mca$speedHookLive();
    }

    /** Ticks per second the server really ran during the last second: the machine may not keep up with the speed. */
    public static float measuredTps() {
        return measuredTps;
    }

    /** Once per server tick, from {@link ServerInteractionManager#tick(MinecraftServer)}. */
    static void onTick(MinecraftServer server) {
        if (checked.get() != server) {
            checked = new WeakReference<>(server);
            // the loop went through the hook before this very first tick, if it is there at all
            if (available(server)) {
                MCA.LOGGER.info("[Tropeles] /mca-velocidad listo: el juego puede ir de {} a {} (o max)", format(MIN), format(MAX));
            } else {
                MCA.LOGGER.warn("[Tropeles] el gancho de velocidad no se aplicó a MinecraftServer.runServer (¿otro mod cambia"
                        + " el bucle de ticks?): /mca-velocidad no podrá acelerar el juego");
            }
        }
        long now = Util.getMeasuringTimeMs();
        int ticks = server.getTicks();
        if (sampleStartMs < 0L || ticks < sampleStartTick) {
            sampleStartMs = now;
            sampleStartTick = ticks;
            return;
        }
        long elapsed = now - sampleStartMs;
        if (elapsed >= SAMPLE_MS && ticks > sampleStartTick) {
            measuredTps = (ticks - sampleStartTick) * 1000.0f / elapsed;
            sampleStartMs = now;
            sampleStartTick = ticks;
        }
    }

    /** Someone joining a sped-up world is told so, and how to undo it. */
    static void onPlayerJoin(ServerPlayerEntity player) {
        float speed = get(player.getServer());
        if (speed != 1.0f) {
            player.sendMessage(Text.literal("[MCA] Velocidad del juego: " + format(speed)
                    + " (/mca-velocidad normal para volver a 1x)").formatted(Formatting.GOLD), false);
        }
    }

    /** "4x", "0.50x", "max". */
    public static String format(float speed) {
        if (speed >= SPRINT) {
            return "max";
        }
        return (speed == (int) speed ? Integer.toString((int) speed) : String.format(Locale.ROOT, "%.2f", speed)) + "x";
    }
}
