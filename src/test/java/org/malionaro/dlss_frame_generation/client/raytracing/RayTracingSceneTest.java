package org.malionaro.dlss_frame_generation.client.raytracing;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RayTracingSceneTest {
    @Test
    void verifiesInitialSceneAndLightBufferLayout() {
        RayTracingScene scene = new RayTracingScene();
        assertEquals(0, scene.blockCount());
        assertEquals(0, scene.lightCount());

        ByteBuffer blocks = scene.blocks();
        assertNotNull(blocks);
        assertEquals(0, blocks.remaining());

        ByteBuffer lightBuffer = scene.lightBuffer();
        assertNotNull(lightBuffer);
        assertEquals(RayTracingScene.LIGHT_BUFFER_BYTES, lightBuffer.capacity());
        assertTrue(RayTracingScene.LIGHT_BUFFER_BYTES >= 16 + 32 * 32);

        assertEquals(0, lightBuffer.getInt(0));
    }
}
