package net.mca.entity.ai.chatAI;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

public class TriggerCommandInfo {
    /**
     * An order that takes a word or two after it, like the item to hand over. It may say how it went, in plain
     * English for the mind that speaks for the villager, or return null.
     */
    @FunctionalInterface
    public interface ArgumentCall {
        String accept(ServerPlayerEntity player, VillagerEntityMCA villager, String argument);
    }

    public String command;
    public String description;
    public BiPredicate<ServerPlayerEntity, VillagerEntityMCA> isActive;
    public BiConsumer<ServerPlayerEntity, VillagerEntityMCA> call;
    /** What the argument is, for the model's instructions; null when the command takes none. */
    public String argument;
    public ArgumentCall callWithArgument;

    public TriggerCommandInfo(String command, String description, BiConsumer<ServerPlayerEntity, VillagerEntityMCA> call, BiPredicate<ServerPlayerEntity, VillagerEntityMCA> isActive) {
        this.command = command;
        this.description = description;
        this.call = call;
        this.isActive = isActive;
    }

    public TriggerCommandInfo(String command, String description, BiConsumer<ServerPlayerEntity, VillagerEntityMCA> call) {
        this(command, description, call, (p, v) -> true);
    }

    public TriggerCommandInfo(String command, String description, String argument, ArgumentCall call, BiPredicate<ServerPlayerEntity, VillagerEntityMCA> isActive) {
        this(command, description, (p, v) -> call.accept(p, v, ""), isActive);
        this.argument = argument;
        this.callWithArgument = call;
    }

    /** Runs the order; {@code argument} may be null or empty. Returns how it went, when the order says. */
    public String run(ServerPlayerEntity player, VillagerEntityMCA villager, String argument) {
        if (callWithArgument != null) {
            return callWithArgument.accept(player, villager, argument == null ? "" : argument.trim());
        }
        call.accept(player, villager);
        return null;
    }
}
