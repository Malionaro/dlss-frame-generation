package org.malionaro.dlss_frame_generation.client.raytracing.scene;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SceneFormatTest {
    @Test
    void boxMatchesShaderLayout() {
        ByteBuffer buffer = ByteBuffer.allocate(RtBoxFormat.BYTES_PER_BOX).order(ByteOrder.LITTLE_ENDIAN);
        int faces = RtBoxFormat.packFacePair(3, 7);
        int tint = RtBoxFormat.packTint(0x55AA33, 0b001000, true);
        RtBoxFormat.putBox(buffer, 1, 2, 3, 4, 5, 6, 0x123456, RtBoxFormat.FLAG_WATER,
                faces, faces, faces, tint);
        assertEquals(RtBoxFormat.BYTES_PER_BOX, buffer.position());
        // The first six floats double as VkAabbPositionsKHR.
        assertEquals(1.0f, buffer.getFloat(0));
        assertEquals(4.0f, buffer.getFloat(12));
        assertEquals(6.0f, buffer.getFloat(20));
        assertEquals(0x123456, buffer.getInt(24));
        assertEquals(RtBoxFormat.FLAG_WATER, buffer.getInt(28));
        assertEquals(3, buffer.getInt(32) & 0xFFFF);
        assertEquals(7, buffer.getInt(32) >>> 16);
        int word = buffer.getInt(44);
        assertEquals(0x55AA33, word & 0xFFFFFF);
        assertEquals(0b001000, (word >>> 24) & 0x3F);
        assertEquals(1, (word >>> 30) & 1);
    }

    @Test
    void outOfRangeSpritesFallBackToUntextured() {
        assertEquals(0, RtBoxFormat.packFacePair(-1, 0x10000));
    }

    @Test
    void spriteTableAssignsStableIdsAndReservesZero() {
        SpriteTable table = new SpriteTable();
        Object grass = new Object();
        Object stone = new Object();
        int grassId = table.idFor(grass, 0.0f, 0.0f, 0.25f, 0.25f);
        int stoneId = table.idFor(stone, 0.25f, 0.0f, 0.5f, 0.25f);
        assertEquals(1, grassId);
        assertEquals(2, stoneId);
        assertEquals(grassId, table.idFor(grass, 9, 9, 9, 9));
        assertEquals(0, table.idFor(null, 0, 0, 0, 0));

        ByteBuffer snapshot = table.snapshot();
        assertEquals(3 * SpriteTable.BYTES_PER_SPRITE, snapshot.remaining());
        assertEquals(0.0f, snapshot.getFloat(0));
        assertEquals(0.25f, snapshot.getFloat(SpriteTable.BYTES_PER_SPRITE * 2));

        long version = table.version();
        table.clear();
        assertEquals(1, table.count());
        assertEquals(version + 1, table.version());
    }

    @Test
    void nearestLightsAreSortedAndCapped() {
        List<SceneLights.Emitter> far = List.of(
                SceneLights.emitter(100, 0, 0, 14, 0xFFFFFF, SceneLights.LightTint.FIRE));
        List<SceneLights.Emitter> near = new java.util.ArrayList<>();
        for (int index = 0; index < SceneLights.MAX_LIGHTS + 5; index++) {
            near.add(SceneLights.emitter(index, 0, 0, 10, 0xFFFFFF, SceneLights.LightTint.BLOCK_COLOR));
        }
        ByteBuffer buffer = SceneLights.nearest(List.of(far, near), 0, 0, 0);
        assertEquals(SceneLights.BUFFER_BYTES, buffer.remaining());
        assertEquals(SceneLights.MAX_LIGHTS, buffer.getInt(0));
        // First light is the one at the camera, the far torch is cut.
        assertEquals(0.0f, buffer.getFloat(SceneLights.HEADER_BYTES));
        float lastX = buffer.getFloat(SceneLights.HEADER_BYTES
                + (SceneLights.MAX_LIGHTS - 1) * SceneLights.BYTES_PER_LIGHT);
        assertEquals(SceneLights.MAX_LIGHTS - 1, lastX);
    }
}
