package org.malionaro.dlss_frame_generation.client.raytracing;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RayQueryShaderCompilerTest {
    @Test
    void compilesHybridRayQueryShaderToSpirV() throws Exception {
        ByteBuffer spirv = RayQueryShaderCompiler.compile(
                "/assets/dlss_fg/shaders/source/raytracing/hybrid_ray_query.comp");
        try {
            assertTrue(spirv.remaining() > 20);
            assertEquals(0x07230203, spirv.order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0));
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }
}
