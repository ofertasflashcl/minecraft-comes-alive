package net.mca.entity.ai.sense;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.mente.Voluntad;
import net.mca.mixin.MixinServerWorldSleepAccessor;
import net.mca.mixin.MixinSleepManagerAccessor;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.SleepManager;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Sleep as a player sleeps: a connected villager ({@link Voluntad#conectado}) is one more player of its world, so
 * when it lies in a bed it counts toward skipping the night and toward the "X/Y jugadores durmiendo" the real players
 * see over the hotbar. total = real players (not spectators) + connected villagers of that world; sleeping = sleeping
 * players + sleeping connected villagers; the night goes when the playersSleepingPercentage game rule is reached and
 * everyone counted has slept at least {@link #SLEPT_ENOUGH} ticks, exactly the vanilla rule
 * ({@code SleepManager}). The world's own counter is kept with these numbers by the mixin on {@code ServerWorld}
 * ({@link #actualizar} every {@link #EVERY} ticks and whenever a player lies down or gets up).
 * <p>
 * The will ({@code mente}) decides when to sleep; this only lies it down ({@link #acostar}), keeps it lying (the brain's
 * WakeUpTask would get it up at once outside the REST activity) and gets it up at the skip, in the morning, when hurt or
 * when the bed goes.
 */
public final class Dormir {
    /** Ticks in bed before a sleeper may end the night: a player's sleep timer reaches 100 the same way. */
    public static final int SLEPT_ENOUGH = 100;
    /** Ticks between two recounts of a world. */
    public static final int EVERY = 10;
    /** How far from the bed it may lie down, like a player's reach to a bed. */
    private static final double BED_REACH = 2.5;

    /** When each villager was first seen asleep (world time). */
    private static final Map<VillagerEntityMCA, Long> ASLEEP_SINCE = new WeakHashMap<>();
    /** Villagers lying in a bed by their own will: the brain does not get them up. */
    private static final Set<VillagerEntityMCA> HELD = Collections.newSetFromMap(new WeakHashMap<>());
    /** For the in-game checks: villagers counted as connected without a running connection. */
    private static final Set<VillagerEntityMCA> PRUEBA = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<ServerWorld, Long> LAST_SKIP = new WeakHashMap<>();
    /** Getting up on purpose right now: the wake-up is let through. */
    private static boolean releasing;

    private Dormir() {
    }

    /** Counted as a connected villager: its connection runs (or a check says so). */
    public static boolean conectado(VillagerEntityMCA villager) {
        return villager.isAlive() && (PRUEBA.contains(villager) || Voluntad.conectado(villager));
    }

    /** For the in-game checks only: count this villager as connected (or stop). */
    public static void contarComoConectado(VillagerEntityMCA villager, boolean on) {
        if (on) {
            PRUEBA.add(villager);
        } else {
            PRUEBA.remove(villager);
        }
    }

    private static List<? extends VillagerEntityMCA> connected(ServerWorld world) {
        return world.getEntitiesByType(TypeFilter.instanceOf(VillagerEntityMCA.class), Dormir::conectado);
    }

    /** Connected villagers alive in this world. */
    public static int conectados(ServerWorld world) {
        return connected(world).size();
    }

    /** Of those, the ones asleep in a bed. */
    public static int conectadosDurmiendo(ServerWorld world) {
        int n = 0;
        for (VillagerEntityMCA v : connected(world)) {
            if (v.isSleeping()) {
                n++;
            }
        }
        return n;
    }

    /** {total, sleeping}: real players (no spectators) plus connected villagers, and the sleepers among them. */
    public static int[] cuenta(ServerWorld world) {
        int total = 0;
        int sleeping = 0;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isSpectator()) {
                total++;
                if (player.isSleeping()) {
                    sleeping++;
                }
            }
        }
        for (VillagerEntityMCA v : connected(world)) {
            total++;
            if (v.isSleeping()) {
                sleeping++;
            }
        }
        return new int[]{total, sleeping};
    }

    /** Sleepers needed out of {@code total} with the game rule at {@code percentage}: the vanilla formula. */
    public static int necesarios(int total, int percentage) {
        return Math.max(1, MathHelper.ceil((float) (total * percentage) / 100.0F));
    }

    /** The night may go: enough of them asleep (and sleeping is on: a percentage over 100 turns it off). */
    public static boolean saltaNoche(int total, int sleeping, int percentage) {
        return percentage <= 100 && sleeping >= necesarios(total, percentage);
    }

    private static int percentage(ServerWorld world) {
        return world.getGameRules().getInt(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
    }

    /**
     * Writes the counts into the world's sleep counter. True when they changed in a way the players are told about
     * (someone is or was asleep), as {@code SleepManager.update} answers.
     */
    private static boolean recount(ServerWorld world) {
        SleepManager manager = ((MixinServerWorldSleepAccessor) world).mca$sleepManager();
        MixinSleepManagerAccessor counter = (MixinSleepManagerAccessor) manager;
        int oldTotal = counter.mca$getTotal();
        int oldSleeping = counter.mca$getSleeping();
        int[] now = cuenta(world);
        counter.mca$setTotal(now[0]);
        counter.mca$setSleeping(now[1]);
        long time = world.getTime();
        for (VillagerEntityMCA v : connected(world)) {
            if (v.isSleeping()) {
                ASLEEP_SINCE.putIfAbsent(v, time);
            } else {
                ASLEEP_SINCE.remove(v);
            }
        }
        return (oldSleeping > 0 || now[1] > 0) && (oldTotal != now[0] || oldSleeping != now[1]);
    }

    /**
     * Recounts now and, when it changed, shows the players of the world "X/Y" (or that the night is being skipped),
     * the vanilla way. The mixin calls it in place of {@code ServerWorld.updateSleepingPlayers} and every
     * {@link #EVERY} ticks; call it right after a villager lies down or gets up if you want the message at once.
     */
    public static void actualizar(ServerWorld world) {
        if (recount(world) && !world.getPlayers().isEmpty()) {
            ((MixinServerWorldSleepAccessor) world).mca$sendSleepingStatus();
        }
    }

    /** Sleepers who have slept long enough to end the night: players by their sleep timer, villagers by {@link #SLEPT_ENOUGH}. */
    private static int ready(ServerWorld world) {
        int n = 0;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.canResetTimeBySleeping()) {
                n++;
            }
        }
        long time = world.getTime();
        for (VillagerEntityMCA v : connected(world)) {
            Long since = ASLEEP_SINCE.get(v);
            if (v.isSleeping() && since != null && time - since >= SLEPT_ENOUGH) {
                n++;
            }
        }
        return n;
    }

    /**
     * The start of each world tick (the mixin): every {@link #EVERY} ticks the counts are made again, and when connected
     * villagers are among the sleepers the night is skipped by the same rule vanilla uses for players (vanilla checks only
     * the players' own timers, so with a villager needed it never would).
     */
    public static void tick(ServerWorld world) {
        if (world.getTime() % EVERY == 0) {
            actualizar(world);
        }
        if (ASLEEP_SINCE.isEmpty()) {
            return;
        }
        int pct = percentage(world);
        int[] now = cuenta(world);
        if (!saltaNoche(now[0], now[1], pct) || conectadosDurmiendo(world) == 0) {
            return;
        }
        if (ready(world) < necesarios(now[0], pct)) {
            return;
        }
        net.mca.MCA.LOGGER.info("[Tropeles] {}/{} durmiendo (necesarios {}): pasa la noche", now[1], now[0], necesarios(now[0], pct));
        if (world.getGameRules().getBoolean(GameRules.DO_DAYLIGHT_CYCLE)) {
            long next = world.getTimeOfDay() + 24000L;
            world.setTimeOfDay(next - next % 24000L);
        }
        MixinServerWorldSleepAccessor access = (MixinServerWorldSleepAccessor) world;
        access.mca$wakeSleepingPlayers();
        if (world.getGameRules().getBoolean(GameRules.DO_WEATHER_CYCLE) && world.isRaining()) {
            access.mca$resetWeather();
        }
    }

    /** After the night was skipped (the mixin, at the end of {@code wakeSleepingPlayers}): the villagers get up too. */
    public static void nocheSaltada(ServerWorld world) {
        LAST_SKIP.put(world, world.getTime());
        for (VillagerEntityMCA v : connected(world)) {
            if (v.isSleeping()) {
                levantar(world, v);
            }
        }
        ASLEEP_SINCE.keySet().removeIf(v -> v.getWorld() == world);
        recount(world);
    }

    /** World time of the last night skipped in this world, or Long.MIN_VALUE. */
    public static long ultimaNocheSaltada(ServerWorld world) {
        return LAST_SKIP.getOrDefault(world, Long.MIN_VALUE);
    }

    // ------------------------------------------------------------------ lying down and getting up

    /** The head of a bed nobody lies in, within {@code radius} of {@code near}, nearest first; null when none. */
    @Nullable
    public static BlockPos camaLibre(ServerWorld world, BlockPos near, int radius) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(near.add(-radius, -radius / 2 - 1, -radius), near.add(radius, radius / 2 + 1, radius))) {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof BedBlock && state.get(BedBlock.PART) == BedPart.HEAD && !state.get(BedBlock.OCCUPIED)) {
                double d = pos.getSquaredDistance(near);
                if (d < bestDistance) {
                    best = pos.toImmutable();
                    bestDistance = d;
                }
            }
        }
        return best;
    }

    /**
     * Lies down in the bed whose head is at {@code bedHead}, as a player does: in reach of it, the bed free, at night or
     * in a thunderstorm. True when it is asleep in it.
     */
    public static boolean acostar(ServerWorld world, VillagerEntityMCA villager, BlockPos bedHead) {
        if (!world.isNight() && !world.isThundering()) {
            return false;
        }
        return lieDown(world, villager, bedHead);
    }

    /** {@link #acostar} at any hour: for the in-game checks, which run in daylight. */
    public static boolean acostarPrueba(ServerWorld world, VillagerEntityMCA villager, BlockPos bedHead) {
        return lieDown(world, villager, bedHead);
    }

    private static boolean lieDown(ServerWorld world, VillagerEntityMCA villager, BlockPos bedHead) {
        if (villager.isSleeping()) {
            return true;
        }
        BlockState state = world.getBlockState(bedHead);
        if (!(state.getBlock() instanceof BedBlock) || state.get(BedBlock.PART) != BedPart.HEAD || state.get(BedBlock.OCCUPIED)) {
            return false;
        }
        if (villager.getPos().distanceTo(Vec3d.ofBottomCenter(bedHead)) > BED_REACH + 1.0) {
            return false;
        }
        villager.getNavigation().stop();
        villager.sleep(bedHead);
        HELD.add(villager);
        ASLEEP_SINCE.put(villager, world.getTime());
        net.mca.MCA.LOGGER.info("[Tropeles] {} se acuesta en la cama de {}", villager.getName().getString(), bedHead.toShortString());
        actualizar(world);
        return villager.isSleeping();
    }

    /** Gets up now. */
    public static void levantar(ServerWorld world, VillagerEntityMCA villager) {
        HELD.remove(villager);
        ASLEEP_SINCE.remove(villager);
        if (villager.isSleeping()) {
            releasing = true;
            try {
                villager.wakeUp();
            } finally {
                releasing = false;
            }
        }
        actualizar(world);
    }

    /** Asleep in a bed now. */
    public static boolean dormido(VillagerEntityMCA villager) {
        return villager.isSleeping();
    }

    /**
     * Whether a wake-up asked for now is held back (VillagerEntityMCA.wakeUp): lying by its own will, the bed still there,
     * still night. Hurt, the bed gone, morning or {@link #levantar}: it gets up.
     */
    public static boolean retenido(VillagerEntityMCA villager) {
        if (releasing || !HELD.contains(villager)) {
            return false;
        }
        if (!(villager.getWorld() instanceof ServerWorld world) || !villager.isAlive() || villager.hurtTime > 0) {
            HELD.remove(villager);
            return false;
        }
        boolean bed = villager.getSleepingPosition().map(pos -> world.getBlockState(pos).getBlock() instanceof BedBlock).orElse(false);
        if (!bed || !world.isNight() && !world.isThundering()) {
            HELD.remove(villager);
            return false;
        }
        return true;
    }

    /** It was hurt: whatever held it in bed lets go (the hit wakes it as it wakes a player). */
    public static void herido(VillagerEntityMCA villager) {
        HELD.remove(villager);
    }
}
