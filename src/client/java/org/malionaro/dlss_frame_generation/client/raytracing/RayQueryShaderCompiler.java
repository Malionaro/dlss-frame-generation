package org.malionaro.dlss_frame_generation.client.raytracing;

import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.util.shaderc.Shaderc.*;

final class RayQueryShaderCompiler {
    private RayQueryShaderCompiler() {
    }

    static ByteBuffer compile(String resourcePath) throws IOException {
        String source;
        try (InputStream input = RayQueryShaderCompiler.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("Missing shader resource " + resourcePath);
            }
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        if (compiler == MemoryUtil.NULL || options == MemoryUtil.NULL) {
            throw new IOException("Could not initialize shaderc");
        }

        long result = MemoryUtil.NULL;
        try {
            shaderc_compile_options_set_target_env(
                    options,
                    shaderc_target_env_vulkan,
                    shaderc_env_version_vulkan_1_2
            );
            shaderc_compile_options_set_optimization_level(
                    options,
                    shaderc_optimization_level_performance
            );
            result = shaderc_compile_into_spv(
                    compiler,
                    source,
                    shaderc_compute_shader,
                    resourcePath,
                    "main",
                    options
            );
            if (result == MemoryUtil.NULL
                    || shaderc_result_get_compilation_status(result)
                    != shaderc_compilation_status_success) {
                String error = result == MemoryUtil.NULL
                        ? "shaderc returned no result"
                        : shaderc_result_get_error_message(result);
                throw new IOException("Ray-query shader compilation failed: " + error);
            }

            ByteBuffer compiled = shaderc_result_get_bytes(result);
            ByteBuffer copy = MemoryUtil.memAlloc(compiled.remaining());
            copy.put(compiled).flip();
            return copy;
        } finally {
            if (result != MemoryUtil.NULL) {
                shaderc_result_release(result);
            }
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }
}
