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

    @Test
    void compilesEveryPerBindingProbeShader() throws Exception {
        for (int binding = 0; binding < 16; binding++) {
            ByteBuffer spirv = RayQueryShaderCompiler.compileSource(
                    VulkanRayQueryRenderer.bindingProbeSource(binding), "binding_probe_" + binding + ".comp");
            try {
                assertEquals(0x07230203, spirv.order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0));
            } finally {
                MemoryUtil.memFree(spirv);
            }
        }
    }

    @Test
    void compilesDiagnosticProbeShaders() throws Exception {
        for (String source : new String[] {
                VulkanRayQueryRenderer.PROBE_SHADER,
                VulkanRayQueryRenderer.LAYOUT_PROBE_SHADER,
                VulkanRayQueryRenderer.RAY_QUERY_PROBE_SHADER}) {
            ByteBuffer spirv = RayQueryShaderCompiler.compileSource(source, "probe.comp");
            try {
                assertEquals(0x07230203, spirv.order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0));
            } finally {
                MemoryUtil.memFree(spirv);
            }
        }
    }
}
