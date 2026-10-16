package net.mca.entity.ai.chatAI;

import com.google.common.collect.ImmutableList;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.MoveState;
import net.mca.entity.ai.body.Cofres;
import net.mca.entity.ai.brain.tasks.chore.ProspectingTask;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Optional;

public class TriggerCommandInfos {
    public static final List<TriggerCommandInfo> triggerCommands = ImmutableList.of(
            new TriggerCommandInfo("follow-player", "Follow the player talking to you and fight at their side",
                    (p, v) -> v.getVillagerBrain().setMoveState(MoveState.FOLLOW, p),
                    (p, v) -> v.getVillagerBrain().getMoveState() != MoveState.FOLLOW),
            new TriggerCommandInfo("stay-here", "Stay here for a while",
                    (p, v) -> v.getVillagerBrain().setMoveState(MoveState.STAY, p),
                    (p, v) -> v.getVillagerBrain().getMoveState() != MoveState.STAY),
            new TriggerCommandInfo("move-freely", "Move freely when asked to leave, move, or done talking",
                    (p, v) -> v.getVillagerBrain().setMoveState(MoveState.MOVE, p),
                    (p, v) -> v.getVillagerBrain().getMoveState() != MoveState.MOVE),
            new TriggerCommandInfo("wear-armor", "Equip any armor you have",
                    (p, v) -> v.getVillagerBrain().setArmorWear(true),
                    (p, v) -> !v.getVillagerBrain().getArmorWear()),
            new TriggerCommandInfo("remove-armor", "Remove all the armor currently equipped",
                    (p, v) -> v.getVillagerBrain().setArmorWear(false),
                    (p, v) -> v.getVillagerBrain().getArmorWear()),
            new TriggerCommandInfo("try-go-home", "Try to go to your home in the village if possible",
                    (p, v) -> v.getResidency().goHome(p)),
            new TriggerCommandInfo("open-trade-window", "Open the trade menu when the player is interested in trading, wants to check your prices or your inventory.",
                    (p, v) -> v.beginTradeWith(p),
                    (p, v) -> v.hasTradeOffers()),
            new TriggerCommandInfo("start-mining", "Start prospecting and play on your own like a real player: chop trees, craft a table and your own tools, then mine for ores",
                    "optional ore to look for first: diamond, iron, gold, coal, copper, redstone, lapis or emerald",
                    (p, v, ore) -> {
                        ProspectingTask.want(v, ore);
                        if (v.getVillagerBrain().getCurrentJob() != Chore.PROSPECT) {
                            v.getVillagerBrain().assignJob(Chore.PROSPECT, p);
                        }
                        String wanted = ProspectingTask.wanted(v);
                        return wanted == null ? "You went mining." : "You went mining, looking for " + wanted.replace('_', ' ') + " first.";
                    },
                    (p, v) -> true),
            job("start-chopping", "Start chopping trees", Chore.CHOP),
            job("start-fishing", "Start fishing", Chore.FISH),
            job("start-harvesting", "Start harvesting crops", Chore.HARVEST),
            job("start-hunting", "Start hunting animals", Chore.HUNT),
            job("start-escort", "Start escorting", Chore.ESCORT),
            new TriggerCommandInfo("stop-working", "Stop the work you are doing",
                    (p, v) -> v.getVillagerBrain().abandonJob(),
                    (p, v) -> v.getVillagerBrain().getCurrentJob() != Chore.NONE),
            new TriggerCommandInfo("give-item", "Hand the player something you are carrying, when they ask for it and you are willing",
                    "the item and how many, as in your bag, for example \"iron_ingot 3\" or \"bread\"",
                    Acciones::give,
                    (p, v) -> !v.getInventory().isEmpty()),
            new TriggerCommandInfo("store-items", "Put what you carry into your own chest nearby",
                    null,
                    (p, v, ignored) -> switch (Cofres.orderStore(v, p)) {
                        case WALKING -> "You are walking to your chest to store your things.";
                        case PLACED -> "You set down your chest here and will store your things in it.";
                        case NONE -> "You have no chest nearby and none in your bag, so you keep your things on you.";
                    },
                    (p, v) -> !v.getInventory().isEmpty())
    );

    private static TriggerCommandInfo job(String command, String description, Chore chore) {
        return new TriggerCommandInfo(command, description,
                (p, v) -> v.getVillagerBrain().assignJob(chore, p),
                (p, v) -> v.getVillagerBrain().getCurrentJob() != chore);
    }

    public static Optional<TriggerCommandInfo> findCommand(String command, ServerPlayerEntity player, VillagerEntityMCA villagerEntityMCA) {
        for (TriggerCommandInfo commandInfo : TriggerCommandInfos.triggerCommands) {
            if (command.equals(commandInfo.command)) {
                if (commandInfo.isActive != null && !commandInfo.isActive.test(player, villagerEntityMCA)) {
                    // not active or available now
                    continue;
                }
                return Optional.of(commandInfo);
            }
        }
        return Optional.empty();
    }
}
