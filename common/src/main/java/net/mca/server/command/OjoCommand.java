package net.mca.server.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.body.Mining;
import net.mca.entity.ai.sense.Entorno;
import net.mca.entity.ai.sense.Escena;
import net.mca.entity.ai.sense.Lugar;
import net.mca.entity.ai.sense.Mirada;
import net.mca.entity.ai.sense.Obras;
import net.mca.entity.ai.sense.Pantalla;
import net.mca.entity.ai.sense.Tacto;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * {@code /mca-ojo}: what the nearest villager is seeing right now. The block under its crosshair with the
 * exact state, face and distance (like Jade), the ore it remembers, the bodies in view, what it says it is
 * doing, where it thinks it is and what it would say it sees, with {@code vista} its whole retina drawn in
 * characters, with {@code mirar} after a whole look around first, and with {@code pantalla [nombre]} the
 * {@link Pantalla} of a connected villager: its PNG on disk and what is on it.
 */
public final class OjoCommand {
    private OjoCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("mca-ojo")
                .executes(ctx -> show(ctx, false, false))
                .then(CommandManager.literal("vista").executes(ctx -> show(ctx, true, false)))
                .then(CommandManager.literal("mirar").executes(ctx -> show(ctx, false, true)))
                .then(CommandManager.literal("pantalla")
                        .executes(ctx -> pantalla(ctx, null))
                        .then(CommandManager.argument("nombre", StringArgumentType.greedyString())
                                .executes(ctx -> pantalla(ctx, StringArgumentType.getString(ctx, "nombre"))))));
    }

    /**
     * {@code /mca-ojo pantalla [nombre]}: the screen of a villager (by name anywhere in this world, or the nearest
     * connected one, or the nearest one): where its PNG is (a click opens it in singleplayer), what is on it, how far
     * it reaches and what it costs.
     */
    private static int pantalla(CommandContext<ServerCommandSource> ctx, @Nullable String name) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        Vec3d from = source.getPosition();
        VillagerEntityMCA villager = null;
        if (name != null) {
            String wanted = name.trim().toLowerCase(Locale.ROOT);
            double best = Double.MAX_VALUE;
            for (Entity e : world.iterateEntities()) {
                if (e instanceof VillagerEntityMCA v && v.isAlive()) {
                    String own = v.getName().getString().toLowerCase(Locale.ROOT);
                    if ((own.equals(wanted) || own.startsWith(wanted)) && v.squaredDistanceTo(from) < best) {
                        best = v.squaredDistanceTo(from);
                        villager = v;
                    }
                }
            }
        } else {
            List<VillagerEntityMCA> near = world.getEntitiesByClass(VillagerEntityMCA.class, new Box(from, from).expand(64), VillagerEntityMCA::isAlive);
            villager = near.stream().filter(Pantalla::on).min(Comparator.comparingDouble(v -> v.squaredDistanceTo(from)))
                    .orElse(near.stream().filter(v -> v.squaredDistanceTo(from) < 16 * 16).min(Comparator.comparingDouble(v -> v.squaredDistanceTo(from))).orElse(null));
        }
        if (villager == null) {
            source.sendError(Text.literal(name == null ? "No hay aldeanos conectados a menos de 64 bloques (ni otro a menos de 16)." : "No encuentro a ningún aldeano llamado " + name + "."));
            return 0;
        }
        Pantalla screen = Pantalla.get(villager);
        boolean live = screen != null && Pantalla.on(villager);
        if (screen == null || screen.frames() == 0) {
            // not connected: one whole frame now, so there is something to show
            screen = Pantalla.of(villager);
            screen.drawAll(world, villager);
        }
        screen.save(world, villager);
        Path file = Pantalla.path(world, villager);
        say(source, Text.literal("Pantalla de " + villager.getName().getString() + (live ? " (conectado)" : " (no conectado: un cuadro ahora)")).formatted(Formatting.GOLD));
        Text link = Text.literal(file.toString()).formatted(Formatting.AQUA, Formatting.UNDERLINE)
                .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.toString()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Abrir la imagen"))));
        Text copy = Text.literal(" [copiar]").formatted(Formatting.GRAY)
                .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, file.toString())));
        say(source, Text.literal("Imagen: ").append(link).append(copy));
        say(source, Text.literal("Ve: " + screen.resumen(world, true)).formatted(Formatting.WHITE));
        say(source, Text.literal(String.format(Locale.ROOT, "Alcance %.0f bloques (último cuadro %.0f, lo más lejano %.0f), cielo %.0f%%",
                screen.range(), screen.lastRange(), screen.lastFarthest(), screen.lastSkyShare() * 100.0)).formatted(Formatting.GRAY));
        say(source, Text.literal("Coste: " + screen.costLine()).formatted(Formatting.GRAY));
        say(source, Text.literal("Tacto: " + Tacto.describeOverhead(villager, true)).formatted(Formatting.GRAY));
        return 1;
    }

    private static int show(CommandContext<ServerCommandSource> ctx, boolean view, boolean around) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        Vec3d from = source.getPosition();
        List<VillagerEntityMCA> near = world.getEntitiesByClass(VillagerEntityMCA.class, new Box(from, from).expand(16), VillagerEntityMCA::isAlive);
        VillagerEntityMCA villager = near.stream().min(Comparator.comparingDouble(v -> v.squaredDistanceTo(from))).orElse(null);
        if (villager == null) {
            source.sendError(Text.literal("No hay aldeanos a menos de 16 bloques."));
            return 0;
        }
        long now = world.getTime();
        VisualMemory memory = Retina.memory(villager);
        if (around) {
            Mirada.lookAround(world, villager);
        }
        Retina.Focus focus = Retina.focus(world, villager, memory, now);

        say(source, Text.literal("Ojo de " + villager.getName().getString()).formatted(Formatting.GOLD));
        say(source, Text.literal("Haciendo: " + (memory.doing() == null || memory.doing().isEmpty() ? "nada en particular" : memory.doing())));
        say(source, Text.literal("Voluntad: " + net.mca.entity.ai.mente.Voluntad.resumen(villager)));
        if (focus == null) {
            say(source, Text.literal("Mira: nada (cielo o demasiado oscuro)"));
        } else if (focus.entity() != null) {
            say(source, Text.literal(String.format(Locale.ROOT, "Mira: %s a %.2f bloques%s",
                    focus.entity().getName().getString(), focus.distance(), focus.inReach() ? " (al alcance)" : "")));
        } else if (focus.state() != null && focus.pos() != null) {
            say(source, Text.literal(String.format(Locale.ROOT, "Mira: %s", focus.state().getBlock().getName().getString())).formatted(Formatting.AQUA));
            say(source, Text.literal(String.format(Locale.ROOT, "  %s, cara %s, en %s, a %.2f bloques%s",
                    focus.state(), focus.face(), focus.pos().toShortString(), focus.distance(), focus.inReach() ? " (al alcance)" : "")));
            Obras.Kind kind = Obras.kind(world, villager, focus.pos());
            say(source, Text.literal("  Lo reconoce como: " + kind.label + (Obras.mayBreak(world, villager, focus.pos()) ? " (lo puede romper)" : " (no lo rompe)"))
                    .formatted(kind == Obras.Kind.NATURE || kind == Obras.Kind.TREE ? Formatting.GREEN : Formatting.YELLOW));
        }

        Lugar lugar = Entorno.lugar(world, villager);
        say(source, Text.literal(String.format(Locale.ROOT, "Lugar: %s, profundidad %d%s, %d celdas en su mapa",
                lugar.palabra(), Entorno.profundidad(world, villager), Entorno.bajoTierra(world, villager) ? " (bajo tierra para minar)" : "",
                memory.mapa().size())).formatted(lugar == Lugar.BAJO_TIERRA ? Formatting.YELLOW : Formatting.GREEN));
        say(source, Text.literal("Dice: " + Escena.of(world, villager, 1200).describe(true)).formatted(Formatting.WHITE));

        List<VisualMemory.SeenBlock> ores = memory.blocks(s -> Mining.isOre(s.state));
        ores.sort(Comparator.comparingDouble(s -> s.pos.getSquaredDistance(villager.getBlockPos())));
        say(source, Text.literal("Minerales vistos: " + ores.size() + " (" + memory.blockCount() + " bloques en memoria)"));
        for (int i = 0; i < Math.min(6, ores.size()); i++) {
            VisualMemory.SeenBlock ore = ores.get(i);
            say(source, Text.literal(String.format(Locale.ROOT, "  %s en %s, hace %ds",
                    ore.state.getBlock().getName().getString(), ore.pos.toShortString(), (now - ore.lastSeen) / 20)));
        }
        List<VisualMemory.SeenEntity> bodies = memory.entities(now, 40);
        say(source, Text.literal("Cuerpos a la vista: " + bodies.size()));
        for (int i = 0; i < Math.min(5, bodies.size()); i++) {
            VisualMemory.SeenEntity body = bodies.get(i);
            say(source, Text.literal(String.format(Locale.ROOT, "  %s a %.1f bloques", body.type.getName().getString(), body.distance)));
        }

        if (view) {
            Retina.lookAll(world, villager);
            Retina.Pixel[] frame = memory.frame();
            if (frame != null) {
                int w = memory.frameWidth();
                for (int y = 0; y < memory.frameHeight(); y++) {
                    StringBuilder row = new StringBuilder();
                    for (int x = 0; x < w; x++) {
                        row.append(glyph(frame[y * w + x]));
                    }
                    say(source, Text.literal(row.toString()).formatted(Formatting.GRAY));
                }
                say(source, Text.literal("# sólido  o mineral  ~ agua  L lava  E cuerpo  . oscuro  (espacio) cielo"));
            }
        }
        return 1;
    }

    private static char glyph(Retina.Pixel pixel) {
        if (pixel == null) {
            return '?';
        }
        if (pixel.entity() != null) {
            return 'E';
        }
        if (pixel.dark()) {
            return '.';
        }
        BlockState state = pixel.state();
        if (state == null) {
            return pixel.water() ? '~' : ' ';
        }
        if (Mining.isOre(state)) {
            return 'o';
        }
        if (!state.getFluidState().isEmpty()) {
            return state.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA) ? 'L' : '~';
        }
        if (pixel.water()) {
            return '~';
        }
        return '#';
    }

    private static void say(ServerCommandSource source, Text text) {
        source.sendFeedback(() -> text, false);
    }
}
