package org.malionaro.dlss_frame_generation.client.raytracing.scene;

import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3fc;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
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
 * <p>Boxes are section-local (0..16), so floats stay exact anywhere in the
 * world; the BLAS instance transform moves them into place. Lights keep
 * world coordinates.</p>
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

    /**
     * @param trianglePositions  {@link RtTriangleFormat} positions, or null
     * @param triangleAttributes {@link RtTriangleFormat} attributes, or null
     */
    public record SectionMesh(long key, long generation, ByteBuffer boxes, int boxCount,
                              ByteBuffer trianglePositions, ByteBuffer triangleAttributes,
                              int triangleCount,
                              List<SceneLights.Emitter> lights, boolean overflowed) {
        public static SectionMesh empty(long key, long generation) {
            return new SectionMesh(key, generation, null, 0, null, null, 0, List.of(), false);
        }

        public boolean isEmpty() {
            return boxCount == 0 && triangleCount == 0;
        }
    }

    /** Upper bound of model triangles per section (leaves, grass and plants add up). */
    public static final int MAX_TRIANGLES = 32_768;
    private static final ThreadLocal<ByteBuffer> TRIANGLE_POSITIONS = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(MAX_TRIANGLES * RtTriangleFormat.BYTES_PER_POSITION)
                    .order(ByteOrder.nativeOrder()));
    private static final ThreadLocal<ByteBuffer> TRIANGLE_ATTRIBUTES = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(MAX_TRIANGLES * RtTriangleFormat.BYTES_PER_ATTRIBUTE)
                    .order(ByteOrder.nativeOrder()));
    private static final Direction[] FACES = BlockAppearances.faces();

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
        ByteBuffer positions = TRIANGLE_POSITIONS.get();
        ByteBuffer attributes = TRIANGLE_ATTRIBUTES.get();
        positions.clear();
        attributes.clear();
        int[] triangles = {0};
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
                        lights.add(SceneLights.emitter(worldX + 0.5, worldY + 0.5, worldZ + 0.5,
                                emission, color, lightTint(mapColor)));
                    }

                    // Anything but a full cube: the model's own triangles, so
                    // plants, torches, stairs and panes have their real shape.
                    boolean modelTriangles = false;
                    if (!state.isAir() && !state.isSolidRender()) {
                        BlockAppearances.ModelQuads quads = appearances.quadsOf(state);
                        if (!quads.isEmpty()) {
                            int flags = RtBoxFormat.withSkyLight(
                                    flags(mapColor, false, state, emission), openSkyLight(snapshot, x, y, z));
                            if (appendQuads(positions, attributes, triangles, quads, snapshot,
                                    x, y, z, state, position, flags, color, blockColors, tintLevel)) {
                                overflowed = true;
                                break outer;
                            }
                            modelTriangles = true;
                        }
                    }
                    if (!state.isAir() && !modelTriangles) {
                        BlockAppearances.Appearance look = appearances.of(state);
                        int tint = look.tintedFaces() != 0
                                ? tint(blockColors, tintLevel, state, position, look.tintIndex(), color)
                                : NO_TINT;
                        int tintWord = RtBoxFormat.packTint(tint, look.tintedFaces(), look.cutout());
                        int flags = RtBoxFormat.withSkyLight(
                                flags(mapColor, false, state, emission), openSkyLight(snapshot, x, y, z));
                        VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, position);
                        if (appendShape(boxes, count, shape, x, y, z, color, flags,
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
                        RtBoxFormat.putBox(boxes, x, y, z,
                                x + 1.0f, y + height, z + 1.0f,
                                packed, RtBoxFormat.withSkyLight(
                                        flags(fluidColor, fluid.createLegacyBlock().getBlock() == Blocks.WATER,
                                                state, 0),
                                        openSkyLight(snapshot, x, y, z)), 0, 0, 0,
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
        ByteBuffer exactPositions = null;
        ByteBuffer exactAttributes = null;
        if (triangles[0] > 0) {
            positions.flip();
            attributes.flip();
            exactPositions = ByteBuffer.allocateDirect(positions.remaining()).order(ByteOrder.nativeOrder());
            exactPositions.put(positions).flip();
            exactAttributes = ByteBuffer.allocateDirect(attributes.remaining()).order(ByteOrder.nativeOrder());
            exactAttributes.put(attributes).flip();
        }
        return new SectionMesh(snapshot.key(), generation, exact, count[0],
                exactPositions, exactAttributes, triangles[0], List.copyOf(lights), overflowed);
    }

    private static boolean enclosed(SectionSnapshot snapshot, int x, int y, int z) {
        return snapshot.get(x - 1, y, z).isSolidRender()
                && snapshot.get(x + 1, y, z).isSolidRender()
                && snapshot.get(x, y - 1, z).isSolidRender()
                && snapshot.get(x, y + 1, z).isSolidRender()
                && snapshot.get(x, y, z - 1).isSolidRender()
                && snapshot.get(x, y, z + 1).isSolidRender();
    }

    /**
     * Appends the model quads of one block as two triangles each. Quads that
     * face a full-cube neighbour are skipped, like the raster's face culling.
     *
     * @return true when the section's triangle budget is exhausted
     */
    private static boolean appendQuads(
            ByteBuffer positions, ByteBuffer attributes, int[] triangles,
            BlockAppearances.ModelQuads quads, SectionSnapshot snapshot,
            int x, int y, int z, BlockState state, BlockPos position, int flags, int mapColor,
            BlockColors blockColors, BlockAndTintGetter tintLevel
    ) {
        int[] tintCache = {Integer.MIN_VALUE, NO_TINT};
        for (int face = 0; face < quads.culled().length; face++) {
            Direction direction = FACES[face];
            if (snapshot.get(x + direction.getStepX(), y + direction.getStepY(),
                    z + direction.getStepZ()).isSolidRender()) {
                continue;
            }
            for (BakedQuad quad : quads.culled()[face]) {
                if (appendQuad(positions, attributes, triangles, quad, x, y, z, state, position,
                        flags, mapColor, blockColors, tintLevel, tintCache)) {
                    return true;
                }
            }
        }
        for (BakedQuad quad : quads.unculled()) {
            if (appendQuad(positions, attributes, triangles, quad, x, y, z, state, position,
                    flags, mapColor, blockColors, tintLevel, tintCache)) {
                return true;
            }
        }
        return false;
    }

    private static boolean appendQuad(
            ByteBuffer positions, ByteBuffer attributes, int[] triangles, BakedQuad quad,
            int x, int y, int z, BlockState state, BlockPos position, int flags, int mapColor,
            BlockColors blockColors, BlockAndTintGetter tintLevel, int[] tintCache
    ) {
        if (triangles[0] + 2 > MAX_TRIANGLES) {
            return true;
        }
        BakedQuad.MaterialInfo material = quad.materialInfo();
        int tint = NO_TINT;
        if (material.isTinted()) {
            // One lookup per tint index and block.
            if (tintCache[0] != material.tintIndex()) {
                tintCache[0] = material.tintIndex();
                tintCache[1] = tint(blockColors, tintLevel, state, position, material.tintIndex(), mapColor);
            }
            tint = tintCache[1];
        }
        int tintWord = (tint & 0xFFFFFF)
                | (material.layer() != ChunkSectionLayer.SOLID ? RtTriangleFormat.CUTOUT_BIT : 0);
        Vector3fc p0 = quad.position0();
        Vector3fc p1 = quad.position1();
        Vector3fc p2 = quad.position2();
        Vector3fc p3 = quad.position3();
        float ax = p1.x() - p0.x();
        float ay = p1.y() - p0.y();
        float az = p1.z() - p0.z();
        float bx = p2.x() - p0.x();
        float by = p2.y() - p0.y();
        float bz = p2.z() - p0.z();
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0e-8f) {
            return false;
        }
        nx /= length;
        ny /= length;
        nz /= length;
        long uv0 = quad.packedUV0();
        long uv1 = quad.packedUV1();
        long uv2 = quad.packedUV2();
        long uv3 = quad.packedUV3();
        RtTriangleFormat.putPositions(positions,
                x + p0.x(), y + p0.y(), z + p0.z(),
                x + p1.x(), y + p1.y(), z + p1.z(),
                x + p2.x(), y + p2.y(), z + p2.z());
        RtTriangleFormat.putAttributes(attributes,
                UVPair.unpackU(uv0), UVPair.unpackV(uv0),
                UVPair.unpackU(uv1), UVPair.unpackV(uv1),
                UVPair.unpackU(uv2), UVPair.unpackV(uv2),
                tintWord, flags, nx, ny, nz);
        RtTriangleFormat.putPositions(positions,
                x + p0.x(), y + p0.y(), z + p0.z(),
                x + p2.x(), y + p2.y(), z + p2.z(),
                x + p3.x(), y + p3.y(), z + p3.z());
        RtTriangleFormat.putAttributes(attributes,
                UVPair.unpackU(uv0), UVPair.unpackV(uv0),
                UVPair.unpackU(uv2), UVPair.unpackV(uv2),
                UVPair.unpackU(uv3), UVPair.unpackV(uv3),
                tintWord, flags, nx, ny, nz);
        triangles[0] += 2;
        return false;
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

    /** Brightest sky light of the block's cell and its six neighbours. */
    private static int openSkyLight(SectionSnapshot snapshot, int x, int y, int z) {
        int light = snapshot.skyLight(x, y, z);
        light = Math.max(light, snapshot.skyLight(x - 1, y, z));
        light = Math.max(light, snapshot.skyLight(x + 1, y, z));
        light = Math.max(light, snapshot.skyLight(x, y - 1, z));
        light = Math.max(light, snapshot.skyLight(x, y + 1, z));
        light = Math.max(light, snapshot.skyLight(x, y, z - 1));
        return Math.max(light, snapshot.skyLight(x, y, z + 1));
    }

    /** Glass blocks and panes (refracting, light passes); not slabs or leaves. */
    static boolean isGlass(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().contains("glass");
    }

    /** @param fluidBlock water (lava is emissive but not water) */
    static int flags(MapColor mapColor, boolean fluidBlock, BlockState state, int emission) {
        int flags = 0;
        if (mapColor == MapColor.WATER || mapColor == MapColor.ICE || mapColor == MapColor.METAL
                || mapColor == MapColor.GOLD || mapColor == MapColor.DIAMOND) {
            flags |= RtBoxFormat.FLAG_REFLECTIVE;
        }
        if (fluidBlock) {
            flags |= RtBoxFormat.FLAG_WATER;
        }
        if (!fluidBlock && isGlass(state)) {
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
