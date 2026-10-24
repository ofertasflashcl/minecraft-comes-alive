package net.mca.entity.ai;

import net.mca.ClientProxy;
import net.mca.Config;
import net.mca.MCA;
import net.mca.cobalt.network.NetworkHandler;
import net.mca.entity.EntityWrapper;
import net.mca.entity.VillagerEntityMCA;
import net.mca.network.s2c.VillagerMessage;
import net.mca.resources.API;
import net.mca.server.ProspectorPresence;
import net.mca.server.world.data.FamilyTree;
import net.mca.server.world.data.FamilyTreeNode;
import net.mca.server.world.data.PlayerSaveData;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.TargetPredicate;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public interface Messenger extends EntityWrapper {
    /** Who hears a villager talk out loud: speech carries around corners and through a wall, not only in sight. */
    TargetPredicate CAN_RECEIVE = TargetPredicate.createNonAttackable().ignoreVisibility();

    default boolean isSpeechImpaired() {
        return false;
    }

    default boolean isToYoungToSpeak() {
        return false;
    }

    default void playSpeechEffect() {

    }

    default DialogueType getDialogueType(PlayerEntity receiver) {
        return DialogueType.UNASSIGNED;
    }

    default MutableText getTranslatable(PlayerEntity target, String phraseId, Object... params) {
        String genderString = "";

        String targetName;
        if (target.getWorld() instanceof ServerWorld world) {
            //todo won't work on a few client side use cases
            targetName = FamilyTree.get(world)
                    .getOrEmpty(target.getUuid())
                    .map(FamilyTreeNode::getName)
                    .filter(n -> !MCA.isBlankString(n))
                    .orElse(target.getName().getString());

            //player gender
            genderString = "#G" + PlayerSaveData.get((ServerPlayerEntity)target).getGender().name().toLowerCase(Locale.ROOT) + ".";
        } else {
            targetName = target.getName().getString();
        }
        Object[] newParams = new Object[params.length + 1];
        System.arraycopy(params, 0, newParams, 1, params.length);
        newParams[0] = targetName;

        //also pass profession
        String professionString = "";
        if (!asEntity().isBaby() && asEntity() instanceof VillagerEntityMCA v) {
            professionString = "#P" + Registries.VILLAGER_PROFESSION.getId(v.getProfession()).getPath() + ".";
        }

        //and personality
        String personalityString = "";
        if (asEntity() instanceof VillagerEntityMCA v) {
            personalityString = "#E" + v.getVillagerBrain().getPersonality().name() + ".";
        }

        return Text.translatable(genderString + personalityString + professionString + "#T" + getDialogueType(target).name() + "." + phraseId, newParams);
    }

    default void sendChatToAllAround(MutableText phrase) {
        sendChatToAllAroundExcept(phrase, null);
    }

    default void sendChatToAllAround(String phrase, Object... params) {
        sendPhraseToAllAroundExcept(null, phrase, params);
    }

    /** Says it out loud to everyone within 20 blocks but {@code except} (who was already told it directly). */
    default void sendChatToAllAroundExcept(MutableText phrase, @Nullable Entity except) {
        for (PlayerEntity player : asEntity().getWorld().getPlayers(CAN_RECEIVE, asEntity(), asEntity().getBoundingBox().expand(20))) {
            if (player == except) {
                continue;
            }
            float dist = player.distanceTo(asEntity());
            // a copy for each: formatted() changes the text it is called on
            deliverChat(phrase.copy().formatted(dist < 10 ? Formatting.WHITE : Formatting.GRAY), player, false);
        }
    }

    /** The phrase in each listener's own words (their name, their gender), to everyone around but {@code except}. */
    default void sendPhraseToAllAroundExcept(@Nullable Entity except, String phrase, Object... params) {
        for (PlayerEntity player : asEntity().getWorld().getPlayers(CAN_RECEIVE, asEntity(), asEntity().getBoundingBox().expand(20))) {
            if (player == except) {
                continue;
            }
            float dist = player.distanceTo(asEntity());
            deliverChat(getTranslatable(player, phrase, params).formatted(dist < 10 ? Formatting.WHITE : Formatting.GRAY), player, false);
        }
    }

    default void sendChatMessage(PlayerEntity target, String phraseId, Object... params) {
        sendChatMessage(getTranslatable(target, phraseId, params), target);
    }

    default MutableText transformMessage(MutableText message) {
        if (isSpeechImpaired()) {
            return Text.literal(API.getRandomSentence("zombie", message.getString()));
        } else if (isToYoungToSpeak()) {
            return Text.literal(API.getRandomSentence("baby", message.getString()));
        }
        return message;
    }

    default MutableText sendChatMessage(MutableText message, Entity receiver) {
        return deliverChat(message, receiver, true);
    }

    /**
     * Says {@code message} to {@code receiver}. {@code direct} is a line addressed to them (job reports, chat
     * answers, menu replies); ambient lines said to whoever is around are not. A villager listed as an online player
     * ({@link ProspectorPresence}) says its addressed lines like player chat, to everyone on the server; the
     * others talk to the one they address. On the server every line reads like a player's, "{@code <Inas> ...}"
     * ({@link Config#tropelesPlayerChatFormat}). Players without a connection (the in-game checks) hear nothing.
     */
    default MutableText deliverChat(MutableText message, Entity receiver, boolean direct) {
        message = transformMessage(message);
        MobEntity self = asEntity();
        boolean server = !self.getWorld().isClient;
        boolean online = server && ProspectorPresence.isListed(self);

        MutableText prefix;
        if (online) {
            prefix = Text.literal(Config.getInstance().villagerChatPrefix).append(ProspectorPresence.chatPrefix(self));
        } else if (server && Config.getInstance().tropelesPlayerChatFormat && receiver instanceof ServerPlayerEntity) {
            prefix = playerStylePrefix();
        } else {
            prefix = Text.literal(Config.getInstance().villagerChatPrefix)
                    .append(self.getDisplayName())
                    .append(": ");
        }

        //use custom packet to have access to sender UUID, and maybe future extra information
        VillagerMessage msg = new VillagerMessage(prefix, message, self.getUuid());
        MinecraftServer host = self.getServer();
        ProspectorPresence.Route route = online && direct && host != null
                ? ProspectorPresence.route(self, message) : ProspectorPresence.Route.PRIVATE;
        if (route == ProspectorPresence.Route.EVERYONE && host != null) {
            for (ServerPlayerEntity player : host.getPlayerManager().getPlayerList()) {
                if (player.networkHandler != null) {
                    NetworkHandler.sendToPlayer(msg, player);
                }
            }
            // and in the server console, like player chat
            host.sendMessage(prefix.copy().append(message.copy()));
        } else if (route == ProspectorPresence.Route.PRIVATE) {
            if (receiver instanceof ServerPlayerEntity serverPlayer) {
                if (serverPlayer.networkHandler != null) {
                    NetworkHandler.sendToPlayer(msg, serverPlayer);
                }
            } else if (!server) {
                ClientProxy.getNetworkHandler().handleVillagerMessage(msg);
            }
        }

        if (route != ProspectorPresence.Route.DROP) {
            playSpeechEffect();
        }

        return prefix.append(message);
    }

    /**
     * "{@code <Inas> }" the way a player's name shows in chat. Hovering tells who and where it is, a click puts
     * "/tp @s" toward it in the chat box, and shift-click starts an answer with its name.
     */
    default MutableText playerStylePrefix() {
        MobEntity self = asEntity();
        String plain = self.getName().getString();
        MutableText tip = Text.literal(plain);
        if (self instanceof VillagerEntityMCA villager && villager.getVillagerBrain() != null) {
            Chore job = villager.getVillagerBrain().getCurrentJob();
            if (job != Chore.NONE) {
                tip.append(" (").append(job.getName()).append(")");
            }
            MoveState move = villager.getVillagerBrain().getMoveState();
            if (move != MoveState.MOVE) {
                tip.append(" (").append(move.getName()).append(")");
            }
        }
        BlockPos at = self.getBlockPos();
        tip.append("\n" + at.getX() + ", " + at.getY() + ", " + at.getZ() + "\n")
                .append(Text.literal("Clic: /tp hacia " + plain + " · Mayús+clic: responder").formatted(Formatting.GRAY));
        String uuid = self.getUuidAsString();
        MutableText name = Text.literal(plain).styled(style -> style
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, tip))
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tp @s " + uuid))
                .withInsertion(plain + ", "));
        return Text.literal(Config.getInstance().villagerChatPrefix).append("<").append(name).append("> ");
    }

    /**
     * A chat answer said to one player, as a player would say it: "{@code <Inas> text}", or with its online name
     * when it is listed ({@link ProspectorPresence}). It stays a game message from the villager (never signed
     * chat), so the voice still reads it. The caller runs {@link #transformMessage} and {@link #playSpeechEffect}
     * once for all who hear it. Players without a connection (the in-game checks) are skipped.
     */
    default void sayInChat(MutableText message, ServerPlayerEntity receiver) {
        if (receiver.networkHandler == null) {
            return;
        }
        MobEntity self = asEntity();
        MutableText prefix = ProspectorPresence.isListed(self)
                ? Text.literal(Config.getInstance().villagerChatPrefix).append(ProspectorPresence.chatPrefix(self))
                : playerStylePrefix();
        NetworkHandler.sendToPlayer(new VillagerMessage(prefix, message, self.getUuid()), receiver);
    }

    default void sendEventMessage(Text message, PlayerEntity receiver) {
        receiver.sendMessage(message, true);
    }

    default void sendEventMessage(Text message) {
        if (!(this instanceof Entity)) {
            return; // Can't tell all
        }
        sendEventMessage(((Entity)this).getWorld(), message);
    }

    static void sendEventMessage(World world, Text message) {
        world.getPlayers().forEach(player -> player.sendMessage(message, true));
    }
}
