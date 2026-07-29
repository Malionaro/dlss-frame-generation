package org.malionaro.dlss_frame_generation.client.compat;
import org.malionaro.dlss_frame_generation.client.render.adapter.RendererBackend;

public record RendererDetection(
        RendererBackend backend,
        String backendDescription,
        String gpuDeviceClass,
        boolean actualVulkanDevice,
        boolean sodiumPresent,
        String sodiumVersion,
        String sodiumDrawBackend,
        boolean irisPresent,
        String irisVersion,
        boolean irisShaderPackInUse,
        boolean irisShaderPackStateKnown,
        boolean aperturePresent,
        String apertureVersion,
        ShaderPipelineState shaderPipelineState
) {
    public boolean allowsVulkanFeatures() {
        return actualVulkanDevice
                && (backend == RendererBackend.VANILLA_VULKAN
                    || backend == RendererBackend.SODIUM_VULKAN)
                && shaderPipelineState.allowsVulkanFeatures();
    }

    public String blockingReason() {
        if (!actualVulkanDevice) {
            return "The active GpuDevice is not Mojang's VulkanDevice";
        }
        return switch (shaderPipelineState) {
            case IRIS_NOT_INSTALLED, IRIS_VULKAN_SHADERPACK_OFF -> "";
            case IRIS_OPENGL_SHADERPACK_OFF ->
                    "Iris is running with OpenGL; Vulkan resources are unavailable";
            case IRIS_OPENGL_SHADERPACK_ACTIVE ->
                    "An Iris OpenGL shader pack is active";
            case IRIS_VULKAN_SHADERPACK_UNKNOWN ->
                    "Iris reports an active Vulkan shader pack without a supported integration";
            case APERTURE_VULKAN_UNVERIFIED ->
                    "Aperture was detected, but no stable frame-resource API is available";
            case IRIS_API_UNAVAILABLE ->
                    "The installed Iris version could not be queried through IrisApi v0";
        };
    }
}
