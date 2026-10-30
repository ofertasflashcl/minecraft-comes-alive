package net.mca.entity.ai;

import net.mca.entity.VillagerLike;
import net.mca.entity.ai.brain.VillagerBrain;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.nbt.NbtCompound;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class Memories {

    private int hearts;

    private final UUID playerUUID;

    private int interactionFatigue;

    private DialogueType dialogueType;

    private final VillagerBrain<?> brain;

    private long lastSeen;

    /** The name the player told it ("me llamo Benja"); empty until they introduce themselves. */
    private String nombre = "";

    /** How the player said they identify: "m", "f", "n", or empty (then the game's own setting counts). */
    private String genero = "";

    /** The bond the two of them agreed on, beyond the hearts: {@code COMPADRE} or {@code PAREJA} (flags). */
    private int lazos;

    /** The highest stage of the bond it already told the player about (an ordinal of the companion's stages). */
    private int etapa;

    /** Ticks it spent connected and at the player's side: time together makes friends. */
    private long juntos;

    public Memories(VillagerBrain<?> brain, long time, UUID uuid) {
        this.brain = brain;
        playerUUID = uuid;
        dialogueType = DialogueType.UNASSIGNED;
        lastSeen = time / 24000L;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public int getHearts() {
        return hearts;
    }

    public void setHearts(int value) {
        this.hearts = value;
        brain.updateMemories(this);
    }

    public void modHearts(int value) {
        setHearts(this.hearts += value);
    }

    public int getInteractionFatigue() {
        return interactionFatigue;
    }

    public void setInteractionFatigue(int value) {
        this.interactionFatigue = value;
        brain.updateMemories(this);
    }

    public void modInteractionFatigue(int value) {
        this.interactionFatigue += value;
        brain.updateMemories(this);
    }

    public DialogueType getDialogueType() {
        return dialogueType;
    }

    public void setDialogueType(DialogueType dialogueType) {
        this.dialogueType = dialogueType;
        brain.updateMemories(this);
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(int lastSeen) {
        this.lastSeen = lastSeen;
        brain.updateMemories(this);
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre == null ? "" : nombre;
        brain.updateMemories(this);
    }

    public String getGenero() {
        return genero;
    }

    public void setGenero(String genero) {
        this.genero = genero == null ? "" : genero;
        brain.updateMemories(this);
    }

    public int getLazos() {
        return lazos;
    }

    public void setLazos(int lazos) {
        this.lazos = lazos;
        brain.updateMemories(this);
    }

    public int getEtapa() {
        return etapa;
    }

    public void setEtapa(int etapa) {
        this.etapa = etapa;
        brain.updateMemories(this);
    }

    public long getJuntos() {
        return juntos;
    }

    /** Writes the memories back into the villager (after {@link #addJuntos}). */
    public void save() {
        brain.updateMemories(this);
    }

    /** Adds time together without a save of its own every tick: the next change saves it with the rest. */
    public void addJuntos(long ticks) {
        this.juntos += ticks;
    }

    public NbtCompound toCNBT() {
        NbtCompound nbt = new NbtCompound();

        nbt.putUuid("playerUUID", playerUUID);
        nbt.putInt("hearts", hearts);
        nbt.putInt("interactionFatigue", interactionFatigue);
        nbt.putInt("dialogueType", dialogueType.ordinal());
        nbt.putLong("lastSeen", lastSeen);
        nbt.putString("nombre", nombre);
        nbt.putString("genero", genero);
        nbt.putInt("lazos", lazos);
        nbt.putInt("etapa", etapa);
        nbt.putLong("juntos", juntos);

        return nbt;
    }

    public static <E extends MobEntity & VillagerLike<E>> Memories fromCNBT(E villager, @Nullable NbtCompound tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }

        Memories memories = new Memories(villager.getVillagerBrain(), villager.getWorld().getTimeOfDay(), tag.getUuid("playerUUID"));

        memories.hearts = tag.getInt("hearts");
        memories.interactionFatigue = tag.getInt("interactionFatigue");
        memories.dialogueType = DialogueType.byId(tag.getInt("dialogueType"));
        memories.lastSeen = tag.getLong("lastSeen");
        memories.nombre = tag.getString("nombre");
        memories.genero = tag.getString("genero");
        memories.lazos = tag.getInt("lazos");
        memories.etapa = tag.getInt("etapa");
        memories.juntos = tag.getLong("juntos");

        return memories;
    }

}
