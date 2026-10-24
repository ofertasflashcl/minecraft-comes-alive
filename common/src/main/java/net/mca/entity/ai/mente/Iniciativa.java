package net.mca.entity.ai.mente;

import net.mca.MCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.brain.tasks.chore.ProspectingTask;
import net.mca.entity.ai.chatAI.ChatAI;
import net.mca.entity.ai.sense.Entorno;
import net.mca.entity.ai.sense.Escena;
import net.mca.entity.ai.sense.Lugar;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a connected villager says on its own, the way a friend playing along does: a monster it sees close to the
 * player or behind them, the night coming while out in the open, its food running out, the bag nearly full, the
 * player badly hurt, diamonds, lava or a cave it found ahead. Only what its own eyes and body tell it, at most one
 * line every {@value #COOLDOWN} ticks, and only to the player it is with (or the one who connected it) within
 * {@value #RANGE} blocks.
 */
final class Iniciativa {
    static final int COOLDOWN = 900;
    static final double RANGE = 32;
    /** A monster this close to the player is worth a warning. */
    private static final double MONSTER_TO_PLAYER = 12;
    /** Seen this recently counts as seen now. */
    private static final int FRESH = 40;

    private long lastSaid = Long.MIN_VALUE / 2;
    private final Map<Integer, Long> warnedMonsters = new HashMap<>();
    private final Set<Long> announced = new HashSet<>();
    private long lastNightDay = -1;
    private long lastHungry = Long.MIN_VALUE / 2;
    private long lastBag = Long.MIN_VALUE / 2;
    private long lastHurt = Long.MIN_VALUE / 2;
    private long lastLava = Long.MIN_VALUE / 2;
    private long lastCave = Long.MIN_VALUE / 2;
    private float playerHealth = -1;
    private long lastPlan = Long.MIN_VALUE / 2;
    private String lastPlanLine = "";

    /** One look at whether there is something worth saying (every second). */
    void tick(ServerWorld world, VillagerEntityMCA villager, Voluntad will, @Nullable ProspectingTask task) {
        if ((villager.age % 20) != 7) {
            return;
        }
        long now = world.getTime();
        PlayerEntity to = listener(world, villager, will);
        float health = to == null ? -1 : to.getHealth();
        boolean hurtNow = to != null && playerHealth > 6.0F && health <= 6.0F;
        playerHealth = health;
        if (to == null || now - lastSaid < COOLDOWN) {
            return;
        }
        boolean es = will.spanish();
        String line = monster(world, villager, to, es, now);
        if (line == null && hurtNow && now - lastHurt > 1200) {
            lastHurt = now;
            line = es ? "¡Estás muy herido! Come algo o aléjate de aquí." : "You're badly hurt! Eat something or get out of here.";
        }
        boolean free = will.modo() == Voluntad.Modo.LIBRE;
        if (line == null) {
            line = lava(villager, to, es, now);
        }
        if (line == null && !free) {
            // its own mining announces ores itself; while it walks with someone, it tells them
            line = diamonds(villager, to, es, now);
        }
        if (line == null && !free && now - lastCave > 2400 && (villager.age % 100) == 7) {
            line = cave(world, villager, to, es, now);
        }
        if (line == null) {
            line = night(world, villager, es);
        }
        if (line == null && task != null && now - lastHungry > 6000 && task.food() <= 6 && ProspectingTask.comidaEnMochila(villager) == 0) {
            lastHungry = now;
            line = es ? "Me muero de hambre y no me queda nada de comer." : "I'm starving and I've got nothing left to eat.";
        }
        if (line == null && task != null && now - lastBag > 6000 && ProspectingTask.espacioLibre(villager) <= 2) {
            lastBag = now;
            line = es ? "Tengo la mochila casi llena." : "My bag is almost full.";
        }
        if (line != null) {
            decir(villager, to, line, now);
        }
    }

    /**
     * A change of plans said out loud ({@link Voluntad#contar}): to its listener, at most once every
     * {@value #PLAN_COOLDOWN} ticks and never the same line twice in a row within a minute. False when it kept quiet.
     */
    boolean contar(ServerWorld world, VillagerEntityMCA villager, Voluntad will, String line, long now) {
        if (now - lastPlan < PLAN_COOLDOWN || line.equals(lastPlanLine) && now - lastPlan < 1200) {
            MCA.LOGGER.info("[Tropeles] {} piensa (sin decirlo): «{}»", villager.getName().getString(), line);
            return false;
        }
        PlayerEntity to = listener(world, villager, will);
        lastPlan = now;
        lastPlanLine = line;
        if (to == null) {
            MCA.LOGGER.info("[Tropeles] {} piensa en voz alta: «{}»", villager.getName().getString(), line);
            return false;
        }
        MCA.LOGGER.info("[Tropeles] {} le cuenta a {} lo que hace: «{}»", villager.getName().getString(), to.getName().getString(), line);
        if (to instanceof ServerPlayerEntity player && player.networkHandler != null) {
            ChatAI.deliver(player, villager, line);
        }
        return true;
    }

    static final int PLAN_COOLDOWN = 160;

    /** Says {@code line} in chat as "<Name> line" to {@code to} and those around (server thread). */
    void decir(VillagerEntityMCA villager, PlayerEntity to, String line, long now) {
        lastSaid = now;
        MCA.LOGGER.info("[Tropeles] {} le dice por su cuenta a {}: «{}»", villager.getName().getString(), to.getName().getString(), line);
        Recuerdos.anotar(villager, "dijo", "le dije a " + to.getName().getString() + " por mi cuenta: «" + line + "»", villager.getBlockPos());
        if (to instanceof ServerPlayerEntity player && player.networkHandler != null) {
            ChatAI.deliver(player, villager, line);
        }
    }

    /** The player it is with, else the one who connected it, when within {@value #RANGE} blocks in this world. */
    @Nullable
    private static PlayerEntity listener(ServerWorld world, VillagerEntityMCA villager, Voluntad will) {
        PlayerEntity player = will.player(world);
        if (player == null) {
            player = villager.getVillagerBrain().getJobAssigner().orElse(null);
        }
        if (player == null || player.getWorld() != world || player.isSpectator() || !player.isAlive()
                || player.squaredDistanceTo(villager) > RANGE * RANGE) {
            return null;
        }
        return player;
    }

    /** "¡Cuidado, un esqueleto a tu izquierda!": a monster its eyes saw, close to the player and not in front of them. */
    @Nullable
    private String monster(ServerWorld world, VillagerEntityMCA villager, PlayerEntity to, boolean es, long now) {
        warnedMonsters.values().removeIf(t -> now - t > 1200);
        VisualMemory.SeenEntity best = null;
        double bestD2 = Double.MAX_VALUE;
        for (VisualMemory.SeenEntity seen : Retina.memory(villager).entities(now, FRESH)) {
            if (seen.type.getSpawnGroup() != SpawnGroup.MONSTER || seen.pos == null || warnedMonsters.containsKey(seen.id)) {
                continue;
            }
            Entity entity = world.getEntityById(seen.id);
            if (entity == null || !entity.isAlive()) {
                continue;
            }
            double d2 = seen.pos.squaredDistanceTo(to.getPos());
            if (d2 > MONSTER_TO_PLAYER * MONSTER_TO_PLAYER) {
                continue;
            }
            String side = side(to, seen.pos, true);
            // one right in front of the player, far enough, they see themselves
            if (side.equals("delante") && d2 > 6 * 6) {
                continue;
            }
            if (d2 < bestD2) {
                best = seen;
                bestD2 = d2;
            }
        }
        if (best == null) {
            return null;
        }
        warnedMonsters.put(best.id, now);
        String side = side(to, best.pos, es);
        String[] name = monsterName(best.type);
        return es ? "¡Cuidado, " + name[0] + " " + side + "!" : "Watch out, " + name[1] + " " + side + "!";
    }

    /** "Ojo, hay lava adelante": lava its eyes saw within ten blocks. */
    @Nullable
    private String lava(VillagerEntityMCA villager, PlayerEntity to, boolean es, long now) {
        if (now - lastLava < 2400) {
            return null;
        }
        Vec3d at = villager.getPos();
        for (VisualMemory.SeenBlock seen : Retina.memory(villager).blocks(b -> now - b.lastSeen <= FRESH && b.state != null
                && b.state.getFluidState().isIn(FluidTags.LAVA) && b.pos.isWithinDistance(at, 10))) {
            lastLava = now;
            String side = side(to, Vec3d.ofCenter(seen.pos), es);
            return es ? "Ojo, hay lava " + side + "." : "Careful, there's lava " + side + ".";
        }
        return null;
    }

    /** "¡Mira, diamantes a tu derecha!": a diamond ore its eyes saw, said once per ore. */
    @Nullable
    private String diamonds(VillagerEntityMCA villager, PlayerEntity to, boolean es, long now) {
        for (VisualMemory.SeenBlock seen : Retina.memory(villager).ores()) {
            if (seen.state == null || now - seen.lastSeen > FRESH || announced.contains(seen.pos.asLong())) {
                continue;
            }
            String id = Registries.BLOCK.getId(seen.state.getBlock()).getPath();
            if (!id.endsWith("diamond_ore")) {
                continue;
            }
            // the whole vein at once: the ores around it are the same find
            for (BlockPos near : BlockPos.iterate(seen.pos.add(-2, -2, -2), seen.pos.add(2, 2, 2))) {
                announced.add(near.asLong());
            }
            String side = side(to, Vec3d.ofCenter(seen.pos), es);
            return es ? "¡Mira, diamantes " + side + "!" : "Look, diamonds " + side + "!";
        }
        return null;
    }

    /** "Ahí hay una cueva": a cave opening in the scene it understands, said once per opening. */
    @Nullable
    private String cave(ServerWorld world, VillagerEntityMCA villager, PlayerEntity to, boolean es, long now) {
        Escena scene = Escena.of(world, villager, 200);
        Escena.Cosa cave = scene.nearest(Escena.CUEVA);
        if (cave == null || cave.distancia() > 24) {
            return null;
        }
        long key = BlockPos.asLong(cave.donde().getX() >> 4, 0, cave.donde().getZ() >> 4);
        if (!announced.add(key ^ 0x5A5A5A5AL)) {
            return null;
        }
        lastCave = now;
        String side = side(to, Vec3d.ofCenter(cave.donde()), es);
        return es ? "Veo una cueva " + side + "." : "I see a cave " + side + ".";
    }

    /** "Se está haciendo de noche": the sun going down while it is out in the open, once a day. */
    @Nullable
    private String night(ServerWorld world, VillagerEntityMCA villager, boolean es) {
        long time = world.getTimeOfDay();
        long day = time / 24000L;
        long hour = time % 24000L;
        if (hour < 12000 || hour > 13200 || day == lastNightDay || Entorno.lugar(world, villager) != Lugar.AFUERA) {
            return null;
        }
        lastNightDay = day;
        return es ? "Se está haciendo de noche, ojo con los monstruos." : "It's getting dark, watch out for monsters.";
    }

    /** Where a point lies from the player, by where they look: "a tu izquierda", "detrás de ti". */
    private static String side(PlayerEntity player, Vec3d point, boolean es) {
        double dx = point.x - player.getX();
        double dz = point.z - player.getZ();
        if (dx * dx + dz * dz < 4.0) {
            return es ? "aquí al lado" : "right next to you";
        }
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float rel = MathHelper.wrapDegrees(yaw - player.getHeadYaw());
        if (Math.abs(rel) <= 45.0F) {
            return es ? "delante" : "ahead of you";
        }
        if (rel > 45.0F && rel <= 135.0F) {
            return es ? "a tu derecha" : "to your right";
        }
        if (rel < -45.0F && rel >= -135.0F) {
            return es ? "a tu izquierda" : "to your left";
        }
        return es ? "detrás de ti" : "behind you";
    }

    /** "un esqueleto" / "a skeleton". */
    private static String[] monsterName(EntityType<?> type) {
        String path = Registries.ENTITY_TYPE.getId(type).getPath();
        String en = type.getName().getString().toLowerCase(java.util.Locale.ROOT);
        String es = switch (path) {
            case "zombie" -> "un zombi";
            case "husk" -> "un zombi momificado";
            case "drowned" -> "un ahogado";
            case "zombie_villager" -> "un aldeano zombi";
            case "skeleton" -> "un esqueleto";
            case "stray" -> "un esqueleto glacial";
            case "creeper" -> "un creeper";
            case "spider" -> "una araña";
            case "cave_spider" -> "una araña de cueva";
            case "enderman" -> "un enderman";
            case "witch" -> "una bruja";
            case "slime" -> "un slime";
            case "phantom" -> "un phantom";
            case "silverfish" -> "un lepisma";
            case "pillager" -> "un saqueador";
            case "vindicator" -> "un vindicador";
            case "evoker" -> "un evocador";
            case "ravager" -> "un devastador";
            default -> "un monstruo";
        };
        String article = !en.isEmpty() && "aeiou".indexOf(en.charAt(0)) >= 0 ? "an " : "a ";
        return new String[]{es, article + en};
    }
}
