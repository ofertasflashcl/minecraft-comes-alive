package net.mca.entity.ai;

import net.minecraft.item.*;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum Chore {
    NONE    ("none", null),
    PROSPECT("prospecting", PickaxeItem.class),
    HARVEST ("harvesting", HoeItem.class),
    CHOP    ("chopping", AxeItem.class),
    HUNT    ("hunting", SwordItem.class),
    FISH    ("fishing", FishingRodItem.class),
    ESCORT  ("escort", SwordItem.class);

    private static final Chore[] VALUES = values();
    private static final Map<String, Chore> REGISTRY = Stream.concat(
            Stream.of(VALUES).map(c -> Map.entry(c.friendlyName, c)),
            // "Conectar": the villager's own life as a player; PROSPECT keeps its name and place, worlds have it saved
            Stream.of(Map.entry("connect", PROSPECT), Map.entry("conectar", PROSPECT))
    ).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    private final String friendlyName;

    @Nullable
    private final Class<?> toolType;

    Chore(String friendlyName, @Nullable Class<?> toolType) {
        this.friendlyName = friendlyName;
        this.toolType = toolType;
    }

    public Text getName() {
        return Text.translatable("gui.label." + friendlyName);
    }

    public String getId() {
        return friendlyName;
    }

    @Nullable
    public Class<?> getToolType() {
        return toolType;
    }

    public static Optional<Chore> byCommand(String action) {
        return Optional.ofNullable(REGISTRY.get(action.toLowerCase(Locale.ENGLISH)));
    }

    public static Chore byId(int id) {
        if (id < 0 || id >= VALUES.length) {
            return NONE;
        }
        return VALUES[id];
    }
}

