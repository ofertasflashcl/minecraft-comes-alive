package net.mca.server.world.data;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.mca.util.WorldUtils;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a world remembers for its villagers' sake: the blocks people placed (so nobody's build is ever taken
 * for nature), the blocks each villager placed itself (so it may pick its own table or bridge back up), the
 * chests villagers own with what they last left in them, and the thefts nobody has noticed yet.
 */
public class TropelesMundo extends PersistentState {
    private static final int OWN_PER_VILLAGER = 4096;

    private final LongSet byPlayers = new LongOpenHashSet();
    private final Long2ObjectMap<UUID> byVillagers = new Long2ObjectOpenHashMap<>();
    private final Map<UUID, Deque<Long>> ownOrder = new HashMap<>();
    private final Long2ObjectMap<Chest> chests = new Long2ObjectOpenHashMap<>();
    private final List<Theft> thefts = new ArrayList<>();

    /** A chest a villager owns and what it believes is inside, item id to count. */
    public static final class Chest {
        public final UUID owner;
        public final Map<String, Integer> snapshot = new HashMap<>();

        Chest(UUID owner) {
            this.owner = owner;
        }
    }

    /** Somebody took from a villager's chest; the owner has not found out yet. */
    public record Theft(UUID owner, UUID thief, String thiefName, long chest, int items, String what, long time) {
    }

    public static TropelesMundo get(ServerWorld world) {
        return WorldUtils.loadData(world, TropelesMundo::new, w -> new TropelesMundo(), "mca_tropeles_mundo");
    }

    TropelesMundo() {
    }

    TropelesMundo(NbtCompound nbt) {
        for (long pos : nbt.getLongArray("players")) {
            byPlayers.add(pos);
        }
        NbtList own = nbt.getList("villagers", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < own.size(); i++) {
            NbtCompound entry = own.getCompound(i);
            UUID owner = entry.getUuid("owner");
            for (long pos : entry.getLongArray("blocks")) {
                addOwn(owner, pos);
            }
        }
        NbtList chestList = nbt.getList("chests", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < chestList.size(); i++) {
            NbtCompound entry = chestList.getCompound(i);
            Chest chest = new Chest(entry.getUuid("owner"));
            NbtCompound snap = entry.getCompound("snapshot");
            for (String key : snap.getKeys()) {
                chest.snapshot.put(key, snap.getInt(key));
            }
            chests.put(entry.getLong("pos"), chest);
        }
        NbtList theftList = nbt.getList("thefts", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < theftList.size(); i++) {
            NbtCompound t = theftList.getCompound(i);
            thefts.add(new Theft(t.getUuid("owner"), t.getUuid("thief"), t.getString("name"), t.getLong("chest"), t.getInt("items"), t.getString("what"), t.getLong("time")));
        }
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putLongArray("players", byPlayers.toLongArray());
        NbtList own = new NbtList();
        ownOrder.forEach((owner, blocks) -> {
            NbtCompound entry = new NbtCompound();
            entry.putUuid("owner", owner);
            entry.putLongArray("blocks", blocks.stream().mapToLong(Long::longValue).toArray());
            own.add(entry);
        });
        nbt.put("villagers", own);
        NbtList chestList = new NbtList();
        chests.long2ObjectEntrySet().forEach(e -> {
            NbtCompound entry = new NbtCompound();
            entry.putLong("pos", e.getLongKey());
            entry.putUuid("owner", e.getValue().owner);
            NbtCompound snap = new NbtCompound();
            e.getValue().snapshot.forEach(snap::putInt);
            entry.put("snapshot", snap);
            chestList.add(entry);
        });
        nbt.put("chests", chestList);
        NbtList theftList = new NbtList();
        for (Theft theft : thefts) {
            NbtCompound t = new NbtCompound();
            t.putUuid("owner", theft.owner());
            t.putUuid("thief", theft.thief());
            t.putString("name", theft.thiefName());
            t.putLong("chest", theft.chest());
            t.putInt("items", theft.items());
            t.putString("what", theft.what());
            t.putLong("time", theft.time());
            theftList.add(t);
        }
        nbt.put("thefts", theftList);
        return nbt;
    }

    // ------------------------------------------------------------------ who placed what

    public void placedByPlayer(BlockPos pos) {
        long key = pos.asLong();
        UUID previous = byVillagers.remove(key);
        if (previous != null) {
            Deque<Long> order = ownOrder.get(previous);
            if (order != null) {
                order.remove(key);
            }
        }
        if (byPlayers.add(key)) {
            markDirty();
        }
    }

    public boolean isPlayerPlaced(BlockPos pos) {
        return byPlayers.contains(pos.asLong());
    }

    public void placedByVillager(UUID villager, BlockPos pos) {
        long key = pos.asLong();
        byPlayers.remove(key);
        addOwn(villager, key);
        markDirty();
    }

    private void addOwn(UUID villager, long key) {
        UUID previous = byVillagers.put(key, villager);
        if (previous != null && !previous.equals(villager)) {
            Deque<Long> order = ownOrder.get(previous);
            if (order != null) {
                order.remove(key);
            }
        }
        Deque<Long> order = ownOrder.computeIfAbsent(villager, v -> new ArrayDeque<>());
        order.remove(key);
        order.addLast(key);
        while (order.size() > OWN_PER_VILLAGER) {
            long old = order.removeFirst();
            byVillagers.remove(old);
        }
    }

    @Nullable
    public UUID placedBy(BlockPos pos) {
        return byVillagers.get(pos.asLong());
    }

    /** The block at {@code pos} is gone; nobody owns that spot any more. */
    public void removed(BlockPos pos) {
        long key = pos.asLong();
        boolean changed = byPlayers.remove(key);
        UUID owner = byVillagers.remove(key);
        if (owner != null) {
            Deque<Long> order = ownOrder.get(owner);
            if (order != null) {
                order.remove(key);
            }
            changed = true;
        }
        if (chests.remove(key) != null) {
            changed = true;
        }
        if (changed) {
            markDirty();
        }
    }

    // ------------------------------------------------------------------ chests

    public void ownChest(BlockPos pos, UUID owner) {
        chests.put(pos.asLong(), new Chest(owner));
        markDirty();
    }

    @Nullable
    public Chest chest(BlockPos pos) {
        return chests.get(pos.asLong());
    }

    public void forgetChest(BlockPos pos) {
        if (chests.remove(pos.asLong()) != null) {
            markDirty();
        }
    }

    public List<BlockPos> chestsOf(UUID owner) {
        List<BlockPos> list = new ArrayList<>();
        chests.long2ObjectEntrySet().forEach(e -> {
            if (e.getValue().owner.equals(owner)) {
                list.add(BlockPos.fromLong(e.getLongKey()));
            }
        });
        return list;
    }

    public Iterable<Long2ObjectMap.Entry<Chest>> allChests() {
        return chests.long2ObjectEntrySet();
    }

    public void theft(Theft theft) {
        thefts.add(theft);
        markDirty();
    }

    public List<Theft> theftsAgainst(UUID owner) {
        return thefts.stream().filter(t -> t.owner().equals(owner)).toList();
    }

    public void noticed(Theft theft) {
        if (thefts.remove(theft)) {
            markDirty();
        }
    }
}
