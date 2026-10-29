package net.mca.entity.ai.chatAI;

import com.google.common.collect.ImmutableList;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.entity.ai.MoveState;
import net.mca.entity.ai.body.Cofres;
import net.mca.entity.ai.brain.tasks.chore.ProspectingTask;
import net.mca.entity.ai.mente.Voluntad;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.GlobalPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

public class TriggerCommandInfos {
    public static final List<TriggerCommandInfo> triggerCommands = ImmutableList.of(
            // a connected villager is never taken off its life by talking to it: these go to its will ({@link Voluntad})
            new TriggerCommandInfo("follow-player", "Follow the player talking to you and fight at their side",
                    null,
                    (p, v, ignored) -> {
                        if (Voluntad.conectado(v)) {
                            return Voluntad.seguir(v, p, spanish(v));
                        }
                        v.getVillagerBrain().setMoveState(MoveState.FOLLOW, p);
                        return null;
                    },
                    (p, v) -> Voluntad.conectado(v) ? !(Voluntad.of(v).modo() == Voluntad.Modo.SIGUIENDO && p.getUuid().equals(Voluntad.of(v).quien()))
                            : v.getVillagerBrain().getMoveState() != MoveState.FOLLOW),
            new TriggerCommandInfo("stay-here", "Stay and wait right here until told otherwise",
                    null,
                    (p, v, ignored) -> {
                        if (Voluntad.conectado(v)) {
                            return Voluntad.esperar(v, p, spanish(v));
                        }
                        v.getVillagerBrain().setMoveState(MoveState.STAY, p);
                        return null;
                    },
                    (p, v) -> Voluntad.conectado(v) ? Voluntad.of(v).modo() != Voluntad.Modo.ESPERANDO : v.getVillagerBrain().getMoveState() != MoveState.STAY),
            new TriggerCommandInfo("move-freely", "Move freely when asked to leave, move, or done talking",
                    null,
                    (p, v, ignored) -> {
                        if (Voluntad.conectado(v)) {
                            return Voluntad.libre(v, p, spanish(v));
                        }
                        v.getVillagerBrain().setMoveState(MoveState.MOVE, p);
                        return null;
                    },
                    (p, v) -> Voluntad.conectado(v) ? Voluntad.of(v).modo() != Voluntad.Modo.LIBRE : v.getVillagerBrain().getMoveState() != MoveState.MOVE),
            new TriggerCommandInfo("alert", "Stop dead at once and be careful: the player says careful, stop, wait, freeze, quiet, or warns you of "
                    + "danger. You stand still, turn to face them and ask what is going on",
                    null,
                    TriggerCommandInfos::alert,
                    (p, v) -> true),
            new TriggerCommandInfo("carry-on", "Carry on with what you were doing before you stopped, when the player says it was nothing, "
                    + "a false alarm, all is fine, or to go on",
                    null,
                    (p, v, ignored) -> Voluntad.seguirComoAntes(v, p, spanish(v)),
                    (p, v) -> Voluntad.conectado(v) && Voluntad.of(v).modo() == Voluntad.Modo.ALERTA),
            new TriggerCommandInfo("free", "Go back to your own life and your own plans (when told to do your own thing, that you are free, "
                    + "or to go back to what you were doing): you stop following or waiting",
                    null,
                    (p, v, ignored) -> Voluntad.libre(v, p, spanish(v)),
                    (p, v) -> Voluntad.conectado(v) && Voluntad.of(v).modo() != Voluntad.Modo.LIBRE),
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
            new TriggerCommandInfo("consider-return", "Someone suggests you head back or go home (advice, not an order): you weigh it yourself "
                    + "(bag, food, pickaxe, wounds, monsters, the night) and decide whether to go; you will be told what you decided",
                    null,
                    (p, v, ignored) -> considerReturn(p, v).how(),
                    (p, v) -> true),
            new TriggerCommandInfo("come-up", "Stop digging and climb out of the mine to go home now",
                    null,
                    (p, v, ignored) -> {
                        ownLife(v, p);
                        ProspectingTask.callHome(v, false);
                        return "You stopped digging and are climbing out of the mine toward home.";
                    },
                    (p, v) -> v.getVillagerBrain().getCurrentJob() == Chore.PROSPECT),
            new TriggerCommandInfo("eat-break", "Stop digging, climb out to find food and eat, then go back to mining",
                    null,
                    (p, v, ignored) -> {
                        ownLife(v, p);
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
                            // "vuelve a la mina" / "sigue minando" to one waiting at home after "sube", or following someone
                            ownLife(v, p);
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
            new TriggerCommandInfo("stop-working", "Stop the work you are doing for good (a connected villager disconnects); not for a quick "
                    + "\"stop!\" or \"careful!\", that is alert",
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
     * What it made of "sería mejor volver": the verdict ({@code juicio}), whether it really sets off ({@code goes}:
     * false also when it agreed but has no home here), the account for a language model ({@code how}, plain
     * English) and its own words in Spanish and English, with its reasons.
     */
    public record Vuelta(ProspectingTask.Juicio juicio, boolean goes, String how, String es, String en) {
    }

    /** The last verdict of each villager, for its own answer right after (server thread only). */
    private static final Map<VillagerEntityMCA, Vuelta> LAST = new WeakHashMap<>();

    /**
     * Someone suggests heading back (server thread): it weighs it ({@link ProspectingTask#juzgarVuelta}). A miner
     * that decides to go leaves along its own trail, goes home, makes a chest if it needs one, stores its haul and
     * comes back down (the running task does all of that); anyone else who decides to go walks home like
     * "try-go-home". Deciding to stay changes nothing.
     */
    public static Vuelta considerReturn(ServerPlayerEntity player, VillagerEntityMCA villager) {
        ProspectingTask.Juicio j = ProspectingTask.juzgarVuelta(villager);
        String name = player.getName().getString();
        Vuelta vuelta;
        if (!j.volver()) {
            vuelta = new Vuelta(j, false, name + " suggested you head back. You weighed it yourself and decided NOT to go back yet: you carry on "
                    + "with what you were doing. In your own words: \"" + j.en() + "\"", j.es(), j.en());
        } else if (j.minando()) {
            vuelta = new Vuelta(j, true, name + " suggested you head back. You weighed it yourself and decided YES, you go back now"
                    + (j.hambre() ? ", hungry" : "") + ": you climb out the way you came, walk home, store your haul in your chest "
                    + "(making one if you have none) and then go back down to keep mining. In your own words: \"" + j.en() + "\"", j.es(), j.en());
        } else {
            String walked = goHome(player, villager, "");
            if (walked.startsWith("You have no")) {
                vuelta = new Vuelta(j, false, name + " suggested you go home and you agreed, but you have no home here to go to, so you stay "
                        + "where you are.", "Tienes razón, pero aquí no tengo casa a la que volver.", "You're right, but I have no home to go back to here.");
            } else {
                vuelta = new Vuelta(j, true, name + " suggested you go home. You weighed it yourself and decided YES: you are walking home now. "
                        + "In your own words: \"" + j.en() + "\"", j.es(), j.en());
            }
        }
        LAST.put(villager, vuelta);
        return vuelta;
    }

    /** The verdict {@link #considerReturn} gave last, or null (server thread). */
    @Nullable
    public static Vuelta lastVuelta(VillagerEntityMCA villager) {
        return LAST.get(villager);
    }

    /**
     * Home, the way a villager really gets there: a miner is called out of its mine (it climbs out like a player
     * and walks home with its haul); anyone else drops its work and walks to its bed's house, when it has one here.
     */
    private static String goHome(ServerPlayerEntity player, VillagerEntityMCA villager, String ignored) {
        if (villager.getVillagerBrain().getCurrentJob() == Chore.PROSPECT) {
            ownLife(villager, player);
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

    /** A connected villager following or waiting goes back to its own life, so an order for that life is carried out. */
    private static void ownLife(VillagerEntityMCA villager, ServerPlayerEntity player) {
        if (Voluntad.conectado(villager) && Voluntad.of(villager).modo() != Voluntad.Modo.LIBRE) {
            Voluntad.libre(villager, player, spanish(villager));
        }
    }

    /** The language the villager last heard ({@link Voluntad#oyo}, told by the chat right before the order runs). */
    private static boolean spanish(VillagerEntityMCA villager) {
        return Voluntad.of(villager).spanish();
    }

    /**
     * "¡Cuidado!", "¡para!": a connected villager's will stops it, turns it to the player and has it ask what is going
     * on ({@link Voluntad#alerta}); anyone else just stops where it is and turns to them.
     */
    private static String alert(ServerPlayerEntity player, VillagerEntityMCA villager, String ignored) {
        if (Voluntad.conectado(villager)) {
            return net.mca.entity.ai.mente.Interrupcion.detener(player, villager, spanish(villager));
        }
        net.mca.entity.ai.mente.Interrupcion.detener(player, villager, spanish(villager));
        return "You stopped dead where you are, crouched, and turned to " + player.getName().getString()
                + ". Ask them what is going on, in a short question.";
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
