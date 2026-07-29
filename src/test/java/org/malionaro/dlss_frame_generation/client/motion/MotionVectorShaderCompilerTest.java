package org.malionaro.dlss_frame_generation.client.motion;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionVectorShaderCompilerTest {
    @Test
    void compilesDenseMotionVectorShaderToSpirV() throws Exception {
        assertCompiles(
                "/assets/dlss_fg/shaders/source/motion/frame_generation_motion.comp");
    }

    @Test
    void compilesRayReconstructionTonemapShaderToSpirV() throws Exception {
        assertCompiles(
                "/assets/dlss_fg/shaders/source/raytracing/ray_reconstruction_tonemap.comp");
    }

    @Test
    void compilesMotionBlurShaderToSpirV() throws Exception {
        assertCompiles(
                "/assets/dlss_fg/shaders/source/motion/motion_blur.comp");
    }

    private static void assertCompiles(String resource) throws Exception {
        ByteBuffer spirv = MotionVectorShaderCompiler.compile(resource);
        try {
            assertTrue(spirv.remaining() > 20);
            assertEquals(
                    0x07230203,
                    spirv.order(ByteOrder.LITTLE_ENDIAN).getInt(0)
            );
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }
}
