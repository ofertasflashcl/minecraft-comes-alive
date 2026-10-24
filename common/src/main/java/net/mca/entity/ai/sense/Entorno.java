package net.mca.entity.ai.sense;

import net.mca.entity.ai.body.Mining;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import org.jetbrains.annotations.Nullable;

/**
 * The villager's sense of place: is it outside, under a roof, or down in the rock, and how far below the land.
 * Built from what a person has: the daylight on the eye and skin (the sky light level, whatever the hour), the sky
 * the eye saw overhead lately, the land heights its eye has seen nearby ({@link MapaVisto}) and the last place it
 * stood outside. A house roof is never a mine; an open ravine is outside but deep.
 * <p>
 * How each case is told:
 * <ul>
 * <li>{@link Lugar#AFUERA}: the sky light at the head is {@value #OPEN_SKY} or more (open sky, or a step under an
 * overhang), or the eye saw the open sky nearly straight up from this same spot in the last
 * {@value #SKY_MEMORY} ticks, or the only thing over the head is a tree's leaves with daylight coming through
 * them, or nothing solid at all within {@value #ROOF_REACH} blocks overhead.</li>
 * <li>{@link Lugar#BAJO_TECHO}: something is overhead and the land is right there. A made roof (a house, a
 * bridge, a porch) or a trunk, with some daylight inside ({@value #HOUSE_SKY}+) or the feet at most
 * {@value #HOUSE_DEPTH} blocks under the land. A natural roof (rock, earth) only near its open side, where the
 * daylight still reaches {@value #MOUTH_SKY}+: the first steps of an overhang or a cave mouth; or inside one of
 * its village's buildings with some daylight (a desert house of sandstone).</li>
 * <li>{@link Lugar#BAJO_TIERRA} otherwise: natural rock overhead and the daylight gone (a tunnel a few blocks into
 * the rock, a cave), or a made roof deep under the land in the dark (an old mineshaft).</li>
 * </ul>
 * The roof is the first solid block in the column over the head, the glance up a person gives, read like a
 * touch: it is a handful of block look-ups, done at most once a tick per villager. A made roof with the open sky a
 * few steps to the side at the height of the head ({@link Tacto#cieloAlLado}) is always the shelter of a building
 * ({@link #refugio}): {@link Lugar#BAJO_TECHO} or {@link Lugar#AFUERA}, and never {@link #bajoTierra}, however
 * deep the land it remembers says it is.
 */
public final class Entorno {
    public static final int OPEN_SKY = 14;
    public static final int HOUSE_SKY = 8;
    public static final int MOUTH_SKY = 12;
    public static final int LEAF_SKY = 10;
    public static final int HOUSE_DEPTH = 3;
    public static final int ROOF_REACH = 24;
    public static final int SKY_MEMORY = 100;
    /** How far around the column the land seen counts toward its level. */
    public static final int LAND_RADIUS = 8;
    /** Deeper than this under the land is down in the rock for a miner's choices, open sky or not. */
    public static final int DEEP = 8;

    private Entorno() {
    }

    /** Where it is right now. Cheap: safe to ask every tick. */
    public static Lugar lugar(ServerWorld world, LivingEntity viewer) {
        VisualMemory memory = Retina.memory(viewer);
        long tick = world.getTime();
        if (memory.placeTick == tick && memory.place != null) {
            return memory.place;
        }
        Lugar place = judge(world, viewer, memory, tick);
        memory.place = place;
        memory.placeTick = tick;
        return place;
    }

    private static Lugar judge(ServerWorld world, LivingEntity viewer, VisualMemory memory, long tick) {
        BlockPos feet = viewer.getBlockPos();
        BlockPos head = BlockPos.ofFloored(viewer.getEyePos());
        int sky = world.getLightLevel(LightType.SKY, head);
        if (sky >= OPEN_SKY || sawSkyHere(memory, feet, tick)) {
            // remembered as the level of the land only out on open ground, not at the bottom of a pit
            Integer land = memory.mapa().landLevel(feet.getX(), feet.getZ(), LAND_RADIUS);
            if ((land == null || land - feet.getY() <= 2) && tick - memory.lastOutsideTick() >= 20 && Mining.openGround(world, feet)) {
                memory.outside(feet, tick);
            }
            return Lugar.AFUERA;
        }
        BlockState roof = roof(world, head);
        if (roof == null) {
            return sky >= 6 ? Lugar.AFUERA : Lugar.BAJO_TIERRA;
        }
        if (roof.isIn(BlockTags.LEAVES)) {
            // under a tree: the canopy lets the day through
            return sky >= LEAF_SKY ? Lugar.AFUERA : Lugar.BAJO_TECHO;
        }
        if (Obras.made(roof) || roof.isIn(BlockTags.LOGS)) {
            if (sky >= HOUSE_SKY || profundidad(world, viewer) <= HOUSE_DEPTH) {
                return Lugar.BAJO_TECHO;
            }
            // a made roof with the open sky a few steps to the side at this same level: an eave, a porch, a house
            if (Tacto.cieloAlLado(world, viewer, Tacto.SIDE_SKY) >= 0) {
                return Lugar.BAJO_TECHO;
            }
            return Lugar.BAJO_TIERRA;
        }
        if (sky >= MOUTH_SKY) {
            return Lugar.BAJO_TECHO;
        }
        // a sandstone or earth roof over one of its village's own houses is still a house
        if (sky >= HOUSE_SKY && Obras.inVillageBuilding(world, feet)) {
            return Lugar.BAJO_TECHO;
        }
        return Lugar.BAJO_TIERRA;
    }

    private static boolean sawSkyHere(VisualMemory memory, BlockPos feet, long tick) {
        BlockPos at = memory.skyFeet();
        return at != null && tick - memory.skyTick() <= SKY_MEMORY
                && Math.abs(at.getX() - feet.getX()) <= 1 && Math.abs(at.getZ() - feet.getZ()) <= 1
                && Math.abs(at.getY() - feet.getY()) <= 2;
    }

    /** The first block over the head that stops the look (fluids, glass, plants and the like do not), or null. */
    @Nullable
    private static BlockState roof(ServerWorld world, BlockPos head) {
        BlockPos.Mutable cell = head.mutableCopy();
        int top = Math.min(world.getTopY(), head.getY() + ROOF_REACH);
        for (int y = head.getY() + 1; y < top; y++) {
            cell.setY(y);
            BlockState state = world.getBlockState(cell);
            if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(world, cell).isEmpty()) {
                continue;
            }
            if (Retina.seeThrough(world, cell, state)) {
                continue;
            }
            return state;
        }
        return null;
    }

    /**
     * Blocks between its feet and the land around it: 0 on the land, positive below it. Taken from the land its eye
     * saw near this column, else the last height it stood outside at, else the sea level.
     */
    public static int profundidad(ServerWorld world, LivingEntity viewer) {
        VisualMemory memory = Retina.memory(viewer);
        long tick = world.getTime();
        if (memory.depthTick == tick) {
            return memory.depth;
        }
        BlockPos feet = viewer.getBlockPos();
        Integer land = memory.mapa().landLevel(feet.getX(), feet.getZ(), LAND_RADIUS);
        int level;
        if (land != null) {
            level = land;
        } else if (memory.lastOutside() != null) {
            level = memory.lastOutside().getY();
        } else {
            level = world.getSeaLevel();
        }
        int depth = Math.max(0, level - feet.getY());
        memory.depth = depth;
        memory.depthTick = tick;
        return depth;
    }

    /**
     * Down in the rock for the purposes of a miner's choices: {@link Lugar#BAJO_TIERRA}, or outside but deep in a
     * pit (more than 8 blocks below the land). False in a house, under a tree, on a hill or at the open top of its
     * own staircase.
     */
    public static boolean bajoTierra(ServerWorld world, LivingEntity viewer) {
        if (lugar(world, viewer) == Lugar.BAJO_TIERRA) {
            return true;
        }
        if (profundidad(world, viewer) <= DEEP) {
            return false;
        }
        // deep below the land it remembers, yet under a made roof with the open sky right beside it at its own level:
        // it is under the eave of a house, not down a mine; the remembered land level is what is wrong
        return !refugio(world, viewer);
    }

    /**
     * Under a made roof (a house, an eave, a porch, a bridge, a trunk) with the open sky a few steps to the side at
     * the height of its head ({@link Tacto#cieloAlLado}): the shelter of a building at the level of the land, never
     * a mine. Cheap: once a tick.
     */
    public static boolean refugio(ServerWorld world, LivingEntity viewer) {
        VisualMemory memory = Retina.memory(viewer);
        long tick = world.getTime();
        if (memory.shelterTick == tick) {
            return memory.shelter;
        }
        BlockState roof = roof(world, BlockPos.ofFloored(viewer.getEyePos()));
        boolean shelter = roof != null && (Obras.made(roof) || roof.isIn(BlockTags.LOGS))
                && Tacto.cieloAlLado(world, viewer, Tacto.SIDE_SKY) >= 0;
        memory.shelter = shelter;
        memory.shelterTick = tick;
        return shelter;
    }

    /** The open sky is a few steps away: outside, or under a roof at the level of the land. */
    public static boolean enLaSuperficie(ServerWorld world, LivingEntity viewer) {
        return !bajoTierra(world, viewer);
    }
}
