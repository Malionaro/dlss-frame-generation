package org.malionaro.dlss_frame_generation.client.raytracing.scene;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Block states of one 16³ section and its six neighbours, copied on the
 * render thread so the mesher can read them on a worker without touching the
 * live level. Neighbours are only needed to drop fully enclosed cubes.
 */
public final class SectionSnapshot {
    /** Neighbour order: -X, +X, -Y, +Y, -Z, +Z. */
    private static final int[][] OFFSETS = {
            {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
    };
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public enum Result { CAPTURED, EMPTY, NOT_LOADED }

    private final long key;
    private final PalettedContainer<BlockState> center;
    private final PalettedContainer<BlockState>[] neighbors;

    @SuppressWarnings("unchecked")
    private SectionSnapshot(long key, PalettedContainer<BlockState> center) {
        this.key = key;
        this.center = center;
        this.neighbors = new PalettedContainer[6];
    }

    public long key() {
        return key;
    }

    public int originX() {
        return SectionPos.sectionToBlockCoord(SectionPos.x(key));
    }

    public int originY() {
        return SectionPos.sectionToBlockCoord(SectionPos.y(key));
    }

    public int originZ() {
        return SectionPos.sectionToBlockCoord(SectionPos.z(key));
    }

    /**
     * Copies section {@code key}. {@link Result#EMPTY} sections (only air or
     * outside the world) need no mesh; {@link Result#NOT_LOADED} ones should
     * be retried once their chunk arrives.
     */
    public static Object capture(ClientLevel level, long key) {
        int sectionX = SectionPos.x(key);
        int sectionY = SectionPos.y(key);
        int sectionZ = SectionPos.z(key);
        LevelChunk chunk = level.getChunkSource().getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            return Result.NOT_LOADED;
        }
        LevelChunkSection section = sectionOf(level, chunk, sectionY);
        if (section == null || section.hasOnlyAir()) {
            return Result.EMPTY;
        }
        SectionSnapshot snapshot = new SectionSnapshot(key, section.getStates().copy());
        for (int index = 0; index < OFFSETS.length; index++) {
            int[] offset = OFFSETS[index];
            int nx = sectionX + offset[0];
            int ny = sectionY + offset[1];
            int nz = sectionZ + offset[2];
            LevelChunk neighborChunk = offset[1] != 0
                    ? chunk
                    : level.getChunkSource().getChunk(nx, nz, ChunkStatus.FULL, false);
            if (neighborChunk == null) {
                continue;
            }
            LevelChunkSection neighbor = sectionOf(level, neighborChunk, ny);
            if (neighbor != null && !neighbor.hasOnlyAir()) {
                snapshot.neighbors[index] = neighbor.getStates().copy();
            }
        }
        return snapshot;
    }

    private static LevelChunkSection sectionOf(ClientLevel level, LevelChunk chunk, int sectionY) {
        if (sectionY < level.getMinSectionY() || sectionY > level.getMaxSectionY()) {
            return null;
        }
        return chunk.getSection(level.getSectionIndexFromSectionY(sectionY));
    }

    /** State at section-local coordinates; -1 and 16 reach into the neighbours. */
    public BlockState get(int x, int y, int z) {
        if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16) {
            return center.get(x, y, z);
        }
        int index;
        if (x < 0) index = 0;
        else if (x > 15) index = 1;
        else if (y < 0) index = 2;
        else if (y > 15) index = 3;
        else if (z < 0) index = 4;
        else index = 5;
        PalettedContainer<BlockState> neighbor = neighbors[index];
        if (neighbor == null) {
            return AIR;
        }
        return neighbor.get(x & 15, y & 15, z & 15);
    }
}
