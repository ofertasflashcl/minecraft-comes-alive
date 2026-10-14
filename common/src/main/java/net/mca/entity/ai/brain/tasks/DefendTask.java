package net.mca.entity.ai.brain.tasks;

import com.google.common.collect.ImmutableMap;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.MemoryModuleTypeMCA;
import net.mca.entity.ai.Threats;
import net.mca.entity.ai.VillagerEyes;
import net.mca.entity.ai.body.CombatMind;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.task.MultiTickTask;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Deals with whatever is attacking this villager, with the best weapon it carries (see {@link CombatMind}).
 * Following and mining have their own plans.
 */
public class DefendTask extends MultiTickTask<VillagerEntityMCA> {
    public DefendTask() {
        super(ImmutableMap.of(), 12000);
    }

    @Override
    protected boolean shouldRun(ServerWorld world, VillagerEntityMCA villager) {
        if (!ownsFight(villager)) {
            return false;
        }
        return target(world, villager) != null || trail(world, villager) != null;
    }

    @Override
    protected boolean shouldKeepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        return shouldRun(world, villager);
    }

    @Override
    protected void finishRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        CombatMind.disengage(villager);
    }

    @Override
    protected void keepRunning(ServerWorld world, VillagerEntityMCA villager, long time) {
        LivingEntity threat = target(world, villager);
        if (threat == null) {
            BlockPos lost = trail(world, villager);
            if (lost == null) {
                return;
            }
            villager.moveTowards(lost, 0.9F, 1);
            if ((villager.age & 3) == 0) {
                villager.getNavigation().startMovingTo(lost.getX() + 0.5, lost.getY(), lost.getZ() + 0.5, 0.9);
            }
            return;
        }
        CombatMind.fight(world, villager, null, threat);
    }

    private static boolean ownsFight(VillagerEntityMCA villager) {
        Chore job = villager.getVillagerBrain().getCurrentJob();
        if (job == Chore.PROSPECT || job == Chore.ESCORT) {
            return false;
        }
        return villager.getBrain().getOptionalMemory(MemoryModuleTypeMCA.PLAYER_FOLLOWING.get()).isEmpty();
    }

    private static LivingEntity target(ServerWorld world, VillagerEntityMCA villager) {
        boolean staying = villager.getBrain().getOptionalMemory(MemoryModuleTypeMCA.STAYING.get()).isPresent();
        return CombatMind.pickTarget(world, villager, null, staying ? 6 : 14);
    }

    private static BlockPos trail(ServerWorld world, VillagerEntityMCA villager) {
        BlockPos lost = VillagerEyes.searchWhere(world, villager);
        if (lost != null) {
            return lost;
        }
        return Threats.footstep(world, villager, null);
    }
}
