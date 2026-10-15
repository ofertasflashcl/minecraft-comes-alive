package net.mca.server.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.mca.MCA;
import net.mca.entity.ai.relationship.Gender;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.VillagerFactory;
import net.mca.entity.ai.MemoryModuleTypeMCA;
import net.mca.entity.ai.MoveState;
import net.mca.entity.ai.chatAI.ChatAI;
import net.mca.entity.ai.body.Archery;
import net.mca.entity.ai.body.Cofres;
import net.mca.entity.ai.body.Mining;
import net.mca.entity.ai.brain.tasks.FollowTask;
import net.mca.entity.ai.brain.tasks.chore.ProspectingTask;
import net.mca.entity.ai.sense.Obras;
import net.mca.entity.ai.sense.Retina;
import net.mca.entity.ai.sense.VisualMemory;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-game checks for the eye, the miner and the fighters, run on a real server. Each one builds its own
 * little arena far from spawn, runs the real code on real ticks and logs {@code PRUEBA OK} or
 * {@code PRUEBA FALLA}, which CI reads from the server log.
 */
public final class Pruebas {
    private static final List<Scenario> RUNNING = new ArrayList<>();
    private static final Map<String, String> RESULTS = new LinkedHashMap<>();

    private Pruebas() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("mca-prueba")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("cual", StringArgumentType.word()).executes(Pruebas::run)));
    }

    private static int run(CommandContext<ServerCommandSource> ctx) {
        String which = StringArgumentType.getString(ctx, "cual");
        ServerWorld world = ctx.getSource().getServer().getOverworld();
        try {
            switch (which) {
                case "ojo" -> ojo(world);
                case "minar" -> start(new Minar(world));
                case "espada" -> start(new Espada(world));
                case "arco" -> start(new Arco(world));
                case "progreso" -> start(new Progreso(world));
                case "nombre" -> start(new Nombre(world));
                case "obras" -> obras(world);
                case "robo" -> robo(world);
                case "regalo" -> regalo(world);
                case "arco-cerca" -> start(new ArcoCerca(world));
                case "horno" -> start(new Horno(world));
                case "hogar" -> start(new Hogar(world));
                case "resumen" -> resumen();
                default -> {
                    ctx.getSource().sendError(Text.literal("ojo | minar | espada | arco | progreso | nombre | obras | robo | regalo | arco-cerca | horno | hogar | resumen"));
                    return 0;
                }
            }
        } catch (RuntimeException e) {
            MCA.LOGGER.error("PRUEBA FALLA " + which + ": excepción", e);
            RESULTS.put(which, "FALLA");
        }
        ctx.getSource().sendFeedback(() -> Text.literal("mca-prueba " + which), false);
        return 1;
    }

    private static void ok(String name, String detail) {
        MCA.LOGGER.info("PRUEBA OK " + name + ": " + detail);
        RESULTS.put(name, "OK");
    }

    private static void fail(String name, String detail) {
        MCA.LOGGER.error("PRUEBA FALLA " + name + ": " + detail);
        RESULTS.put(name, "FALLA");
    }

    private static void check(String name, boolean passed, String detail) {
        if (passed) {
            ok(name, detail);
        } else {
            fail(name, detail);
        }
    }

    private static void resumen() {
        for (Scenario scenario : RUNNING) {
            fail(scenario.name, "no terminó a tiempo (" + scenario.ticks + " ticks) " + scenario.state());
        }
        RUNNING.clear();
        long passed = RESULTS.values().stream().filter("OK"::equals).count();
        MCA.LOGGER.info("PRUEBA RESUMEN " + passed + "/" + RESULTS.size() + " " + RESULTS);
    }

    /** Called at the end of every server tick. */
    public static void tick() {
        if (RUNNING.isEmpty()) {
            return;
        }
        for (Scenario scenario : new ArrayList<>(RUNNING)) {
            try {
                Boolean done = scenario.tick();
                if (done != null) {
                    RUNNING.remove(scenario);
                    scenario.cleanup();
                }
            } catch (RuntimeException e) {
                MCA.LOGGER.error("PRUEBA FALLA " + scenario.name + ": excepción", e);
                RESULTS.put(scenario.name, "FALLA");
                RUNNING.remove(scenario);
            }
        }
    }

    private static void start(Scenario scenario) {
        RUNNING.add(scenario);
    }

    // ------------------------------------------------------------------ arena helpers

    private static void load(ServerWorld world, BlockPos origin, int radius) {
        for (int cx = (origin.getX() - radius) >> 4; cx <= (origin.getX() + radius) >> 4; cx++) {
            for (int cz = (origin.getZ() - radius) >> 4; cz <= (origin.getZ() + radius) >> 4; cz++) {
                world.setChunkForced(cx, cz, true);
                world.getChunk(cx, cz);
            }
        }
    }

    private static void fill(ServerWorld world, BlockPos a, BlockPos b, BlockState state) {
        for (BlockPos pos : BlockPos.iterate(a, b)) {
            world.setBlockState(pos, state, 2);
        }
    }

    private static VillagerEntityMCA villager(ServerWorld world, BlockPos feet, Direction facing) {
        return villager(world, feet, facing, "Prueba");
    }

    private static VillagerEntityMCA villager(ServerWorld world, BlockPos feet, Direction facing, String name) {
        VillagerEntityMCA villager = VillagerFactory.newVillager(world)
                .withGender(Gender.MALE)
                .withAge(0)
                .withName(name)
                .withPosition(Vec3d.ofBottomCenter(feet))
                .spawn(SpawnReason.COMMAND);
        villager.getInventory().clear();
        villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, ItemStack.EMPTY);
        face(villager, facing.asRotation(), 0);
        villager.setPersistent();
        // "stay here": no wandering off, the self-defence and the checked code drive it
        villager.getVillagerBrain().setMoveState(MoveState.STAY, null);
        return villager;
    }

    private static void face(LivingEntity entity, float yaw, float pitch) {
        entity.setYaw(yaw);
        entity.prevYaw = yaw;
        entity.setHeadYaw(yaw);
        entity.prevHeadYaw = yaw;
        entity.setBodyYaw(yaw);
        entity.prevBodyYaw = yaw;
        entity.setPitch(pitch);
        entity.prevPitch = pitch;
    }

    private static ZombieEntity zombie(ServerWorld world, Vec3d at, boolean ai) {
        ZombieEntity zombie = EntityType.ZOMBIE.create(world);
        if (zombie == null) {
            throw new IllegalStateException("no zombie");
        }
        zombie.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
        zombie.setBaby(false);
        // a helmet keeps the sun from doing the villager's job
        zombie.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        zombie.setEquipmentDropChance(EquipmentSlot.HEAD, 0);
        zombie.setAiDisabled(!ai);
        zombie.setPersistent();
        world.spawnEntity(zombie);
        return zombie;
    }

    // ------------------------------------------------------------------ the eye, checked in one tick

    private static void ojo(ServerWorld world) {
        BlockPos o = new BlockPos(2000, 100, 0);
        load(world, o, 24);
        fill(world, o.add(-8, -1, -8), o.add(8, -1, 8), Blocks.STONE.getDefaultState());
        fill(world, o.add(-8, 0, -8), o.add(8, 6, 8), Blocks.AIR.getDefaultState());
        VillagerEntityMCA v = villager(world, o, Direction.EAST);
        v.setAiDisabled(true);
        face(v, -90.0F, 0.0F);
        try {
            Vec3d eye = v.getEyePos();
            int eyeY = MathHelper.floor(eye.y);
            double frac = eye.y - eyeY;
            BlockPos eyeRow = new BlockPos(o.getX(), eyeY, o.getZ());
            VisualMemory memory = Retina.memory(v);

            // 1. a stone wall 3 blocks ahead: exact block, face and distance to the face
            world.setBlockState(eyeRow.east(3), Blocks.STONE.getDefaultState(), 2);
            Retina.Focus focus = Retina.focus(world, v, memory, world.getTime());
            double expected = o.getX() + 3 - eye.x;
            check("ojo-piedra", focus != null && focus.state() != null && focus.state().isOf(Blocks.STONE) && focus.face() == Direction.WEST
                            && Math.abs(focus.distance() - expected) < 1.0E-3 && focus.inReach(),
                    describe(focus) + " esperado piedra cara oeste a " + fmt(expected));

            // 2. glass in front: the crosshair names the glass (like Jade), the sight ray goes through it
            world.setBlockState(eyeRow.east(3), Blocks.AIR.getDefaultState(), 2);
            world.setBlockState(eyeRow.east(2), Blocks.GLASS.getDefaultState(), 2);
            world.setBlockState(eyeRow.east(5), Blocks.STONE.getDefaultState(), 2);
            focus = Retina.focus(world, v, memory, world.getTime());
            Retina.Sight sight = Retina.cast(world, eye, eye.add(10, 0, 0), v, memory, world.getTime(), false);
            double toStone = o.getX() + 5 - eye.x;
            check("ojo-vidrio", focus != null && focus.state() != null && focus.state().isOf(Blocks.GLASS)
                            && sight.hit() != null && sight.hit().getBlockPos().equals(eyeRow.east(5)) && Math.abs(sight.distance() - toStone) < 1.0E-3,
                    "mira " + describe(focus) + ", ve a través hasta " + (sight.hit() == null ? "nada" : sight.hit().getBlockPos().toShortString() + " a " + fmt(sight.distance())));
            world.setBlockState(eyeRow.east(2), Blocks.AIR.getDefaultState(), 2);

            // 3. slabs: the half the ray crosses is hit at the exact distance, the other half is passed over
            SlabType hitHalf = frac >= 0.5 ? SlabType.TOP : SlabType.BOTTOM;
            SlabType missHalf = hitHalf == SlabType.TOP ? SlabType.BOTTOM : SlabType.TOP;
            world.setBlockState(eyeRow.east(2), Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, hitHalf), 2);
            focus = Retina.focus(world, v, memory, world.getTime());
            double toSlab = o.getX() + 2 - eye.x;
            boolean slabHit = focus != null && focus.state() != null && focus.state().isOf(Blocks.STONE_SLAB)
                    && Math.abs(focus.distance() - toSlab) < 1.0E-3;
            world.setBlockState(eyeRow.east(2), Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, missHalf), 2);
            Retina.Focus over = Retina.focus(world, v, memory, world.getTime());
            boolean slabMiss = over != null && over.state() != null && over.state().isOf(Blocks.STONE) && Math.abs(over.distance() - toStone) < 1.0E-3;
            check("ojo-losa", slabHit && slabMiss, "losa " + hitHalf + ": " + describe(focus) + "; losa " + missHalf + ": " + describe(over)
                    + " (ojo a " + fmt(frac) + " del bloque)");
            world.setBlockState(eyeRow.east(2), Blocks.AIR.getDefaultState(), 2);

            // 4. ore in plain view is remembered, ore behind stone is not
            BlockPos hidden = eyeRow.east(6);
            BlockPos shown = eyeRow.east(4).north(2);
            world.setBlockState(hidden, Blocks.DIAMOND_ORE.getDefaultState(), 2);
            world.setBlockState(shown, Blocks.EMERALD_ORE.getDefaultState(), 2);
            Retina.lookAll(world, v);
            BlockState seenShown = memory.stateAt(shown);
            BlockState seenHidden = memory.stateAt(hidden);
            check("ojo-oculto", seenShown != null && seenShown.isOf(Blocks.EMERALD_ORE) && seenHidden == null,
                    "esmeralda a la vista: " + (seenShown != null) + ", diamante tras la piedra: " + (seenHidden != null)
                            + ", bloques en memoria " + memory.blockCount());

            // 5. a body in the crosshair: distance to its box like a player's hit
            fill(world, eyeRow.east(1), eyeRow.east(6), Blocks.AIR.getDefaultState());
            ArmorStandEntity stand = EntityType.ARMOR_STAND.create(world);
            if (stand != null) {
                stand.refreshPositionAndAngles(o.getX() + 3.5, o.getY(), o.getZ() + 0.5, 0, 0);
                world.spawnEntity(stand);
                focus = Retina.focus(world, v, memory, world.getTime());
                double toBox = stand.getBoundingBox().minX - eye.x;
                check("ojo-cuerpo", focus != null && focus.entity() == stand && Math.abs(focus.distance() - toBox) < 1.0E-3,
                        describe(focus) + " esperado soporte a " + fmt(toBox));
                stand.discard();
            }
        } finally {
            v.discard();
        }
    }

    // ------------------------------------------------------------------ calling a villager by name from afar

    /** Two villagers 2500 blocks from the one asking, checked once their chunk has been ticking for a while. */
    private static final class Nombre extends Scenario {
        private final VillagerEntityMCA rosa;
        private final VillagerEntityMCA other;

        Nombre(ServerWorld world) {
            super("nombre", world, 200);
            BlockPos o = new BlockPos(2500, 100, 0);
            load(world, o, 8);
            fill(world, o.add(-3, -1, -3), o.add(3, -1, 3), Blocks.STONE.getDefaultState());
            rosa = villager(world, o, Direction.EAST, "Rosa Pérez");
            other = villager(world, o.east(2), Direction.EAST, "Rosalía Pérez");
        }

        @Override
        Boolean step() {
            if (ticks < 40) {
                return null;
            }
            // the one asking stands 2500 blocks away, at spawn
            ArmorStandEntity far = EntityType.ARMOR_STAND.create(world);
            if (far != null) {
                far.refreshPositionAndAngles(0.5, 100, 0.5, 0, 0);
            }
            var full = ChatAI.findByFullName(world.getServer(), "Hola rosa perez, ¿puedes venir?", far);
            var exact = ChatAI.findByFullName(world.getServer(), "rosalía pérez ven", far);
            var partial = ChatAI.findByFullName(world.getServer(), "hola rosa", far);
            var glued = ChatAI.findByFullName(world.getServer(), "rosaperez", far);
            boolean passed = full.orElse(null) == rosa && exact.orElse(null) == other && partial.isEmpty() && glued.isEmpty();
            check(name, passed, "nombre completo sin tildes a 2500 bloques: " + full.map(v -> v.getName().getString()).orElse("nadie")
                    + ", el otro nombre: " + exact.map(v -> v.getName().getString()).orElse("nadie")
                    + ", solo el primer nombre: " + partial.isPresent() + ", pegado: " + glued.isPresent());
            return passed;
        }

        @Override
        String state() {
            return "rosa viva " + rosa.isAlive() + " con nombre '" + rosa.getName().getString() + "'";
        }

        @Override
        void cleanup() {
            rosa.discard();
            other.discard();
        }
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.3f", d);
    }

    private static String describe(@Nullable Retina.Focus focus) {
        if (focus == null) {
            return "nada";
        }
        if (focus.entity() != null) {
            return focus.entity().getName().getString() + " a " + fmt(focus.distance());
        }
        return (focus.state() == null ? "?" : focus.state().toString()) + " cara " + focus.face() + " a " + fmt(focus.distance());
    }

    // ------------------------------------------------------------------ tick-driven checks

    private abstract static class Scenario {
        final String name;
        final ServerWorld world;
        final int limit;
        int ticks;

        Scenario(String name, ServerWorld world, int limit) {
            this.name = name;
            this.world = world;
            this.limit = limit;
        }

        /** Null while running, TRUE or FALSE when it has reported. */
        @Nullable
        Boolean tick() {
            ticks++;
            if (ticks < 10) {
                return null; // let the forced chunks start ticking
            }
            Boolean result = step();
            if (result == null && ticks > limit) {
                fail(name, "sin terminar tras " + ticks + " ticks; " + state());
                return Boolean.FALSE;
            }
            return result;
        }

        @Nullable
        abstract Boolean step();

        abstract String state();

        abstract void cleanup();
    }

    /** A diamond at the end of a stone corridor, across a three-wide pit: walk, bridge, mine what it saw. */
    private static final class Minar extends Scenario {
        private final ProspectingTask task = new ProspectingTask();
        private final VillagerEntityMCA miner;
        private final BlockPos diamond;
        private final BlockPos origin;

        Minar(ServerWorld world) {
            super("minar", world, 1600);
            origin = new BlockPos(2100, 100, 0);
            load(world, origin, 24);
            fill(world, origin.add(-3, -5, -3), origin.add(14, 4, 3), Blocks.STONE.getDefaultState());
            fill(world, origin.add(0, 0, 0), origin.add(9, 1, 0), Blocks.AIR.getDefaultState());
            // the pit: no floor for three blocks, three deep
            fill(world, origin.add(3, -3, 0), origin.add(5, -1, 0), Blocks.AIR.getDefaultState());
            // light, so the eye can tell the ore
            for (int x = 0; x <= 9; x += 3) {
                world.setBlockState(origin.add(x, 2, 0), Blocks.GLOWSTONE.getDefaultState(), 2);
            }
            diamond = origin.add(10, 0, 0);
            world.setBlockState(diamond, Blocks.DIAMOND_ORE.getDefaultState(), 2);
            miner = villager(world, origin, Direction.EAST);
            miner.getInventory().addStack(new ItemStack(Items.DIAMOND_PICKAXE));
            miner.getInventory().addStack(new ItemStack(Items.COBBLESTONE, 16));
            face(miner, -90.0F, 0.0F);
            Retina.lookAll(world, miner);
        }

        @Override
        Boolean step() {
            if (!miner.isAlive()) {
                fail(name, "el minero murió");
                return Boolean.FALSE;
            }
            miner.getNavigation().stop();
            task.practice(world, miner);
            if (world.getBlockState(diamond).isOf(Blocks.DIAMOND_ORE)) {
                return null;
            }
            boolean got = miner.getInventory().count(Items.DIAMOND) > 0;
            if (!got && ticks % 20 != 0) {
                return null; // let the drop be picked up
            }
            boolean bridged = Mining.standable(world, origin.add(4, -1, 0)) || Mining.standable(world, origin.add(3, -1, 0));
            check(name, got && bridged, "diamante picado en " + ticks + " ticks, en la mochila: " + got + ", puente sobre el pozo: " + bridged + ", " + state());
            return got && bridged;
        }

        @Override
        String state() {
            return "en " + miner.getBlockPos().toShortString() + ", haciendo: " + Retina.memory(miner).doing()
                    + ", diamante visto: " + (Retina.memory(miner).stateAt(diamond) != null);
        }

        @Override
        void cleanup() {
            miner.discard();
        }
    }

    /** A villager with a sword guarding someone while a zombie comes at them. */
    private static final class Espada extends Scenario {
        private final FollowTask follow = new FollowTask();
        private final VillagerEntityMCA guard;
        private final ArmorStandEntity ward;
        private final ZombieEntity zombie;

        Espada(ServerWorld world) {
            super("espada", world, 900);
            BlockPos o = new BlockPos(2200, 100, 0);
            load(world, o, 24);
            fill(world, o.add(-10, -1, -10), o.add(10, -1, 10), Blocks.STONE.getDefaultState());
            fill(world, o.add(-10, 0, -10), o.add(10, 3, 10), Blocks.AIR.getDefaultState());
            fill(world, o.add(-10, 4, -10), o.add(10, 4, 10), Blocks.GLOWSTONE.getDefaultState());
            ward = EntityType.ARMOR_STAND.create(world);
            if (ward == null) {
                throw new IllegalStateException("no armor stand");
            }
            ward.refreshPositionAndAngles(o.getX() - 1.5, o.getY(), o.getZ() + 0.5, 0, 0);
            world.spawnEntity(ward);
            guard = villager(world, o, Direction.EAST);
            guard.getInventory().addStack(new ItemStack(Items.IRON_SWORD));
            zombie = zombie(world, Vec3d.ofBottomCenter(o.east(8)), true);
            zombie.setTarget(guard);
        }

        @Override
        Boolean step() {
            if (!guard.isAlive()) {
                fail(name, "el aldeano murió; zombi con " + zombie.getHealth());
                return Boolean.FALSE;
            }
            follow.guard(world, guard, ward);
            if (zombie.isAlive()) {
                return null;
            }
            ok(name, "zombi muerto en " + ticks + " ticks, aldeano con " + guard.getHealth() + " de vida, arma " + guard.getMainHandStack().getItem());
            return Boolean.TRUE;
        }

        @Override
        String state() {
            return "zombi " + zombie.getHealth() + " vida, aldeano " + guard.getHealth() + " en " + guard.getBlockPos().toShortString()
                    + " con " + guard.getMainHandStack().getItem() + ", " + Retina.memory(guard).doing();
        }

        @Override
        void cleanup() {
            guard.discard();
            ward.discard();
            zombie.discard();
        }
    }

    /**
     * A bow villager on a platform; two zombies it cannot walk to, one 15 blocks away level, one 20 away and
     * 5 lower. Both must die, with no more arrows than a careful player would use.
     */
    private static final class Arco extends Scenario {
        private final FollowTask follow = new FollowTask();
        private final VillagerEntityMCA archer;
        private final ArmorStandEntity ward;
        private final ZombieEntity near;
        private final ZombieEntity far;

        Arco(ServerWorld world) {
            super("arco", world, 1200);
            BlockPos o = new BlockPos(2300, 100, 0);
            load(world, o, 40);
            fill(world, o.add(-4, -1, -4), o.add(4, -1, 4), Blocks.STONE.getDefaultState());
            fill(world, o.add(-4, 0, -4), o.add(30, 6, 6), Blocks.AIR.getDefaultState());
            fill(world, o.add(14, -1, -1), o.add(16, -1, 1), Blocks.STONE.getDefaultState());
            fill(world, o.add(19, -6, 2), o.add(21, -6, 4), Blocks.STONE.getDefaultState());
            ward = EntityType.ARMOR_STAND.create(world);
            if (ward == null) {
                throw new IllegalStateException("no armor stand");
            }
            ward.refreshPositionAndAngles(o.getX() - 1.5, o.getY(), o.getZ() + 0.5, 0, 0);
            world.spawnEntity(ward);
            archer = villager(world, o, Direction.EAST);
            archer.getInventory().addStack(new ItemStack(Items.BOW));
            archer.getInventory().addStack(new ItemStack(Items.ARROW, 32));
            near = zombie(world, Vec3d.ofBottomCenter(o.east(15)), false);
            far = zombie(world, Vec3d.ofBottomCenter(o.add(20, -5, 3)), false);
            face(archer, -90.0F, 0.0F);
        }

        @Override
        Boolean step() {
            if (!archer.isAlive()) {
                fail(name, "el arquero murió");
                return Boolean.FALSE;
            }
            if (archer.getY() < 95) {
                fail(name, "el arquero se cayó de su plataforma");
                return Boolean.FALSE;
            }
            follow.guard(world, archer, ward);
            if (near.isAlive() || far.isAlive()) {
                return null;
            }
            int shots = Archery.SHOTS.of(archer);
            // a full-draw arrow does 6 to 9, a zombie has 20 life and a leather cap: 3 or 4 hits each
            check(name, shots <= 12, "dos zombis muertos (15 y 20 bloques, uno 5 más abajo) con " + shots + " flechas en " + ticks + " ticks");
            return shots <= 12;
        }

        @Override
        String state() {
            return "cerca " + near.getHealth() + ", lejos " + far.getHealth() + ", flechas " + Archery.SHOTS.of(archer)
                    + ", arquero en " + archer.getBlockPos().toShortString() + " con " + archer.getMainHandStack().getItem() + ", " + Retina.memory(archer).doing();
        }

        @Override
        void cleanup() {
            archer.discard();
            ward.discard();
            near.discard();
            far.discard();
        }
    }

    // ------------------------------------------------------------------ trees and buildings

    /** A natural tree: a trunk on grass with leaves the world grew around its top. */
    private static void tree(ServerWorld world, BlockPos base, int height) {
        fill(world, base, base.up(height - 1), Blocks.OAK_LOG.getDefaultState());
        BlockState leaves = Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.DISTANCE, 1);
        BlockPos top = base.up(height - 1);
        for (Direction dir : Direction.Type.HORIZONTAL) {
            world.setBlockState(top.offset(dir), leaves, 2);
            world.setBlockState(top.offset(dir).down(), leaves, 2);
        }
        world.setBlockState(top.up(), leaves, 2);
    }

    /** A 3x3 log cabin: logs at the four corners, planks walls, a glass window and a planks roof. */
    private static void cabin(ServerWorld world, BlockPos corner) {
        fill(world, corner, corner.add(2, 2, 2), Blocks.OAK_PLANKS.getDefaultState());
        fill(world, corner.add(1, 0, 1), corner.add(1, 1, 1), Blocks.AIR.getDefaultState());
        for (BlockPos c : new BlockPos[]{corner, corner.add(2, 0, 0), corner.add(0, 0, 2), corner.add(2, 0, 2)}) {
            fill(world, c, c.up(2), Blocks.OAK_LOG.getDefaultState());
        }
        world.setBlockState(corner.add(1, 1, 0), Blocks.GLASS.getDefaultState(), 2);
        fill(world, corner.add(0, 3, 0), corner.add(2, 3, 2), Blocks.OAK_PLANKS.getDefaultState());
    }

    private static int cabinLogs(ServerWorld world, BlockPos corner) {
        int n = 0;
        for (BlockPos c : new BlockPos[]{corner, corner.add(2, 0, 0), corner.add(0, 0, 2), corner.add(2, 0, 2)}) {
            for (int y = 0; y < 3; y++) {
                if (world.getBlockState(c.up(y)).isOf(Blocks.OAK_LOG)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** The eye tells the tree from the cabin, the player's block from nature, its own block from another's. */
    private static void obras(ServerWorld world) {
        BlockPos o = new BlockPos(2600, 100, 0);
        load(world, o, 16);
        fill(world, o.add(-8, -4, -8), o.add(8, -2, 8), Blocks.STONE.getDefaultState());
        fill(world, o.add(-8, -1, -8), o.add(8, -1, 8), Blocks.GRASS_BLOCK.getDefaultState());
        fill(world, o.add(-8, 0, -8), o.add(8, 8, 8), Blocks.AIR.getDefaultState());
        BlockPos trunk = o.add(4, 0, 0);
        tree(world, trunk, 4);
        BlockPos corner = o.add(-5, 0, -1);
        cabin(world, corner);
        // dirt under the cabin floor, among planks
        world.setBlockState(corner.add(1, -1, 1), Blocks.DIRT.getDefaultState(), 2);
        BlockPos stone = o.add(0, -3, 6);
        BlockPos byPlayer = o.add(0, -1, -6);
        BlockPos byVillager = o.add(2, -1, -6);
        VillagerEntityMCA v = villager(world, o, Direction.EAST);
        try {
            net.mca.server.world.data.TropelesMundo mundo = net.mca.server.world.data.TropelesMundo.get(world);
            mundo.placedByPlayer(byPlayer);
            Obras.placedByVillager(world, v, byVillager);
            Obras.Kind treeKind = Obras.kind(world, v, trunk.up());
            Obras.Kind cabinKind = Obras.kind(world, v, corner.up());
            Obras.Kind planks = Obras.kind(world, v, corner.add(1, 2, 0));
            Obras.Kind dirt = Obras.kind(world, v, corner.add(1, -1, 1));
            Obras.Kind rock = Obras.kind(world, v, stone);
            Obras.Kind player = Obras.kind(world, v, byPlayer);
            Obras.Kind own = Obras.kind(world, v, byVillager);
            boolean passed = treeKind == Obras.Kind.TREE && cabinKind == Obras.Kind.BUILDING && planks == Obras.Kind.BUILDING
                    && dirt == Obras.Kind.BUILDING && rock == Obras.Kind.NATURE && player == Obras.Kind.PLAYER_BUILT && own == Obras.Kind.OWN
                    && Obras.mayBreak(world, v, trunk) && !Obras.mayBreak(world, v, corner) && !Obras.mayBreak(world, v, byPlayer)
                    && Obras.mayBreak(world, v, byVillager);
            check("obras", passed, "árbol: " + treeKind.label + ", tronco de la cabaña: " + cabinKind.label + ", tablas: " + planks.label
                    + ", tierra bajo el piso: " + dirt.label + ", roca: " + rock.label + ", bloque de jugador: " + player.label + ", bloque suyo: " + own.label);
            mundo.removed(byPlayer);
            mundo.removed(byVillager);
        } finally {
            v.discard();
        }
    }

    // ------------------------------------------------------------------ chests: theft and gifts

    private static net.minecraft.server.network.ServerPlayerEntity fakePlayer(ServerWorld world, String name, Vec3d at) {
        var player = new net.minecraft.server.network.ServerPlayerEntity(world.getServer(), world,
                new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes(("mca-prueba-" + name).getBytes()), name));
        player.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
        return player;
    }

    private static BlockPos chestFor(ServerWorld world, VillagerEntityMCA owner, BlockPos pos) {
        world.setBlockState(pos, Blocks.CHEST.getDefaultState(), 3);
        Cofres.claim(world, owner, pos);
        return pos;
    }

    /** Someone who is not family takes from its chest next to it: it notices and the hearts go down. */
    private static void robo(ServerWorld world) {
        BlockPos o = new BlockPos(2700, 100, 0);
        load(world, o, 8);
        fill(world, o.add(-4, -1, -4), o.add(4, -1, 4), Blocks.STONE.getDefaultState());
        fill(world, o.add(-4, 0, -4), o.add(4, 4, 4), Blocks.AIR.getDefaultState());
        fill(world, o.add(-4, 5, -4), o.add(4, 5, 4), Blocks.GLOWSTONE.getDefaultState());
        VillagerEntityMCA owner = villager(world, o, Direction.EAST);
        try {
            BlockPos chest = chestFor(world, owner, o.add(2, 0, 0));
            var inv = Cofres.inventory(world, chest);
            if (inv == null) {
                fail("robo", "no hay cofre");
                return;
            }
            inv.setStack(0, new ItemStack(Items.DIAMOND, 10));
            Cofres.remember(world, chest);
            var thief = fakePlayer(world, "Ladron", Vec3d.ofBottomCenter(o.add(3, 0, 1)));
            int before = owner.getVillagerBrain().getMemoriesForPlayer(thief).getHearts();
            inv.getStack(0).decrement(5);
            int missing = Cofres.check(world, chest, thief);
            int after = owner.getVillagerBrain().getMemoriesForPlayer(thief).getHearts();
            boolean pending = !net.mca.server.world.data.TropelesMundo.get(world).theftsAgainst(owner.getUuid()).isEmpty();
            check("robo", missing == 5 && after < before && !pending, "le faltan " + missing + " diamantes, corazones " + before + " -> " + after
                    + ", lo notó al momento: " + !pending);
            world.setBlockState(chest, Blocks.AIR.getDefaultState(), 3);
            net.mca.server.world.data.TropelesMundo.get(world).removed(chest);
        } finally {
            owner.discard();
        }
    }

    /** A gift with the bag full goes into its chest, and the hearts still go up for it. */
    private static void regalo(ServerWorld world) {
        BlockPos o = new BlockPos(2800, 100, 0);
        load(world, o, 8);
        fill(world, o.add(-4, -1, -4), o.add(4, -1, 4), Blocks.STONE.getDefaultState());
        fill(world, o.add(-4, 0, -4), o.add(4, 4, 4), Blocks.AIR.getDefaultState());
        VillagerEntityMCA owner = villager(world, o, Direction.EAST);
        try {
            BlockPos chest = chestFor(world, owner, o.add(2, 0, 0));
            for (int i = 0; i < owner.getInventory().size(); i++) {
                owner.getInventory().setStack(i, new ItemStack(Items.DIRT, 64));
            }
            ItemStack gift = new ItemStack(Items.DIAMOND);
            boolean full = !owner.getInventory().canInsert(gift);
            boolean room = Cofres.hasRoom(world, owner, gift);
            boolean stored = Cofres.storeGift(world, owner, gift.copy());
            var inv = Cofres.inventory(world, chest);
            int inChest = inv == null ? 0 : Cofres.contents(inv).getOrDefault("minecraft:diamond", 0);
            check("regalo", full && room && stored && inChest == 1, "mochila llena: " + full + ", lugar en su cofre: " + room + ", diamante guardado en el cofre: " + inChest);
            world.setBlockState(chest, Blocks.AIR.getDefaultState(), 3);
            net.mca.server.world.data.TropelesMundo.get(world).removed(chest);
        } finally {
            owner.discard();
        }
    }

    /**
     * An empty-handed villager on grass with two bare trunks and solid stone below: it must chop, make a table,
     * a wooden pickaxe, dig, and come back up with a stone pickaxe of its own making.
     */
    private static final class Progreso extends Scenario {
        private final ProspectingTask task = new ProspectingTask();
        private final VillagerEntityMCA player;
        private final BlockPos origin;
        private boolean wooden;
        private int woodenAt;
        private final BlockPos cabin;

        Progreso(ServerWorld world) {
            super("progreso", world, 2900);
            origin = new BlockPos(2400, 100, 0);
            load(world, origin, 32);
            fill(world, origin.add(-14, -14, -14), origin.add(14, -2, 14), Blocks.STONE.getDefaultState());
            fill(world, origin.add(-14, -1, -14), origin.add(14, -1, 14), Blocks.GRASS_BLOCK.getDefaultState());
            fill(world, origin.add(-14, 0, -14), origin.add(14, 12, 14), Blocks.AIR.getDefaultState());
            tree(world, origin.add(4, 0, 1), 5);
            tree(world, origin.add(3, 0, -4), 3);
            // a log cabin on the other side: its logs are walls, not firewood
            cabin = origin.add(-7, 0, -1);
            cabin(world, cabin);
            player = villager(world, origin, Direction.EAST);
            // free to walk: its own legs carry it to the trees, no "stay here"
            player.getBrain().forget(MemoryModuleTypeMCA.STAYING.get());
            face(player, -90.0F, 0.0F);
            Retina.lookAll(world, player);
        }

        @Override
        Boolean step() {
            if (!player.isAlive()) {
                fail(name, "el aldeano murió; " + state());
                return Boolean.FALSE;
            }
            task.practice(world, player);
            if (!wooden && owns(Items.WOODEN_PICKAXE)) {
                wooden = true;
                woodenAt = ticks;
                MCA.LOGGER.info("PRUEBA progreso: pico de madera hecho en " + ticks + " ticks; " + state());
            }
            if (!owns(Items.STONE_PICKAXE)) {
                return null;
            }
            boolean tableBack = player.getInventory().count(Items.CRAFTING_TABLE) > 0;
            int cabinLogs = cabinLogs(world, cabin);
            boolean passed = wooden && cabinLogs == 12;
            check(name, passed, "con las manos vacías: pico de madera en " + woodenAt + " ticks, pico de piedra en " + ticks
                    + ", mesa de vuelta en la mochila: " + tableBack + ", troncos de la cabaña intactos " + cabinLogs + "/12; " + state());
            return passed;
        }

        private boolean owns(net.minecraft.item.Item item) {
            return player.getMainHandStack().isOf(item) || player.getInventory().count(item) > 0;
        }

        @Override
        String state() {
            var inv = player.getInventory();
            return "en " + player.getBlockPos().toShortString() + ", haciendo: " + Retina.memory(player).doing()
                    + ", troncos " + inv.count(Items.OAK_LOG) + ", tablas " + inv.count(Items.OAK_PLANKS) + ", palos " + inv.count(Items.STICK)
                    + ", roca " + inv.count(Items.COBBLESTONE) + ", mano " + player.getMainHandStack().getItem();
        }

        @Override
        void cleanup() {
            player.discard();
        }
    }

    /**
     * A zombie three blocks from an archer who also carries a sword: it must back off and shoot, never swap to
     * the sword, and kill it with arrows.
     */
    private static final class ArcoCerca extends Scenario {
        private final FollowTask follow = new FollowTask();
        private final VillagerEntityMCA archer;
        private final ArmorStandEntity ward;
        private final ZombieEntity zombie;
        private final Vec3d start;
        private boolean swapped;
        private double farthest;

        ArcoCerca(ServerWorld world) {
            super("arco-cerca", world, 1400);
            BlockPos o = new BlockPos(2900, 100, 0);
            load(world, o, 32);
            fill(world, o.add(-16, -1, -16), o.add(16, -1, 16), Blocks.STONE.getDefaultState());
            fill(world, o.add(-16, 0, -16), o.add(16, 4, 16), Blocks.AIR.getDefaultState());
            fill(world, o.add(-16, 5, -16), o.add(16, 5, 16), Blocks.GLOWSTONE.getDefaultState());
            ward = EntityType.ARMOR_STAND.create(world);
            if (ward == null) {
                throw new IllegalStateException("no armor stand");
            }
            ward.refreshPositionAndAngles(o.getX() - 1.5, o.getY(), o.getZ() + 0.5, 0, 0);
            world.spawnEntity(ward);
            archer = villager(world, o, Direction.EAST);
            archer.getInventory().addStack(new ItemStack(Items.BOW));
            archer.getInventory().addStack(new ItemStack(Items.IRON_SWORD));
            archer.getInventory().addStack(new ItemStack(Items.ARROW, 32));
            start = archer.getPos();
            zombie = zombie(world, Vec3d.ofBottomCenter(o.east(3)), true);
            zombie.setTarget(archer);
            face(archer, -90.0F, 0.0F);
        }

        @Override
        Boolean step() {
            if (!archer.isAlive()) {
                fail(name, "el arquero murió; " + state());
                return Boolean.FALSE;
            }
            follow.guard(world, archer, ward);
            if (archer.getMainHandStack().isOf(Items.IRON_SWORD)) {
                swapped = true;
            }
            farthest = Math.max(farthest, archer.distanceTo(zombie));
            if (zombie.isAlive()) {
                return null;
            }
            int shots = Archery.SHOTS.of(archer);
            boolean passed = !swapped && shots > 0;
            check(name, passed, "zombi muerto a flechazos (" + shots + " flechas) en " + ticks + " ticks, sacó la espada: " + swapped
                    + ", se alejó hasta " + String.format(Locale.ROOT, "%.1f", farthest) + " bloques, vida " + archer.getHealth());
            return passed;
        }

        @Override
        String state() {
            return "zombi " + zombie.getHealth() + " a " + String.format(Locale.ROOT, "%.1f", archer.distanceTo(zombie)) + ", flechas " + Archery.SHOTS.of(archer)
                    + ", mano " + archer.getMainHandStack().getItem() + ", espada: " + swapped + ", " + Retina.memory(archer).doing();
        }

        @Override
        void cleanup() {
            archer.discard();
            ward.discard();
            zombie.discard();
        }
    }

    /**
     * Raw iron, one coal, cobblestone and planks: it makes a table and a furnace, leaves the iron cooking while it
     * digs, comes back for the ingots, makes an iron pickaxe and takes its furnace with it at the end.
     */
    private static final class Horno extends Scenario {
        private final ProspectingTask task = new ProspectingTask();
        private final VillagerEntityMCA miner;
        private final BlockPos origin;
        private boolean sawCooking;
        private boolean minedWhileCooking;
        private int brokenAtCook = -1;

        Horno(ServerWorld world) {
            super("horno", world, 3200);
            origin = new BlockPos(3000, 100, 0);
            load(world, origin, 32);
            fill(world, origin.add(-12, -8, -12), origin.add(12, 6, 12), Blocks.STONE.getDefaultState());
            fill(world, origin.add(-2, 0, -2), origin.add(2, 2, 2), Blocks.AIR.getDefaultState());
            world.setBlockState(origin.add(0, 3, 0), Blocks.GLOWSTONE.getDefaultState(), 2);
            miner = villager(world, origin, Direction.EAST);
            miner.getBrain().forget(MemoryModuleTypeMCA.STAYING.get());
            miner.getInventory().addStack(new ItemStack(Items.STONE_PICKAXE));
            miner.getInventory().addStack(new ItemStack(Items.RAW_IRON, 6));
            miner.getInventory().addStack(new ItemStack(Items.COAL, 1));
            miner.getInventory().addStack(new ItemStack(Items.COBBLESTONE, 8));
            miner.getInventory().addStack(new ItemStack(Items.OAK_PLANKS, 4));
            miner.getInventory().addStack(new ItemStack(Items.STICK, 4));
            face(miner, -90.0F, 0.0F);
            Retina.lookAll(world, miner);
        }

        private int stoneMined() {
            return miner.getInventory().count(Items.COBBLESTONE);
        }

        @Override
        Boolean step() {
            if (!miner.isAlive()) {
                fail(name, "el minero murió; " + state());
                return Boolean.FALSE;
            }
            task.practice(world, miner);
            if (task.cooking()) {
                if (!sawCooking) {
                    sawCooking = true;
                    brokenAtCook = stoneMined();
                    MCA.LOGGER.info("PRUEBA horno: hierro en el horno a los " + ticks + " ticks; " + state());
                } else if (stoneMined() > brokenAtCook) {
                    minedWhileCooking = true;
                }
            }
            boolean pick = miner.getMainHandStack().isOf(Items.IRON_PICKAXE) || miner.getInventory().count(Items.IRON_PICKAXE) > 0;
            if (!pick || task.cooking() || !task.gearIdle()) {
                return null;
            }
            boolean furnaceBack = miner.getInventory().count(Items.FURNACE) > 0;
            boolean passed = sawCooking && minedWhileCooking && furnaceBack;
            check(name, passed, "pico de hierro en " + ticks + " ticks, el hierro se fundió mientras minaba: " + minedWhileCooking
                    + ", horno de vuelta en la mochila: " + furnaceBack + "; " + state());
            return passed;
        }

        @Override
        String state() {
            var inv = miner.getInventory();
            return "en " + miner.getBlockPos().toShortString() + ", haciendo: " + Retina.memory(miner).doing() + ", crudo " + inv.count(Items.RAW_IRON)
                    + ", lingotes " + inv.count(Items.IRON_INGOT) + ", carbón " + inv.count(Items.COAL) + ", roca " + inv.count(Items.COBBLESTONE)
                    + ", horno " + inv.count(Items.FURNACE) + ", fundiendo " + task.cooking() + ", mano " + miner.getMainHandStack().getItem();
        }

        @Override
        void cleanup() {
            miner.discard();
        }
    }

    /**
     * Hungry with nothing to eat ten blocks underground: it digs its way up, walks home, makes a chest there
     * from its planks and stores its gold in it.
     */
    private static final class Hogar extends Scenario {
        private final ProspectingTask task = new ProspectingTask();
        private final VillagerEntityMCA miner;
        private final BlockPos home;

        Hogar(ServerWorld world) {
            super("hogar", world, 3400);
            BlockPos o = new BlockPos(3100, 100, 0);
            load(world, o, 32);
            fill(world, o.add(-12, -14, -12), o.add(12, -2, 12), Blocks.STONE.getDefaultState());
            fill(world, o.add(-12, -1, -12), o.add(12, -1, 12), Blocks.GRASS_BLOCK.getDefaultState());
            fill(world, o.add(-12, 0, -12), o.add(12, 10, 12), Blocks.AIR.getDefaultState());
            home = o.add(6, 0, 0);
            BlockPos pocket = o.add(0, -11, 0);
            fill(world, pocket, pocket.up(), Blocks.AIR.getDefaultState());
            miner = villager(world, pocket, Direction.EAST);
            miner.getBrain().forget(MemoryModuleTypeMCA.STAYING.get());
            miner.getInventory().addStack(new ItemStack(Items.STONE_PICKAXE));
            miner.getInventory().addStack(new ItemStack(Items.RAW_GOLD, 20));
            miner.getInventory().addStack(new ItemStack(Items.COBBLESTONE, 32));
            miner.getInventory().addStack(new ItemStack(Items.OAK_PLANKS, 12));
            miner.getInventory().addStack(new ItemStack(Items.STICK, 4));
            miner.getInventory().addStack(new ItemStack(Items.WOODEN_SWORD));
            task.setHome(home);
            task.setFood(3);
            face(miner, -90.0F, 0.0F);
        }

        @Override
        Boolean step() {
            if (!miner.isAlive()) {
                fail(name, "el minero murió; " + state());
                return Boolean.FALSE;
            }
            if (!task.atWork() || ticks < 20) {
                task.practice(world, miner);
                return null;
            }
            // back at work (or it gave up): what is in its chest at home?
            int gold = 0;
            BlockPos chestAt = null;
            for (BlockPos pos : Cofres.own(world, miner, home)) {
                var inv = Cofres.inventory(world, pos);
                if (inv != null && pos.isWithinDistance(home, Cofres.HOME_RANGE)) {
                    gold += Cofres.contents(inv).getOrDefault("minecraft:raw_gold", 0);
                    chestAt = pos;
                }
            }
            boolean surface = world.isSkyVisible(miner.getBlockPos().up());
            boolean passed = gold == 20 && surface;
            check(name, passed, "subió desde 11 bloques bajo tierra a la superficie: " + surface + ", cofre hecho y puesto en "
                    + (chestAt == null ? "ningún lado" : chestAt.toShortString()) + " con " + gold + " de oro crudo, en " + ticks + " ticks; " + state());
            if (chestAt != null) {
                world.setBlockState(chestAt, Blocks.AIR.getDefaultState(), 3);
                net.mca.server.world.data.TropelesMundo.get(world).removed(chestAt);
            }
            return passed;
        }

        @Override
        String state() {
            var inv = miner.getInventory();
            return "en " + miner.getBlockPos().toShortString() + ", haciendo: " + Retina.memory(miner).doing() + ", comida " + task.food()
                    + ", oro " + inv.count(Items.RAW_GOLD) + ", tablas " + inv.count(Items.OAK_PLANKS) + ", cofres " + inv.count(Items.CHEST)
                    + ", mesas " + inv.count(Items.CRAFTING_TABLE);
        }

        @Override
        void cleanup() {
            miner.discard();
        }
    }
}
