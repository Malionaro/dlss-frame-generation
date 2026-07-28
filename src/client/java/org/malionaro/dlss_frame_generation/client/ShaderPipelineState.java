package org.malionaro.dlss_frame_generation.client;

/**
 * Renderer/shader state relevant to Vulkan feature safety.
 */
public enum ShaderPipelineState {
    IRIS_NOT_INSTALLED(true),
    IRIS_VULKAN_SHADERPACK_OFF(true),
    IRIS_OPENGL_SHADERPACK_OFF(false),
    IRIS_OPENGL_SHADERPACK_ACTIVE(false),
    IRIS_VULKAN_SHADERPACK_UNKNOWN(false),
    APERTURE_VULKAN_UNVERIFIED(false),
    IRIS_API_UNAVAILABLE(false);

    private final boolean allowsVulkanFeatures;

    ShaderPipelineState(boolean allowsVulkanFeatures) {
        this.allowsVulkanFeatures = allowsVulkanFeatures;
    }

    public boolean allowsVulkanFeatures() {
        return allowsVulkanFeatures;
    }
}
