package org.malionaro.dlss_frame_generation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import net.fabricmc.loader.api.FabricLoader;
import org.malionaro.dlss_frame_generation.client.mixin.GpuDeviceAccessor;

import java.lang.reflect.Method;

public final class RendererDetector {
    private RendererDetector() {
    }

    public static RendererDetection detect() {
        String description = readBackendDescription();
        com.mojang.blaze3d.systems.GpuDevice device =
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
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return new IrisQuery(false, false);
        }
    }

    private static String versionOf(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(container -> container.getMetadata()
                        .getVersion().getFriendlyString())
                .orElse("not installed");
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
