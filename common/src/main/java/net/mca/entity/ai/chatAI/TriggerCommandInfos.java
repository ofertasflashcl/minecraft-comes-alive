package net.mca.entity.ai.chatAI;

import com.google.common.collect.ImmutableList;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.MoveState;
import net.mca.entity.ai.body.Cofres;
import net.mca.entity.ai.brain.tasks.chore.ProspectingTask;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.GlobalPos;

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
            new TriggerCommandInfo("try-go-home", "Go home now (a miner stops digging and climbs out of the mine first)",
                    null,
                    TriggerCommandInfos::goHome,
                    (p, v) -> true),
            new TriggerCommandInfo("come-up", "Stop digging and climb out of the mine to go home now",
                    null,
                    (p, v, ignored) -> {
                        ProspectingTask.callHome(v, false);
                        return "You stopped digging and are climbing out of the mine toward home.";
                    },
                    (p, v) -> v.getVillagerBrain().getCurrentJob() == Chore.PROSPECT),
            new TriggerCommandInfo("eat-break", "Stop digging, climb out to find food and eat, then go back to mining",
                    null,
                    (p, v, ignored) -> {
                        ProspectingTask.callHome(v, true);
                        return "You stopped digging and are going up to find food; after eating you will go back to mining.";
                    },
                    (p, v) -> v.getVillagerBrain().getCurrentJob() == Chore.PROSPECT),
            new TriggerCommandInfo("open-trade-window", "Open the trade menu when the player is interested in trading, wants to check your prices or your inventory.",
                    (p, v) -> v.beginTradeWith(p),
                    (p, v) -> v.hasTradeOffers()),
            new TriggerCommandInfo("start-mining", "Start prospecting and play on your own like a real player: chop trees, craft a table and your own tools, then mine for ores",
                    "optional ore to look for first: diamond, iron, gold, coal, copper, redstone, lapis or emerald",
                    (p, v, ore) -> {
                        // "sigue minando" to one already at it keeps the ore it was asked for; a new ore replaces it
                        boolean already = v.getVillagerBrain().getCurrentJob() == Chore.PROSPECT;
                        if (!already || !ore.isBlank()) {
                            ProspectingTask.want(v, ore);
                        }
                        if (!already) {
                            v.getVillagerBrain().assignJob(Chore.PROSPECT, p);
                        } else {
                            // "vuelve a la mina" / "sigue minando" to one waiting at home after "sube"
                            ProspectingTask.backToWork(v);
                        }
                        String wanted = ProspectingTask.wanted(v);
                        String looking = wanted == null ? "" : ", looking for " + wanted.replace('_', ' ') + " first";
                        return (already ? "You keep mining" : "You went mining") + looking + ".";
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
                        default -> "You have no chest nearby and none in your bag, so you keep your things on you.";
                    },
                    (p, v) -> !v.getInventory().isEmpty())
    );

    /**
     * Home, the way a villager really gets there: a miner is called out of its mine (it climbs out like a player
     * and walks home with its haul); anyone else drops its work and walks to its bed's house, when it has one here.
     */
    private static String goHome(ServerPlayerEntity player, VillagerEntityMCA villager, String ignored) {
        if (villager.getVillagerBrain().getCurrentJob() == Chore.PROSPECT) {
            ProspectingTask.callHome(villager, false);
            return "You stopped digging and are climbing out of the mine to go home.";
        }
        Optional<GlobalPos> home = villager.getResidency().getHome().filter(h -> h.getDimension() == villager.getWorld().getRegistryKey());
        if (home.isEmpty()) {
            return "You have no home to go to here, so you stay where you are.";
        }
        if (villager.getVillagerBrain().getCurrentJob() != Chore.NONE) {
            villager.getVillagerBrain().abandonJob();
        }
        villager.getVillagerBrain().setMoveState(MoveState.MOVE, player);
        villager.getInteractions().stopInteracting();
        villager.moveTowards(home.get().getPos());
        return "You are walking home.";
    }

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
