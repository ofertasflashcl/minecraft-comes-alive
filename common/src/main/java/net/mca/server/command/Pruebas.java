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
import net.mca.entity.ai.sense.Entorno;
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
                case "robo" -> start(new Robo(world));
                case "regalo" -> regalo(world);
                case "arco-cerca" -> start(new ArcoCerca(world));
                case "horno" -> start(new Horno(world));
                case "hogar" -> start(new Hogar(world));
                case "cultivos" -> start(new Cultivos(world));
                case "agua-tunel" -> start(new AguaTunel(world));
                case "agua-pozo" -> start(new AguaPozo(world));
                case "explorar" -> start(new Exploracion(world));
                case "pelea-muro" -> start(new PeleaMuro(world));
                case "ia-local" -> start(new IaLocal(world));
                case "ia-red" -> start(new IaRed(world));
                case "conectar" -> start(new Conectar(world));
                case "tala" -> start(new Tala(world));
                case "puente" -> start(new Puente(world));
                case "vista-lejos" -> start(new VistaLejos(world));
                case "arbol-alto" -> start(new ArbolAlto(world));
                case "escalera" -> start(new Escalera(world));
                case "techo" -> start(new Techo(world));
                case "subida-lado" -> start(new SubidaLado(world));
                case "atasco-7s" -> start(new AtascoSiete(world));
                case "cofre-campo" -> start(new CofreCampo(world));
                case "cofre-basura" -> start(new CofreBasura(world));
                case "hojas" -> start(new Hojas(world));
                case "resumen" -> resumen();
                default -> {
                    ctx.getSource().sendError(Text.literal("ojo | minar | espada | arco | progreso | nombre | obras | robo | regalo | arco-cerca | horno | hogar | cultivos | agua-tunel | agua-pozo | explorar | ia-local | ia-red | resumen"));
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
    /**
     * A miner with a sword and a pickaxe sees a zombie through a hole it cannot walk through: three blocks of rock
     * in between, out of sword reach. It must dig through to it and kill it, not stand there staring.
     */
    private static final class PeleaMuro extends Scenario {
        private final ProspectingTask task = new ProspectingTask();
        private final VillagerEntityMCA miner;
        private final ZombieEntity zombie;
        private boolean dug;

        PeleaMuro(ServerWorld world) {
            super("pelea-muro", world, 1500);
            BlockPos o = new BlockPos(4000, 100, 0);
            load(world, o, 24);
            fill(world, o.add(-5, -2, -4), o.add(9, 4, 4), Blocks.STONE.getDefaultState());
            fill(world, o.add(-3, 0, -1), o.add(2, 2, 1), Blocks.AIR.getDefaultState());
            // the hole it sees through, one block high: nothing two blocks tall passes it
            fill(world, o.add(3, 1, 0), o.add(5, 1, 0), Blocks.AIR.getDefaultState());
            fill(world, o.add(6, 0, 0), o.add(6, 1, 0), Blocks.AIR.getDefaultState());
            miner = villager(world, o, Direction.EAST);
            miner.getInventory().addStack(new ItemStack(Items.IRON_SWORD));
            miner.getInventory().addStack(new ItemStack(Items.STONE_PICKAXE));
            miner.getInventory().addStack(new ItemStack(Items.COBBLESTONE, 16));
            miner.getInventory().addStack(new ItemStack(Items.BREAD, 8));
            zombie = zombie(world, Vec3d.ofBottomCenter(o.east(6)), true);
            zombie.setTarget(miner);
            face(miner, -90.0F, 0.0F);
            Retina.lookAll(world, miner);
        }

        @Override
        Boolean step() {
            if (!miner.isAlive()) {
                fail(name, "el aldeano murió; " + state());
                return Boolean.FALSE;
            }
            task.practice(world, miner);
            dug |= world.getBlockState(new BlockPos(4004, 100, 0)).isAir() || world.getBlockState(new BlockPos(4003, 100, 0)).isAir();
            if (zombie.isAlive()) {
                return null;
            }
            check(name, dug, "zombi tras la roca muerto en " + ticks + " ticks, cavó hacia él: " + dug + "; " + state());
            return dug;
        }

        @Override
        String state() {
            return "zombi " + zombie.getHealth() + " vida en " + zombie.getBlockPos().toShortString() + ", aldeano " + miner.getHealth()
                    + " en " + miner.getBlockPos().toShortString() + " con " + miner.getMainHandStack().getItem() + ", tarea " + task.debug()
                    + ", haciendo: " + Retina.memory(miner).doing();
        }

        @Override
        void cleanup() {
            miner.discard();
            zombie.discard();
        }
    }

    /**
     * A connected villager living its own life in a dark stone room. "Sígueme": it comes to the player's side.
     * "¡Cuidado!" with a zombie behind glass: within a few ticks it stops dead, turns to the player, crouches (in the
     * rock) and asks what is going on. "Tranquilo, sigue": it stands up and follows again. Every step must hold.
     */
    private static final class Conectar extends Scenario {
        private final ProspectingTask task = new ProspectingTask();
        private final VillagerEntityMCA v;
        private final net.minecraft.server.network.ServerPlayerEntity p;
        private final BlockPos o;
        @Nullable
        private ZombieEntity zombie;
        private int stage;
        private int mark;
        private double before;
        private String said = "";
        private String alerted = "";

        Conectar(ServerWorld world) {
            super("conectar", world, 1100);
            o = new BlockPos(4200, 100, 0);
            load(world, o, 24);
            fill(world, o.add(-5, -3, -6), o.add(20, 5, 6), Blocks.STONE.getDefaultState());
            fill(world, o.add(-3, 0, -3), o.add(16, 2, 3), Blocks.AIR.getDefaultState());
            // the zombie's cell, behind glass: it can be seen, it cannot reach anyone
            fill(world, o.add(12, 0, -3), o.add(12, 2, 3), Blocks.GLASS.getDefaultState());
            v = villager(world, o, Direction.EAST, "Lía Conecta");
            v.getBrain().forget(MemoryModuleTypeMCA.STAYING.get());
            v.getInventory().addStack(new ItemStack(Items.STONE_PICKAXE));
            v.getInventory().addStack(new ItemStack(Items.BREAD, 8));
            v.getInventory().addStack(new ItemStack(Items.COBBLESTONE, 16));
            p = fakePlayer(world, "Prueba_Conecta", Vec3d.ofBottomCenter(o.east(8)));
            face(v, -90.0F, 0.0F);
        }

        private String say(String msg) {
            net.mca.entity.ai.chatAI.Cerebros.OFFLINE.set(true);
            try {
                String answer = new net.mca.entity.ai.chatAI.OpenAIChatAI().answer(p, v, msg).orElse("");
                MCA.LOGGER.info("PRUEBA conectar t=" + ticks + ": «" + msg + "» -> «" + answer + "», " + state());
                return answer;
            } finally {
                net.mca.entity.ai.chatAI.Cerebros.OFFLINE.set(false);
            }
        }

        private net.mca.entity.ai.mente.Voluntad.Modo mode() {
            return net.mca.entity.ai.mente.Voluntad.of(v).modo();
        }

        /** Crouched for a reason ({@link net.mca.entity.ai.body.Postura}); false while that is still a stub. */
        private boolean crouched() {
            try {
                return net.mca.entity.ai.body.Postura.agachado(v);
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        Boolean step() {
            if (!v.isAlive()) {
                fail(name, "el aldeano murió; " + state());
                return Boolean.FALSE;
            }
            if (ticks < 20) {
                return null;
            }
            // its own life, run as the brain would: the will first, then the task
            task.practice(world, v);
            switch (stage) {
                case 0 -> {
                    if (ticks < 80) {
                        return null;
                    }
                    boolean own = mode() == net.mca.entity.ai.mente.Voluntad.Modo.LIBRE && net.mca.entity.ai.mente.Voluntad.conectado(v);
                    before = v.distanceTo(p);
                    said = say("Lía, sígueme");
                    boolean follows = mode() == net.mca.entity.ai.mente.Voluntad.Modo.SIGUIENDO;
                    if (!own || !follows || said.isBlank()) {
                        fail(name, "conectada y a lo suyo: " + own + ", con «sígueme» pasa a seguir: " + follows + " («" + said + "»); " + state());
                        return Boolean.FALSE;
                    }
                    stage = 1;
                    mark = ticks;
                }
                case 1 -> {
                    double d = v.distanceTo(p);
                    if (d < 4.0 && d < before - 1.0) {
                        stage = 2;
                    } else if (ticks - mark > 400) {
                        fail(name, "dijo «" + said + "» pero no se acercó: de " + fmt(before) + " a " + fmt(d) + " bloques; " + state());
                        return Boolean.FALSE;
                    }
                }
                case 2 -> {
                    zombie = zombie(world, Vec3d.ofBottomCenter(o.add(14, 0, 0)), false);
                    alerted = say("¡cuidado!");
                    mark = ticks;
                    stage = 3;
                }
                case 3 -> {
                    if (ticks - mark < 5) {
                        return null;
                    }
                    Vec3d eye = p.getEyePos();
                    float want = (float) (MathHelper.atan2(eye.z - v.getZ(), eye.x - v.getX()) * (180.0 / Math.PI)) - 90.0F;
                    float head = Math.abs(MathHelper.wrapDegrees(v.getHeadYaw() - want));
                    float body = Math.abs(MathHelper.wrapDegrees(v.getBodyYaw() - want));
                    boolean alert = mode() == net.mca.entity.ai.mente.Voluntad.Modo.ALERTA;
                    boolean still = v.getNavigation().isIdle() && v.getVelocity().horizontalLength() < 0.08;
                    boolean asks = alerted.contains("?");
                    boolean low = crouched();
                    if (!(alert && still && head <= 30 && body <= 30 && asks && low)) {
                        fail(name, "con «¡cuidado!»: alerta " + alert + ", quieta " + still + ", cabeza a " + fmt(head) + "° y cuerpo a " + fmt(body)
                                + "° del jugador, agachada " + low + ", pregunta «" + alerted + "» " + asks + "; " + state());
                        return Boolean.FALSE;
                    }
                    MCA.LOGGER.info("PRUEBA conectar: alerta a los " + (ticks - mark) + " ticks, cabeza a " + fmt(head) + "°, cuerpo a " + fmt(body)
                            + "°, agachada, «" + alerted + "»");
                    zombie.discard();
                    zombie = null;
                    // the player has stepped back meanwhile: following again means coming over
                    p.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0, 0);
                    before = v.distanceTo(p);
                    said = say("tranquilo, sigue");
                    mark = ticks;
                    stage = 4;
                }
                case 4 -> {
                    if (ticks - mark == 3) {
                        boolean follows = mode() == net.mca.entity.ai.mente.Voluntad.Modo.SIGUIENDO;
                        boolean up = !crouched();
                        if (!follows || !up) {
                            fail(name, "con «tranquilo, sigue» («" + said + "»): vuelve a seguir " + follows + ", de pie " + up + "; " + state());
                            return Boolean.FALSE;
                        }
                    }
                    double d = v.distanceTo(p);
                    if (ticks - mark > 3 && d < 4.0 && d < before - 1.0) {
                        ok(name, "a lo suyo -> «sígueme» la trajo a mi lado -> «¡cuidado!» se detuvo, me miró, se agachó y preguntó «" + alerted
                                + "» -> «tranquilo, sigue» («" + said + "») se paró y me siguió de nuevo (" + fmt(before) + " -> " + fmt(d) + " bloques)");
                        return Boolean.TRUE;
                    }
                    if (ticks - mark > 400) {
                        fail(name, "volvió a seguir pero no se acercó: de " + fmt(before) + " a " + fmt(d) + " bloques; " + state());
                        return Boolean.FALSE;
                    }
                }
                default -> {
                }
            }
            return null;
        }

        @Override
        String state() {
            return "etapa " + stage + ", en " + v.getBlockPos().toShortString() + ", " + net.mca.entity.ai.mente.Voluntad.resumen(v)
                    + ", tarea " + task.debug() + ", haciendo: " + Retina.memory(v).doing();
        }

        @Override
        void cleanup() {
            v.discard();
            if (zombie != null) {
                zombie.discard();
            }
            net.mca.entity.ai.chatAI.OpenAIChatAI.forget(v.getUuid());
        }
    }

    /**
     * Felling like a player: a seven-log oak with a real crown (leaves from the fourth log up) on open grass and an
     * empty-handed villager. One whole tree, the bottom log kept to stand on and cut last, every log picked up, and
     * nothing else of the place touched but, at most, one block of its own it stood on and took back.
     */
    private static final class Tala extends Scenario {
        private final net.mca.entity.ai.body.Progreso progreso = new net.mca.entity.ai.body.Progreso();
        private final VillagerEntityMCA v;
        private final BlockPos o;
        private final BlockPos base;
        private final BlockPos low;
        private final BlockPos high;
        private final List<BlockPos> trunk = new ArrayList<>();
        private final java.util.Set<BlockPos> treeCells = new java.util.HashSet<>();
        private final Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        /** Cells that held, at some tick, a block that was neither there before nor of the tree. */
        private final java.util.Set<BlockPos> foreign = new java.util.HashSet<>();
        private final java.util.Set<BlockPos> cut = new java.util.HashSet<>();
        private final List<String> order = new ArrayList<>();
        private int lastLogAt = -1;
        private boolean onBottomLog;

        Tala(ServerWorld world) {
            super("tala", world, 2600);
            o = new BlockPos(4600, 100, 0);
            load(world, o, 16);
            low = o.add(-8, -1, -8);
            high = o.add(8, 12, 8);
            fill(world, o.add(-8, -4, -8), o.add(8, -2, 8), Blocks.STONE.getDefaultState());
            fill(world, o.add(-8, -1, -8), o.add(8, -1, 8), Blocks.GRASS_BLOCK.getDefaultState());
            fill(world, o.add(-8, 0, -8), high, Blocks.AIR.getDefaultState());
            base = o.add(3, 0, 0);
            // under a trunk the ground is dirt
            world.setBlockState(base.down(), Blocks.DIRT.getDefaultState(), 2);
            BlockState leaves = Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.DISTANCE, 1);
            // an oak's crown: two wide layers from the fourth log, two narrow ones, a cap over the top log
            for (int dy = 3; dy <= 7; dy++) {
                int r = dy <= 4 ? 2 : 1;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
                        if (r == 2 && corner || dy == 7 && corner) {
                            continue;
                        }
                        BlockPos leaf = base.add(dx, dy, dz);
                        world.setBlockState(leaf, leaves, 2);
                        treeCells.add(leaf);
                    }
                }
            }
            for (int dy = 0; dy < 7; dy++) {
                BlockPos log = base.up(dy);
                world.setBlockState(log, Blocks.OAK_LOG.getDefaultState(), 2);
                trunk.add(log);
                treeCells.add(log);
            }
            for (BlockPos pos : BlockPos.iterate(low, high)) {
                before.put(pos.toImmutable(), world.getBlockState(pos));
            }
            v = villager(world, o, Direction.EAST);
            // free to walk to the tree
            v.getBrain().forget(MemoryModuleTypeMCA.STAYING.get());
            face(v, -90.0F, 0.0F);
            Retina.lookAll(world, v);
        }

        private static boolean ground(BlockState state) {
            return state.isOf(Blocks.GRASS_BLOCK) || state.isOf(Blocks.DIRT);
        }

        /** Every cell as it should be: the tree's own cells empty or still leaves, everything else as it was. */
        private void watch() {
            for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
                BlockPos pos = e.getKey();
                BlockState now = world.getBlockState(pos);
                BlockState was = e.getValue();
                if (now == was || ground(now) && ground(was)) {
                    continue;
                }
                if (treeCells.contains(pos) && (now.isAir() || now.isIn(net.minecraft.registry.tag.BlockTags.LEAVES) || now.isOf(Blocks.OAK_LOG))) {
                    continue;
                }
                foreign.add(pos);
            }
            for (BlockPos log : trunk) {
                if (!cut.contains(log) && !world.getBlockState(log).isOf(Blocks.OAK_LOG)) {
                    cut.add(log);
                    order.add((log.getY() - base.getY() + 1) + "@" + ticks);
                    lastLogAt = log.getY() - base.getY();
                }
            }
            onBottomLog |= v.getBlockPos().equals(base.up()) && world.getBlockState(base).isOf(Blocks.OAK_LOG) && v.isOnGround();
        }

        @Override
        Boolean step() {
            if (!v.isAlive()) {
                fail(name, "el aldeano murió; " + state());
                return Boolean.FALSE;
            }
            progreso.tick(world, v, (key, args) -> {
            });
            watch();
            if (ticks % 200 == 0) {
                MCA.LOGGER.info("PRUEBA tala t=" + ticks + ": " + state());
            }
            if (cut.size() < trunk.size()) {
                return null;
            }
            boolean logsLeft = !world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(base).expand(10),
                    e -> e.isAlive() && e.getStack().isIn(net.minecraft.registry.tag.ItemTags.LOGS)).isEmpty();
            if (logsLeft) {
                return null; // what fell is picked up before it goes anywhere
            }
            boolean bottomLast = lastLogAt == 0;
            boolean restored = true;
            for (BlockPos pos : foreign) {
                BlockState now = world.getBlockState(pos);
                BlockState was = before.get(pos);
                restored &= now == was || ground(now) && ground(was) || treeCells.contains(pos) && now.isAir();
            }
            int logs = v.getInventory().count(Items.OAK_LOG);
            boolean passed = bottomLast && foreign.size() <= 1 && restored && logs >= trunk.size();
            check(name, passed, "tronco talado en " + ticks + " ticks, orden (tronco@tick) " + order + ", el de abajo el último: " + bottomLast
                    + ", se subió al primer tronco: " + onBottomLog + ", bloques ajenos " + foreign.size() + " " + foreign + " devueltos: " + restored
                    + ", troncos en la mochila " + logs + "/" + trunk.size() + "; " + state());
            return passed;
        }

        @Override
        String state() {
            return "en " + v.getBlockPos().toShortString() + ", haciendo: " + Retina.memory(v).doing() + ", cortados " + order
                    + ", troncos " + v.getInventory().count(Items.OAK_LOG) + ", agachado " + v.isSneaking() + ", " + progreso.debugTree();
        }

        @Override
        void cleanup() {
            v.discard();
        }
    }

    /**
     * Bridging like a player: a goal across a six-wide, ten-deep gap that runs wall to wall (barrier), so there is no
     * way round. The motor alone must bridge it crouched, never fall, never get hurt.
     */
    private static final class Puente extends Scenario {
        private final net.mca.entity.ai.body.Motor motor = new net.mca.entity.ai.body.Motor();
        private final VillagerEntityMCA v;
        private final BlockPos o;
        private final BlockPos goal;
        private final float health;
        private final java.util.Set<BlockPos> placed = new java.util.HashSet<>();
        private int placedStanding;
        private int blocked;
        private float lowest;

        Puente(ServerWorld world) {
            super("puente", world, 1400);
            o = new BlockPos(4800, 100, 0);
            load(world, o, 24);
            fill(world, o.add(-4, -12, -3), o.add(15, 6, 3), Blocks.BARRIER.getDefaultState());
            fill(world, o.add(-3, -12, -2), o.add(14, -1, 2), Blocks.STONE.getDefaultState());
            fill(world, o.add(-3, 0, -2), o.add(14, 6, 2), Blocks.AIR.getDefaultState());
            // the gap: six wide, ten deep, from wall to wall
            fill(world, o.add(3, -10, -2), o.add(8, -1, 2), Blocks.AIR.getDefaultState());
            for (int x = -2; x <= 13; x += 3) {
                world.setBlockState(o.add(x, 5, 0), Blocks.GLOWSTONE.getDefaultState(), 2);
            }
            goal = o.add(11, 0, 0);
            v = villager(world, o, Direction.EAST);
            v.getInventory().addStack(new ItemStack(Items.COBBLESTONE, 32));
            face(v, -90.0F, 0.0F);
            Retina.lookAll(world, v);
            health = v.getHealth();
            lowest = health;
        }

        @Override
        Boolean step() {
            if (!v.isAlive()) {
                fail(name, "el aldeano murió; " + state());
                return Boolean.FALSE;
            }
            lowest = Math.min(lowest, v.getHealth());
            if (v.getY() < o.getY() - 1.5) {
                fail(name, "se cayó al hueco; " + state());
                return Boolean.FALSE;
            }
            net.mca.entity.ai.body.Motor.Result r = motor.step(world, v, goal, false, true);
            for (int x = 3; x <= 8; x++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos cell = o.add(x, -1, z);
                    if (!world.getBlockState(cell).isAir() && placed.add(cell) && !v.isSneaking()) {
                        placedStanding++;
                    }
                }
            }
            if (r == net.mca.entity.ai.body.Motor.Result.BLOCKED && ++blocked > 30) {
                fail(name, "el motor se rindió " + blocked + " veces; " + state());
                return Boolean.FALSE;
            }
            if (r != net.mca.entity.ai.body.Motor.Result.ARRIVED) {
                return null;
            }
            boolean unhurt = lowest >= health;
            boolean passed = unhurt && placed.size() >= 6 && placedStanding == 0;
            check(name, passed, "cruzó el hueco en " + ticks + " ticks, bloques del puente " + placed.size() + ", puestos sin agacharse "
                    + placedStanding + ", vida " + lowest + "/" + health + ", veces bloqueado " + blocked + "; " + state());
            return passed;
        }

        @Override
        String state() {
            return "en " + String.format(Locale.ROOT, "%.2f %.2f %.2f", v.getX(), v.getY(), v.getZ()) + ", haciendo: " + motor.doing()
                    + ", agachado " + v.isSneaking() + ", piedra " + v.getInventory().count(Items.COBBLESTONE);
        }

        @Override
        void cleanup() {
            v.discard();
        }
    }

    /**
     * The screen sees as far as a player with 12 chunks: a villager on an open platform, one oak 110 blocks to the
     * west on its own island, nothing in between. Its {@link net.mca.entity.ai.sense.Pantalla} frames must put a tree
     * on its map within 6 blocks of the oak and its scene must name a tree about 110 blocks off. A second villager in
     * a closed, 1-wide stone tunnel must see no farther than 48. A third under a planks roof with the open sky two
     * steps to the side must feel the planks over its head and never think itself under the ground.
     */
    private static final class VistaLejos extends Scenario {
        private final VillagerEntityMCA v;
        private final VillagerEntityMCA miner;
        private final VillagerEntityMCA sheltered;
        private final BlockPos o;
        private final BlockPos tree;
        private final BlockPos tunnel;
        private final BlockPos porch;
        private final List<BlockPos> built = new ArrayList<>();

        VistaLejos(ServerWorld world) {
            super("vista-lejos", world, 1800);
            o = new BlockPos(5310, 100, 0);
            tree = new BlockPos(5200, 100, 0);
            tunnel = new BlockPos(5350, 100, 40);
            porch = new BlockPos(5330, 100, -24);
            // the whole line of sight loaded, and nothing else in the way
            for (int x = tree.getX() - 16; x <= o.getX() + 16; x += 16) {
                load(world, new BlockPos(x, 100, 0), 4);
            }
            load(world, tunnel.east(8), 16);
            load(world, porch, 8);
            fill(world, tree.add(-8, -1, -8), o.add(8, 12, 8), Blocks.AIR.getDefaultState());
            // the platform it stands on, and the oak's island
            fill(world, o.add(-4, -2, -4), o.add(4, -2, 4), Blocks.STONE.getDefaultState());
            fill(world, o.add(-4, -1, -4), o.add(4, -1, 4), Blocks.GRASS_BLOCK.getDefaultState());
            fill(world, tree.add(-3, -2, -3), tree.add(3, -2, 3), Blocks.STONE.getDefaultState());
            fill(world, tree.add(-3, -1, -3), tree.add(3, -1, 3), Blocks.GRASS_BLOCK.getDefaultState());
            Pruebas.tree(world, tree, 5);
            // a closed stone tunnel, one wide, two high, sixteen long
            fill(world, tunnel.add(-2, -2, -2), tunnel.add(18, 3, 2), Blocks.STONE.getDefaultState());
            fill(world, tunnel, tunnel.add(15, 1, 0), Blocks.AIR.getDefaultState());
            // a planks porch roof over the head, open on every side
            fill(world, porch.add(-3, -2, -3), porch.add(3, -2, 3), Blocks.STONE.getDefaultState());
            fill(world, porch.add(-3, -1, -3), porch.add(3, -1, 3), Blocks.GRASS_BLOCK.getDefaultState());
            fill(world, porch.add(-3, 0, -3), porch.add(3, 4, 3), Blocks.AIR.getDefaultState());
            for (BlockPos pos : BlockPos.iterate(porch.add(-1, 3, -1), porch.add(1, 3, 1))) {
                world.setBlockState(pos, Blocks.OAK_PLANKS.getDefaultState(), 2);
                Obras.placedByPlayer(world, pos);
                built.add(pos.toImmutable());
            }
            v = villager(world, o, Direction.WEST, "Vista Lejos");
            v.setAiDisabled(true);
            face(v, 90.0F, 0.0F);
            miner = villager(world, tunnel, Direction.EAST, "Vista Tunel");
            miner.setAiDisabled(true);
            face(miner, -90.0F, 0.0F);
            sheltered = villager(world, porch, Direction.NORTH, "Vista Alero");
            sheltered.setAiDisabled(true);
        }

        @Override
        Boolean step() {
            net.mca.entity.ai.sense.Pantalla far = net.mca.entity.ai.sense.Pantalla.of(v);
            net.mca.entity.ai.sense.Pantalla near = net.mca.entity.ai.sense.Pantalla.of(miner);
            far.step(world, v);
            near.step(world, miner);
            if (ticks % 100 == 0) {
                MCA.LOGGER.info("PRUEBA vista-lejos t=" + ticks + ": " + state());
            }
            if (far.frames() < 3 || near.frames() < 3) {
                return null;
            }
            List<BlockPos> seen = Retina.memory(v).mapa().trees(tree, 6);
            net.mca.entity.ai.sense.Escena.Cosa named = null;
            for (net.mca.entity.ai.sense.Escena.Cosa c : net.mca.entity.ai.sense.Escena.of(world, v, 1200).cosas()) {
                if (net.mca.entity.ai.sense.Escena.ARBOL.equals(c.tipo()) && (named == null || Math.abs(c.distancia() - 110) < Math.abs(named.distancia() - 110))) {
                    named = c;
                }
            }
            boolean onMap = !seen.isEmpty();
            boolean inScene = named != null && Math.abs(named.distancia() - 110) <= 12;
            boolean wide = far.lastRange() > 48;
            boolean narrow = near.lastRange() <= 48 && near.range() <= 48;
            net.mca.entity.ai.sense.Tacto.Toque roof = net.mca.entity.ai.sense.Tacto.techo(world, sheltered);
            boolean felt = roof != null && roof.state().isOf(Blocks.OAK_PLANKS);
            boolean house = Entorno.lugar(world, sheltered) != net.mca.entity.ai.sense.Lugar.BAJO_TIERRA && !Entorno.bajoTierra(world, sheltered)
                    && Entorno.refugio(world, sheltered);
            boolean passed = onMap && inScene && wide && narrow && felt && house;
            if (!passed && far.frames() < 8) {
                return null; // a few more frames: a thin trunk far off can take some to be caught
            }
            far.save(world, v);
            check(name, passed, "árbol en el mapa: " + (onMap ? seen.get(0).toShortString() : "no") + ", en la escena: "
                    + (named == null ? "no" : named.nombre() + " a " + fmt(named.distancia()) + " " + named.direccion())
                    + ", alcance afuera " + fmt(far.lastRange()) + ", alcance en el túnel " + fmt(near.lastRange()) + " (lo más lejano " + fmt(near.lastFarthest()) + ")"
                    + ", techo: " + (roof == null ? "nada" : roof.state().getBlock().getName().getString() + " a " + roof.distancia())
                    + ", bajo el alero: " + Entorno.lugar(world, sheltered).palabra() + (house ? "" : " (¡se cree bajo tierra!)")
                    + "; coste afuera: " + far.costLine() + "; coste en el túnel: " + near.costLine()
                    + "; ve: " + far.resumen(world, true) + "; toca: " + net.mca.entity.ai.sense.Tacto.describeOverhead(sheltered, true)
                    + "; imagen " + net.mca.entity.ai.sense.Pantalla.path(world, v));
            return passed;
        }

        @Override
        String state() {
            net.mca.entity.ai.sense.Pantalla far = net.mca.entity.ai.sense.Pantalla.get(v);
            net.mca.entity.ai.sense.Pantalla near = net.mca.entity.ai.sense.Pantalla.get(miner);
            return "afuera " + (far == null ? "sin pantalla" : far.frames() + " cuadros, alcance " + fmt(far.range()) + ", " + far.costLine())
                    + "; túnel " + (near == null ? "sin pantalla" : near.frames() + " cuadros, alcance " + fmt(near.range()))
                    + "; celdas en el mapa " + Retina.memory(v).mapa().size();
        }

        @Override
        void cleanup() {
            v.discard();
            miner.discard();
            sheltered.discard();
            fill(world, tree.add(-3, -1, -3), tree.add(3, 6, 3), Blocks.AIR.getDefaultState());
            for (BlockPos pos : built) {
                Obras.broken(world, pos);
            }
        }
    }

    /**
     * A tall spruce the way a world grows them: a 1x1 trunk sixteen logs high inside a spruce's crown, on open grass, and
     * a villager with nothing but sixteen dirt. It must fell all of it like a player: the bottom log kept and cut last,
     * up onto it and up blocks of its own for the top, every one of those blocks taken back, every log in its bag, and
     * never standing there doing nothing (feet, bag and trunk all still) for more than fifteen seconds.
     */
    private static final class ArbolAlto extends Scenario {
        private static final int HEIGHT = 16;
        private static final int MAX_IDLE = 300;
        private final net.mca.entity.ai.body.Progreso progreso = new net.mca.entity.ai.body.Progreso();
        private final VillagerEntityMCA v;
        private final BlockPos o;
        private final BlockPos base;
        private final List<BlockPos> trunk = new ArrayList<>();
        private final java.util.Set<BlockPos> treeCells = new java.util.HashSet<>();
        private final Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        /** Cells that held, at some tick, a block that was neither there before nor of the tree. */
        private final java.util.Set<BlockPos> foreign = new java.util.HashSet<>();
        private final java.util.Set<BlockPos> cut = new java.util.HashSet<>();
        private final List<String> order = new ArrayList<>();
        private int lastLogAt = -1;
        private int highest;
        private Vec3d lastPos;
        private int lastBag = -1;
        private long lastColumn;
        private int idle;
        private int maxIdle;
        private int maxIdleAt;
        private String maxIdleState = "";

        ArbolAlto(ServerWorld world) {
            super("arbol-alto", world, 4000);
            o = new BlockPos(6000, 100, 0);
            load(world, o, 16);
            BlockPos low = o.add(-8, -1, -8);
            BlockPos high = o.add(8, HEIGHT + 4, 8);
            fill(world, o.add(-8, -4, -8), o.add(8, -2, 8), Blocks.STONE.getDefaultState());
            fill(world, o.add(-8, -1, -8), o.add(8, -1, 8), Blocks.GRASS_BLOCK.getDefaultState());
            fill(world, o.add(-8, 0, -8), high, Blocks.AIR.getDefaultState());
            base = o.add(3, 0, 0);
            world.setBlockState(base.down(), Blocks.DIRT.getDefaultState(), 2);
            BlockState leaves = Blocks.SPRUCE_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.DISTANCE, 1);
            // a spruce's crown: from the fifth log up, wide and narrow rings by turns, narrowing to the top, a cap over it
            for (int dy = 4; dy <= HEIGHT + 1; dy++) {
                int r = dy >= HEIGHT ? 0 : dy >= HEIGHT - 4 ? 1 : (dy % 2 == 0 ? 2 : 1);
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (r == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2) {
                            continue;
                        }
                        if (dx == 0 && dz == 0 && dy < HEIGHT) {
                            continue; // the trunk
                        }
                        BlockPos leaf = base.add(dx, dy, dz);
                        world.setBlockState(leaf, leaves, 2);
                        treeCells.add(leaf);
                    }
                }
            }
            for (int dy = 0; dy < HEIGHT; dy++) {
                BlockPos log = base.up(dy);
                world.setBlockState(log, Blocks.SPRUCE_LOG.getDefaultState(), 2);
                trunk.add(log);
                treeCells.add(log);
            }
            for (BlockPos pos : BlockPos.iterate(low, high)) {
                before.put(pos.toImmutable(), world.getBlockState(pos));
            }
            v = villager(world, o, Direction.EAST);
            // free to walk to the tree
            v.getBrain().forget(MemoryModuleTypeMCA.STAYING.get());
            v.getInventory().addStack(new ItemStack(Items.DIRT, 16));
            face(v, -90.0F, 0.0F);
            Retina.lookAll(world, v);
            lastPos = v.getPos();
            highest = v.getBlockY();
        }

        private static boolean ground(BlockState state) {
            return state.isOf(Blocks.GRASS_BLOCK) || state.isOf(Blocks.DIRT);
        }

        private int bag() {
            var inv = v.getInventory();
            int n = 0;
            for (int i = 0; i < inv.size(); i++) {
                n += inv.getStack(i).getCount();
            }
            return n + v.getMainHandStack().getCount();
        }

        /** The trunk's column as it is now (logs, its blocks, air): a cut or a block set or taken back changes it. */
        private long column() {
            long h = 17;
            for (int dy = -1; dy <= HEIGHT + 1; dy++) {
                h = h * 31 + net.minecraft.block.Block.getRawIdFromState(world.getBlockState(base.up(dy)));
            }
            return h;
        }

        private void watch() {
            for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
                BlockPos pos = e.getKey();
                BlockState now = world.getBlockState(pos);
                BlockState was = e.getValue();
                if (now == was || ground(now) && ground(was)) {
                    continue;
                }
                if (treeCells.contains(pos) && (now.isAir() || now.isIn(net.minecraft.registry.tag.BlockTags.LEAVES) || now.isOf(Blocks.SPRUCE_LOG))) {
                    continue;
                }
                foreign.add(pos);
            }
            for (BlockPos log : trunk) {
                if (!cut.contains(log) && !world.getBlockState(log).isOf(Blocks.SPRUCE_LOG)) {
                    cut.add(log);
                    order.add((log.getY() - base.getY() + 1) + "@" + ticks);
                    lastLogAt = log.getY() - base.getY();
                }
            }
            highest = Math.max(highest, v.getBlockY());
            // standing there doing nothing: the feet, the bag and the trunk all as they were
            Vec3d pos = v.getPos();
            int bag = bag();
            long column = column();
            if (pos.squaredDistanceTo(lastPos) > 0.0025 || bag != lastBag || column != lastColumn) {
                lastPos = pos;
                lastBag = bag;
                lastColumn = column;
                idle = 0;
            } else if (++idle > maxIdle) {
                maxIdle = idle;
                maxIdleAt = ticks;
                if (idle == MAX_IDLE + 1) {
                    maxIdleState = state();
                    MCA.LOGGER.info("PRUEBA arbol-alto: lleva " + idle + " ticks quieto a los " + ticks + "; " + maxIdleState);
                }
            }
        }

        @Override
        Boolean step() {
            if (!v.isAlive()) {
                fail(name, "el aldeano murió; " + state());
                return Boolean.FALSE;
            }
            progreso.tick(world, v, (key, args) -> {
            });
            watch();
            if (ticks % 200 == 0) {
                MCA.LOGGER.info("PRUEBA arbol-alto t=" + ticks + ": " + state());
            }
            if (maxIdle > MAX_IDLE * 3) {
                fail(name, "se quedó parado " + maxIdle + " ticks sin hacer nada; " + state());
                return Boolean.FALSE;
            }
            if (cut.size() < trunk.size()) {
                return null;
            }
            boolean logsLeft = !world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(base).expand(10, HEIGHT + 4, 10),
                    e -> e.isAlive() && e.getStack().isIn(net.minecraft.registry.tag.ItemTags.LOGS)).isEmpty();
            if (logsLeft) {
                return null; // what fell is picked up before it goes anywhere
            }
            boolean bottomLast = lastLogAt == 0;
            boolean restored = true;
            List<BlockPos> left = new ArrayList<>();
            for (BlockPos pos : foreign) {
                BlockState now = world.getBlockState(pos);
                BlockState was = before.get(pos);
                boolean back = now == was || ground(now) && ground(was) || treeCells.contains(pos) && now.isAir();
                if (!back) {
                    left.add(pos);
                }
                restored &= back;
            }
            int logs = v.getInventory().count(Items.SPRUCE_LOG);
            int dirt = v.getInventory().count(Items.DIRT);
            boolean passed = bottomLast && restored && logs >= trunk.size() && maxIdle <= MAX_IDLE;
            check(name, passed, "pino de " + HEIGHT + " troncos talado en " + ticks + " ticks, orden (tronco@tick) " + order + ", el de abajo el último: " + bottomLast
                    + ", subió hasta " + (highest - base.getY()) + " sobre el tocón, bloques suyos puestos " + foreign.size() + " y dejados " + left
                    + ", tierra en la mochila " + dirt + "/16, troncos en la mochila " + logs + "/" + trunk.size()
                    + ", máximo quieto " + maxIdle + " ticks (a los " + maxIdleAt + ")" + (maxIdleState.isEmpty() ? "" : " " + maxIdleState) + "; " + state());
            return passed;
        }

        @Override
        String state() {
            return "en " + v.getBlockPos().toShortString() + ", haciendo: " + Retina.memory(v).doing() + ", cortados " + order
                    + ", troncos " + v.getInventory().count(Items.SPRUCE_LOG) + ", tierra " + v.getInventory().count(Items.DIRT) + ", " + progreso.debugTree();
        }

        @Override
        void cleanup() {
            v.discard();
        }
    }

    // ------------------------------------------------------------------ memory: the way it came, and the roof of a hut

    /**
     * The walk a miner took earlier, laid into its memories ({@link net.mca.entity.ai.mente.Recuerdos}) one cell a
     * tick the way its feet would: teleported to the cell, its sense of place judged there the next tick.
     */
    private static final class Paseo {
        private final List<BlockPos> cells;
        private int at;

        Paseo(List<BlockPos> cells) {
            this.cells = cells;
        }

        /** One tick of the walk; true once every cell is remembered. */
        boolean tick(ServerWorld world, VillagerEntityMCA v, int ticks) {
            if (at >= cells.size()) {
                return true;
            }
            BlockPos cell = cells.get(at);
            if ((ticks & 1) == 0) {
                v.refreshPositionAndAngles(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, v.getYaw(), 0);
                v.setVelocity(Vec3d.ZERO);
                return false;
            }
            net.mca.entity.ai.mente.Recuerdos.of(world, v).pisado(world, v, cell);
            at++;
            return at >= cells.size();
        }
    }

    /** Which cells of a box are air: a snapshot to count what was broken or placed since. */
    private static boolean[] airIn(ServerWorld world, BlockPos a, BlockPos b) {
        List<Boolean> list = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(a, b)) {
            list.add(world.getBlockState(pos).isAir());
        }
        boolean[] air = new boolean[list.size()];
        for (int i = 0; i < air.length; i++) {
            air[i] = list.get(i);
        }
        return air;
    }

    /** {broken, placed} in the box since {@code before}. */
    private static int[] changedIn(ServerWorld world, BlockPos a, BlockPos b, boolean[] before) {
        int broken = 0;
        int placed = 0;
        int i = 0;
        for (BlockPos pos : BlockPos.iterate(a, b)) {
            boolean air = world.getBlockState(pos).isAir();
            if (air && !before[i]) {
                broken++;
            } else if (!air && before[i]) {
                placed++;
            }
            i++;
        }
        return new int[]{broken, placed};
    }

    /**
     * "Aunque tenga la escalera al lado prefiere minar y hacer otra": a miner deep in a mine, at the foot of the
     * staircase it walked down a moment ago (its memories hold every step), is called home. It must walk up that
     * staircase, breaking nothing, not cut another one.
     */
    private static final class Escalera extends Scenario {
        private final ProspectingTask task = new ProspectingTask();
        private final VillagerEntityMCA miner;
        private final BlockPos o;
        private final Paseo paseo;
        private final BlockPos boxA;
        private final BlockPos boxB;
        @Nullable
        private boolean[] before;
        private boolean called;
        private boolean memoryUsed;
        private int climbStart;

        Escalera(ServerWorld world) {
            super("escalera", world, 1800);
            o = new BlockPos(5450, 100, 0);
            load(world, o, 32);
            fill(world, o.add(-16, -16, -8), o.add(18, -2, 8), Blocks.STONE.getDefaultState());
            fill(world, o.add(-16, -1, -8), o.add(18, -1, 8), Blocks.GRASS_BLOCK.getDefaultState());
            fill(world, o.add(-16, 0, -8), o.add(18, 8, 8), Blocks.AIR.getDefaultState());
            // a one-wide staircase down to the east, three blocks of headroom on every step, then a short tunnel
            List<BlockPos> walk = new ArrayList<>();
            for (int x = -4; x <= 0; x++) {
                walk.add(o.add(x, 0, 0));
            }
            for (int k = 1; k <= 11; k++) {
                fill(world, o.add(k, -k, 0), o.add(k, -k + 2, 0), Blocks.AIR.getDefaultState());
                walk.add(o.add(k, -k, 0));
            }
            fill(world, o.add(12, -11, 0), o.add(14, -10, 0), Blocks.AIR.getDefaultState());
            walk.add(o.add(12, -11, 0));
            walk.add(o.add(13, -11, 0));
            // light down there, so its eyes work as they would by its torches
            world.setBlockState(o.add(14, -9, 0), Blocks.GLOWSTONE.getDefaultState(), 2);
            paseo = new Paseo(walk);
            boxA = o.add(-16, -16, -8);
            boxB = o.add(18, 8, 8);
            miner = villager(world, o.add(-4, 0, 0), Direction.EAST, "Teo Escalera");
            miner.getBrain().forget(MemoryModuleTypeMCA.STAYING.get());
            miner.setAiDisabled(true);
            miner.getInventory().addStack(new ItemStack(Items.STONE_PICKAXE));
            miner.getInventory().addStack(new ItemStack(Items.COBBLESTONE, 32));
            miner.getInventory().addStack(new ItemStack(Items.BREAD, 6));
            miner.getInventory().addStack(new ItemStack(Items.RAW_IRON, 5));
            task.setHome(o.add(-10, 0, 0));
            task.setFood(20);
        }

        @Override
        Boolean step() {
            if (!miner.isAlive()) {
                fail(name, "el minero murió; " + state());
                return Boolean.FALSE;
            }
            if (!called) {
                if (!paseo.tick(world, miner, ticks)) {
                    return null;
                }
                // it walked down earlier: now it stands deep in its mine and is called home
                miner.setAiDisabled(false);
                face(miner, -90.0F, 0.0F);
                before = airIn(world, boxA, boxB);
                ProspectingTask.callHome(miner, false);
                called = true;
                climbStart = ticks;
                MCA.LOGGER.info("PRUEBA escalera: recuerda " + net.mca.entity.ai.mente.Recuerdos.of(world, miner).size()
                        + " pasos, lo llaman a casa desde " + miner.getBlockPos().toShortString());
                return null;
            }
            task.practice(world, miner);
            memoryUsed |= task.porMemoria();
            if ((ticks - climbStart) % 100 == 0) {
                MCA.LOGGER.info("PRUEBA escalera t=" + (ticks - climbStart) + ": " + task.debug() + ", " + state());
            }
            int[] changed = changedIn(world, boxA, boxB, before);
            if (changed[0] > 0) {
                fail(name, "rompió " + changed[0] + " bloques en vez de subir por la escalera que recordaba; " + state());
                return Boolean.FALSE;
            }
            boolean up = miner.getBlockY() >= o.getY() && world.isSkyVisible(miner.getBlockPos().up());
            String phase = task.fase();
            if (!up || phase.equals("HOME_UP")) {
                return null;
            }
            boolean passed = memoryUsed && changed[0] == 0;
            check(name, passed, "subió por la escalera que recordaba en " + (ticks - climbStart) + " ticks, por su memoria: " + memoryUsed
                    + ", bloques rotos " + changed[0] + ", puestos " + changed[1] + "; " + state());
            return passed;
        }

        @Override
        String state() {
            return "en " + miner.getBlockPos().toShortString() + ", fase " + task.fase() + ", haciendo: " + Retina.memory(miner).doing()
                    + ", tarea " + task.debug();
        }

        @Override
        void cleanup() {
            net.mca.entity.ai.mente.Recuerdos.olvidar(world, miner);
            miner.discard();
        }
    }

    /**
     * "Cree que sigue en la mina cuando está debajo de un techo que sobresale de alguna casa": a miner with a full bag
     * comes up its own staircase, whose top opens under the wide plank roof of a hut with open sides (three blocks
     * over the ground). It must know it is out within a few seconds, walk to its chest ten blocks away and store its
     * haul, never digging or pillaring under the roof.
     */
    private static final class Techo extends Scenario {
        private final ProspectingTask task = new ProspectingTask();
        private final VillagerEntityMCA miner;
        private final BlockPos o;
        private final BlockPos chest;
        private final Paseo paseo;
        private final BlockPos boxA;
        private final BlockPos boxB;
        private final BlockPos hutA;
        private final BlockPos hutB;
        @Nullable
        private boolean[] before;
        @Nullable
        private boolean[] hutBefore;
        private boolean started;
        private int startTick;
        private int topAt = -1;
        private int outAt = -1;

        Techo(ServerWorld world) {
            super("techo", world, 2400);
            o = new BlockPos(5650, 100, 0);
            load(world, o, 32);
            fill(world, o.add(-16, -14, -9), o.add(16, -2, 9), Blocks.STONE.getDefaultState());
            fill(world, o.add(-16, -1, -9), o.add(16, -1, 9), Blocks.GRASS_BLOCK.getDefaultState());
            fill(world, o.add(-16, 0, -9), o.add(16, 8, 9), Blocks.AIR.getDefaultState());
            // the hut: a wide plank roof on four posts, three blocks over the ground, open on every side
            fill(world, o.add(-5, 3, -5), o.add(5, 3, 5), Blocks.OAK_PLANKS.getDefaultState());
            for (int[] c : new int[][]{{-5, -5}, {-5, 5}, {5, -5}, {5, 5}}) {
                fill(world, o.add(c[0], 0, c[1]), o.add(c[0], 2, c[1]), Blocks.OAK_LOG.getDefaultState());
            }
            // its own staircase, down to the east from the middle of the hut, and the tunnel at its foot
            List<BlockPos> walk = new ArrayList<>();
            for (int x = -9; x <= 0; x++) {
                walk.add(o.add(x, 0, 0));
            }
            for (int k = 1; k <= 10; k++) {
                fill(world, o.add(k, -k, 0), o.add(k, -k + 2, 0), Blocks.AIR.getDefaultState());
                walk.add(o.add(k, -k, 0));
            }
            fill(world, o.add(11, -10, 0), o.add(13, -9, 0), Blocks.AIR.getDefaultState());
            walk.add(o.add(11, -10, 0));
            walk.add(o.add(12, -10, 0));
            world.setBlockState(o.add(13, -8, 0), Blocks.GLOWSTONE.getDefaultState(), 2);
            paseo = new Paseo(walk);
            // home: its chest ten blocks from the top of the staircase, out in the open
            chest = o.add(-10, 0, 1);
            world.setBlockState(chest, Blocks.CHEST.getDefaultState(), 3);
            boxA = o.add(-16, -14, -9);
            boxB = o.add(16, 8, 9);
            hutA = o.add(-6, 0, -6);
            hutB = o.add(6, 4, 6);
            miner = villager(world, o.add(-9, 0, 0), Direction.EAST, "Ana Techo");
            miner.getBrain().forget(MemoryModuleTypeMCA.STAYING.get());
            miner.setAiDisabled(true);
            Cofres.claim(world, miner, chest);
            miner.getInventory().addStack(new ItemStack(Items.STONE_PICKAXE));
            while (miner.getInventory().addStack(new ItemStack(Items.RAW_GOLD, 64)).isEmpty()) {
                // a bag full to the last slot
            }
            task.setHome(o.add(-10, 0, 0));
            task.setFood(20);
        }

        @Override
        Boolean step() {
            if (!miner.isAlive()) {
                fail(name, "el minero murió; " + state());
                return Boolean.FALSE;
            }
            if (!started) {
                if (!paseo.tick(world, miner, ticks)) {
                    return null;
                }
                miner.setAiDisabled(false);
                face(miner, -90.0F, 0.0F);
                before = airIn(world, boxA, boxB);
                hutBefore = airIn(world, hutA, hutB);
                started = true;
                startTick = ticks;
                MCA.LOGGER.info("PRUEBA techo: recuerda " + net.mca.entity.ai.mente.Recuerdos.of(world, miner).size() + " pasos, mochila llena, en "
                        + miner.getBlockPos().toShortString());
                return null;
            }
            task.practice(world, miner);
            int t = ticks - startTick;
            if (t % 100 == 0) {
                MCA.LOGGER.info("PRUEBA techo t=" + t + ": " + task.debug() + ", " + state());
            }
            if (topAt < 0 && miner.getBlockY() >= o.getY() - 1 && miner.getBlockX() <= o.getX() + 1) {
                topAt = t;
            }
            if (outAt < 0 && topAt >= 0 && !task.fase().equals("HOME_UP")) {
                outAt = t;
                MCA.LOGGER.info("PRUEBA techo: se dio cuenta de que estaba afuera " + (outAt - topAt) + " ticks después de llegar arriba, en "
                        + miner.getBlockPos().toShortString() + " (" + Entorno.lugar(world, miner).palabra() + ")");
            }
            int[] hut = changedIn(world, hutA, hutB, hutBefore);
            int[] all = changedIn(world, boxA, boxB, before);
            if (hut[0] + hut[1] > 0 || all[0] > 0) {
                fail(name, "bajo el techo rompió " + hut[0] + " y puso " + hut[1] + " bloques, en total rompió " + all[0] + "; " + state());
                return Boolean.FALSE;
            }
            int gold = 0;
            var inv = Cofres.inventory(world, chest);
            if (inv != null) {
                gold = Cofres.contents(inv).getOrDefault("minecraft:raw_gold", 0);
            }
            if (gold == 0) {
                if (topAt >= 0 && outAt < 0 && t - topAt > 400) {
                    fail(name, "llegó arriba bajo el techo y en " + (t - topAt) + " ticks no se dio cuenta de que estaba afuera; " + state());
                    return Boolean.FALSE;
                }
                return null;
            }
            boolean quick = outAt >= 0 && outAt - topAt <= 100;
            check(name, quick, "subió bajo el alero, supo que estaba afuera en " + (outAt - topAt) + " ticks, caminó a su cofre y guardó "
                    + gold + " de oro crudo en " + t + " ticks, sin romper ni poner nada bajo el techo; " + state());
            return quick;
        }

        @Override
        String state() {
            return "en " + miner.getBlockPos().toShortString() + ", fase " + task.fase() + ", haciendo: " + Retina.memory(miner).doing()
                    + ", oro en la mochila " + miner.getInventory().count(Items.RAW_GOLD) + ", tarea " + task.debug();
        }

        @Override
        void cleanup() {
            net.mca.entity.ai.mente.Recuerdos.olvidar(world, miner);
            miner.discard();
            world.setBlockState(chest, Blocks.AIR.getDefaultState(), 3);
            net.mca.server.world.data.TropelesMundo.get(world).removed(chest);
        }
    }

    /**
     * "Debe romper hojas de árbol para poder talar o subir, ya que se queda atascado a veces": an oak wrapped in its
     * leaves down to the ground (no free cell beside the trunk), seen from the end of a stone corridor that a hedge of
     * natural leaves closes. It must click the hedge away, clear its place by the trunk and fell every log; two
     * persistent (player's) leaves by the stump and the corridor's stone stay as they are.
     */
    private static final class Hojas extends Scenario {
        private static final int LOGS = 6;
        private final net.mca.entity.ai.body.Progreso progreso = new net.mca.entity.ai.body.Progreso();
        private final VillagerEntityMCA v;
        private final BlockPos base;
        private final List<BlockPos> trunk = new ArrayList<>();
        private final List<BlockPos> hedge = new ArrayList<>();
        private final List<BlockPos> players = new ArrayList<>();
        private final List<BlockPos> walls = new ArrayList<>();
        private final java.util.Set<BlockPos> cut = new java.util.HashSet<>();
        private final List<String> order = new ArrayList<>();
        private int hedgeGoneAt = -1;

        Hojas(ServerWorld world) {
            super("hojas", world, 3000);
            BlockPos o = new BlockPos(7010, 100, 0);
            load(world, o, 16);
            fill(world, o.add(-4, -4, -6), o.add(14, -2, 6), Blocks.STONE.getDefaultState());
            fill(world, o.add(-4, -1, -6), o.add(14, -1, 6), Blocks.GRASS_BLOCK.getDefaultState());
            fill(world, o.add(-4, 0, -6), o.add(14, 12, 6), Blocks.AIR.getDefaultState());
            // a corridor one wide, three high, closed behind: the only way out is east
            for (BlockPos pos : BlockPos.iterate(o.add(-1, 0, -1), o.add(4, 2, 1))) {
                if (pos.getZ() != o.getZ() || pos.getX() == o.getX() - 1) {
                    BlockPos wall = pos.toImmutable();
                    world.setBlockState(wall, Blocks.STONE.getDefaultState(), 2);
                    walls.add(wall);
                }
            }
            base = o.add(7, 0, 0);
            BlockState leaves = Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.DISTANCE, 1);
            for (int dy = 0; dy <= 6; dy++) {
                int r = dy >= 2 && dy <= 4 ? 2 : 1;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx == 0 && dz == 0 && dy < LOGS || r == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2) {
                            continue;
                        }
                        if (dy == 0 && (dx == -1 || dx != 0 && dz != 0)) {
                            continue; // at the ground only north, south and east of the stump: its west face shows
                        }
                        world.setBlockState(base.add(dx, dy, dz), leaves, 2);
                    }
                }
            }
            for (int dy = 0; dy < LOGS; dy++) {
                world.setBlockState(base.up(dy), Blocks.OAK_LOG.getDefaultState(), 2);
                trunk.add(base.up(dy));
            }
            v = villager(world, o, Direction.EAST);
            v.getBrain().forget(MemoryModuleTypeMCA.STAYING.get());
            v.getInventory().addStack(new ItemStack(Items.DIRT, 16));
            face(v, -90.0F, 0.0F);
            // it saw the stump down the corridor, then the hedge grew across it (the logs above hide in the leaves)
            Retina.lookAll(world, v);
            Retina.peek(world, v, base);
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos leaf = o.add(2, dy, 0);
                world.setBlockState(leaf, leaves, 2);
                hedge.add(leaf);
            }
            // a player's leaves by the stump, in reach of the hands at work (the far corners: two made blocks beside a natural
            // leaf would make that leaf part of a build): persistent, one of them also logged as placed
            BlockState persistent = Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.PERSISTENT, true);
            for (BlockPos leaf : List.of(base.add(1, 0, -1), base.add(1, 0, 1))) {
                world.setBlockState(leaf, persistent, 2);
                players.add(leaf);
            }
            Obras.placedByPlayer(world, players.get(0));
        }

        @Override
        Boolean step() {
            if (!v.isAlive()) {
                fail(name, "el aldeano murió; " + state());
                return Boolean.FALSE;
            }
            progreso.tick(world, v, (key, args) -> {
            });
            for (BlockPos log : trunk) {
                if (!cut.contains(log) && !world.getBlockState(log).isOf(Blocks.OAK_LOG)) {
                    cut.add(log);
                    order.add((log.getY() - base.getY() + 1) + "@" + ticks);
                }
            }
            if (hedgeGoneAt < 0 && hedge.stream().allMatch(p -> world.getBlockState(p).isAir())) {
                hedgeGoneAt = ticks;
            }
            for (BlockPos leaf : players) {
                BlockState now = world.getBlockState(leaf);
                if (!now.isOf(Blocks.OAK_LEAVES) || !now.get(net.minecraft.block.LeavesBlock.PERSISTENT)) {
                    fail(name, "rompió las hojas de un jugador en " + leaf.toShortString() + "; " + state());
                    return Boolean.FALSE;
                }
            }
            if (ticks % 200 == 0) {
                MCA.LOGGER.info("PRUEBA hojas t=" + ticks + ": " + state());
            }
            if (cut.size() < trunk.size()) {
                return null;
            }
            boolean logsLeft = !world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(base).expand(8, 10, 8),
                    e -> e.isAlive() && e.getStack().isIn(net.minecraft.registry.tag.ItemTags.LOGS)).isEmpty();
            if (logsLeft) {
                return null; // what fell is picked up first
            }
            long wallsLeft = walls.stream().filter(p -> world.getBlockState(p).isOf(Blocks.STONE)).count();
            boolean passed = hedgeGoneAt >= 0 && wallsLeft == walls.size();
            check(name, passed, "roble envuelto en hojas talado en " + ticks + " ticks, orden (tronco@tick) " + order
                    + ", seto de hojas quitado a los " + hedgeGoneAt + ", hojas de jugador intactas " + players.size() + "/" + players.size()
                    + ", piedra del pasillo " + wallsLeft + "/" + walls.size() + "; " + state());
            return passed;
        }

        @Override
        String state() {
            return "en " + v.getBlockPos().toShortString() + ", haciendo: " + Retina.memory(v).doing() + ", cortados " + order
                    + ", troncos " + v.getInventory().count(Items.OAK_LOG) + ", seto "
                    + hedge.stream().map(p -> p.getY() - 100 + ":" + world.getBlockState(p).getBlock().getName().getString()).toList()
                    + ", " + progreso.debugTree();
        }

        @Override
        void cleanup() {
            v.discard();
            for (BlockPos leaf : players) {
                world.setBlockState(leaf, Blocks.AIR.getDefaultState(), 2);
                net.mca.server.world.data.TropelesMundo.get(world).removed(leaf);
            }
        }
    }

    // ------------------------------------------------------------------ round 10: the way up beside it, seven seconds, chests anywhere

    /**
     * "Sigue sin recordar muy bien el camino por donde bajó aun teniendo una subida al lado": a miner that walked down
     * a staircase earlier fell down a shaft two blocks beside its foot and stands in a pocket at the bottom. The cells
     * between the shaft and the staircase it never stood on. Called home, it must find the staircase beside it and
     * walk up it, breaking nothing, not cut another one.
     */
    private static final class SubidaLado extends Scenario {
        private final ProspectingTask task = new ProspectingTask();
        private final VillagerEntityMCA miner;
        private final BlockPos o;
        private final Paseo paseo;
        private final BlockPos boxA;
        private final BlockPos boxB;
        @Nullable
        private boolean[] before;
        private boolean called;
        private boolean memoryUsed;
        private boolean onStair;
        private int climbStart;

        SubidaLado(ServerWorld world) {
            super("subida-lado", world, 1800);
            o = new BlockPos(6540, 100, 0);
            load(world, o, 32);
            fill(world, o.add(-12, -14, -8), o.add(16, -2, 8), Blocks.STONE.getDefaultState());
            fill(world, o.add(-12, -1, -8), o.add(16, -1, 8), Blocks.GRASS_BLOCK.getDefaultState());
            fill(world, o.add(-12, 0, -8), o.add(16, 8, 8), Blocks.AIR.getDefaultState());
            // the staircase it walked down earlier: one wide, to the east, three blocks of headroom on every step
            List<BlockPos> walk = new ArrayList<>();
            for (int x = -4; x <= 0; x++) {
                walk.add(o.add(x, 0, 0));
            }
            for (int k = 1; k <= 10; k++) {
                fill(world, o.add(k, -k, 0), o.add(k, -k + 2, 0), Blocks.AIR.getDefaultState());
                walk.add(o.add(k, -k, 0));
            }
            // a shaft open to the sky two blocks to the side of the staircase's foot, joined to it at the bottom by
            // two cells it never stood on, and the pocket at the bottom of the shaft where it ended up
            fill(world, o.add(10, -10, 3), o.add(10, -1, 3), Blocks.AIR.getDefaultState());
            fill(world, o.add(10, -10, 1), o.add(10, -9, 2), Blocks.AIR.getDefaultState());
            fill(world, o.add(11, -10, 3), o.add(11, -9, 3), Blocks.AIR.getDefaultState());
            world.setBlockState(o.add(11, -8, 3), Blocks.GLOWSTONE.getDefaultState(), 2);
            world.setBlockState(o.add(10, -7, 0), Blocks.GLOWSTONE.getDefaultState(), 2);
            walk.add(o.add(11, -10, 3));
            paseo = new Paseo(walk);
            boxA = o.add(-12, -14, -8);
            boxB = o.add(16, 8, 8);
            miner = villager(world, o.add(-4, 0, 0), Direction.EAST, "Teo Subida");
            miner.getBrain().forget(MemoryModuleTypeMCA.STAYING.get());
            miner.setAiDisabled(true);
            miner.getInventory().addStack(new ItemStack(Items.STONE_PICKAXE));
            miner.getInventory().addStack(new ItemStack(Items.COBBLESTONE, 32));
            miner.getInventory().addStack(new ItemStack(Items.BREAD, 6));
            task.setHome(o.add(-10, 0, 0));
            task.setFood(20);
        }

        @Override
        Boolean step() {
            if (!miner.isAlive()) {
                fail(name, "el minero murió; " + state());
                return Boolean.FALSE;
            }
            if (!called) {
                if (!paseo.tick(world, miner, ticks)) {
                    return null;
                }
                miner.setAiDisabled(false);
                face(miner, 90.0F, 0.0F);
                before = airIn(world, boxA, boxB);
                ProspectingTask.callHome(miner, false);
                called = true;
                climbStart = ticks;
                MCA.LOGGER.info("PRUEBA subida-lado: recuerda " + net.mca.entity.ai.mente.Recuerdos.of(world, miner).size()
                        + " pasos, cayó por un pozo junto a la escalera, lo llaman a casa desde " + miner.getBlockPos().toShortString());
                return null;
            }
            task.practice(world, miner);
            memoryUsed |= task.porMemoria();
            BlockPos feet = miner.getBlockPos();
            int k = feet.getX() - o.getX();
            if (feet.getZ() == o.getZ() && k >= 2 && k <= 8 && feet.getY() == o.getY() - k) {
                onStair = true;
            }
            if ((ticks - climbStart) % 100 == 0) {
                MCA.LOGGER.info("PRUEBA subida-lado t=" + (ticks - climbStart) + ": " + task.debug() + ", " + state());
            }
            int[] changed = changedIn(world, boxA, boxB, before);
            if (changed[0] > 0) {
                fail(name, "rompió " + changed[0] + " bloques en vez de subir por la escalera de al lado; " + state());
                return Boolean.FALSE;
            }
            boolean up = miner.getBlockY() >= o.getY() && world.isSkyVisible(miner.getBlockPos().up());
            if (!up || task.fase().equals("HOME_UP")) {
                return null;
            }
            boolean passed = memoryUsed && onStair;
            check(name, passed, "salió del pozo por la escalera de al lado en " + (ticks - climbStart) + " ticks, por su memoria: " + memoryUsed
                    + ", pisó la escalera: " + onStair + ", bloques rotos " + changed[0] + ", puestos " + changed[1] + "; " + state());
            return passed;
        }

        @Override
        String state() {
            return "en " + miner.getBlockPos().toShortString() + ", fase " + task.fase() + ", haciendo: " + Retina.memory(miner).doing()
                    + ", tarea " + task.debug();
        }

        @Override
        void cleanup() {
            net.mca.entity.ai.mente.Recuerdos.olvidar(world, miner);
            miner.discard();
        }
    }

    /**
     * "15 segundos es mucho, ponle unos 10 o 7 segundos": standing still and getting nowhere, the first remedy comes
     * after seven seconds ({@link net.mca.entity.ai.mente.Atasco#TICKS}), not fifteen; standing still by choice
     * (waiting, resting) is never counted.
     */
    private static final class AtascoSiete extends Scenario {
        private final net.mca.entity.ai.mente.Atasco calm = new net.mca.entity.ai.mente.Atasco();
        private final net.mca.entity.ai.mente.Atasco stuck = new net.mca.entity.ai.mente.Atasco();
        private final VillagerEntityMCA v;
        private int startAt = -1;
        private int firstAt = -1;

        AtascoSiete(ServerWorld world) {
            super("atasco-7s", world, 900);
            BlockPos o = new BlockPos(6600, 100, 0);
            load(world, o, 16);
            fill(world, o.add(-4, -1, -4), o.add(4, -1, 4), Blocks.STONE.getDefaultState());
            fill(world, o.add(-4, 0, -4), o.add(4, 4, 4), Blocks.AIR.getDefaultState());
            v = villager(world, o, Direction.EAST, "Rita Atasco");
            v.setAiDisabled(true);
        }

        @Override
        Boolean step() {
            if (!v.isAlive()) {
                fail(name, "el aldeano murió");
                return Boolean.FALSE;
            }
            // by choice for fifteen seconds: nothing
            if (ticks < 320) {
                if (calm.tick(world, v, true, "esperando por gusto") != net.mca.entity.ai.mente.Atasco.Remedio.NINGUNO) {
                    fail(name, "contó como atascado a uno que estaba quieto por gusto (" + ticks + " ticks)");
                    return Boolean.FALSE;
                }
                return null;
            }
            if (startAt < 0) {
                startAt = ticks;
            }
            net.mca.entity.ai.mente.Atasco.Remedio r = stuck.tick(world, v, false, "sin poder avanzar");
            if (r == net.mca.entity.ai.mente.Atasco.Remedio.NINGUNO) {
                if (ticks - startAt > 400) {
                    fail(name, "quieto sin avanzar " + (ticks - startAt) + " ticks y ningún remedio");
                    return Boolean.FALSE;
                }
                return null;
            }
            firstAt = ticks - startAt;
            boolean passed = net.mca.entity.ai.mente.Atasco.TICKS <= 200 && firstAt <= net.mca.entity.ai.mente.Atasco.TICKS + 40
                    && firstAt >= net.mca.entity.ai.mente.Atasco.TICKS - 20 && r == net.mca.entity.ai.mente.Atasco.Remedio.REINICIAR;
            check(name, passed, "primer remedio (" + r + ") tras " + firstAt + " ticks quieto (umbral " + net.mca.entity.ai.mente.Atasco.TICKS
                    + "), nada en 15 s quieto por gusto");
            return passed;
        }

        @Override
        String state() {
            return "en " + v.getBlockPos().toShortString() + ", edad " + v.age + ", primer remedio " + firstAt;
        }

        @Override
        void cleanup() {
            v.discard();
        }
    }

    /** The chests (any) in a box, nearest the first corner first. */
    private static List<BlockPos> chestsIn(ServerWorld world, BlockPos a, BlockPos b) {
        List<BlockPos> list = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(a, b)) {
            if (world.getBlockState(pos).getBlock() instanceof net.minecraft.block.ChestBlock) {
                list.add(pos.toImmutable());
            }
        }
        return list;
    }

    /** Cobblestone that went into what a villager carries: a furnace 8, a stone pickaxe or axe 3, a sword or hoe 2, a shovel 1. */
    private static int stoneMade(VillagerEntityMCA v) {
        int n = 0;
        List<ItemStack> all = new ArrayList<>();
        for (int i = 0; i < v.getInventory().size(); i++) {
            all.add(v.getInventory().getStack(i));
        }
        all.add(v.getMainHandStack());
        all.add(v.getOffHandStack());
        for (ItemStack stack : all) {
            int each = stack.isOf(Items.FURNACE) ? 8 : stack.isOf(Items.STONE_PICKAXE) || stack.isOf(Items.STONE_AXE) ? 3
                    : stack.isOf(Items.STONE_SWORD) || stack.isOf(Items.STONE_HOE) ? 2 : stack.isOf(Items.STONE_SHOVEL) ? 1 : 0;
            n += each * stack.getCount();
        }
        return n;
    }

    private static int furnacesIn(ServerWorld world, BlockPos a, BlockPos b) {
        int n = 0;
        for (BlockPos pos : BlockPos.iterate(a, b)) {
            if (world.getBlockState(pos).isOf(Blocks.FURNACE)) {
                n++;
            }
        }
        return n;
    }

    /** A one-wide, two-high tunnel deep in stone, lit, with the land far over it: where a miner fills its bag. */
    private static void tunnel(ServerWorld world, BlockPos o) {
        load(world, o, 24);
        fill(world, o.add(-14, -14, -6), o.add(14, -2, 6), Blocks.STONE.getDefaultState());
        fill(world, o.add(-14, -1, -6), o.add(14, -1, 6), Blocks.GRASS_BLOCK.getDefaultState());
        fill(world, o.add(-14, 0, -6), o.add(14, 6, 6), Blocks.AIR.getDefaultState());
        fill(world, o.add(-8, -10, 0), o.add(8, -9, 0), Blocks.AIR.getDefaultState());
        for (int x = -6; x <= 6; x += 6) {
            world.setBlockState(o.add(x, -8, 0), Blocks.GLOWSTONE.getDefaultState(), 2);
        }
    }

    private static void clearChests(ServerWorld world, BlockPos a, BlockPos b) {
        for (BlockPos pos : chestsIn(world, a, b)) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
            net.mca.server.world.data.TropelesMundo.get(world).removed(pos);
        }
    }

    /**
     * "Que pueda crear cofres en más partes del mapa, tal cual cuando hace mesas de crafteo y hornos": a miner deep in
     * its tunnel, home sixty blocks away, its bag full of raw gold. It makes a chest from its wood right there, sets it
     * into the tunnel wall (never across the way), stores the gold, remembers it by name as one of its own, and keeps
     * working instead of walking home. Its bag filled again with copper, it goes back to that same chest.
     */
    private static final class CofreCampo extends Scenario {
        private final ProspectingTask task = new ProspectingTask();
        private final VillagerEntityMCA miner;
        private final BlockPos o;
        private final BlockPos boxA;
        private final BlockPos boxB;
        @Nullable
        private BlockPos chest;
        private String chestName = "";
        private int refillAt = -1;

        CofreCampo(ServerWorld world) {
            super("cofre-campo", world, 3600);
            o = new BlockPos(6700, 100, 0);
            tunnel(world, o);
            boxA = o.add(-12, -12, -4);
            boxB = o.add(12, -7, 4);
            miner = villager(world, o.add(0, -10, 0), Direction.EAST, "Iván Cofre");
            miner.getBrain().forget(MemoryModuleTypeMCA.STAYING.get());
            var inv = miner.getInventory();
            inv.addStack(new ItemStack(Items.STONE_PICKAXE));
            inv.addStack(new ItemStack(Items.COBBLESTONE, 24));
            inv.addStack(new ItemStack(Items.BREAD, 8));
            inv.addStack(new ItemStack(Items.OAK_LOG, 3));
            while (ProspectingTask.espacioLibre(miner) > 2) {
                inv.addStack(new ItemStack(Items.RAW_GOLD, 64));
            }
            task.setHome(o.add(-60, 0, 0));
            task.setFood(20);
            face(miner, -90.0F, 0.0F);
        }

        @Override
        Boolean step() {
            if (!miner.isAlive()) {
                fail(name, "el minero murió; " + state());
                return Boolean.FALSE;
            }
            task.practice(world, miner);
            if (ticks % 200 == 0) {
                MCA.LOGGER.info("PRUEBA cofre-campo t=" + ticks + ": " + task.debug() + ", " + state());
            }
            if (task.fase().startsWith("HOME")) {
                fail(name, "se fue a casa con la mochila llena en vez de poner un cofre ahí mismo; " + state());
                return Boolean.FALSE;
            }
            if (chest == null) {
                for (BlockPos pos : chestsIn(world, boxA, boxB)) {
                    var inv = Cofres.inventory(world, pos);
                    int gold = inv == null ? 0 : Cofres.contents(inv).getOrDefault("minecraft:raw_gold", 0);
                    if (gold == 0) {
                        continue;
                    }
                    var r = net.mca.entity.ai.mente.Recuerdos.of(world, miner);
                    String named = "";
                    for (var e : r.cofresDeCampo().entrySet()) {
                        if (e.getValue().equals(pos)) {
                            named = e.getKey();
                        }
                    }
                    boolean own = Cofres.own(world, miner, pos).contains(pos);
                    boolean inTheWay = pos.getZ() == o.getZ() && pos.getY() >= o.getY() - 10 && pos.getY() <= o.getY() - 9
                            && Math.abs(pos.getX() - o.getX()) <= 7;
                    if (!own || named.isEmpty() || inTheWay || gold < 64 * 10) {
                        fail(name, "cofre en " + pos.toShortString() + " con " + gold + " de oro: suyo " + own + ", recordado como «" + named
                                + "», tapa el túnel " + inTheWay + "; " + state());
                        return Boolean.FALSE;
                    }
                    chest = pos;
                    chestName = named;
                    MCA.LOGGER.info("PRUEBA cofre-campo: puso «" + named + "» en " + pos.toShortString() + " con " + gold + " de oro en " + ticks
                            + " ticks; ahora se le llena de cobre");
                    var bag = miner.getInventory();
                    while (ProspectingTask.espacioLibre(miner) > 2) {
                        bag.addStack(new ItemStack(Items.RAW_COPPER, 64));
                    }
                    refillAt = ticks;
                    return null;
                }
                return null;
            }
            var inv = Cofres.inventory(world, chest);
            if (inv == null) {
                fail(name, "su cofre de campo desapareció; " + state());
                return Boolean.FALSE;
            }
            int copper = Cofres.contents(inv).getOrDefault("minecraft:raw_copper", 0);
            if (copper == 0) {
                return null;
            }
            check(name, true, "puso «" + chestName + "» en " + chest.toShortString() + " sin volver a casa, y volvió a él a guardar " + copper
                    + " de cobre " + (ticks - refillAt) + " ticks después; " + state());
            return Boolean.TRUE;
        }

        @Override
        String state() {
            var inv = miner.getInventory();
            return "en " + miner.getBlockPos().toShortString() + ", fase " + task.fase() + ", haciendo: " + Retina.memory(miner).doing()
                    + ", oro " + inv.count(Items.RAW_GOLD) + ", cobre " + inv.count(Items.RAW_COPPER) + ", troncos " + inv.count(Items.OAK_LOG)
                    + ", tablas " + inv.count(Items.OAK_PLANKS) + ", cofres " + inv.count(Items.CHEST) + ", libres " + ProspectingTask.espacioLibre(miner);
        }

        @Override
        void cleanup() {
            net.mca.entity.ai.mente.Recuerdos.olvidar(world, miner);
            miner.discard();
            clearChests(world, boxA, boxB);
        }
    }

    /**
     * "Que pueda crear algún cofre para botar basura y que lo deje ahí olvidado": a miner whose bag is nearly full of
     * cobblestone, dirt, gravel, andesite, diorite and rotten flesh puts it all into a chest it makes and sets down
     * right there, keeping {@code 32} cobblestone for pillars and its furnace and every ore. The chest is not its own
     * (no theft, no hearts, no home), it is not among the places it remembers, and nothing is thrown on the ground.
     */
    private static final class CofreBasura extends Scenario {
        private final ProspectingTask task = new ProspectingTask();
        private final VillagerEntityMCA miner;
        private final BlockPos o;
        private final BlockPos boxA;
        private final BlockPos boxB;
        private final int gold;

        CofreBasura(ServerWorld world) {
            super("cofre-basura", world, 2400);
            o = new BlockPos(6800, 100, 0);
            tunnel(world, o);
            boxA = o.add(-12, -12, -4);
            boxB = o.add(12, -7, 4);
            miner = villager(world, o.add(0, -10, 0), Direction.EAST, "Lalo Basura");
            miner.getBrain().forget(MemoryModuleTypeMCA.STAYING.get());
            var inv = miner.getInventory();
            inv.addStack(new ItemStack(Items.STONE_PICKAXE));
            inv.addStack(new ItemStack(Items.BREAD, 8));
            inv.addStack(new ItemStack(Items.OAK_LOG, 6));
            for (int k = 0; k < 3; k++) {
                inv.addStack(new ItemStack(Items.COBBLESTONE, 64));
            }
            inv.addStack(new ItemStack(Items.DIRT, 64));
            inv.addStack(new ItemStack(Items.GRAVEL, 64));
            inv.addStack(new ItemStack(Items.ANDESITE, 64));
            inv.addStack(new ItemStack(Items.DIORITE, 32));
            inv.addStack(new ItemStack(Items.ROTTEN_FLESH, 10));
            while (ProspectingTask.espacioLibre(miner) > 2) {
                inv.addStack(new ItemStack(Items.RAW_GOLD, 64));
            }
            gold = inv.count(Items.RAW_GOLD);
            task.setHome(o.add(-60, 0, 0));
            task.setFood(20);
            face(miner, -90.0F, 0.0F);
        }

        @Override
        Boolean step() {
            if (!miner.isAlive()) {
                fail(name, "el minero murió; " + state());
                return Boolean.FALSE;
            }
            task.practice(world, miner);
            if (ticks % 200 == 0) {
                MCA.LOGGER.info("PRUEBA cofre-basura t=" + ticks + ": " + task.debug() + ", " + state());
            }
            if (task.fase().startsWith("HOME")) {
                fail(name, "se fue a casa en vez de dejar la basura en un cofre; " + state());
                return Boolean.FALSE;
            }
            boolean thrown = !world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(o.add(0, -10, 0)).expand(10),
                    e -> e.getStack().isOf(Items.DIRT) || e.getStack().isOf(Items.ANDESITE) || e.getStack().isOf(Items.ROTTEN_FLESH)).isEmpty();
            if (thrown) {
                fail(name, "tiró la basura al suelo en vez de dejarla en un cofre; " + state());
                return Boolean.FALSE;
            }
            for (BlockPos pos : chestsIn(world, boxA, boxB)) {
                var inv = Cofres.inventory(world, pos);
                if (inv == null) {
                    continue;
                }
                Map<String, Integer> in = Cofres.contents(inv);
                if (in.getOrDefault("minecraft:dirt", 0) == 0) {
                    continue;
                }
                var bag = miner.getInventory();
                int cobble = bag.count(Items.COBBLESTONE);
                boolean allJunk = in.getOrDefault("minecraft:dirt", 0) == 64 && in.getOrDefault("minecraft:gravel", 0) == 64
                        && in.getOrDefault("minecraft:andesite", 0) == 64 && in.getOrDefault("minecraft:diorite", 0) == 32
                        && in.getOrDefault("minecraft:rotten_flesh", 0) == 10;
                // every cobblestone beyond the 32 it keeps went into the chest: what it had (192, and one from the niche it
                // cut) is the chest's, the bag's and what it made from it with its own hands (a furnace, stone tools)
                int made = stoneMade(miner) + 8 * furnacesIn(world, boxA, boxB) - 3;
                int inChest = in.getOrDefault("minecraft:cobblestone", 0);
                // and what it set down as blocks (pillars, floors, plugs) or let fall
                int placed = 0;
                for (BlockPos q : BlockPos.iterate(boxA, boxB)) {
                    if (world.getBlockState(q).isOf(Blocks.COBBLESTONE)) {
                        placed++;
                    }
                }
                int dropped = 0;
                for (var e : world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(boxA).union(new net.minecraft.util.math.Box(boxB)).expand(2),
                        e -> e.getStack().isOf(Items.COBBLESTONE))) {
                    dropped += e.getStack().getCount();
                }
                int total = inChest + cobble + made + placed + dropped;
                allJunk &= inChest >= 100 && total >= 192 && total <= 193;
                boolean kept = cobble == 32 && bag.count(Items.RAW_GOLD) == gold && bag.count(Items.STONE_PICKAXE) + (miner.getMainHandStack().isOf(Items.STONE_PICKAXE) ? 1 : 0) >= 1
                        && in.getOrDefault("minecraft:raw_gold", 0) == 0;
                boolean unowned = net.mca.server.world.data.TropelesMundo.get(world).chest(pos) == null
                        && !net.mca.entity.ai.mente.Recuerdos.of(world, miner).sitios().containsValue(pos);
                boolean passed = allJunk && kept && unowned;
                check(name, passed, "dejó la basura en un cofre en " + pos.toShortString() + " en " + ticks + " ticks: " + in + "; se quedó con "
                        + cobble + " de piedra (gastó " + made + " en lo que fabricó, puso " + placed + " como bloques, en el suelo " + dropped + "; cuenta " + total + "/192-193) y su oro (" + bag.count(Items.RAW_GOLD) + "/" + gold + "); cofre sin dueño ni nombre: " + unowned + "; " + state());
                return passed;
            }
            return null;
        }

        @Override
        String state() {
            var inv = miner.getInventory();
            return "en " + miner.getBlockPos().toShortString() + ", fase " + task.fase() + ", haciendo: " + Retina.memory(miner).doing()
                    + ", piedra " + inv.count(Items.COBBLESTONE) + ", tierra " + inv.count(Items.DIRT) + ", troncos " + inv.count(Items.OAK_LOG)
                    + ", cofres " + inv.count(Items.CHEST) + ", libres " + ProspectingTask.espacioLibre(miner);
        }

        @Override
        void cleanup() {
            net.mca.entity.ai.mente.Recuerdos.olvidar(world, miner);
            miner.discard();
            clearChests(world, boxA, boxB);
        }
    }

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

    /** Someone not of the family takes from its chest in front of it: it notices at once and the hearts drop. */
    private static final class Robo extends Scenario {
        private final VillagerEntityMCA owner;
        private final BlockPos chest;

        Robo(ServerWorld world) {
            super("robo", world, 200);
            BlockPos o = new BlockPos(2700, 100, 0);
            load(world, o, 8);
            fill(world, o.add(-4, -1, -4), o.add(4, -1, 4), Blocks.STONE.getDefaultState());
            fill(world, o.add(-4, 0, -4), o.add(4, 4, 4), Blocks.AIR.getDefaultState());
            fill(world, o.add(-4, 5, -4), o.add(4, 5, 4), Blocks.GLOWSTONE.getDefaultState());
            owner = villager(world, o, Direction.EAST);
            owner.setAiDisabled(true);
            chest = chestFor(world, owner, o.add(2, 0, 0));
        }

        @Override
        Boolean step() {
            // the owner must be in the world's entity index before the thief is put down to it
            if (ticks < 40) {
                return null;
            }
            var inv = Cofres.inventory(world, chest);
            if (inv == null) {
                fail(name, "no hay cofre");
                return Boolean.FALSE;
            }
            inv.setStack(0, new ItemStack(Items.DIAMOND, 10));
            Cofres.remember(world, chest);
            var thief = fakePlayer(world, "Ladron", Vec3d.ofBottomCenter(chest.add(1, 0, 1)));
            int before = owner.getVillagerBrain().getMemoriesForPlayer(thief).getHearts();
            inv.getStack(0).decrement(5);
            int missing = Cofres.check(world, chest, thief);
            int after = owner.getVillagerBrain().getMemoriesForPlayer(thief).getHearts();
            boolean pending = !net.mca.server.world.data.TropelesMundo.get(world).theftsAgainst(owner.getUuid()).isEmpty();
            boolean passed = missing == 5 && after < before && !pending;
            check(name, passed, "le faltan " + missing + " diamantes, corazones " + before + " -> " + after
                    + ", lo notó al momento: " + !pending + ", dueño en el mundo: " + (world.getEntity(owner.getUuid()) != null));
            return passed;
        }

        @Override
        String state() {
            return "esperando";
        }

        @Override
        void cleanup() {
            world.setBlockState(chest, Blocks.AIR.getDefaultState(), 3);
            net.mca.server.world.data.TropelesMundo.get(world).removed(chest);
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
            // the whole bag, slot by slot (the order says which block the hands reach for first)
            StringBuilder bag = new StringBuilder();
            int free = 0;
            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = inv.getStack(i);
                if (stack.isEmpty()) {
                    free++;
                } else {
                    bag.append(bag.length() == 0 ? "" : " ").append(i).append(':').append(stack.getItem()).append('x').append(stack.getCount());
                }
            }
            // where its tables and furnaces stand, and what is in each furnace
            StringBuilder placed = new StringBuilder();
            for (BlockPos pos : BlockPos.iterate(origin.add(-16, -10, -16), origin.add(16, 8, 16))) {
                BlockState block = world.getBlockState(pos);
                if (block.isOf(Blocks.CRAFTING_TABLE)) {
                    placed.append(" mesa en ").append(pos.toShortString()).append(';');
                } else if (block.isOf(Blocks.FURNACE) && world.getBlockEntity(pos) instanceof net.minecraft.block.entity.AbstractFurnaceBlockEntity oven) {
                    placed.append(" horno en ").append(pos.toShortString()).append(" encendido ").append(block.get(net.minecraft.block.AbstractFurnaceBlock.LIT))
                            .append(" [").append(oven.getStack(0).getItem()).append('x').append(oven.getStack(0).getCount())
                            .append(", ").append(oven.getStack(1).getItem()).append('x').append(oven.getStack(1).getCount())
                            .append(", ").append(oven.getStack(2).getItem()).append('x').append(oven.getStack(2).getCount()).append("];");
                }
            }
            return "en " + miner.getBlockPos().toShortString() + ", haciendo: " + Retina.memory(miner).doing() + ", crudo " + inv.count(Items.RAW_IRON)
                    + ", lingotes " + inv.count(Items.IRON_INGOT) + ", carbón " + inv.count(Items.COAL) + ", roca " + inv.count(Items.COBBLESTONE)
                    + ", horno " + inv.count(Items.FURNACE) + ", fundiendo " + task.cooking() + ", mano " + miner.getMainHandStack().getItem()
                    + ", tarea " + task.debug() + ", equipo quieto " + task.gearIdle() + ", comida " + task.food()
                    + ", mochila (" + free + " libres) " + bag + ", en el mundo:" + (placed.length() == 0 ? " nada" : placed);
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
            if (ticks % 200 == 0) {
                MCA.LOGGER.info("PRUEBA hogar t=" + ticks + ": " + task.debug() + ", " + state());
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

    // ------------------------------------------------------------------ fields

    /**
     * A small field: ripe wheat on farmland, a pumpkin on its stem and a lone pumpkin. Crops and farmland are
     * never to be broken; a villager dropped onto the field does not trample it; a farmer leaves a player's
     * field alone but still tends the village's.
     */
    private static final class Cultivos extends Scenario {
        private final VillagerEntityMCA v;
        private final BlockPos o;
        private final BlockPos wheat;
        private final BlockPos playersWheat;
        private final BlockPos stem;
        private final BlockPos pumpkin;
        private final BlockPos lonePumpkin;
        private final BlockPos landing;
        private boolean dropped;
        private String labels = "";

        Cultivos(ServerWorld world) {
            super("cultivos", world, 400);
            o = new BlockPos(3200, 100, 0);
            load(world, o, 10);
            fill(world, o.add(-6, -3, -6), o.add(6, -2, 6), Blocks.STONE.getDefaultState());
            fill(world, o.add(-6, -1, -6), o.add(6, -1, 6), Blocks.GRASS_BLOCK.getDefaultState());
            fill(world, o.add(-6, 0, -6), o.add(6, 8, 6), Blocks.AIR.getDefaultState());
            BlockState soil = Blocks.FARMLAND.getDefaultState().with(net.minecraft.block.FarmlandBlock.MOISTURE, 7);
            BlockState ripe = Blocks.WHEAT.getDefaultState().with(net.minecraft.block.CropBlock.AGE, 7);
            fill(world, o.add(0, -1, 0), o.add(2, -1, 2), soil);
            fill(world, o.add(0, 0, 0), o.add(2, 0, 2), ripe);
            wheat = o.add(0, 0, 0);
            playersWheat = o.add(2, 0, 2);
            landing = o.add(1, 0, 1);
            net.mca.server.world.data.TropelesMundo.get(world).placedByPlayer(playersWheat.down());
            pumpkin = o.add(-2, 0, 0);
            stem = o.add(-3, 0, 0);
            world.setBlockState(stem.down(), soil, 2);
            world.setBlockState(pumpkin, Blocks.PUMPKIN.getDefaultState(), 2);
            world.setBlockState(stem, Blocks.ATTACHED_PUMPKIN_STEM.getDefaultState().with(net.minecraft.block.AttachedStemBlock.FACING, Direction.EAST), 2);
            lonePumpkin = o.add(-4, 0, 4);
            world.setBlockState(lonePumpkin, Blocks.PUMPKIN.getDefaultState(), 2);
            v = villager(world, o.add(4, 0, 0), Direction.WEST);
        }

        @Override
        Boolean step() {
            if (ticks < 40) {
                return null;
            }
            if (!dropped) {
                Obras.Kind crop = Obras.kind(world, v, wheat);
                Obras.Kind ground = Obras.kind(world, v, wheat.down());
                boolean neverBroken = !Obras.mayBreak(world, v, wheat) && !Obras.mayBreak(world, v, wheat.down())
                        && !Obras.mayBreak(world, v, pumpkin) && !Obras.mayBreak(world, v, stem);
                boolean gourds = Obras.cropAt(world, pumpkin) && !Obras.cropAt(world, lonePumpkin);
                var farmer = (net.mca.mixin.MixinFarmerVillagerTaskInvoker) (Object) new net.minecraft.entity.ai.brain.task.FarmerVillagerTask();
                boolean village = farmer.mca$isSuitableTarget(wheat, world);
                boolean players = farmer.mca$isSuitableTarget(playersWheat, world);
                labels = "trigo: " + crop.label + ", tierra arada: " + ground.label + ", nunca se rompen: " + neverBroken
                        + ", zapallo en su tallo es cultivo: " + Obras.cropAt(world, pumpkin) + ", zapallo suelto: " + Obras.cropAt(world, lonePumpkin)
                        + ", el granjero cosecharía el del pueblo: " + village + ", el del jugador: " + players;
                if (!(crop == Obras.Kind.CROP && ground == Obras.Kind.CROP && neverBroken && gourds && village && !players)) {
                    fail(name, labels);
                    return Boolean.FALSE;
                }
                // now drop it from three blocks onto the wheat
                v.requestTeleport(landing.getX() + 0.5, landing.getY() + 3.0, landing.getZ() + 0.5);
                v.fallDistance = 0;
                dropped = true;
                return null;
            }
            if (!v.isOnGround() || ticks < 120) {
                return null;
            }
            boolean field = world.getBlockState(landing.down()).isOf(Blocks.FARMLAND) && world.getBlockState(landing).isOf(Blocks.WHEAT);
            check(name, field, labels + ", cayó 3 bloques sobre el trigo y el campo sigue arado: " + field);
            return field;
        }

        @Override
        String state() {
            return "aldeano en " + v.getBlockPos().toShortString() + ", " + labels;
        }

        @Override
        void cleanup() {
            v.discard();
            net.mca.server.world.data.TropelesMundo.get(world).removed(playersWheat.down());
        }
    }

    // ------------------------------------------------------------------ water

    /** The diamond at the end of a stone corridor, with a spring coming out of the wall on the way. */
    private static final class AguaTunel extends Scenario {
        private final ProspectingTask task = new ProspectingTask();
        private final VillagerEntityMCA miner;
        private final BlockPos diamond;
        private final BlockPos spring;
        private final BlockPos origin;
        private int wetTicks;

        AguaTunel(ServerWorld world) {
            super("agua-tunel", world, 2400);
            origin = new BlockPos(3300, 100, 0);
            load(world, origin, 24);
            fill(world, origin.add(-16, -24, -10), origin.add(20, 4, 10), Blocks.STONE.getDefaultState());
            fill(world, origin.add(0, 0, 0), origin.add(9, 1, 0), Blocks.AIR.getDefaultState());
            for (int x = 0; x <= 9; x += 3) {
                world.setBlockState(origin.add(x, 2, 0), Blocks.GLOWSTONE.getDefaultState(), 2);
            }
            diamond = origin.add(10, 0, 0);
            world.setBlockState(diamond, Blocks.DIAMOND_ORE.getDefaultState(), 2);
            // a spring in the side wall, halfway: it floods the corridor
            spring = origin.add(5, 0, 1);
            world.setBlockState(spring, Blocks.WATER.getDefaultState(), 3);
            miner = villager(world, origin, Direction.EAST);
            miner.getInventory().addStack(new ItemStack(Items.DIAMOND_PICKAXE));
            miner.getInventory().addStack(new ItemStack(Items.COBBLESTONE, 32));
            face(miner, -90.0F, 0.0F);
            // it looks down the corridor and sees the diamond, like in "minar"
            Retina.lookAll(world, miner);
            net.mca.entity.ai.body.Agua.trace = miner;
        }

        @Override
        Boolean step() {
            if (!miner.isAlive()) {
                fail(name, "el minero murió; " + state());
                return Boolean.FALSE;
            }
            if (ticks < 30) {
                return null; // let the water run into the corridor first
            }
            if (miner.isSubmergedInWater()) {
                wetTicks++;
            }
            if (ticks % 100 == 0) {
                MCA.LOGGER.info("PRUEBA agua-tunel t=" + ticks + ": " + task.debug() + ", " + state()
                        + String.format(", velocidad %.3f %.3f", miner.getVelocity().x, miner.getVelocity().z));
            }
            miner.getNavigation().stop();
            task.practice(world, miner);
            if (world.getBlockState(diamond).isOf(Blocks.DIAMOND_ORE)) {
                return null;
            }
            boolean got = miner.getInventory().count(Items.DIAMOND) > 0;
            if (!got && ticks % 20 != 0) {
                return null;
            }
            boolean plugged = !net.mca.entity.ai.body.Agua.water(world, spring);
            boolean passed = got && plugged;
            check(name, passed, "diamante picado en " + ticks + " ticks, en la mochila: " + got + ", manantial tapado: " + plugged
                    + ", ticks con la cabeza bajo el agua: " + wetTicks + "; " + state());
            return passed;
        }

        @Override
        String state() {
            return "en " + miner.getBlockPos().toShortString() + ", haciendo: " + Retina.memory(miner).doing() + ", piedra "
                    + miner.getInventory().count(Items.COBBLESTONE) + ", manantial: " + world.getBlockState(spring).getBlock().getName().getString();
        }

        @Override
        void cleanup() {
            miner.discard();
            net.mca.entity.ai.body.Agua.trace = null;
        }
    }

    /**
     * The picture Benja sent: a villager at the bottom of a one-wide shaft with three blocks of water in it and
     * hungry, so it heads home. It has to get out of the water without drowning and climb the dry shaft to the
     * surface, like a player spamming blocks under their feet.
     */
    private static final class AguaPozo extends Scenario {
        private final ProspectingTask task = new ProspectingTask();
        private final VillagerEntityMCA miner;
        private final BlockPos o;
        private int lowestAir = Integer.MAX_VALUE;

        AguaPozo(ServerWorld world) {
            super("agua-pozo", world, 2600);
            o = new BlockPos(3400, 100, 0);
            load(world, o, 32);
            fill(world, o.add(-12, -14, -12), o.add(12, -2, 12), Blocks.STONE.getDefaultState());
            fill(world, o.add(-12, -1, -12), o.add(12, -1, 12), Blocks.GRASS_BLOCK.getDefaultState());
            fill(world, o.add(-12, 0, -12), o.add(12, 10, 12), Blocks.AIR.getDefaultState());
            BlockPos bottom = o.add(0, -11, 0);
            fill(world, bottom, o.add(0, -1, 0), Blocks.AIR.getDefaultState());
            fill(world, bottom, bottom.up(2), Blocks.WATER.getDefaultState());
            miner = villager(world, bottom, Direction.EAST);
            miner.getBrain().forget(MemoryModuleTypeMCA.STAYING.get());
            miner.getInventory().addStack(new ItemStack(Items.STONE_PICKAXE));
            miner.getInventory().addStack(new ItemStack(Items.COBBLESTONE, 32));
            miner.getInventory().addStack(new ItemStack(Items.OAK_PLANKS, 12));
            miner.getInventory().addStack(new ItemStack(Items.STICK, 4));
            task.setHome(o.add(6, 0, 0));
            task.setFood(3);
            face(miner, -90.0F, 0.0F);
        }

        @Override
        Boolean step() {
            if (!miner.isAlive()) {
                fail(name, "el minero se ahogó o murió; " + state());
                return Boolean.FALSE;
            }
            lowestAir = Math.min(lowestAir, miner.getAir());
            miner.getNavigation().stop();
            task.practice(world, miner);
            boolean surface = miner.getBlockY() >= o.getY() - 1 && world.isSkyVisible(miner.getBlockPos().up()) && !miner.isTouchingWater();
            if (!surface) {
                return null;
            }
            boolean hurt = miner.getHealth() < miner.getMaxHealth() - 4;
            check(name, !hurt, "salió del pozo inundado a la superficie en " + ticks + " ticks, aire mínimo " + lowestAir + "/" + miner.getMaxAir()
                    + ", vida " + (int) miner.getHealth() + "/" + (int) miner.getMaxHealth() + "; " + state());
            return !hurt;
        }

        @Override
        String state() {
            return "en " + miner.getBlockPos().toShortString() + ", haciendo: " + Retina.memory(miner).doing() + ", aire " + miner.getAir()
                    + ", en el agua: " + miner.isTouchingWater() + ", piedra " + miner.getInventory().count(Items.COBBLESTONE) + ", comida " + task.food();
        }

        @Override
        void cleanup() {
            miner.discard();
        }
    }

    // ------------------------------------------------------------------ exploring

    /**
     * What Benja saw: an empty-handed villager inside a small roofed hut (a player's build), the trees out of sight
     * behind a ridge on the far side. It must not take the roof for a mine: out of the door, a look around, explore
     * toward what it has not seen, spot the canopies, chop, make a wooden pickaxe and start a staircase on natural
     * ground, never climbing "out of a mine" and never breaking the hut.
     */
    private static final class Exploracion extends Scenario {
        private final ProspectingTask task = new ProspectingTask();
        private final VillagerEntityMCA v;
        private final BlockPos o;
        private final List<BlockPos> hut = new ArrayList<>();
        private boolean wooden;
        private int woodenAt;
        private boolean homeUp;
        private String homeUpState = "";
        private int outAt = -1;

        Exploracion(ServerWorld world) {
            super("explorar", world, 4000);
            o = new BlockPos(3800, 100, 0);
            load(world, o, 40);
            fill(world, o.add(-31, -14, -31), o.add(31, -2, 31), Blocks.STONE.getDefaultState());
            fill(world, o.add(-31, -1, -31), o.add(31, -1, 31), Blocks.GRASS_BLOCK.getDefaultState());
            fill(world, o.add(-31, 0, -31), o.add(31, 14, 31), Blocks.AIR.getDefaultState());
            // a low stone rim, so that nobody walks off the edge of the platform
            fill(world, o.add(-31, 0, -31), o.add(31, 1, -31), Blocks.STONE.getDefaultState());
            fill(world, o.add(-31, 0, 31), o.add(31, 1, 31), Blocks.STONE.getDefaultState());
            fill(world, o.add(-31, 0, -31), o.add(-31, 1, 31), Blocks.STONE.getDefaultState());
            fill(world, o.add(31, 0, -31), o.add(31, 1, 31), Blocks.STONE.getDefaultState());
            // a dirt ridge across the whole platform, five high in the middle, one block a step: it hides the trees
            for (int x = 9; x <= 17; x++) {
                int h = 5 - Math.abs(x - 13);
                fill(world, o.add(x, 0, -30), o.add(x, h - 1, 30), Blocks.DIRT.getDefaultState());
            }
            tree(world, o.add(26, 0, -4), 5);
            tree(world, o.add(28, 0, 3), 5);
            tree(world, o.add(25, 0, 9), 5);
            // the hut: planks walls and roof around a 3x3 room, a door gap on the west side, all set by a player
            BlockState planks = Blocks.OAK_PLANKS.getDefaultState();
            for (BlockPos pos : BlockPos.iterate(o.add(-2, 0, -2), o.add(2, 3, 2))) {
                boolean wall = Math.abs(pos.getX() - o.getX()) == 2 || Math.abs(pos.getZ() - o.getZ()) == 2 || pos.getY() == o.getY() + 3;
                boolean door = pos.getX() == o.getX() - 2 && pos.getZ() == o.getZ() && pos.getY() <= o.getY() + 1;
                if (wall && !door) {
                    world.setBlockState(pos, planks, 2);
                    Obras.placedByPlayer(world, pos);
                    hut.add(pos.toImmutable());
                }
            }
            v = villager(world, o, Direction.WEST);
            // free to walk: its own legs take it out of the door, no "stay here"
            v.getBrain().forget(MemoryModuleTypeMCA.STAYING.get());
            // facing the door, its back to the hut wall and the trees
            face(v, 90.0F, 0.0F);
        }

        private int hutLeft() {
            int n = 0;
            for (BlockPos pos : hut) {
                if (world.getBlockState(pos).isOf(Blocks.OAK_PLANKS)) {
                    n++;
                }
            }
            return n;
        }

        @Override
        Boolean step() {
            if (!v.isAlive()) {
                fail(name, "el aldeano murió; " + state());
                return Boolean.FALSE;
            }
            task.practice(world, v);
            if (ticks % 200 == 0) {
                MCA.LOGGER.info("PRUEBA explorar t=" + ticks + ": " + state());
            }
            if (!homeUp && task.debug().startsWith("HOME_UP")) {
                homeUp = true;
                homeUpState = state();
                MCA.LOGGER.info("PRUEBA explorar: subiendo de una mina que no existe a los " + ticks + " ticks; " + homeUpState);
            }
            if (outAt < 0 && Math.max(Math.abs(v.getBlockX() - o.getX()), Math.abs(v.getBlockZ() - o.getZ())) > 2) {
                outAt = ticks;
                MCA.LOGGER.info("PRUEBA explorar: salió de la choza a los " + ticks + " ticks; " + state());
            }
            if (!wooden && (v.getMainHandStack().isOf(Items.WOODEN_PICKAXE) || v.getInventory().count(Items.WOODEN_PICKAXE) > 0)) {
                wooden = true;
                woodenAt = ticks;
                MCA.LOGGER.info("PRUEBA explorar: pico de madera hecho en " + ticks + " ticks; " + state());
            }
            int left = hutLeft();
            if (left < hut.size()) {
                fail(name, "rompió la choza: quedan " + left + "/" + hut.size() + " tablas; " + state());
                return Boolean.FALSE;
            }
            if (!wooden || v.getBlockY() > o.getY() - 3 || !v.isOnGround()) {
                return null;
            }
            boolean passed = !homeUp;
            check(name, passed, "salió de la choza a los " + outAt + " ticks, pico de madera en " + woodenAt + " ticks, cavó hasta "
                    + v.getBlockPos().toShortString() + " a los " + ticks + " ticks, choza intacta " + left + "/" + hut.size()
                    + ", se creyó en una mina: " + homeUp + (homeUp ? " (" + homeUpState + ")" : "") + "; " + state());
            return passed;
        }

        @Override
        String state() {
            var inv = v.getInventory();
            StringBuilder bag = new StringBuilder();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = inv.getStack(i);
                if (!stack.isEmpty()) {
                    bag.append(bag.length() == 0 ? "" : " ").append(stack.getItem()).append('x').append(stack.getCount());
                }
            }
            String lugar;
            try {
                lugar = Entorno.lugar(world, v).palabra() + " (" + Entorno.profundidad(world, v) + " bajo la tierra)";
            } catch (RuntimeException e) {
                lugar = "? (" + e + ")";
            }
            return "en " + v.getBlockPos().toShortString() + ", lugar " + lugar + ", tarea " + task.debug() + ", haciendo: " + Retina.memory(v).doing()
                    + ", mano " + v.getMainHandStack().getItem() + ", mochila [" + bag + "]";
        }

        @Override
        void cleanup() {
            v.discard();
            for (BlockPos pos : hut) {
                Obras.broken(world, pos);
            }
        }
    }

    // ------------------------------------------------------------------ talking

    /**
     * With no internet at all: the villager's own mind understands plain orders and questions and carries them
     * out, the same way it does when a language model speaks for it.
     */
    private static final class IaLocal extends Scenario {
        private final VillagerEntityMCA v;
        private final net.minecraft.server.network.ServerPlayerEntity p;

        IaLocal(ServerWorld world) {
            super("ia-local", world, 200);
            BlockPos o = new BlockPos(3500, 100, 0);
            load(world, o, 8);
            fill(world, o.add(-5, -1, -5), o.add(5, -1, 5), Blocks.STONE.getDefaultState());
            fill(world, o.add(-5, 0, -5), o.add(5, 4, 5), Blocks.AIR.getDefaultState());
            v = villager(world, o, Direction.EAST, "Rosa Mente");
            p = fakePlayer(world, "Prueba_IA", Vec3d.ofBottomCenter(o.east(3)));
            v.getInventory().addStack(new ItemStack(Items.BREAD, 5));
            v.getInventory().addStack(new ItemStack(Items.IRON_INGOT, 3));
        }

        private String say(String msg) {
            String answer = new net.mca.entity.ai.chatAI.OpenAIChatAI().answer(p, v, msg).orElse("");
            MCA.LOGGER.info("PRUEBA ia-local: «" + msg + "» -> «" + answer + "»");
            return answer;
        }

        @Override
        Boolean step() {
            if (ticks < 40) {
                return null;
            }
            net.mca.entity.ai.chatAI.Cerebros.OFFLINE.set(true);
            try {
                String hello = say("Hola Rosa, sígueme por favor");
                boolean follows = v.getVillagerBrain().getMoveState() == MoveState.FOLLOW;
                say("quédate aquí");
                boolean stays = v.getVillagerBrain().getMoveState() == MoveState.STAY;
                say("¿Te animas a acompañarme un rato por el bosque?");
                boolean comes = v.getVillagerBrain().getMoveState() == MoveState.FOLLOW;
                String gave = say("dame 2 lingotes de hierro");
                boolean handed = v.getInventory().count(Items.IRON_INGOT) == 1;
                String bag = say("¿qué tienes en la mochila?");
                boolean knowsBag = bag.contains("pan") && bag.contains("lingote");
                String mine = say("busca diamantes");
                boolean mining = v.getVillagerBrain().getCurrentJob() == net.mca.entity.ai.Chore.PROSPECT
                        && "diamond".equals(ProspectingTask.wanted(v));
                v.getVillagerBrain().abandonJob();
                v.getVillagerBrain().setMoveState(MoveState.STAY, null);
                String english = say("what are you doing?");
                boolean inEnglish = english.contains("waiting") || english.contains("I'm") || english.contains("Nothing");
                String lost = say("dime algo bonito");
                boolean helps = lost.contains("Puedo seguirte");
                boolean passed = !hello.isEmpty() && follows && stays && comes && handed && gave.contains("hierro") && knowsBag && mining
                        && !mine.isEmpty() && inEnglish && helps;
                check(name, passed, "sin internet: me sigue " + follows + ", se queda " + stays + ", me acompaña " + comes + ", me dio 2 lingotes " + handed
                        + " («" + gave + "»), sabe lo que lleva " + knowsBag + ", va a minar buscando diamantes " + mining
                        + ", contesta en inglés " + inEnglish + ", si no entiende dice lo que sabe hacer " + helps);
                return passed;
            } finally {
                net.mca.entity.ai.chatAI.Cerebros.OFFLINE.set(false);
                net.mca.entity.ai.chatAI.OpenAIChatAI.forget(v.getUuid());
            }
        }

        @Override
        String state() {
            return "movimiento " + v.getVillagerBrain().getMoveState() + ", trabajo " + v.getVillagerBrain().getCurrentJob();
        }

        @Override
        void cleanup() {
            v.discard();
        }
    }

    /**
     * The free minds, from a real server on the internet: each one is asked once and the log says which answered;
     * then a real conversation and an order only a language model would understand.
     */
    private static final class IaRed extends Scenario {
        private static final String SYSTEM = "Eres un aldeano de Minecraft. Responde SOLO con un JSON {\"message\": \"lo que dices\", "
                + "\"optionalCommand\": \"\", \"argument\": \"\"}. Comandos: follow-player (seguir al jugador), stay-here (quedarse).";
        private final VillagerEntityMCA v;
        private final net.minecraft.server.network.ServerPlayerEntity p;
        private java.util.concurrent.CompletableFuture<Map<String, String>> probe;
        private java.util.concurrent.CompletableFuture<String> chat;
        private java.util.concurrent.CompletableFuture<String> order;
        private String minds = "";
        private String chatMind = "";

        IaRed(ServerWorld world) {
            super("ia-red", world, 3000);
            BlockPos o = new BlockPos(3600, 100, 0);
            load(world, o, 8);
            fill(world, o.add(-5, -1, -5), o.add(5, -1, 5), Blocks.STONE.getDefaultState());
            fill(world, o.add(-5, 0, -5), o.add(5, 4, 5), Blocks.AIR.getDefaultState());
            v = villager(world, o, Direction.EAST, "Tomás Red");
            p = fakePlayer(world, "Tropeles_CI", Vec3d.ofBottomCenter(o.east(3)));
            v.getInventory().addStack(new ItemStack(Items.BREAD, 5));
            v.getInventory().addStack(new ItemStack(Items.STONE_PICKAXE));
        }

        private java.util.concurrent.CompletableFuture<String> talk(String msg) {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> new net.mca.entity.ai.chatAI.OpenAIChatAI().answer(p, v, msg).orElse(""))
                    .exceptionally(e -> {
                        MCA.LOGGER.error("PRUEBA ia-red: excepción", e);
                        return "";
                    });
        }

        @Override
        Boolean step() {
            if (ticks < 40) {
                return null;
            }
            if (probe == null) {
                probe = java.util.concurrent.CompletableFuture.supplyAsync(() -> net.mca.entity.ai.chatAI.Cerebros.probe("Tropeles_CI", SYSTEM, "Hola, sígueme por favor"))
                        .exceptionally(e -> Map.of("error", e.toString()));
                return null;
            }
            if (!probe.isDone()) {
                return null;
            }
            if (chat == null) {
                Map<String, String> results = probe.join();
                results.forEach((mind, how) -> MCA.LOGGER.info("PRUEBA ia-red mente " + mind + ": " + how));
                minds = results.entrySet().stream().filter(e -> e.getValue().startsWith("ok")).map(Map.Entry::getKey).toList().toString();
                chat = talk("Hola Tomás, ¿cómo estás? ¿Qué llevas en la mochila?");
                return null;
            }
            if (!chat.isDone()) {
                return null;
            }
            if (order == null) {
                chatMind = net.mca.entity.ai.chatAI.Cerebros.last(v.getUuid());
                MCA.LOGGER.info("PRUEBA ia-red charla con " + chatMind + ": «" + chat.join() + "»");
                order = talk("Me da miedo cruzar el bosque solo esta noche, ¿me echas una mano y caminas a mi lado?");
                return null;
            }
            if (!order.isDone()) {
                return null;
            }
            String orderMind = net.mca.entity.ai.chatAI.Cerebros.last(v.getUuid());
            boolean follows = v.getVillagerBrain().getMoveState() == MoveState.FOLLOW;
            MCA.LOGGER.info("PRUEBA ia-red orden con " + orderMind + ": «" + order.join() + "», lo sigue: " + follows);
            boolean passed = !chat.join().isBlank() && !order.join().isBlank();
            check(name, passed, "mentes gratis que respondieron desde el servidor: " + minds + "; charla con " + chatMind
                    + "; orden que solo entiende la IA («caminas a mi lado») con " + orderMind + ", lo sigue: " + follows);
            return passed;
        }

        @Override
        String state() {
            return "sonda " + (probe != null && probe.isDone()) + ", charla " + (chat != null && chat.isDone()) + ", orden " + (order != null && order.isDone());
        }

        @Override
        void cleanup() {
            v.discard();
            net.mca.entity.ai.chatAI.OpenAIChatAI.forget(v.getUuid());
        }
    }
}
