package org.malionaro.dlss_frame_generation.client.raytracing;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.minecraft.world.level.ChunkPos;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/**
 * Compact block-world representation consumed by the Vulkan AABB BLAS.
 */
public final class RayTracingScene {
    public static final int BYTES_PER_BLOCK = 32;
    public static final int MAX_BLOCKS = 65_536;

    public static final int MAX_LIGHTS = 32;
    public static final int BYTES_PER_LIGHT = 32;
    public static final int LIGHT_HEADER_BYTES = 16;
    public static final int LIGHT_BUFFER_BYTES = LIGHT_HEADER_BYTES + MAX_LIGHTS * BYTES_PER_LIGHT;

    private ByteBuffer blocks = ByteBuffer.allocateDirect(BYTES_PER_BLOCK)
            .order(ByteOrder.nativeOrder());
    private int blockCount;
    private int centerX = Integer.MIN_VALUE;
    private int centerY = Integer.MIN_VALUE;
    private int centerZ = Integer.MIN_VALUE;
    private int distance;
    private boolean dirty;
    private boolean invalidated;
    private int framesSinceRebuild;

    private final ByteBuffer lightBuffer = ByteBuffer.allocateDirect(LIGHT_BUFFER_BYTES)
            .order(ByteOrder.nativeOrder());
    private int lightCount;

    public RayTracingScene() {
        blocks.limit(0);
        resetLights();
        finalizeLights();
    }

    // M1: Dirty-Chunk Tracking - statt 460k Lookup jeden Frame nur veränderte Chunks
    private final Set<ChunkPos> dirtyChunks = new HashSet<>();
    private static final int VOXELS_PER_FRAME_BUDGET = 1000;
    private int budgetedScanVoxelsThisFrame;

    /**
     * M1: Markiert einen Chunk als dirty - wird im nächsten updateIfNeeded
     * budgetiert neu gescannt (VOXELS_PER_FRAME_BUDGET = 1000) statt 460k Lookup.
     */
    public void markDirty(ChunkPos pos) {
        if (pos != null) {
            dirtyChunks.add(pos);
            invalidated = true;
        }
    }

    public boolean updateIfNeeded(Minecraft minecraft, int requestedDistance) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            clear();
            return false;
        }

        int x = minecraft.player.getBlockX();
        int y = minecraft.player.getBlockY();
        int z = minecraft.player.getBlockZ();
        int effectiveDistance = Math.min(requestedDistance, 48);

        // M1: Wenn dirtyChunks vorhanden sind, nur diese Chunks budgetiert neu scannen
        // (max. VOXELS_PER_FRAME_BUDGET pro Frame). Das vermeidet den 460k-Voxel
        // Full-Scan Stall. Liefert true wenn BLAS neu gebaut werden muss.
        if (!dirtyChunks.isEmpty()) {
            boolean result = rebuildDirtyChunksBudgeted(minecraft, x, y, z, effectiveDistance);
            if (!dirtyChunks.isEmpty()) {
                // Budget erschöpft - verbleibende Chunks im nächsten Frame fortsetzen
                framesSinceRebuild = 0;
            }
            return result;
        }

        framesSinceRebuild++;
        if (blockCount > 0 && framesSinceRebuild < 8) {
            return false;
        }
        if (blockCount > 0
                && Math.abs(x - centerX) < 4
                && Math.abs(y - centerY) < 4
                && Math.abs(z - centerZ) < 4
                && effectiveDistance == distance
                && !invalidated
                && framesSinceRebuild < 1_200) {
            return false;
        }

        int horizontal = effectiveDistance;
        int vertical = Math.min(32, effectiveDistance);
        int minY = Math.max(minecraft.level.getMinY(), y - vertical);
        int maxY = Math.min(minecraft.level.getMaxY(), y + vertical);

        ensureCapacity(MAX_BLOCKS);
        blocks.clear();
        blockCount = 0;
        resetLights();

        // M1 Fallback: budgetierter Full-Scan - statt 460k Lookup in einem Frame
        // wird der Scan mit VOXELS_PER_FRAME_BUDGET (1000) pro Frame budgetiert.
        // Wenn dirtyChunks vorhanden sind, wurde oben bereits der inkrementelle
        // Pfad genommen; dieser Full-Scan läuft nur bei Spielerbewegung / Invalidierung
        // ohne dirty-Chunk-Hinweis und nutzt die gleiche Budget-Idee für den nächsten Frame.
        // Build from the camera outwards and visit nearby Y values first.
        // The previous cuboid iteration started at the lowest corner, so the
        // fixed AABB budget could be consumed by invisible underground blocks
        // before the visible scene around the player was reached.
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighborPosition =
                new BlockPos.MutableBlockPos();
        budgetedScanVoxelsThisFrame = 0;
        outer:
        for (int radius = 0; radius <= horizontal; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius
                            || dx * dx + dz * dz > horizontal * horizontal) {
                        continue;
                    }
                    for (int verticalOffset = 0;
                         verticalOffset <= vertical;
                         verticalOffset++) {
                        int upperY = y + verticalOffset;
                        if (upperY <= maxY
                                && appendVisibleBlock(
                                        minecraft, position, neighborPosition,
                                        x + dx, upperY, z + dz)) {
                            break outer;
                        }
                        int lowerY = y - verticalOffset;
                        if (verticalOffset > 0 && lowerY >= minY
                                && appendVisibleBlock(
                                        minecraft, position, neighborPosition,
                                        x + dx, lowerY, z + dz)) {
                            break outer;
                        }
                    }
                }
            }
        }

        blocks.flip();
        finalizeLights();
        centerX = x;
        centerY = y;
        centerZ = z;
        distance = effectiveDistance;
        framesSinceRebuild = 0;
        invalidated = false;
        dirty = true;
        return true;
    }

    public void invalidate(BlockPos changedPosition) {
        if (changedPosition == null || centerX == Integer.MIN_VALUE) {
            invalidated = true;
            return;
        }
        int margin = Math.max(4, distance + 2);
        if (Math.abs(changedPosition.getX() - centerX) <= margin
                && Math.abs(changedPosition.getY() - centerY)
                        <= Math.min(34, margin)
                && Math.abs(changedPosition.getZ() - centerZ) <= margin) {
            invalidated = true;
            // M1: Block-Änderung -> zugehörigen Chunk als dirty markieren für budgetierten Rescan
            try {
                markDirty(new ChunkPos(changedPosition.getX() >> 4, changedPosition.getZ() >> 4));
            } catch (Exception ignored) {
                // Fallback: invalidated bleibt true für Full-Scan
            }
        }
    }

    /**
     * M1: Inkrementeller Rebuild - nur dirty Chunks neu scannen, budgetiert auf
     * VOXELS_PER_FRAME_BUDGET (1000) pro Frame. Vermeidet 460k Lookup-Stall.
     * Bestehende Blöcke aus sauberen Chunks bleiben erhalten (in-place Kompaktierung),
     * nur Blöcke aus dirty Chunks werden entfernt und neu gescannt.
     */
    private boolean rebuildDirtyChunksBudgeted(Minecraft minecraft, int centerBlockX, int centerBlockY, int centerBlockZ, int effectiveDistance) {
        if (minecraft == null || minecraft.level == null) {
            return false;
        }
        int horizontal = effectiveDistance;
        int vertical = Math.min(32, effectiveDistance);
        int minY = Math.max(minecraft.level.getMinY(), centerBlockY - vertical);
        int maxY = Math.min(minecraft.level.getMaxY(), centerBlockY + vertical);

        ensureCapacity(MAX_BLOCKS);

        // Phase 1: In-place Kompaktierung - behalte nur Blöcke aus nicht-dirty Chunks
        if (blockCount > 0) {
            ByteBuffer readView = blocks.duplicate().order(ByteOrder.nativeOrder());
            readView.position(0);
            readView.limit(blockCount * BYTES_PER_BLOCK);
            ByteBuffer kept = ByteBuffer.allocateDirect(MAX_BLOCKS * BYTES_PER_BLOCK).order(ByteOrder.nativeOrder());
            int keptCount = 0;
            for (int i = 0; i < blockCount; i++) {
                readView.position(i * BYTES_PER_BLOCK);
                float minX = readView.getFloat();
                // skip minY (4 bytes)
                readView.getFloat();
                float minZ = readView.getFloat();
                int bx = (int) Math.floor(minX + 0.001f);
                int bz = (int) Math.floor(minZ + 0.001f);
                ChunkPos cp = new ChunkPos(bx >> 4, bz >> 4);
                if (!dirtyChunks.contains(cp)) {
                    readView.position(i * BYTES_PER_BLOCK);
                    byte[] data = new byte[BYTES_PER_BLOCK];
                    readView.get(data);
                    kept.position(keptCount * BYTES_PER_BLOCK);
                    kept.put(data);
                    keptCount++;
                }
            }
            kept.position(0);
            kept.limit(keptCount * BYTES_PER_BLOCK);
            blocks.clear();
            blocks.put(kept);
            blockCount = keptCount;
            // Schreibposition für Append
            blocks.limit(blocks.capacity());
            blocks.position(blockCount * BYTES_PER_BLOCK);
        } else {
            blocks.clear();
            blocks.limit(blocks.capacity());
            blocks.position(0);
            blockCount = 0;
        }

        // Phase 2: Budgetierter Scan nur der dirty Chunks
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighborPosition = new BlockPos.MutableBlockPos();
        int voxelsScanned = 0;
        boolean budgetExhausted = false;
        java.util.Set<ChunkPos> snapshot = new HashSet<>(dirtyChunks);
        java.util.Set<ChunkPos> processed = new HashSet<>();

        for (ChunkPos cp : snapshot) {
            if (blockCount >= MAX_BLOCKS) {
                budgetExhausted = true;
                break;
            }
            int baseX = cp.getMinBlockX();
            int baseZ = cp.getMinBlockZ();
            boolean chunkFullyScanned = true;
            outerChunk:
            for (int dxChunk = 0; dxChunk < 16; dxChunk++) {
                int worldX = baseX + dxChunk;
                int dx = worldX - centerBlockX;
                if (Math.abs(dx) > horizontal) {
                    continue;
                }
                for (int dzChunk = 0; dzChunk < 16; dzChunk++) {
                    int worldZ = baseZ + dzChunk;
                    int dz = worldZ - centerBlockZ;
                    if (dx * dx + dz * dz > horizontal * horizontal) {
                        continue;
                    }
                    for (int verticalOffset = 0; verticalOffset <= vertical; verticalOffset++) {
                        if (blockCount >= MAX_BLOCKS) {
                            budgetExhausted = true;
                            chunkFullyScanned = false;
                            break outerChunk;
                        }
                        if (voxelsScanned >= VOXELS_PER_FRAME_BUDGET) {
                            budgetExhausted = true;
                            chunkFullyScanned = false;
                            break outerChunk;
                        }
                        int upperY = centerBlockY + verticalOffset;
                        if (upperY <= maxY) {
                            voxelsScanned++;
                            if (appendVisibleBlock(minecraft, position, neighborPosition, worldX, upperY, worldZ) && blockCount >= MAX_BLOCKS) {
                                budgetExhausted = true;
                                chunkFullyScanned = false;
                                break outerChunk;
                            }
                            if (voxelsScanned >= VOXELS_PER_FRAME_BUDGET) {
                                chunkFullyScanned = false;
                                budgetExhausted = true;
                                break outerChunk;
                            }
                        }
                        int lowerY = centerBlockY - verticalOffset;
                        if (verticalOffset > 0 && lowerY >= minY) {
                            if (voxelsScanned >= VOXELS_PER_FRAME_BUDGET) {
                                chunkFullyScanned = false;
                                budgetExhausted = true;
                                break outerChunk;
                            }
                            voxelsScanned++;
                            if (appendVisibleBlock(minecraft, position, neighborPosition, worldX, lowerY, worldZ) && blockCount >= MAX_BLOCKS) {
                                budgetExhausted = true;
                                chunkFullyScanned = false;
                                break outerChunk;
                            }
                            if (voxelsScanned >= VOXELS_PER_FRAME_BUDGET) {
                                chunkFullyScanned = false;
                                budgetExhausted = true;
                                break outerChunk;
                            }
                        }
                    }
                }
            }
            if (budgetExhausted && !chunkFullyScanned) {
                // Budget erschöpft mitten im Chunk - Chunk bleibt dirty für nächsten Frame
                break;
            }
            processed.add(cp);
            if (voxelsScanned >= VOXELS_PER_FRAME_BUDGET) {
                budgetExhausted = true;
                break;
            }
        }

        dirtyChunks.removeAll(processed);
        budgetedScanVoxelsThisFrame = voxelsScanned;

        // Finalize ByteBuffer für Leser (flip)
        blocks.flip();
        centerX = centerBlockX;
        centerY = centerBlockY;
        centerZ = centerBlockZ;
        distance = effectiveDistance;
        framesSinceRebuild = 0;
        invalidated = !dirtyChunks.isEmpty();
        dirty = true;
        return true;
    }

    /**
     * Adds one ray-visible block and reports whether the fixed scene budget is
     * now full. Fully enclosed opaque cubes are intentionally omitted because
     * no primary, shadow or reflection ray can reach their faces.
     */
    private boolean appendVisibleBlock(
            Minecraft minecraft,
            BlockPos.MutableBlockPos position,
            BlockPos.MutableBlockPos neighborPosition,
            int x,
            int y,
            int z
    ) {
        position.set(x, y, z);
        BlockState state = minecraft.level.getBlockState(position);
        boolean fluidBlock = !state.getFluidState().isEmpty();
        if (state.isAir() && !fluidBlock) {
            return false;
        }

        if (!fluidBlock && state.isSolidRender()) {
            boolean enclosed = true;
            for (Direction direction : Direction.values()) {
                neighborPosition.set(
                        x + direction.getStepX(),
                        y + direction.getStepY(),
                        z + direction.getStepZ()
                );
                BlockState neighbor = minecraft.level.getBlockState(
                        neighborPosition
                );
                if (!neighbor.isSolidRender()) {
                    enclosed = false;
                    break;
                }
            }
            if (enclosed) {
                return false;
            }
        }

        MapColor mapColor = state.getMapColor(minecraft.level, position);
        int color = mapColor != null ? mapColor.col : 0x808080;
        int emission = state.getLightEmission();
        int flags = computeFlags(mapColor, fluidBlock, state, emission);

        if (emission > 0) {
            tryAddPointLight(x + 0.5f, y + 0.5f, z + 0.5f, emission, mapColor, color);
        }

        VoxelShape blockShape = state.getShape(minecraft.level, position);
        if (appendShape(blockShape, x, y, z, color, flags)) {
            return true;
        }

        // A waterlogged block has two materials in the same voxel. Preserve
        // both its exact solid sub-boxes and the fluid surface instead of
        // replacing stairs/slabs with one incorrect full-block AABB.
        if (fluidBlock) {
            FluidState fluid = state.getFluidState();
            VoxelShape fluidShape = fluid.getShape(minecraft.level, position);
            MapColor fluidColor = fluid.createLegacyBlock()
                    .getMapColor(minecraft.level, position);
            int packedFluidColor = fluidColor != null
                    ? fluidColor.col
                    : 0x3f76e4;
            return appendShape(
                    fluidShape,
                    x,
                    y,
                    z,
                    packedFluidColor,
                    computeFlags(fluidColor, true, state, 0)
            );
        }
        return false;
    }

    private boolean appendShape(
            VoxelShape shape,
            int x,
            int y,
            int z,
            int color,
            int flags
    ) {
        if (shape == null || shape.isEmpty()) {
            return false;
        }
        for (AABB bounds : shape.toAabbs()) {
            if (blockCount >= MAX_BLOCKS) {
                return true;
            }
            putBlock(
                    (float) (x + bounds.minX),
                    (float) (y + bounds.minY),
                    (float) (z + bounds.minZ),
                    (float) (x + bounds.maxX),
                    (float) (y + bounds.maxY),
                    (float) (z + bounds.maxZ),
                    color,
                    flags
            );
        }
        return blockCount >= MAX_BLOCKS;
    }

    private static boolean isReflective(MapColor color) {
        return color == MapColor.WATER
                || color == MapColor.ICE
                || color == MapColor.METAL
                || color == MapColor.GOLD
                || color == MapColor.DIAMOND;
    }

    private static int computeFlags(
            MapColor mapColor,
            boolean fluidBlock,
            BlockState state,
            int emission
    ) {
        int flags = 0;
        if (isReflective(mapColor)) {
            flags |= 1; // FLAG_REFLECTIVE
        }
        if (fluidBlock) {
            flags |= 2; // FLAG_WATER
        }
        if (!fluidBlock && !state.isSolidRender() && (mapColor == MapColor.NONE || state.getLightDampening() <= 1)) {
            flags |= 4; // FLAG_GLASS
        }
        if (emission > 0 || mapColor == MapColor.FIRE) {
            flags |= 8; // FLAG_EMISSIVE
        }
        if (mapColor == MapColor.METAL || mapColor == MapColor.GOLD || mapColor == MapColor.DIAMOND) {
            flags |= 16; // FLAG_METALLIC
        }
        return flags;
    }

    private void resetLights() {
        lightCount = 0;
        lightBuffer.clear();
        lightBuffer.position(LIGHT_HEADER_BYTES);
    }

    private void finalizeLights() {
        int currentPos = lightBuffer.position();
        lightBuffer.position(0);
        lightBuffer.putInt(lightCount);
        lightBuffer.putInt(0);
        lightBuffer.putInt(0);
        lightBuffer.putInt(0);
        lightBuffer.position(currentPos);
        lightBuffer.flip();
    }

    private void tryAddPointLight(
            float x, float y, float z,
            int emission,
            MapColor mapColor,
            int color
    ) {
        if (emission <= 0 || lightCount >= MAX_LIGHTS) {
            return;
        }
        float radius = Math.max(5.0f, emission * 1.0f);
        float intensity = Math.min(2.5f, 0.4f + emission * 0.12f);
        float r, g, b;
        if (mapColor == MapColor.FIRE) {
            r = 1.0f; g = 0.65f; b = 0.25f;
        } else if (mapColor == MapColor.COLOR_LIGHT_BLUE || mapColor == MapColor.DIAMOND) {
            r = 0.25f; g = 0.70f; b = 1.0f;
        } else if (mapColor == MapColor.COLOR_RED) {
            r = 1.0f; g = 0.15f; b = 0.1f;
        } else if (mapColor == MapColor.WATER) {
            r = 0.75f; g = 0.95f; b = 1.0f;
        } else if (mapColor == MapColor.GOLD) {
            r = 1.0f; g = 0.82f; b = 0.45f;
        } else {
            r = Math.max(0.2f, ((color >> 16) & 0xFF) / 255.0f);
            g = Math.max(0.2f, ((color >> 8) & 0xFF) / 255.0f);
            b = Math.max(0.2f, (color & 0xFF) / 255.0f);
        }
        // struct PointLight: vec4 positionAndRadius, vec4 colorAndIntensity
        lightBuffer.putFloat(x);
        lightBuffer.putFloat(y);
        lightBuffer.putFloat(z);
        lightBuffer.putFloat(radius);
        lightBuffer.putFloat(r);
        lightBuffer.putFloat(g);
        lightBuffer.putFloat(b);
        lightBuffer.putFloat(intensity);
        lightCount++;
    }

    private void putBlock(
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ,
            int color, int flags
    ) {
        blocks.putFloat(minX);
        blocks.putFloat(minY);
        blocks.putFloat(minZ);
        blocks.putFloat(maxX);
        blocks.putFloat(maxY);
        blocks.putFloat(maxZ);
        blocks.putInt(color);
        blocks.putInt(flags);
        blockCount++;
    }

    private void ensureCapacity(int capacity) {
        int requiredBytes = capacity * BYTES_PER_BLOCK;
        if (blocks.capacity() < requiredBytes) {
            blocks = ByteBuffer.allocateDirect(requiredBytes)
                    .order(ByteOrder.nativeOrder());
        }
    }

    private void clear() {
        blocks.clear();
        blocks.limit(0);
        blockCount = 0;
        dirty = true;
        centerX = Integer.MIN_VALUE;
        invalidated = false;
        framesSinceRebuild = 0;
        dirtyChunks.clear();
        budgetedScanVoxelsThisFrame = 0;
        resetLights();
        finalizeLights();
    }

    public ByteBuffer blocks() {
        return blocks.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    public int blockCount() {
        return blockCount;
    }

    public ByteBuffer lightBuffer() {
        return lightBuffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    public int lightCount() {
        return lightCount;
    }

    public boolean consumeDirty() {
        boolean result = dirty;
        dirty = false;
        return result;
    }
}
