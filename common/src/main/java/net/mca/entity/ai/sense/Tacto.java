package net.mca.entity.ai.sense;

import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Touch: what is right against the body, the way a person knows a ceiling is over their head without looking up.
 * A handful of block look-ups around the head and the feet, no rays: the first solid block over the head within
 * {@value #OVER} blocks, the blocks on each side at the height of the head, what the feet stand on, and how many
 * steps away the open sky is at this same level. {@link Entorno} uses it to tell a roof at the level of the land
 * from the rock of a mine; the chat reads {@link #describeOverhead(LivingEntity, boolean)}.
 * <p>
 * "Solid" is what the body would bump into: any block with a collision shape (leaves and glass included, plants,
 * torches and fluids not).
 */
public final class Tacto {
    /** How far over the head a ceiling is felt. */
    public static final int OVER = 4;
    /** How far under the feet the floor is looked for. */
    public static final int UNDER = 3;
    /** How far to the side the open sky is looked for (horizontal steps through air). */
    public static final int SIDE_SKY = 6;

    private Tacto() {
    }

    /** One block felt: where, what, and how many blocks from the head (or the feet). */
    public record Toque(BlockPos pos, BlockState state, int distancia) {
        public String nombre(boolean es) {
            return Tacto.nombre(state, es);
        }
    }

    public static BlockPos head(LivingEntity viewer) {
        return BlockPos.ofFloored(viewer.getEyePos());
    }

    static boolean solid(World world, BlockPos pos, BlockState state) {
        return !state.isAir() && !state.getCollisionShape(world, pos).isEmpty();
    }

    /** The first solid block over the head within {@value #OVER}, or null when the space over it is free. */
    @Nullable
    public static Toque techo(World world, LivingEntity viewer) {
        return techo(world, viewer, OVER);
    }

    @Nullable
    public static Toque techo(World world, LivingEntity viewer, int reach) {
        BlockPos head = head(viewer);
        BlockPos.Mutable cell = head.mutableCopy();
        for (int d = 1; d <= reach; d++) {
            cell.setY(head.getY() + d);
            if (cell.getY() >= world.getTopY()) {
                return null;
            }
            BlockState state = world.getBlockState(cell);
            if (solid(world, cell, state)) {
                return new Toque(cell.toImmutable(), state, d);
            }
        }
        return null;
    }

    /** The solid blocks touching the head on its four sides (a side with nothing solid is left out). */
    public static Map<Direction, Toque> lados(World world, LivingEntity viewer) {
        return sides(world, head(viewer));
    }

    /** The same at the height of the feet. */
    public static Map<Direction, Toque> ladosPies(World world, LivingEntity viewer) {
        return sides(world, viewer.getBlockPos());
    }

    private static Map<Direction, Toque> sides(World world, BlockPos at) {
        Map<Direction, Toque> found = new EnumMap<>(Direction.class);
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos pos = at.offset(dir);
            BlockState state = world.getBlockState(pos);
            if (solid(world, pos, state)) {
                found.put(dir, new Toque(pos, state, 1));
            }
        }
        return found;
    }

    /** What the feet stand on: the first solid block under them within {@value #UNDER}, or null (a drop). */
    @Nullable
    public static Toque suelo(World world, LivingEntity viewer) {
        BlockPos feet = viewer.getBlockPos();
        BlockPos.Mutable cell = feet.mutableCopy();
        // standing on a slab or a path the feet are inside the block they stand on
        BlockState at = world.getBlockState(feet);
        if (solid(world, feet, at)) {
            return new Toque(feet.toImmutable(), at, 0);
        }
        for (int d = 1; d <= UNDER; d++) {
            cell.setY(feet.getY() - d);
            if (cell.getY() < world.getBottomY()) {
                return null;
            }
            BlockState state = world.getBlockState(cell);
            if (solid(world, cell, state) || !state.getFluidState().isEmpty()) {
                return new Toque(cell.toImmutable(), state, d);
            }
        }
        return null;
    }

    /**
     * How many steps to the side, at the level of the head, the open sky is (a column with full sky light, which only
     * the sky straight above gives): walking out through cells the body fits in, in the four directions and the four
     * diagonals, at most {@code reach}. 0 when the sky is right over the head, -1 when none was found.
     */
    public static int cieloAlLado(World world, LivingEntity viewer, int reach) {
        BlockPos head = head(viewer);
        if (world.getLightLevel(LightType.SKY, head) >= 15) {
            return 0;
        }
        int best = -1;
        BlockPos.Mutable cell = new BlockPos.Mutable();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int step = 1; step <= reach && (best < 0 || step < best); step++) {
                    cell.set(head.getX() + dx * step, head.getY(), head.getZ() + dz * step);
                    BlockState state = world.getBlockState(cell);
                    if (solid(world, cell, state)) {
                        break;
                    }
                    if (world.getLightLevel(LightType.SKY, cell) >= 15) {
                        best = step;
                        break;
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ words

    /**
     * What the body feels around it, one or two sentences in the villager's voice: what is over its head and how
     * high, what touches it on each side at the height of the head (by where it faces), what it stands on, and how
     * far the open sky is. For the chat.
     */
    public static String describeOverhead(LivingEntity viewer, boolean es) {
        World world = viewer.getWorld();
        StringBuilder sb = new StringBuilder();
        Toque over = techo(world, viewer);
        if (over == null) {
            boolean sky = world.getLightLevel(LightType.SKY, head(viewer).up()) >= 15;
            sb.append(es ? (sky ? "No tengo nada sobre la cabeza, arriba está el cielo." : "No tengo nada encima en " + OVER + " bloques.")
                    : (sky ? "There's nothing over my head, just the sky." : "Nothing over my head for " + OVER + " blocks."));
        } else {
            String what = over.nombre(es);
            if (es) {
                sb.append(over.distancia() == 1 ? "Tengo " + what + " justo sobre la cabeza." : "Tengo " + what + " sobre mí, a " + over.distancia() + " bloques.");
            } else {
                sb.append(over.distancia() == 1 ? "There's " + what + " right over my head." : "There's " + what + " over me, " + over.distancia() + " blocks up.");
            }
        }
        Map<Direction, Toque> sides = lados(world, viewer);
        Direction ahead = Direction.fromRotation(viewer.getBodyYaw());
        List<String> walls = new ArrayList<>();
        List<String> open = new ArrayList<>();
        for (Direction dir : new Direction[]{ahead, ahead.rotateYClockwise(), ahead.rotateYCounterclockwise(), ahead.getOpposite()}) {
            String side = side(ahead, dir, es);
            Toque t = sides.get(dir);
            if (t != null) {
                walls.add(t.nombre(es) + " " + side);
            } else {
                open.add(side);
            }
        }
        if (!walls.isEmpty()) {
            sb.append(es ? " A la altura de la cabeza toco " : " At head height I touch ").append(join(walls, es));
            sb.append(open.isEmpty() ? "" : (es ? "; libre " : "; open ") + join(open, es)).append('.');
        }
        Toque floor = suelo(world, viewer);
        if (floor == null) {
            sb.append(es ? " Bajo los pies no hay nada en " + UNDER + " bloques." : " There's nothing under my feet for " + UNDER + " blocks.");
        } else {
            sb.append(es ? " Piso " : " I'm standing on ").append(floor.nombre(es)).append('.');
        }
        if (over != null) {
            int sky = cieloAlLado(world, viewer, SIDE_SKY);
            if (sky > 0) {
                sb.append(es ? " El cielo abierto está a " + sky + (sky == 1 ? " paso" : " pasos") + " a un lado." : " The open sky is " + sky + (sky == 1 ? " step" : " steps") + " to one side.");
            }
        }
        return sb.toString().trim();
    }

    private static String side(Direction ahead, Direction dir, boolean es) {
        if (dir == ahead) {
            return es ? "delante" : "ahead";
        }
        if (dir == ahead.getOpposite()) {
            return es ? "detrás" : "behind";
        }
        if (dir == ahead.rotateYClockwise()) {
            return es ? "a la derecha" : "to the right";
        }
        return es ? "a la izquierda" : "to the left";
    }

    private static String join(List<String> parts, boolean es) {
        if (parts.size() <= 1) {
            return parts.isEmpty() ? "" : parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + (es ? " y " : " and ") + parts.get(parts.size() - 1);
    }

    /**
     * A block's name as the villager says it: Spanish words for the common ones (with an article), the game's own
     * name otherwise (the server's language is English).
     */
    public static String nombre(BlockState state, boolean es) {
        if (state.isOf(net.minecraft.block.Blocks.WATER)) {
            return es ? "agua" : "water";
        }
        if (state.isOf(net.minecraft.block.Blocks.LAVA)) {
            return "lava";
        }
        String path = Registries.BLOCK.getId(state.getBlock()).getPath();
        if (!es) {
            return state.getBlock().getName().getString().toLowerCase(java.util.Locale.ROOT);
        }
        String wood = woodEs(path);
        if (state.isIn(BlockTags.LEAVES)) {
            return "hojas" + wood;
        }
        if (state.isIn(BlockTags.LOGS)) {
            return "un tronco" + wood;
        }
        if (path.endsWith("_planks")) {
            return "tablones" + wood;
        }
        if (path.endsWith("_stairs")) {
            return "escalones" + material(path.substring(0, path.length() - 7));
        }
        if (path.endsWith("_slab")) {
            return "una losa" + material(path.substring(0, path.length() - 5));
        }
        if (path.endsWith("_ore")) {
            return "un mineral";
        }
        if (path.endsWith("_wool")) {
            return "lana";
        }
        if (path.endsWith("_glass") || path.equals("glass") || path.endsWith("_pane")) {
            return "vidrio";
        }
        if (path.endsWith("_fence")) {
            return "una cerca";
        }
        if (path.endsWith("_door") || path.endsWith("_trapdoor")) {
            return path.endsWith("_trapdoor") ? "una trampilla" : "una puerta";
        }
        return switch (path) {
            case "stone" -> "piedra";
            case "cobblestone", "mossy_cobblestone" -> "adoquín";
            case "deepslate", "cobbled_deepslate" -> "pizarra profunda";
            case "dirt", "coarse_dirt", "rooted_dirt" -> "tierra";
            case "grass_block" -> "pasto";
            case "dirt_path" -> "un camino de tierra";
            case "farmland" -> "tierra arada";
            case "sand", "red_sand" -> "arena";
            case "sandstone", "red_sandstone", "smooth_sandstone", "cut_sandstone" -> "arenisca";
            case "gravel" -> "grava";
            case "clay" -> "arcilla";
            case "andesite", "polished_andesite" -> "andesita";
            case "diorite", "polished_diorite" -> "diorita";
            case "granite", "polished_granite" -> "granito";
            case "tuff" -> "toba";
            case "calcite" -> "calcita";
            case "bricks" -> "ladrillos";
            case "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks" -> "ladrillos de piedra";
            case "snow", "snow_block" -> "nieve";
            case "ice", "packed_ice", "blue_ice" -> "hielo";
            case "bedrock" -> "roca madre";
            case "obsidian" -> "obsidiana";
            case "netherrack" -> "netherrack";
            case "ladder" -> "una escalera de mano";
            case "torch", "wall_torch" -> "una antorcha";
            case "hay_block" -> "un fardo de paja";
            case "bookshelf" -> "una estantería";
            case "crafting_table" -> "una mesa de trabajo";
            case "furnace" -> "un horno";
            case "chest", "barrel" -> "un cofre";
            case "glowstone" -> "piedra luminosa";
            case "barrier" -> "una barrera";
            case "terracotta" -> "terracota";
            case "moss_block" -> "musgo";
            case "mud", "packed_mud", "mud_bricks" -> "barro";
            default -> state.getBlock().getName().getString().toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String woodEs(String path) {
        if (path.startsWith("dark_oak")) return " de roble oscuro";
        if (path.startsWith("oak") || path.startsWith("stripped_oak")) return " de roble";
        if (path.contains("birch")) return " de abedul";
        if (path.contains("spruce")) return " de abeto";
        if (path.contains("jungle")) return " de la jungla";
        if (path.contains("acacia")) return " de acacia";
        if (path.contains("mangrove")) return " de mangle";
        if (path.contains("cherry")) return " de cerezo";
        if (path.contains("azalea")) return " de azalea";
        return "";
    }

    private static String material(String base) {
        String wood = woodEs(base);
        if (!wood.isEmpty()) {
            return wood.replace(" de ", " de madera de ");
        }
        return switch (base) {
            case "stone", "smooth_stone" -> " de piedra";
            case "cobblestone", "mossy_cobblestone" -> " de adoquín";
            case "stone_brick", "mossy_stone_brick" -> " de ladrillo de piedra";
            case "brick" -> " de ladrillo";
            case "sandstone", "smooth_sandstone", "red_sandstone" -> " de arenisca";
            case "cobbled_deepslate", "deepslate_brick", "deepslate_tile", "polished_deepslate" -> " de pizarra";
            default -> "";
        };
    }
}
