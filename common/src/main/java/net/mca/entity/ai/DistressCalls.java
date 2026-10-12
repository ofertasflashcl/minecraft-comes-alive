package net.mca.entity.ai;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A miner in trouble leaves a short-lived call: who, where, and who sent them.
 * Escorts of that player and loaded relatives can answer it.
 */
public final class DistressCalls {
    private static final List<Call> ACTIVE = new ArrayList<>();

    private DistressCalls() {
    }

    public record Call(UUID caller, UUID assigner, BlockPos pos, RegistryKey<World> dimension, long expiresAt, String name) {
    }

    public static void raise(ServerWorld world, VillagerEntityMCA caller, UUID assigner) {
        long now = world.getTime();
        expire(now);
        ACTIVE.removeIf(call -> call.caller().equals(caller.getUuid()));
        ACTIVE.add(new Call(
                caller.getUuid(),
                assigner,
                caller.getBlockPos(),
                world.getRegistryKey(),
                now + 600,
                caller.getName().getString()
        ));
    }

    public static Optional<Call> nearestForAssigner(ServerWorld world, UUID assigner, BlockPos from, int range) {
        return nearest(world, from, range, call -> assigner.equals(call.assigner()));
    }

    public static Optional<Call> nearestFamilyCall(VillagerEntityMCA villager, int range) {
        if (!(villager.getWorld() instanceof ServerWorld world)) {
            return Optional.empty();
        }
        return nearest(world, villager.getBlockPos(), range, call -> {
            if (call.caller().equals(villager.getUuid())) {
                return false;
            }
            return villager.getRelationships().getFamilyEntry().isRelative(call.caller());
        });
    }

    private static Optional<Call> nearest(ServerWorld world, BlockPos from, int range, Predicate<Call> filter) {
        expire(world.getTime());
        Call best = null;
        double bestDistance = (double) range * range;
        for (Call call : ACTIVE) {
            if (!call.dimension().equals(world.getRegistryKey()) || !filter.test(call)) {
                continue;
            }
            double distance = call.pos().getSquaredDistance(from);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = call;
            }
        }
        return Optional.ofNullable(best);
    }

    private static void expire(long now) {
        ACTIVE.removeIf(call -> call.expiresAt() <= now);
    }
}
