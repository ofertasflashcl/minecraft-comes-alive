package net.mca.entity.ai.chatAI.modules;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;

import java.util.List;

/** The time and the weather where the villager is (not where the player is). */
public class EnvironmentModule {
    public static void apply(List<String> input, VillagerEntityMCA villager, ServerPlayerEntity player) {
        World world = villager.getWorld();
        if (world.getRegistryKey() == World.NETHER) {
            input.add("You are in the Nether, a hot, dangerous place. ");
            return;
        }
        if (world.getRegistryKey() == World.END) {
            input.add("You are in the End, far from home. ");
            return;
        }
        long day = world.getTimeOfDay() / 24000L + 1;
        long time = world.getTimeOfDay() % 24000L;
        String part = time < 1000 ? "early morning" : time < 6000 ? "morning" : time < 11000 ? "afternoon"
                : time < 13000 ? "evening" : time < 23000 ? "night" : "dawn";
        input.add("It is day " + day + ", " + part + ". ");
        if (world.isThundering()) {
            input.add("There is a thunderstorm. ");
        } else if (world.isRaining()) {
            input.add("It is raining. ");
        }
    }
}
