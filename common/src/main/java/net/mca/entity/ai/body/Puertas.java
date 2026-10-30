package net.mca.entity.ai.body;

import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Doors the way a player uses them: a wooden door or a fence gate is a way through, open or shut, for every way it
 * plans (on foot, over what it remembers); the hand opens it when the body gets to it (a right click: the arm swings,
 * the door creaks, the game hears it), and once it is through, a couple of steps on, it shuts the door it opened behind
 * itself, as a tidy villager does, so nothing follows it in at night. An iron door is a wall: no hand opens it.
 * <p>
 * A door is never a floor: the top of the lower half is the upper half, nobody stands on it; a closed gate is a fence.
 */
public final class Puertas {
    /** Reach of the hand to a door, like a player's. */
    private static final double REACH = 4.5;
    /** It shuts a door it opened once its body is this far from the door's middle and the doorway is empty. */
    private static final double SHUT_AT = 1.9;
    /** Ticks before a door it opened may be shut again: the time to walk through. */
    private static final int OPEN_MIN = 10;
    /** A door it opened and never got away from (it stays in the house) is shut after this long all the same. */
    private static final int OPEN_MAX = 600;
    /** A door on the path is opened when the body is this close to it: one step before the doorway, not from afar. */
    private static final double OPEN_AT = 2.6;
    /** The body this close to the middle of a doorway is going through it. */
    private static final double PASSED = 0.8;

    /** Per villager: the doors it opened itself (packed position) and the world time it opened each. */
    private static final Map<UUID, Map<Long, Long>> ABIERTAS = new ConcurrentHashMap<>();
    /** Per villager: the doors it opened that its body went through. */
    private static final Map<UUID, Set<Long>> PASADAS = new ConcurrentHashMap<>();

    private Puertas() {
    }

    /** A wooden door (either half, open or shut) or a fence gate: a way through for a hand. */
    public static boolean puerta(BlockState state) {
        return state.getBlock() instanceof DoorBlock && state.isIn(BlockTags.WOODEN_DOORS) || state.getBlock() instanceof FenceGateBlock;
    }

    /** Room for the body in that cell: nothing in it, or a door it can open. */
    public static boolean libre(World world, BlockPos pos) {
        return Mining.passable(world, pos) || puerta(world.getBlockState(pos));
    }

    /** Something to stand on that is not a door nor a gate. */
    public static boolean suelo(World world, BlockPos pos) {
        return Mining.standable(world, pos) && !puerta(world.getBlockState(pos));
    }

    private static boolean abierta(BlockState state) {
        return state.contains(DoorBlock.OPEN) && state.get(DoorBlock.OPEN);
    }

    /**
     * Opens that door if it is a shut wooden door or gate within reach of the hand: true when it opened it just now
     * (the body waits that tick for it).
     */
    public static boolean abrir(ServerWorld world, VillagerEntityMCA villager, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!puerta(state) || abierta(state)) {
            return false;
        }
        Vec3d middle = Vec3d.ofCenter(pos);
        if (villager.getEyePos().distanceTo(middle) > REACH) {
            return false;
        }
        villager.getLookControl().lookAt(middle);
        villager.swingHand(Hand.MAIN_HAND);
        if (state.getBlock() instanceof DoorBlock door) {
            door.setOpen(villager, world, state, pos, true);
        } else {
            world.setBlockState(pos, state.with(FenceGateBlock.OPEN, true), net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.REDRAW_ON_MAIN_THREAD);
            world.playSound(null, pos, SoundEvents.BLOCK_FENCE_GATE_OPEN, SoundCategory.BLOCKS, 1.0F, world.random.nextFloat() * 0.1F + 0.9F);
            world.emitGameEvent(villager, GameEvent.BLOCK_OPEN, pos);
        }
        BlockPos lower = state.contains(DoorBlock.HALF) && state.get(DoorBlock.HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER ? pos.down() : pos;
        ABIERTAS.computeIfAbsent(villager.getUuid(), k -> new ConcurrentHashMap<>()).put(lower.asLong(), world.getTime());
        MCA.LOGGER.info("[Tropeles] {} abre la puerta de {}", villager.getName().getString(), lower.toShortString());
        return true;
    }

    /**
     * Opens what stands shut between the feet and {@code goal} on this step: the goal's two cells, the next cell
     * toward it at the feet and the head (and one up, a door on a raised floor), the corners of a diagonal, and the
     * doorway it stands in. True when a door opened this tick.
     */
    public static boolean abrirHacia(ServerWorld world, VillagerEntityMCA villager, BlockPos goal) {
        BlockPos feet = Mining.pies(villager);
        List<BlockPos> cells = new ArrayList<>(10);
        cells.add(goal);
        cells.add(goal.up());
        int dx = Integer.signum(goal.getX() - feet.getX());
        int dz = Integer.signum(goal.getZ() - feet.getZ());
        if (dx != 0 || dz != 0) {
            BlockPos next = feet.add(dx, 0, dz);
            cells.add(next);
            cells.add(next.up());
            cells.add(next.up(2));
            if (dx != 0 && dz != 0) {
                cells.add(feet.add(dx, 0, 0));
                cells.add(feet.add(0, 0, dz));
            }
        }
        cells.add(feet);
        cells.add(feet.up());
        boolean opened = false;
        for (BlockPos cell : cells) {
            opened |= abrir(world, villager, cell);
        }
        return opened;
    }

    /**
     * Every tick of the villager: the doors on the path the path finder walks are opened as it comes to them, and the
     * doors it opened itself are shut once it is through and the doorway is empty.
     */
    public static void tick(ServerWorld world, VillagerEntityMCA villager) {
        // only a connected villager's own legs: the others' doors are their brain's (it opens and shuts its own)
        Path path = net.mca.entity.ai.mente.Voluntad.conectado(villager) ? villager.getNavigation().getCurrentPath() : null;
        if (path != null && !path.isFinished()) {
            int at = path.getCurrentNodeIndex();
            for (int i = at; i < Math.min(path.getLength(), at + 2); i++) {
                BlockPos node = path.getNodePos(i);
                if (node.isWithinDistance(villager.getPos(), OPEN_AT)) {
                    abrir(world, villager, node);
                    abrir(world, villager, node.up());
                }
            }
        }
        Map<Long, Long> mine = ABIERTAS.get(villager.getUuid());
        if (mine == null || mine.isEmpty()) {
            return;
        }
        Set<Long> through = PASADAS.computeIfAbsent(villager.getUuid(), k -> ConcurrentHashMap.newKeySet());
        for (Long door : mine.keySet()) {
            Vec3d middle = Vec3d.ofBottomCenter(BlockPos.fromLong(door));
            double dx = villager.getX() - middle.x;
            double dz = villager.getZ() - middle.z;
            if (dx * dx + dz * dz < PASSED * PASSED && Math.abs(villager.getY() - middle.y) < 1.5) {
                through.add(door); // its body was in the doorway: it went through (or is going through) this door
            }
        }
        if ((villager.age % 5) != 0) {
            return;
        }
        long now = world.getTime();
        for (Iterator<Map.Entry<Long, Long>> it = mine.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Long> e = it.next();
            BlockPos pos = BlockPos.fromLong(e.getKey());
            BlockState state = world.getBlockState(pos);
            if (!puerta(state) || !abierta(state)) {
                it.remove(); // gone, or someone shut it already
                continue;
            }
            long age = now - e.getValue();
            if (age < OPEN_MIN) {
                continue;
            }
            Vec3d middle = Vec3d.ofBottomCenter(pos);
            double dx = villager.getX() - middle.x;
            double dz = villager.getZ() - middle.z;
            boolean away = dx * dx + dz * dz > SHUT_AT * SHUT_AT || Math.abs(villager.getY() - pos.getY()) > 2.5;
            if (!away && age < OPEN_MAX || away && !through.contains(e.getKey()) && age < OPEN_MAX) {
                // never shut in front of it a door it has not gone through yet (it opened it on its way there)
                continue;
            }
            if (!away || villager.getEyePos().distanceTo(Vec3d.ofCenter(pos)) > REACH + 2.0) {
                if (age > OPEN_MAX * 4) {
                    it.remove(); // it went off and left it; not its to shut from afar
                }
                continue;
            }
            List<Entity> inside = world.getOtherEntities(null, new Box(pos).expand(0.0, 1.0, 0.0), Entity::isAlive);
            if (!inside.isEmpty()) {
                continue;
            }
            villager.swingHand(Hand.MAIN_HAND);
            if (state.getBlock() instanceof DoorBlock door) {
                door.setOpen(villager, world, state, pos, false);
            } else {
                world.setBlockState(pos, state.with(FenceGateBlock.OPEN, false), net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.REDRAW_ON_MAIN_THREAD);
                world.playSound(null, pos, SoundEvents.BLOCK_FENCE_GATE_CLOSE, SoundCategory.BLOCKS, 1.0F, world.random.nextFloat() * 0.1F + 0.9F);
                world.emitGameEvent(villager, GameEvent.BLOCK_CLOSE, pos);
            }
            it.remove();
            through.remove(e.getKey());
        }
    }

    /** Forgets the doors of a villager that is gone. */
    public static void olvidar(UUID villager) {
        ABIERTAS.remove(villager);
        PASADAS.remove(villager);
    }
}
