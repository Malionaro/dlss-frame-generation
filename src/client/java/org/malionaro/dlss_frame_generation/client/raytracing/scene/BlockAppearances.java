package org.malionaro.dlss_frame_generation.client.raytracing.scene;

import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-face textures of a block state, read from Minecraft's baked block
 * models: which atlas sprite each face shows, which faces take the biome
 * tint, and whether the texture is alpha-tested.
 *
 * <p>Baked models are immutable after a reload, so this runs on the mesher
 * threads. The cache is tied to one {@link BlockStateModelSet}; a reload
 * creates a new set and therefore a fresh cache.</p>
 */
public final class BlockAppearances {
    /** Face order matches {@link RtBoxFormat}: -X, +X, -Y, +Y, -Z, +Z. */
    private static final Direction[] FACES = {
            Direction.WEST, Direction.EAST,
            Direction.DOWN, Direction.UP,
            Direction.NORTH, Direction.SOUTH
    };

    public record Appearance(int facesX, int facesY, int facesZ,
                             int tintedFaces, int tintIndex, boolean cutout) {
        public static final Appearance NONE = new Appearance(0, 0, 0, 0, -1, false);
    }

    private final BlockStateModelSet models;
    private final SpriteTable sprites;
    private final ConcurrentHashMap<BlockState, Appearance> cache = new ConcurrentHashMap<>();

    public BlockAppearances(BlockStateModelSet models, SpriteTable sprites) {
        this.models = models;
        this.sprites = sprites;
    }

    public BlockStateModelSet models() {
        return models;
    }

    public Appearance of(BlockState state) {
        Appearance cached = cache.get(state);
        if (cached != null) {
            return cached;
        }
        Appearance resolved;
        try {
            resolved = resolve(state);
        } catch (RuntimeException | LinkageError error) {
            // A modded model that cannot be read off-thread: fall back to the
            // map colour instead of failing the whole section.
            resolved = Appearance.NONE;
        }
        cache.putIfAbsent(state, resolved);
        return resolved;
    }

    private Appearance resolve(BlockState state) {
        BlockStateModel model = models.get(state);
        if (model == null) {
            return Appearance.NONE;
        }
        List<BlockStateModelPart> parts = new ArrayList<>();
        // Fixed seed: random variants (stone, grass) pick one stable look.
        model.collectParts(RandomSource.create(42L), parts);

        int[] faceSprites = new int[RtBoxFormat.FACE_COUNT];
        int tintedFaces = 0;
        int tintIndex = -1;
        boolean cutout = false;
        for (int face = 0; face < RtBoxFormat.FACE_COUNT; face++) {
            BakedQuad quad = firstQuadFacing(parts, FACES[face]);
            if (quad == null) {
                continue;
            }
            BakedQuad.MaterialInfo material = quad.materialInfo();
            TextureAtlasSprite sprite = material.sprite();
            faceSprites[face] = spriteId(sprite);
            if (material.isTinted()) {
                tintedFaces |= 1 << face;
                if (tintIndex < 0) {
                    tintIndex = material.tintIndex();
                }
            }
            if (material.layer() != ChunkSectionLayer.SOLID) {
                cutout = true;
            }
        }
        // Cross models (flowers, grass) have no axis faces: show the particle
        // sprite on all faces and let the alpha test cut the shape.
        boolean any = false;
        for (int sprite : faceSprites) {
            any |= sprite != 0;
        }
        if (!any) {
            BakedQuad quad = firstQuad(parts);
            TextureAtlasSprite sprite = quad != null
                    ? quad.materialInfo().sprite()
                    : model.particleMaterial() != null ? model.particleMaterial().sprite() : null;
            int id = spriteId(sprite);
            if (id == 0) {
                return Appearance.NONE;
            }
            java.util.Arrays.fill(faceSprites, id);
            if (quad != null) {
                cutout = quad.materialInfo().layer() != ChunkSectionLayer.SOLID;
                if (quad.materialInfo().isTinted()) {
                    tintedFaces = 0x3F;
                    tintIndex = quad.materialInfo().tintIndex();
                }
            }
        }
        return new Appearance(
                RtBoxFormat.packFacePair(faceSprites[0], faceSprites[1]),
                RtBoxFormat.packFacePair(faceSprites[2], faceSprites[3]),
                RtBoxFormat.packFacePair(faceSprites[4], faceSprites[5]),
                tintedFaces, tintIndex, cutout);
    }

    private int spriteId(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return 0;
        }
        return sprites.idFor(sprite, sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
    }

    private static BakedQuad firstQuadFacing(List<BlockStateModelPart> parts, Direction direction) {
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> culled = part.getQuads(direction);
            if (!culled.isEmpty()) {
                return culled.getFirst();
            }
        }
        for (BlockStateModelPart part : parts) {
            for (BakedQuad quad : part.getQuads(null)) {
                if (quad.direction() == direction) {
                    return quad;
                }
            }
        }
        return null;
    }

    private static BakedQuad firstQuad(List<BlockStateModelPart> parts) {
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> unculled = part.getQuads(null);
            if (!unculled.isEmpty()) {
                return unculled.getFirst();
            }
        }
        return null;
    }
}
