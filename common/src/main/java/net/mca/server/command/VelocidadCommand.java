package net.mca.server.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.mca.MCA;
import net.mca.server.GameSpeed;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Locale;

/**
 * /mca-velocidad [factor 0.1..20 | normal | max]: the whole game in fast (or slow) motion, to watch the villagers
 * live a day in minutes. Without an argument it shows the speed and the ticks per second the server really manages.
 * Ops may use it, and so may the host of a single player or LAN world even with cheats off.
 */
public final class VelocidadCommand {
    private VelocidadCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("mca-velocidad")
                .requires(VelocidadCommand::allowed)
                .executes(ctx -> show(ctx.getSource()))
                .then(CommandManager.literal("normal").executes(ctx -> set(ctx.getSource(), 1.0f)))
                .then(CommandManager.literal("max").executes(ctx -> set(ctx.getSource(), GameSpeed.SPRINT)))
                .then(CommandManager.argument("factor", FloatArgumentType.floatArg(GameSpeed.MIN, GameSpeed.MAX))
                        .executes(ctx -> set(ctx.getSource(), FloatArgumentType.getFloat(ctx, "factor")))));
    }

    /** Ops (level 2), plus the host of a single player or LAN world: it is their own world, cheats or not. */
    private static boolean allowed(ServerCommandSource source) {
        if (source.hasPermissionLevel(2)) {
            return true;
        }
        ServerPlayerEntity player = source.getPlayer();
        return player != null && source.getServer().isHost(player.getGameProfile());
    }

    private static int show(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        if (!GameSpeed.available(server)) {
            return unavailable(source);
        }
        float speed = GameSpeed.get(server);
        float target = speed >= GameSpeed.SPRINT ? 1000.0f : 20.0f * speed;
        source.sendFeedback(() -> Text.literal(String.format(Locale.ROOT,
                "Velocidad: %s | TPS medidos: %.1f (objetivo %.0f) | tick: %.1f ms",
                GameSpeed.format(speed), GameSpeed.measuredTps(), target, server.getTickTime())), false);
        return Math.round(speed * 100);
    }

    private static int set(ServerCommandSource source, float speed) {
        MinecraftServer server = source.getServer();
        if (!GameSpeed.available(server)) {
            return unavailable(source);
        }
        GameSpeed.set(server, speed);
        float applied = GameSpeed.get(server);
        MCA.LOGGER.info("[Tropeles] velocidad del juego {} (pedida por {})", GameSpeed.format(applied), source.getName());
        // one gold line to everyone, the one who typed it included, and to the console
        server.getPlayerManager().broadcast(Text.literal("[MCA] Velocidad del juego: " + GameSpeed.format(applied)
                + (applied == 1.0f ? "" : " (/mca-velocidad normal para volver a 1x)")).formatted(Formatting.GOLD), false);
        return Math.round(applied * 100);
    }

    /** The tick loop does not go through our hook (another mod replaced it): say so instead of pretending. */
    private static int unavailable(ServerCommandSource source) {
        source.sendError(Text.literal("[MCA] Este servidor no deja cambiar la velocidad: otro mod controla el bucle de ticks"
                + " (mira el registro: \"gancho de velocidad\")."));
        return 0;
    }
}
