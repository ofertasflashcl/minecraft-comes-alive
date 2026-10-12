package net.conczin.mca.entity.ai;

import net.conczin.mca.entity.VillagerEntityMCA;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A miner in trouble leaves a short-lived call: who, where, and who sent them.
 * Escorts of that player and loaded relatives can answer it.
 */
public final class DistressCalls {
    private static final List<Call> ACTIVE = new ArrayList<>();

    private DistressCalls() {
    }

    public record Call(UUID caller, UUID assigner, BlockPos pos, ResourceKey<Level> dimension, long expiresAt, String name) {
    }

    public static void raise(ServerLevel world, VillagerEntityMCA caller, UUID assigner) {
        long now = world.getGameTime();
        expire(now);
        ACTIVE.removeIf(call -> call.caller().equals(caller.getUUID()));
        ACTIVE.add(new Call(
                caller.getUUID(),
                assigner,
                caller.blockPosition(),
                world.dimension(),
                now + 600,
                caller.getName().getString()
        ));
    }

    public static Optional<Call> nearestForAssigner(ServerLevel world, UUID assigner, BlockPos from, int range) {
        return nearest(world, from, range, call -> assigner.equals(call.assigner()));
    }

    public static Optional<Call> nearestFamilyCall(VillagerEntityMCA villager, int range) {
        if (!(villager.level() instanceof ServerLevel world)) {
            return Optional.empty();
        }
        return nearest(world, villager.blockPosition(), range, call -> {
            if (call.caller().equals(villager.getUUID())) {
                return false;
            }
            return villager.getRelationships().getFamilyEntry().isRelative(call.caller());
        });
    }

    private static Optional<Call> nearest(ServerLevel world, BlockPos from, int range, java.util.function.Predicate<Call> filter) {
        expire(world.getGameTime());
        Call best = null;
        double bestDistance = (double) range * range;
        for (Call call : ACTIVE) {
            if (!call.dimension().equals(world.dimension()) || !filter.test(call)) {
                continue;
            }
            double distance = call.pos().distSqr(from);
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
