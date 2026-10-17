package org.malionaro.dlss_frame_generation.client.raytracing;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtShaderPacksTest {
    @Test
    void packNamesCannotLeaveTheFolder() {
        assertTrue(RtShaderPacks.isSafeName("my-pack"));
        assertTrue(RtShaderPacks.isSafeName("Sunny RT.zip"));
        assertFalse(RtShaderPacks.isSafeName("../mods"));
        assertFalse(RtShaderPacks.isSafeName("a/b"));
        assertFalse(RtShaderPacks.isSafeName("a\\b"));
        assertFalse(RtShaderPacks.isSafeName("C:x"));
        assertFalse(RtShaderPacks.isSafeName(""));
    }

    @Test
    void builtInSourceCompilesThroughThePackPath() throws Exception {
        ByteBuffer spirv = RayQueryShaderCompiler.compileSource(RtShaderPacks.builtIn(), "built-in");
        try {
            assertTrue(spirv.remaining() > 20);
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }
}
