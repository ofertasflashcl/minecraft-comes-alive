package net.mca.entity.ai.chatAI.modules;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.relationship.AgeState;
import net.mca.entity.ai.relationship.Gender;
import net.mca.entity.ai.relationship.Personality;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.village.VillagerProfession;

import java.util.List;
import java.util.Map;

import static net.mca.entity.ai.chatAI.OpenAIChatAI.translate;

/** Who the villager is: age, gender, trade, and how its personality and mood make it talk. */
public class PersonalityModule {
    /** How each personality talks, so the words sound like this villager and not like any other. */
    private static final Map<Personality, String> STYLE = Map.ofEntries(
            Map.entry(Personality.ATHLETIC, "energetic and sporty, always ready to move"),
            Map.entry(Personality.CONFIDENT, "self-assured and a bit proud, says things straight"),
            Map.entry(Personality.FRIENDLY, "warm and kind, glad to help"),
            Map.entry(Personality.FLIRTY, "playful and a little flirty"),
            Map.entry(Personality.WITTY, "quick-witted, likes jokes and teasing"),
            Map.entry(Personality.SHY, "shy, uses few words and hesitates"),
            Map.entry(Personality.GLOOMY, "gloomy, always expects the worst"),
            Map.entry(Personality.SENSITIVE, "sensitive, easily hurt by rude words"),
            Map.entry(Personality.GREEDY, "greedy, thinks about what things are worth and what is in it for them"),
            Map.entry(Personality.ODD, "odd, says unexpected things now and then"),
            Map.entry(Personality.LAZY, "lazy, would rather rest or eat than work, grumbles about effort"),
            Map.entry(Personality.GRUMPY, "grumpy, short and blunt"),
            Map.entry(Personality.PEPPY, "peppy and hyperactive, many exclamations")
    );

    public static void apply(List<String> input, VillagerEntityMCA villager, ServerPlayerEntity player) {
        Gender gender = villager.getGenetics().getGender();
        AgeState age = villager.getAgeState();
        String who = switch (age) {
            case BABY, TODDLER -> "a toddler";
            case CHILD -> "a child";
            case TEEN -> gender == Gender.FEMALE ? "a teenage girl" : gender == Gender.MALE ? "a teenage boy" : "a teenager";
            default -> gender == Gender.FEMALE ? "a woman" : gender == Gender.MALE ? "a man" : "a person";
        };
        boolean grown = age == AgeState.ADULT || age == AgeState.UNASSIGNED;
        String trade = grown && villager.getProfession() != VillagerProfession.NONE ? ", " + translate(villager.getProfession().id()) + " by trade" : "";
        input.add("$villager is " + who + " of the village" + trade + ". ");

        Personality personality = villager.getVillagerBrain().getPersonality();
        String style = STYLE.get(personality);
        input.add("$villager is " + translate(personality.name()) + (style != null ? " (" + style + ")" : "")
                + " and feels " + translate(villager.getVillagerBrain().getMood().getName()) + " right now; talk like that. ");
    }
}
