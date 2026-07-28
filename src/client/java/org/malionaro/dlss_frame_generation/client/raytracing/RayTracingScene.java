package org.malionaro.dlss_frame_generation.client.raytracing;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Compact block-world representation consumed by the Vulkan AABB BLAS.
 */
public final class RayTracingScene {
    public static final int BYTES_PER_BLOCK = 32;
    public static final int MAX_BLOCKS = 65_536;

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

    public boolean updateIfNeeded(Minecraft minecraft, int requestedDistance) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            clear();
            return false;
        }

        int x = minecraft.player.getBlockX();
        int y = minecraft.player.getBlockY();
        int z = minecraft.player.getBlockZ();
        int effectiveDistance = Math.min(requestedDistance, 48);
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

        // Build from the camera outwards and visit nearby Y values first.
        // The previous cuboid iteration started at the lowest corner, so the
        // fixed AABB budget could be consumed by invisible underground blocks
        // before the visible scene around the player was reached.
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighborPosition =
                new BlockPos.MutableBlockPos();
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
        }
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
        int flags = isReflective(mapColor) ? 1 : 0;

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
                    isReflective(fluidColor) ? 1 : 0
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
    }

    public ByteBuffer blocks() {
        return blocks.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    public int blockCount() {
        return blockCount;
    }

    public boolean consumeDirty() {
        boolean result = dirty;
        dirty = false;
        return result;
    }
}
