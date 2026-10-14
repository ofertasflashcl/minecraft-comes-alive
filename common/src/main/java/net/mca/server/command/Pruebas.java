package net.mca.server.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.mca.MCA;
import net.mca.entity.ai.relationship.Gender;
import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.VillagerFactory;
import net.mca.entity.ai.MoveState;
import net.mca.entity.ai.body.Archery;
import net.mca.entity.ai.body.Mining;
import net.mca.entity.ai.brain.tasks.FollowTask;
import net.mca.entity.ai.brain.tasks.chore.ProspectingTask;
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
                case "resumen" -> resumen();
                default -> {
                    ctx.getSource().sendError(Text.literal("ojo | minar | espada | arco | resumen"));
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
        VillagerEntityMCA villager = VillagerFactory.newVillager(world)
                .withGender(Gender.MALE)
                .withAge(0)
                .withName("Prueba")
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
     * A bow villager on a platform; two zombies it cannot walk to, one 15 blocks away level, one 24 away and
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
            fill(world, o.add(23, -6, 2), o.add(25, -6, 4), Blocks.STONE.getDefaultState());
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
            far = zombie(world, Vec3d.ofBottomCenter(o.add(24, -5, 3)), false);
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
            check(name, shots <= 12, "dos zombis muertos (15 y 24 bloques, uno 5 más abajo) con " + shots + " flechas en " + ticks + " ticks");
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

}
