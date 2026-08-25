package org.malionaro.dlss_frame_generation.client.compat;
import org.malionaro.dlss_frame_generation.client.render.adapter.RendererBackend;
import net.fabricmc.loader.api.FabricLoader;

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
        if (FabricLoader.getInstance().isModLoaded("vulkanmod")) {
            return false;
        }
        if (sodiumPresent && !isVersionAtLeast(sodiumVersion, "0.9.1")) {
            return false;
        }
        if (irisPresent && !isVersionAtLeast(irisVersion, "1.11.2")) {
            return false;
        }
        return actualVulkanDevice
                && (backend == RendererBackend.VANILLA_VULKAN
                    || backend == RendererBackend.SODIUM_VULKAN)
                && shaderPipelineState.allowsVulkanFeatures();
    }

    public String blockingReason() {
        if (FabricLoader.getInstance().isModLoaded("vulkanmod")) {
            return "vulkanmod is present and incompatible with the Vulkan FG bridge";
        }
        if (sodiumPresent && !isVersionAtLeast(sodiumVersion, "0.9.1")) {
            return "Sodium version " + sodiumVersion + " is below required 0.9.1";
        }
        if (irisPresent && !isVersionAtLeast(irisVersion, "1.11.2")) {
            return "Iris version " + irisVersion + " is below required 1.11.2";
        }
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

    private static boolean isVersionAtLeast(String version, String required) {
        if (version == null || version.equals("not installed") || version.isBlank()) return false;
        if ("test".equals(version)) return true;
        try {
            String v = version.split("\\+")[0].split("-")[0];
            String r = required.split("\\+")[0].split("-")[0];
            String[] vParts = v.split("\\.");
            String[] rParts = r.split("\\.");
            int len = Math.max(vParts.length, rParts.length);
            for (int i = 0; i < len; i++) {
                int vNum = i < vParts.length ? parseVersionPart(vParts[i]) : 0;
                int rNum = i < rParts.length ? parseVersionPart(rParts[i]) : 0;
                if (vNum > rNum) return true;
                if (vNum < rNum) return false;
            }
            return true;
        } catch (Exception e) {
            return version.compareTo(required) >= 0;
        }
    }

    private static int parseVersionPart(String part) {
        try {
            String numeric = part.replaceAll("[^0-9]", "");
            return numeric.isEmpty() ? 0 : Integer.parseInt(numeric);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
