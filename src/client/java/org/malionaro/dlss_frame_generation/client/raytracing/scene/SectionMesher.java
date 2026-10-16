package org.malionaro.dlss_frame_generation.client.raytracing.scene;

import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns one {@link SectionSnapshot} into ray-traced boxes ({@link RtBoxFormat})
 * plus its light emitters. Runs on a mesher thread.
 *
 * <p>Fully enclosed opaque cubes are skipped: no primary, shadow or
 * reflection ray can reach them, and they were most of every section.</p>
 */
public final class SectionMesher {
    /** Upper bound per section; complex shapes add several boxes per block. */
    public static final int MAX_BOXES = 12_288;
    private static final int NO_TINT = 0xFFFFFF;
    /** Reused per mesher thread; each mesh is copied out at its exact size. */
    private static final ThreadLocal<ByteBuffer> SCRATCH = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(MAX_BOXES * RtBoxFormat.BYTES_PER_BOX)
                    .order(ByteOrder.nativeOrder()));

    public record SectionMesh(long key, long generation, ByteBuffer boxes, int boxCount,
                              List<SceneLights.Emitter> lights, boolean overflowed) {}

    private SectionMesher() {}

    public static SectionMesh mesh(
            SectionSnapshot snapshot,
            long generation,
            BlockAppearances appearances,
            BlockColors blockColors,
            BlockAndTintGetter tintLevel
    ) {
        ByteBuffer boxes = SCRATCH.get();
        boxes.clear();
        List<SceneLights.Emitter> lights = new ArrayList<>();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        int[] count = {0};
        boolean overflowed = false;
        int originX = snapshot.originX();
        int originY = snapshot.originY();
        int originZ = snapshot.originZ();

        outer:
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = snapshot.get(x, y, z);
                    FluidState fluid = state.getFluidState();
                    boolean fluidBlock = !fluid.isEmpty();
                    if (state.isAir() && !fluidBlock) {
                        continue;
                    }
                    if (!fluidBlock && state.isSolidRender() && enclosed(snapshot, x, y, z)) {
                        continue;
                    }
                    int worldX = originX + x;
                    int worldY = originY + y;
                    int worldZ = originZ + z;
                    position.set(worldX, worldY, worldZ);

                    MapColor mapColor = state.getMapColor(EmptyBlockGetter.INSTANCE, position);
                    int color = mapColor != null ? mapColor.col : 0x808080;
                    int emission = state.getLightEmission();
                    if (emission > 0) {
                        lights.add(SceneLights.emitter(worldX + 0.5f, worldY + 0.5f, worldZ + 0.5f,
                                emission, color, lightTint(mapColor)));
                    }

                    if (!state.isAir()) {
                        BlockAppearances.Appearance look = appearances.of(state);
                        int tint = look.tintedFaces() != 0
                                ? tint(blockColors, tintLevel, state, position, look.tintIndex(), color)
                                : NO_TINT;
                        int tintWord = RtBoxFormat.packTint(tint, look.tintedFaces(), look.cutout());
                        int flags = flags(mapColor, false, state, emission);
                        VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, position);
                        if (appendShape(boxes, count, shape, worldX, worldY, worldZ, color, flags,
                                look.facesX(), look.facesY(), look.facesZ(), tintWord)) {
                            overflowed = true;
                            break outer;
                        }
                    }
                    if (fluidBlock) {
                        // Water is still shaded from its map colour; its
                        // surface sits at the fluid's own height.
                        MapColor fluidColor = fluid.createLegacyBlock()
                                .getMapColor(EmptyBlockGetter.INSTANCE, position);
                        int packed = fluidColor != null ? fluidColor.col : 0x3f76e4;
                        float height = Math.max(0.0625f, Math.min(1.0f, fluid.getOwnHeight()));
                        if (count[0] >= MAX_BOXES) {
                            overflowed = true;
                            break outer;
                        }
                        RtBoxFormat.putBox(boxes, worldX, worldY, worldZ,
                                worldX + 1.0f, worldY + height, worldZ + 1.0f,
                                packed, flags(fluidColor, true, state, 0), 0, 0, 0,
                                RtBoxFormat.packTint(NO_TINT, 0, false));
                        count[0]++;
                    }
                }
            }
        }
        boxes.flip();
        ByteBuffer exact = ByteBuffer.allocateDirect(Math.max(RtBoxFormat.BYTES_PER_BOX, boxes.remaining()))
                .order(ByteOrder.nativeOrder());
        exact.put(boxes).flip();
        return new SectionMesh(snapshot.key(), generation, exact, count[0], List.copyOf(lights), overflowed);
    }

    private static boolean enclosed(SectionSnapshot snapshot, int x, int y, int z) {
        return snapshot.get(x - 1, y, z).isSolidRender()
                && snapshot.get(x + 1, y, z).isSolidRender()
                && snapshot.get(x, y - 1, z).isSolidRender()
                && snapshot.get(x, y + 1, z).isSolidRender()
                && snapshot.get(x, y, z - 1).isSolidRender()
                && snapshot.get(x, y, z + 1).isSolidRender();
    }

    /** Returns true when the section's box budget is exhausted. */
    private static boolean appendShape(
            ByteBuffer boxes, int[] count, VoxelShape shape,
            int x, int y, int z, int color, int flags,
            int facesX, int facesY, int facesZ, int tintWord
    ) {
        if (shape == null || shape.isEmpty()) {
            return false;
        }
        for (AABB bounds : shape.toAabbs()) {
            if (count[0] >= MAX_BOXES) {
                return true;
            }
            RtBoxFormat.putBox(boxes,
                    (float) (x + bounds.minX), (float) (y + bounds.minY), (float) (z + bounds.minZ),
                    (float) (x + bounds.maxX), (float) (y + bounds.maxY), (float) (z + bounds.maxZ),
                    color, flags, facesX, facesY, facesZ, tintWord);
            count[0]++;
        }
        return false;
    }

    private static int tint(BlockColors colors, BlockAndTintGetter level, BlockState state,
                            BlockPos position, int tintIndex, int fallback) {
        if (colors == null || level == null || tintIndex < 0) {
            return fallback;
        }
        try {
            BlockTintSource source = colors.getTintSource(state, tintIndex);
            if (source == null) {
                return fallback;
            }
            return source.colorInWorld(state, level, position) & 0xFFFFFF;
        } catch (RuntimeException | LinkageError error) {
            // Biome lookups off-thread are best effort; the map colour is a
            // close stand-in for grass and leaves.
            return fallback;
        }
    }

    static int flags(MapColor mapColor, boolean fluidBlock, BlockState state, int emission) {
        int flags = 0;
        if (mapColor == MapColor.WATER || mapColor == MapColor.ICE || mapColor == MapColor.METAL
                || mapColor == MapColor.GOLD || mapColor == MapColor.DIAMOND) {
            flags |= RtBoxFormat.FLAG_REFLECTIVE;
        }
        if (fluidBlock) {
            flags |= RtBoxFormat.FLAG_WATER;
        }
        if (!fluidBlock && !state.isSolidRender()
                && (mapColor == MapColor.NONE || state.getLightDampening() <= 1)) {
            flags |= RtBoxFormat.FLAG_GLASS;
        }
        if (emission > 0 || mapColor == MapColor.FIRE) {
            flags |= RtBoxFormat.FLAG_EMISSIVE;
        }
        if (mapColor == MapColor.METAL || mapColor == MapColor.GOLD || mapColor == MapColor.DIAMOND) {
            flags |= RtBoxFormat.FLAG_METALLIC;
        }
        return flags;
    }

    private static SceneLights.LightTint lightTint(MapColor mapColor) {
        if (mapColor == MapColor.FIRE) return SceneLights.LightTint.FIRE;
        if (mapColor == MapColor.COLOR_LIGHT_BLUE || mapColor == MapColor.DIAMOND) return SceneLights.LightTint.COOL;
        if (mapColor == MapColor.COLOR_RED) return SceneLights.LightTint.RED;
        if (mapColor == MapColor.WATER) return SceneLights.LightTint.WATER;
        if (mapColor == MapColor.GOLD) return SceneLights.LightTint.GOLD;
        return SceneLights.LightTint.BLOCK_COLOR;
    }
}
