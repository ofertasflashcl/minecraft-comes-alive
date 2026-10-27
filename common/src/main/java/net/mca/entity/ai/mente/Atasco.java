package net.mca.entity.ai.mente;

import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.body.Mining;
import net.minecraft.block.Block;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * Never stuck anywhere: the feeling a player gets after a few seconds of pressing on and getting nowhere (seven,
 * not a quarter of a minute: a player does not wait that long to try something else).
 * Once a second it looks at three things: where its feet are (more than a block from where they were counts), what
 * is in its bag, and the blocks around it (one broken or placed counts). When none of them changed for
 * {@value #TICKS} ticks, and it was not standing still by choice (waiting, on alert, resting at home, hiding, its
 * iron in the furnace), it tries the next remedy, one more each round:
 * <ol>
 * <li>{@link Remedio#REINICIAR}: forget the half-made step and the plan, think again from where it stands;</li>
 * <li>{@link Remedio#SOLTAR}: give up what it was going for (that ore, that spot, that animal, that cell of its way)
 * and leave it alone for a while;</li>
 * <li>{@link Remedio#APARTARSE}: a step to a free cell beside it;</li>
 * <li>{@link Remedio#CAVAR}: boxed in, it digs toward the nearest open cell it remembers walking ({@link Recuerdos}),
 * or a step up ahead (its task first looks for a way on foot to where it was going: digging is for a real box);</li>
 * <li>{@link Remedio#AYUDA}: it asks for help in the chat, with its exact coordinates, and starts over at the second
 * remedy.</li>
 * </ol>
 * Getting more than {@value #FREED} blocks away from where it got stuck, or {@value #FORGIVE} ticks without being
 * stuck, ends the episode. Every remedy is logged as {@code [Tropeles] <name> atascado ...: <what it tries>}.
 */
public final class Atasco {
    public static final int TICKS = 140;
    /** Ticks a step aside or a dig-out may take before it counts as failed. */
    public static final int MOVE_TICKS = 200;
    public static final int DIG_TICKS = 600;
    private static final int FREED = 4;
    private static final int FORGIVE = 2400;

    public enum Remedio {NINGUNO, REINICIAR, SOLTAR, APARTARSE, CAVAR, AYUDA}

    @Nullable
    private BlockPos ancla;
    private int bolsa = -1;
    private int mundo;
    private int quieto;
    private int nivel;
    @Nullable
    private BlockPos origen;
    private long ultimoNivel;
    @Nullable
    private BlockPos meta;
    private boolean cavar;
    private int metaTicks;

    /**
     * One tick. {@code porGusto}: it is standing still by choice right now (nothing is counted). Returns the remedy to
     * apply this tick, NINGUNO almost always. {@code haciendo} is what it thinks it is doing, for the log.
     */
    public Remedio tick(ServerWorld world, VillagerEntityMCA villager, boolean porGusto, String haciendo) {
        if ((villager.age % 20) != 3) {
            return Remedio.NINGUNO;
        }
        long now = world.getTime();
        BlockPos feet = villager.getBlockPos();
        int items = items(villager.getInventory());
        int around = around(world, feet);
        boolean moved = ancla == null || ancla.getSquaredDistance(feet) > 1.0;
        if (porGusto || moved || items != bolsa || around != mundo) {
            ancla = feet;
            bolsa = items;
            mundo = around;
            quieto = 0;
            if (origen != null && (porGusto || !origen.isWithinDistance(feet, FREED) || now - ultimoNivel > FORGIVE)) {
                if (!porGusto && !origen.isWithinDistance(feet, FREED)) {
                    MCA.LOGGER.info("[Tropeles] {} ya no está atascado: salió de {} a {}", villager.getName().getString(), origen.toShortString(), feet.toShortString());
                }
                origen = null;
                nivel = 0;
            }
            return Remedio.NINGUNO;
        }
        quieto += 20;
        if (quieto < TICKS) {
            return Remedio.NINGUNO;
        }
        quieto = 0;
        if (origen == null) {
            origen = feet;
        }
        ultimoNivel = now;
        nivel = nivel >= 5 ? 2 : nivel + 1;
        Remedio r = Remedio.values()[nivel];
        MCA.LOGGER.info("[Tropeles] {} atascado {} s en {} ({}): {}", villager.getName().getString(), TICKS / 20, feet.toShortString(),
                haciendo == null || haciendo.isBlank() ? "sin hacer nada" : haciendo, switch (r) {
                    case REINICIAR -> "olvida el paso a medias y el plan, y piensa de nuevo";
                    case SOLTAR -> "deja lo que buscaba y lo ignora un rato";
                    case APARTARSE -> "da un paso a una celda libre de al lado";
                    case CAVAR -> "está encerrado: cava hacia la celda abierta más cercana que recuerda, o hacia el cielo";
                    case AYUDA -> "pide ayuda en el chat con sus coordenadas";
                    default -> "";
                });
        return r;
    }

    /** The step aside or dig-out under way, or null. */
    @Nullable
    public BlockPos meta() {
        return meta;
    }

    public boolean cavando() {
        return cavar;
    }

    /** Sets a step aside ({@code dig} false) or a dig-out ({@code dig} true) to drive for a while. */
    public void mover(BlockPos goal, boolean dig) {
        meta = goal.toImmutable();
        cavar = dig;
        metaTicks = 0;
    }

    /** One more tick of the move; false once it is out of time. */
    public boolean seguirMoviendo() {
        return meta != null && ++metaTicks <= (cavar ? DIG_TICKS : MOVE_TICKS);
    }

    /** The move is over: {@code failed} skips straight to the next remedy on the next round. */
    public void fin(boolean failed) {
        boolean wasDig = cavar;
        meta = null;
        cavar = false;
        metaTicks = 0;
        if (failed) {
            // a step aside that did not work: the next round digs; a dig-out that did not work: the next asks for help
            nivel = wasDig ? 4 : 3;
            quieto = TICKS - 40;
        }
    }

    /** Where it got stuck this time, or null when it is not. */
    @Nullable
    public BlockPos origen() {
        return origen;
    }

    public void reset() {
        ancla = null;
        quieto = 0;
        nivel = 0;
        origen = null;
        meta = null;
        cavar = false;
    }

    /** A free cell touching the feet it can step to without digging (round from {@code turn}), or null when boxed in. */
    @Nullable
    public static BlockPos libreAlLado(ServerWorld world, BlockPos feet, int turn, @Nullable Recuerdos recuerdos) {
        BlockPos best = null;
        for (int k = 0; k < 8; k++) {
            int dir = (k + turn) & 7;
            int dx = new int[]{1, 1, 0, -1, -1, -1, 0, 1}[dir];
            int dz = new int[]{0, 1, 1, 1, 0, -1, -1, -1}[dir];
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos cell = feet.add(dx, dy, dz);
                if (!Mining.passable(world, cell) || !Mining.passable(world, cell.up()) || !Mining.standable(world, cell.down()) || Mining.heat(world, cell)) {
                    continue;
                }
                if (dy > 0 && !Mining.passable(world, feet.up(2)) || dy < 0 && !Mining.passable(world, cell.up(2))) {
                    continue;
                }
                if (dx != 0 && dz != 0 && !(Mining.passable(world, feet.add(dx, Math.max(0, dy), 0)) || Mining.passable(world, feet.add(0, Math.max(0, dy), dz)))) {
                    continue;
                }
                if (recuerdos != null && recuerdos.recuerda(cell)) {
                    return cell; // a cell it walked before is a known good one
                }
                if (best == null) {
                    best = cell;
                }
            }
        }
        return best;
    }

    /**
     * Where to dig to when boxed in: the nearest open cell it remembers walking, or a step up ahead. Never the nearest
     * cell out on the land: that was a whole staircase of its own cut toward the sky, right beside the one it came down
     * by (and the way out is the caller's to find without digging first).
     */
    public static BlockPos salidaCavando(ServerWorld world, BlockPos feet, @Nullable Recuerdos recuerdos, Direction facing) {
        BlockPos open = recuerdos == null ? null : recuerdos.abiertoCerca(world, feet, 16, false);
        if (open != null) {
            return open;
        }
        return feet.offset(facing).up(2);
    }

    private static int items(SimpleInventory inv) {
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            n += inv.getStack(i).getCount() * 31 + i;
        }
        return n;
    }

    /** A fingerprint of the blocks around the feet: it changes when one is broken or placed. */
    private static int around(ServerWorld world, BlockPos feet) {
        int h = 1;
        BlockPos.Mutable cell = new BlockPos.Mutable();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    cell.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    h = h * 31 + Block.getRawIdFromState(world.getBlockState(cell));
                }
            }
        }
        return h;
    }
}
