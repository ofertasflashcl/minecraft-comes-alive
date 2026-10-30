package net.mca.entity.ai.sense;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.body.Mining;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * A picture of what the villager's eye sees right now, for a language model that can look at images: one ray per
 * pixel from the eye through the same perspective as {@link Retina}, coloured by each block's map colour with face
 * shading, light and distance haze, the sky by the time of day, water tinted, bodies in their own colours. Encoded
 * as PNG with no image library (deflate and CRC from java.util.zip). Server thread.
 * <p>
 * The rays walk the voxel grid themselves (a plain DDA over the chunk's own block storage, skipping every cell
 * above the column's highest block), and a block is taken as a whole cube: the picture is a map-coloured
 * sketch, not the exact outline view of the {@link Retina}. Blocks with no body (grass, flowers, torches) and
 * clear ones (glass) are looked through. 160x90 is 14,400 rays.
 * <p>
 * Drawn to be read by a vision model, the way a player reads the game: every block face carries a faint texture
 * of its own (so the grid of blocks shows), ores keep the colour of their specks (iron tan, diamond cyan...), a lit
 * torch or lantern is a small bright dot, the outlines where the depth jumps are drawn dark (where one thing stands
 * in front of another), and a small crosshair marks where the eye points.
 * <p>
 * When the server runs inside the client the game's own block textures are on the class path, and the picture is
 * painted with them instead ({@link Texturas}, {@link #rayTex}): the real texel of each face, tinted by the biome, with
 * holes in leaves and glass and plants drawn where their model is. On a dedicated server it is the map-coloured sketch.
 */
public final class Foto {
    /** Larger pictures are refused. */
    public static final int MAX_PIXELS = 640 * 360;
    static final int WATER = 0x2F5FD0;
    static final int LAVA = 0xE0601A;

    private Foto() {
    }

    /** PNG bytes of a {@code width}x{@code height} view, or null when it could not be made. */
    @Nullable
    public static byte[] png(ServerWorld world, LivingEntity viewer, int width, int height) {
        if (width <= 0 || height <= 0 || width * height > MAX_PIXELS) {
            return null;
        }
        int[] rgb = render(world, viewer, width, height);
        return encode(rgb, width, height);
    }

    /** The same as a data URL ("data:image/png;base64,..."), or null. */
    @Nullable
    public static String dataUrl(ServerWorld world, LivingEntity viewer, int width, int height) {
        byte[] png = png(world, viewer, width, height);
        return png == null ? null : "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }

    // ---------------------------------------------------------------- rendering

    static int[] render(ServerWorld world, LivingEntity viewer, int w, int h) {
        Vec3d eye = Retina.eye(viewer);
        Vec3d forward = Retina.forward(viewer);
        Vec3d[] axes = Retina.axes(forward);
        Vec3d right = axes[0];
        Vec3d up = axes[1];
        double tanV = Math.tan(Math.toRadians(Retina.VERTICAL_FOV / 2.0));
        double tanH = tanV * w / (double) h;
        double range = Retina.range(viewer);
        boolean night = Retina.nightVision(viewer);
        int sky = skyColor(world);

        // bodies first: a depth buffer of the boxes, each only over the pixels it can cover
        double[] bodyT = new double[w * h];
        int[] bodyColor = new int[w * h];
        Arrays.fill(bodyT, Double.POSITIVE_INFINITY);
        bodies(world, viewer, eye, forward, right, up, tanH, tanV, w, h, Math.min(range, Retina.ENTITY_RANGE), bodyT, bodyColor);

        int bottom = world.getBottomY();
        int top = world.getTopY();
        // the game's own textures when the server runs inside the client; null on a dedicated one (the map colours)
        Texturas.Pincel brush = Texturas.pincel(world);
        double footprint = 2.0 * tanV / h * Texturas.SIZE;
        BlockPos.Mutable cell = new BlockPos.Mutable();
        BlockPos.Mutable from = new BlockPos.Mutable();
        int[] out = new int[w * h];
        double[] depth = new double[w * h];
        for (int row = 0; row < h; row++) {
            double v = (1.0 - (row + 0.5) / h * 2.0) * tanV;
            for (int col = 0; col < w; col++) {
                double u = ((col + 0.5) / w * 2.0 - 1.0) * tanH;
                double dx = forward.x + right.x * u + up.x * v;
                double dy = forward.y + right.y * u + up.y * v;
                double dz = forward.z + right.z * u + up.z * v;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                dx /= len;
                dy /= len;
                dz /= len;
                int i = row * w + col;
                out[i] = brush == null ? ray(world, eye, dx, dy, dz, range, night, sky, bottom, top, cell, from, bodyT[i], bodyColor[i], depth, i)
                        : rayTex(brush, footprint, world, eye, dx, dy, dz, range, night, sky, bottom, top, cell, from, bodyT[i], bodyColor[i], depth, i);
            }
        }
        outline(out, depth, w, h);
        crosshair(out, w, h);
        return out;
    }

    /** Dark lines where the depth jumps: the edge of one thing in front of another, the horizon, a body's outline. */
    static void outline(int[] rgb, double[] depth, int w, int h) {
        boolean[] edge = new boolean[w * h];
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int i = row * w + col;
                double d = depth[i];
                if (col + 1 < w && jump(d, depth[i + 1])) {
                    edge[Double.isInfinite(d) || d > depth[i + 1] ? i + 1 : i] = true;
                }
                if (row + 1 < h && jump(d, depth[i + w])) {
                    edge[Double.isInfinite(d) || d > depth[i + w] ? i + w : i] = true;
                }
            }
        }
        for (int i = 0; i < rgb.length; i++) {
            if (edge[i]) {
                rgb[i] = scale(rgb[i], 0.55);
            }
        }
    }

    private static boolean jump(double a, double b) {
        if (Double.isInfinite(a) || Double.isInfinite(b)) {
            return Double.isInfinite(a) != Double.isInfinite(b);
        }
        return Math.abs(a - b) > Math.max(0.75, 0.12 * Math.min(a, b));
    }

    /** A small plus in the middle, drawn inverted like the game's own. */
    static void crosshair(int[] rgb, int w, int h) {
        int cx = w / 2;
        int cy = h / 2;
        int arm = Math.max(2, Math.min(w, h) / 40);
        for (int k = -arm; k <= arm; k++) {
            invert(rgb, w, h, cx + k, cy);
            if (k != 0) {
                invert(rgb, w, h, cx, cy + k);
            }
        }
    }

    private static void invert(int[] rgb, int w, int h, int x, int y) {
        if (x >= 0 && y >= 0 && x < w && y < h) {
            rgb[y * w + x] = 0xFFFFFF - rgb[y * w + x];
        }
    }

    /** A faint shade of its own for each block, from its position: the grid of blocks shows, like textures do. */
    private static double texture(int x, int y, int z) {
        int hsh = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        hsh ^= hsh >>> 13;
        return 0.93 + (hsh & 15) / 15.0 * 0.14;
    }

    /** The colour of an ore's specks, mixed into the stone it sits in; -1 for anything else. */
    private static int oreColor(BlockState state) {
        if (!Mining.isOre(state)) {
            return -1;
        }
        return switch (Escena.oreKey(state)) {
            case "coal" -> 0x1A1A1A;
            case "iron" -> 0xD8AF93;
            case "copper" -> 0xE0734D;
            case "gold" -> 0xFCEE4B;
            case "redstone" -> 0xE01010;
            case "lapis" -> 0x1F4FBF;
            case "diamond" -> 0x5DECF5;
            case "emerald" -> 0x17DD62;
            case "quartz" -> 0xEDE6DC;
            default -> 0xB050B0;
        };
    }

    private static int ray(ServerWorld world, Vec3d eye, double dx, double dy, double dz, double range, boolean night, int sky,
                           int bottom, int top, BlockPos.Mutable cell, BlockPos.Mutable from, double bodyT, int bodyColor, double[] depth, int index) {
        int x = (int) Math.floor(eye.x);
        int y = (int) Math.floor(eye.y);
        int z = (int) Math.floor(eye.z);
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? x + 1 - eye.x : eye.x - x) * tDeltaX;
        double tMaxY = dy == 0 ? Double.POSITIVE_INFINITY : (dy > 0 ? y + 1 - eye.y : eye.y - y) * tDeltaY;
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : (dz > 0 ? z + 1 - eye.z : eye.z - z) * tDeltaZ;

        double t = 0;
        int axis = -1;
        int water = 0;
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;
        Chunk chunk = null;
        int columnX = Integer.MIN_VALUE;
        int columnZ = Integer.MIN_VALUE;
        int columnTop = 0;
        int color = -1;
        double hitT = range;
        boolean under = false;
        while (t <= range && t < bodyT) {
            if (y >= top && dy >= 0 || y < bottom && dy <= 0) {
                break;
            }
            if (y >= bottom && y < top) {
                int cx = x >> 4;
                int cz = z >> 4;
                if (cx != chunkX || cz != chunkZ) {
                    chunkX = cx;
                    chunkZ = cz;
                    chunk = world.isChunkLoaded(cx, cz) ? world.getChunk(cx, cz) : null;
                }
                if (chunk == null) {
                    break; // the eye's world ends where nothing is loaded
                }
                if (x != columnX || z != columnZ) {
                    columnX = x;
                    columnZ = z;
                    columnTop = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15);
                }
                under = y < columnTop;
                if (under) {
                    cell.set(x, y, z);
                    BlockState state = chunk.getBlockState(cell);
                    if (!state.isAir()) {
                        FluidState fluid = state.getFluidState();
                        boolean stop = false;
                        if (!fluid.isEmpty()) {
                            if (fluid.isIn(FluidTags.LAVA)) {
                                color = LAVA;
                                stop = true;
                            } else if (fluid.isIn(FluidTags.WATER)) {
                                water++;
                            }
                        }
                        if (!stop && fluid.getBlockState().getBlock() != state.getBlock() && state.blocksMovement()) {
                            MapColor map = state.getMapColor(world, cell);
                            if (map != MapColor.CLEAR && map.color != 0) {
                                color = scale(map.color, texture(x, y, z));
                                int ore = oreColor(state);
                                if (ore >= 0) {
                                    color = mix(color, ore, 0.6);
                                }
                                stop = true;
                            }
                        } else if (!stop && state.getLuminance() >= 10 && !state.blocksMovement() && fluid.isEmpty()) {
                            // a torch, a lantern: a small bright dot where the ray passes close to its flame
                            double tx = x + 0.5 - eye.x;
                            double ty = y + 0.45 - eye.y;
                            double tz = z + 0.5 - eye.z;
                            double along = tx * dx + ty * dy + tz * dz;
                            double ox = tx - dx * along;
                            double oy = ty - dy * along;
                            double oz = tz - dz * along;
                            if (along > 0 && ox * ox + oy * oy + oz * oz < 0.2 * 0.2) {
                                hitT = along;
                                color = 0xFFD24A;
                                break;
                            }
                        }
                        if (stop) {
                            hitT = t;
                            // light on the face it came in through
                            if (axis < 0) {
                                from.set(x, y, z);
                            } else {
                                from.set(axis == 0 ? x - stepX : x, axis == 1 ? y - stepY : y, axis == 2 ? z - stepZ : z);
                            }
                            int light = Math.max(world.getLightLevel(from), state.getLuminance());
                            double shade = face(axis, stepY) * lightShade(light, t, night);
                            if (color == LAVA) {
                                shade = 1.0;
                            }
                            color = scale(color, shade);
                            break;
                        }
                    }
                }
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
                axis = 0;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
                axis = 1;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                axis = 2;
            }
        }
        if (bodyT < hitT && bodyT <= range) {
            color = bodyColor;
            hitT = bodyT;
        }
        depth[index] = color < 0 ? Double.POSITIVE_INFINITY : hitT;
        if (color < 0) {
            if (under && water == 0 && !night) {
                return 0x050505; // the far end of a cave: no sky down here, only the dark
            }
            return water > 0 ? mix(WATER, sky, 0.25) : sky;
        }
        double haze = Math.min(1.0, hitT / range);
        color = mix(color, sky, haze * haze * 0.7);
        if (water > 0) {
            color = mix(color, WATER, Math.min(0.85, 0.35 + 0.08 * water));
        }
        return color;
    }

    /**
     * The same ray with the game's textures: the texel where the ray meets each face (16x16, a smaller mip level the
     * farther it is), tinted by the biome; holes in leaves and glass let it go on, a stained pane or ice tints what
     * is behind, and plants, torches, rails and slabs are met where their model really is (the planes of a flower,
     * the box of a slab). The light, the face shading, the haze and the water are the same as {@link #ray}.
     */
    private static int rayTex(Texturas.Pincel brush, double footprint, ServerWorld world, Vec3d eye, double dx, double dy, double dz, double range,
                              boolean night, int sky, int bottom, int top, BlockPos.Mutable cell, BlockPos.Mutable from, double bodyT, int bodyColor,
                              double[] depth, int index) {
        int x = (int) Math.floor(eye.x);
        int y = (int) Math.floor(eye.y);
        int z = (int) Math.floor(eye.z);
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? x + 1 - eye.x : eye.x - x) * tDeltaX;
        double tMaxY = dy == 0 ? Double.POSITIVE_INFINITY : (dy > 0 ? y + 1 - eye.y : eye.y - y) * tDeltaY;
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : (dz > 0 ? z + 1 - eye.z : eye.z - z) * tDeltaZ;

        double t = 0;
        int axis = -1;
        int water = 0;
        int waterColor = WATER;
        int veil = 0;
        double veilAlpha = 0;
        double veilT = Double.POSITIVE_INFINITY;
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;
        Chunk chunk = null;
        int columnX = Integer.MIN_VALUE;
        int columnZ = Integer.MIN_VALUE;
        int columnTop = 0;
        int color = -1;
        double hitT = range;
        boolean under = false;
        while (t <= range && t < bodyT) {
            if (y >= top && dy >= 0 || y < bottom && dy <= 0) {
                break;
            }
            if (y >= bottom && y < top) {
                int cx = x >> 4;
                int cz = z >> 4;
                if (cx != chunkX || cz != chunkZ) {
                    chunkX = cx;
                    chunkZ = cz;
                    chunk = world.isChunkLoaded(cx, cz) ? world.getChunk(cx, cz) : null;
                }
                if (chunk == null) {
                    break;
                }
                if (x != columnX || z != columnZ) {
                    columnX = x;
                    columnZ = z;
                    columnTop = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15);
                }
                under = y < columnTop;
                if (under) {
                    cell.set(x, y, z);
                    BlockState state = chunk.getBlockState(cell);
                    if (!state.isAir()) {
                        FluidState fluid = state.getFluidState();
                        boolean lava = !fluid.isEmpty() && fluid.isIn(FluidTags.LAVA);
                        boolean plainFluid = !fluid.isEmpty() && fluid.getBlockState().getBlock() == state.getBlock();
                        if (!fluid.isEmpty() && fluid.isIn(FluidTags.WATER)) {
                            if (water == 0) {
                                int wc = brush.water(state, x, y, z);
                                if (wc >= 0) {
                                    waterColor = wc;
                                }
                            }
                            water++;
                        }
                        Direction side = axis < 0 ? Direction.getFacing(-dx, -dy, -dz) : switch (axis) {
                            case 0 -> stepX > 0 ? Direction.WEST : Direction.EAST;
                            case 1 -> stepY > 0 ? Direction.DOWN : Direction.UP;
                            default -> stepZ > 0 ? Direction.NORTH : Direction.SOUTH;
                        };
                        int hit = -1;
                        double at = t;
                        boolean inside = false; // met inside the cell (a plant, a slab): lit by the cell itself
                        if (lava) {
                            hit = brush.color(fluid.getBlockState(), side, x, y, z, 0.5, 0.5, 0.5, Texturas.level(t * footprint));
                            if (hit < 0) {
                                hit = LAVA;
                            }
                        } else if (plainFluid) {
                            // water: looked through, tinted at the end
                        } else if (!brush.textured(state)) {
                            // no texture for it: the map colour, as without textures
                            if (state.blocksMovement()) {
                                MapColor map = state.getMapColor(world, cell);
                                if (map != MapColor.CLEAR && map.color != 0) {
                                    hit = scale(map.color, texture(x, y, z));
                                    int ore = oreColor(state);
                                    if (ore >= 0) {
                                        hit = mix(hit, ore, 0.6);
                                    }
                                }
                            }
                        } else if (state.blocksMovement() && brush.full(state) && !brush.cross(state)) {
                            // a whole cube: the texel where the ray comes in
                            Texturas.Cara c = brush.cara(state, side);
                            if (c != null) {
                                int argb = brush.texel(c, x, y, z, eye.x + dx * t - x, eye.y + dy * t - y, eye.z + dz * t - z, Texturas.level(t * footprint));
                                int alpha = argb >>> 24;
                                if (alpha >= Texturas.OPAQUE) {
                                    hit = argb & 0xFFFFFF;
                                } else if (alpha >= Texturas.HOLE && t < veilT) {
                                    veil = argb & 0xFFFFFF;
                                    veilAlpha = alpha / 255.0 * 0.85;
                                    veilT = t;
                                }
                            }
                        } else {
                            // its model's own shape: the planes of a cross, the box of a slab, a torch, a rail
                            int argb = brush.shape(state, x, y, z, eye.x, eye.y, eye.z, dx, dy, dz, footprint);
                            double st = brush.t;
                            int alpha = argb >>> 24;
                            if (st <= range && st < bodyT && alpha >= Texturas.HOLE) {
                                if (alpha >= Texturas.OPAQUE) {
                                    hit = argb & 0xFFFFFF;
                                    at = st;
                                    side = brush.side;
                                    inside = true;
                                } else if (st < veilT) {
                                    veil = argb & 0xFFFFFF;
                                    veilAlpha = alpha / 255.0 * 0.85;
                                    veilT = st;
                                }
                            }
                        }
                        if (hit >= 0) {
                            hitT = at;
                            int light;
                            if (inside) {
                                light = Math.max(world.getLightLevel(cell), state.getLuminance());
                            } else {
                                if (axis < 0) {
                                    from.set(x, y, z);
                                } else {
                                    from.set(axis == 0 ? x - stepX : x, axis == 1 ? y - stepY : y, axis == 2 ? z - stepZ : z);
                                }
                                light = Math.max(world.getLightLevel(from), state.getLuminance());
                            }
                            double shade = lava || state.getLuminance() >= 10 && inside ? 1.0 : faceShade(side) * lightShade(light, at, night);
                            color = scale(hit, shade);
                            break;
                        }
                    }
                }
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
                axis = 0;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
                axis = 1;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                axis = 2;
            }
        }
        if (bodyT < hitT && bodyT <= range) {
            color = bodyColor;
            hitT = bodyT;
        }
        depth[index] = color < 0 ? Double.POSITIVE_INFINITY : hitT;
        int result;
        if (color < 0) {
            result = under && water == 0 && !night ? 0x050505 : water > 0 ? mix(waterColor, sky, 0.25) : sky;
        } else {
            double haze = Math.min(1.0, hitT / range);
            result = mix(color, sky, haze * haze * 0.7);
            if (water > 0) {
                result = mix(result, waterColor, Math.min(0.85, 0.35 + 0.08 * water));
            }
        }
        if (veilT < (color < 0 ? Double.POSITIVE_INFINITY : hitT)) {
            result = mix(result, veil, veilAlpha);
        }
        return result;
    }

    /** The flat shading of a side; a cross plane (null) a little under full. */
    private static double faceShade(@Nullable Direction side) {
        if (side == null) {
            return 0.9;
        }
        return switch (side) {
            case UP -> 1.0;
            case DOWN -> 0.5;
            case NORTH, SOUTH -> 0.8;
            default -> 0.6;
        };
    }

    /** Like the game's own flat shading: tops full, north and south faces 0.8, east and west 0.6, bottoms 0.5. */
    private static double face(int axis, int stepY) {
        return switch (axis) {
            case 0 -> 0.6;
            case 1 -> stepY < 0 ? 1.0 : 0.5;
            case 2 -> 0.8;
            default -> 1.0;
        };
    }

    static double lightShade(int light, double distance, boolean night) {
        if (night) {
            return 1.0;
        }
        double shade = 0.12 + 0.88 * light / 15.0;
        if (distance <= Retina.DARK_SIGHT) {
            shade = Math.max(shade, 0.35); // up close the eye always makes out something
        } else if (light < Retina.MIN_LIGHT) {
            shade = 0.05;
        }
        return shade;
    }

    private static void bodies(ServerWorld world, LivingEntity viewer, Vec3d eye, Vec3d forward, Vec3d right, Vec3d up, double tanH, double tanV,
                               int w, int h, double range, double[] bodyT, int[] bodyColor) {
        List<Entity> bodies = world.getOtherEntities(viewer, new Box(eye, eye).expand(range),
                e -> !e.isSpectator() && e.isAlive() && !e.isInvisible() && (e instanceof LivingEntity || e instanceof ItemEntity));
        if (bodies.size() > 32) {
            bodies.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(eye)));
            bodies = bodies.subList(0, 32);
        }
        boolean night = Retina.nightVision(viewer);
        for (Entity body : bodies) {
            Box box = body.getBoundingBox();
            int c0 = 0, c1 = w - 1, r0 = 0, r1 = h - 1;
            double minC = Double.MAX_VALUE, maxC = -Double.MAX_VALUE, minR = Double.MAX_VALUE, maxR = -Double.MAX_VALUE;
            boolean behind = false;
            for (int k = 0; k < 8 && !behind; k++) {
                double px = (k & 1) == 0 ? box.minX : box.maxX;
                double py = (k & 2) == 0 ? box.minY : box.maxY;
                double pz = (k & 4) == 0 ? box.minZ : box.maxZ;
                double ex = px - eye.x, ey = py - eye.y, ez = pz - eye.z;
                double zc = ex * forward.x + ey * forward.y + ez * forward.z;
                if (zc <= 0.05) {
                    behind = true;
                    break;
                }
                double u = (ex * right.x + ey * right.y + ez * right.z) / zc;
                double v = (ex * up.x + ey * up.y + ez * up.z) / zc;
                double col = (u / tanH + 1.0) / 2.0 * w - 0.5;
                double row = (1.0 - v / tanV) / 2.0 * h - 0.5;
                minC = Math.min(minC, col);
                maxC = Math.max(maxC, col);
                minR = Math.min(minR, row);
                maxR = Math.max(maxR, row);
            }
            if (!behind) {
                c0 = Math.max(0, (int) Math.floor(minC) - 1);
                c1 = Math.min(w - 1, (int) Math.ceil(maxC) + 1);
                r0 = Math.max(0, (int) Math.floor(minR) - 1);
                r1 = Math.min(h - 1, (int) Math.ceil(maxR) + 1);
                if (c0 > c1 || r0 > r1) {
                    continue;
                }
            }
            int light = world.getLightLevel(BlockPos.ofFloored(box.getCenter()));
            int base = bodyColor(body);
            for (int row = r0; row <= r1; row++) {
                double v = (1.0 - (row + 0.5) / h * 2.0) * tanV;
                for (int col = c0; col <= c1; col++) {
                    double u = ((col + 0.5) / w * 2.0 - 1.0) * tanH;
                    double dx = forward.x + right.x * u + up.x * v;
                    double dy = forward.y + right.y * u + up.y * v;
                    double dz = forward.z + right.z * u + up.z * v;
                    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double t = slab(eye, dx / len, dy / len, dz / len, box);
                    int i = row * w + col;
                    if (t >= 0 && t < bodyT[i]) {
                        bodyT[i] = t;
                        bodyColor[i] = scale(base, lightShade(light, t, night || body.isGlowing() || body.isOnFire()));
                    }
                }
            }
        }
    }

    /** Distance along a unit ray to where it enters the box, or -1. */
    private static double slab(Vec3d o, double dx, double dy, double dz, Box b) {
        double t0 = 0, t1 = Double.POSITIVE_INFINITY;
        double[] origin = {o.x, o.y, o.z};
        double[] dir = {dx, dy, dz};
        double[] min = {b.minX, b.minY, b.minZ};
        double[] max = {b.maxX, b.maxY, b.maxZ};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(dir[a]) < 1.0E-9) {
                if (origin[a] < min[a] || origin[a] > max[a]) {
                    return -1;
                }
                continue;
            }
            double inv = 1.0 / dir[a];
            double near = (min[a] - origin[a]) * inv;
            double far = (max[a] - origin[a]) * inv;
            if (near > far) {
                double s = near;
                near = far;
                far = s;
            }
            t0 = Math.max(t0, near);
            t1 = Math.min(t1, far);
            if (t0 > t1) {
                return -1;
            }
        }
        return t0;
    }

    /** Players blue, villagers brown, monsters green (the rotting kind) or red, animals pink or white, things yellow. */
    static int bodyColor(Entity body) {
        if (body instanceof PlayerEntity) {
            return 0x3B5BDB;
        }
        if (body instanceof VillagerEntityMCA || body instanceof MerchantEntity) {
            return 0x8B5A2B;
        }
        if (body instanceof ItemEntity) {
            return 0xE0C040;
        }
        EntityType<?> type = body.getType();
        if (type.getSpawnGroup() == SpawnGroup.MONSTER) {
            return type == EntityType.ZOMBIE || type == EntityType.CREEPER || type == EntityType.DROWNED || type == EntityType.HUSK
                    || type == EntityType.SLIME || type == EntityType.ZOMBIE_VILLAGER ? 0x4CAF50 : 0xC0392B;
        }
        if (type == EntityType.SHEEP || type == EntityType.CHICKEN || type == EntityType.POLAR_BEAR || type == EntityType.GOAT
                || type == EntityType.LLAMA || type == EntityType.RABBIT) {
            return 0xF2F2F2;
        }
        return 0xF4A6B8;
    }

    /** The sky by the hour: blue by day, deep blue by night, warm at dawn and dusk, grey under rain. */
    static int skyColor(ServerWorld world) {
        long t = world.getTimeOfDay() % 24000L;
        double day;
        if (t < 12000) {
            day = 1.0;
        } else if (t < 13800) {
            day = 1.0 - (t - 12000) / 1800.0;
        } else if (t < 22200) {
            day = 0.0;
        } else {
            day = (t - 22200) / 1800.0;
        }
        int color = mix(0x0B1026, 0x7BA4FF, day);
        if (day > 0 && day < 1) {
            color = mix(color, 0xE8925A, 0.5 * (1.0 - Math.abs(day - 0.5) * 2.0));
        }
        if (world.isThundering()) {
            color = scale(mix(color, 0x60666E, 0.7), 0.7);
        } else if (world.isRaining()) {
            color = scale(mix(color, 0x7A8088, 0.5), 0.85);
        }
        return color;
    }

    static int mix(int a, int b, double f) {
        f = Math.max(0.0, Math.min(1.0, f));
        int r = (int) Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = (int) Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return (r << 16) | (g << 8) | bl;
    }

    static int scale(int c, double f) {
        int r = (int) Math.min(255, Math.round(((c >> 16) & 255) * f));
        int g = (int) Math.min(255, Math.round(((c >> 8) & 255) * f));
        int b = (int) Math.min(255, Math.round((c & 255) * f));
        return (r << 16) | (g << 8) | b;
    }

    // ---------------------------------------------------------------- PNG

    static byte[] encode(int[] rgb, int w, int h) {
        byte[] raw = new byte[h * (1 + w * 3)];
        int p = 0;
        for (int row = 0; row < h; row++) {
            raw[p++] = 0; // filter: none
            for (int col = 0; col < w; col++) {
                int c = rgb[row * w + col];
                raw[p++] = (byte) (c >> 16);
                raw[p++] = (byte) (c >> 8);
                raw[p++] = (byte) c;
            }
        }
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        ByteArrayOutputStream idat = new ByteArrayOutputStream(raw.length / 4 + 64);
        try {
            deflater.setInput(raw);
            deflater.finish();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                idat.write(buffer, 0, n);
            }
        } finally {
            deflater.end();
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream(idat.size() + 64);
        png.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, 0, 8);
        byte[] header = new byte[13];
        putInt(header, 0, w);
        putInt(header, 4, h);
        header[8] = 8;  // bit depth
        header[9] = 2;  // colour type: RGB
        header[10] = 0; // deflate
        header[11] = 0; // adaptive filtering
        header[12] = 0; // no interlace
        chunk(png, "IHDR", header);
        chunk(png, "IDAT", idat.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] length = new byte[4];
        putInt(length, 0, data.length);
        out.write(length, 0, 4);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.write(name, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        byte[] sum = new byte[4];
        putInt(sum, 0, (int) crc.getValue());
        out.write(sum, 0, 4);
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }
}
