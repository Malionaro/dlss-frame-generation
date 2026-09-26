package org.malionaro.dlss_frame_generation.client.compat;
import org.malionaro.dlss_frame_generation.client.render.adapter.RendererBackend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import net.fabricmc.loader.api.FabricLoader;
import org.malionaro.dlss_frame_generation.client.mixin.GpuDeviceAccessor;

import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RendererDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger("dlss_fg.detector");

    private RendererDetector() {
    }

    public static RendererDetection detect() {
        String description = readBackendDescription();
        com.mojang.renderpearl.api.device.GpuDevice device =
                RenderSystem.tryGetDevice();
        if (device instanceof GpuDeviceAccessor access
                && access.dlss_fg$getBackend()
                        instanceof VulkanDevice vulkanDevice) {
            return detect(vulkanDevice);
        }
        return detectInternal(
                description,
                false,
                device == null
                        ? "unavailable"
                        : device instanceof GpuDeviceAccessor access
                                ? access.dlss_fg$getBackend()
                                        .getClass().getName()
                                : device.getClass().getName()
        );
    }

    public static RendererDetection detect(VulkanDevice device) {
        String description = readBackendDescription();
        return detectInternal(
                description,
                device != null,
                device == null ? "unavailable" : device.getClass().getName()
        );
    }

    static RendererDetection classifyForTest(
            boolean actualVulkan,
            boolean sodiumPresent,
            boolean irisPresent,
            boolean irisApiAvailable,
            boolean shaderPackInUse,
            boolean aperturePresent
    ) {
        return buildDetection(
                actualVulkan ? "Vulkan test backend" : "OpenGL test backend",
                actualVulkan,
                actualVulkan ? VulkanDevice.class.getName() : "OpenGLDevice",
                sodiumPresent,
                sodiumPresent ? "test" : "not installed",
                irisPresent,
                irisPresent ? "test" : "not installed",
                irisApiAvailable,
                shaderPackInUse,
                aperturePresent,
                aperturePresent ? "test" : "not installed"
        );
    }

    private static RendererDetection detectInternal(
            String description,
            boolean actualVulkan,
            String deviceClass
    ) {
        FabricLoader loader = FabricLoader.getInstance();
        boolean sodiumPresent = loader.isModLoaded("sodium");
        boolean irisPresent = loader.isModLoaded("iris");
        boolean aperturePresent = loader.isModLoaded("aperture");
        IrisQuery iris = queryIrisApi(irisPresent);
        return buildDetection(
                description,
                actualVulkan,
                deviceClass,
                sodiumPresent,
                versionOf("sodium"),
                irisPresent,
                versionOf("iris"),
                iris.apiAvailable(),
                iris.shaderPackInUse(),
                aperturePresent,
                versionOf("aperture")
        );
    }

    private static RendererDetection buildDetection(
            String description,
            boolean actualVulkan,
            String deviceClass,
            boolean sodiumPresent,
            String sodiumVersion,
            boolean irisPresent,
            String irisVersion,
            boolean irisApiAvailable,
            boolean shaderPackInUse,
            boolean aperturePresent,
            String apertureVersion
    ) {
        // SemVer gating and vulkanmod incompatibility
        boolean vulkanModPresent = FabricLoader.getInstance().isModLoaded("vulkanmod");
        boolean sodiumVersionOk = !sodiumPresent || isVersionAtLeast(sodiumVersion, "0.9.1");
        boolean irisVersionOk = !irisPresent || isVersionAtLeast(irisVersion, "1.11.2");
        if (vulkanModPresent) {
            LOGGER.warn("Renderer detection: vulkanmod is present and incompatible with DLSS FG bridge - Vulkan features blocked");
        }
        if (sodiumPresent && !sodiumVersionOk) {
            LOGGER.warn("Renderer detection: Sodium version {} is below required 0.9.1", sodiumVersion);
        }
        if (irisPresent && !irisVersionOk) {
            LOGGER.warn("Renderer detection: Iris version {} is below required 1.11.2", irisVersion);
        }
        RendererBackend backend;
        if ("unavailable".equals(deviceClass)) {
            backend = RendererBackend.UNKNOWN;
        } else if (actualVulkan) {
            backend = sodiumPresent
                    ? RendererBackend.SODIUM_VULKAN
                    : RendererBackend.VANILLA_VULKAN;
        } else {
            backend = irisPresent
                    ? RendererBackend.IRIS_OPENGL
                    : RendererBackend.OPENGL;
        }
        ShaderPipelineState shaderState;
        if (!irisPresent) {
            shaderState = ShaderPipelineState.IRIS_NOT_INSTALLED;
        } else if (!irisApiAvailable) {
            shaderState = ShaderPipelineState.IRIS_API_UNAVAILABLE;
        } else if (!actualVulkan && shaderPackInUse) {
            shaderState = ShaderPipelineState.IRIS_OPENGL_SHADERPACK_ACTIVE;
        } else if (!actualVulkan) {
            shaderState = ShaderPipelineState.IRIS_OPENGL_SHADERPACK_OFF;
        } else if (!shaderPackInUse) {
            shaderState = ShaderPipelineState.IRIS_VULKAN_SHADERPACK_OFF;
        } else if (aperturePresent) {
            shaderState = ShaderPipelineState.APERTURE_VULKAN_UNVERIFIED;
        } else {
            shaderState = ShaderPipelineState.IRIS_VULKAN_SHADERPACK_UNKNOWN;
        }
        return new RendererDetection(
                backend,
                description,
                deviceClass,
                actualVulkan,
                sodiumPresent,
                sodiumVersion,
                sodiumPresent && actualVulkan
                        ? "Mojang VulkanDevice resources"
                        : "inactive",
                irisPresent,
                irisVersion,
                shaderPackInUse,
                irisPresent && irisApiAvailable,
                aperturePresent,
                apertureVersion,
                shaderState
        );
    }

    private static IrisQuery queryIrisApi(boolean irisPresent) {
        if (!irisPresent) {
            return new IrisQuery(false, false);
        }
        try {
            Class<?> apiClass = Class.forName(
                    "net.irisshaders.iris.api.v0.IrisApi",
                    false,
                    RendererDetector.class.getClassLoader()
            );
            Method getInstance = apiClass.getMethod("getInstance");
            Object api = getInstance.invoke(null);
            Method isShaderPackInUse =
                    apiClass.getMethod("isShaderPackInUse");
            return new IrisQuery(
                    true,
                    Boolean.TRUE.equals(isShaderPackInUse.invoke(api))
            );
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.debug("Failed to query IrisApi via reflection", e);
            return new IrisQuery(false, false);
        }
    }

    private static String versionOf(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(container -> container.getMetadata()
                        .getVersion().getFriendlyString())
                .orElse("not installed");
    }

    static boolean isVersionAtLeast(String version, String required) {
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
            LOGGER.debug("Failed to compare versions: {} vs {}", version, required, e);
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

    private static String readBackendDescription() {
        try {
            String description = RenderSystem.getBackendDescription();
            return description == null || description.isBlank() ? "unavailable" : description;
        } catch (RuntimeException exception) {
            return "unavailable: " + exception.getClass().getSimpleName();
        }
    }

    private record IrisQuery(
            boolean apiAvailable,
            boolean shaderPackInUse
    ) {
    }
}
