#include "FidelityFxBridge.h"

#if defined(_WIN32)
#include <windows.h>
#endif

#include <algorithm>
#include <filesystem>
#include <iostream>
#include <mutex>

#include "ffx_api/ffx_api.h"
#include "ffx_api/ffx_framegeneration.h"
#include "ffx_api/ffx_upscale.h"
#include "ffx_api/vk/ffx_api_vk.h"

namespace fidelityfx {
namespace {

std::mutex g_mutex;

#if defined(_WIN32)
HMODULE g_module = nullptr;
#endif

PfnFfxCreateContext g_createContext = nullptr;
PfnFfxDestroyContext g_destroyContext = nullptr;
PfnFfxConfigure g_configure = nullptr;
PfnFfxQuery g_query = nullptr;
PfnFfxDispatch g_dispatch = nullptr;

ffxContext g_swapchainContext = nullptr;
ffxContext g_frameGenerationContext = nullptr;
ffxContext g_upscaleContext = nullptr;
VkPhysicalDevice g_physicalDevice = VK_NULL_HANDLE;
VkDevice g_device = VK_NULL_HANDLE;
VkQueue g_graphicsQueue = VK_NULL_HANDLE;
uint32_t g_graphicsQueueFamily = 0;
uint32_t g_frameGenerationWidth = 0;
uint32_t g_frameGenerationHeight = 0;
VkFormat g_frameGenerationBackbufferFormat = VK_FORMAT_UNDEFINED;
VkFormat g_frameGenerationHudlessFormat = VK_FORMAT_UNDEFINED;
uint64_t g_frameId = 0;
bool g_frameGenerationInputsReady = false;
uint32_t g_upscaleRenderWidth = 0;
uint32_t g_upscaleRenderHeight = 0;
uint32_t g_upscaleOutputWidth = 0;
uint32_t g_upscaleOutputHeight = 0;
VkFormat g_upscaleColorFormat = VK_FORMAT_UNDEFINED;
VkFormat g_upscaleOutputFormat = VK_FORMAT_UNDEFINED;
int g_upscaleMode = 0;
float g_upscaleSharpness = 0.5f;
bool g_upscalingInputsReady = false;
bool g_optiScalerLoaded = false;

PFN_vkCreateSwapchainFFXAPI g_createSwapchain = nullptr;
PFN_vkDestroySwapchainFFXAPI g_destroySwapchain = nullptr;
PFN_vkGetSwapchainImagesKHR g_getSwapchainImages = nullptr;
PFN_vkAcquireNextImageKHR g_acquireNextImage = nullptr;
PFN_vkQueuePresentKHR g_queuePresent = nullptr;

bool g_runtimeReady = false;
std::string g_status = "FidelityFX Vulkan runtime has not been initialized";

void destroyFrameGenerationContext() {
    if (g_frameGenerationContext != nullptr && g_destroyContext != nullptr) {
        g_destroyContext(&g_frameGenerationContext, nullptr);
    }
    g_frameGenerationContext = nullptr;
    g_frameGenerationWidth = 0;
    g_frameGenerationHeight = 0;
    g_frameGenerationBackbufferFormat = VK_FORMAT_UNDEFINED;
    g_frameGenerationHudlessFormat = VK_FORMAT_UNDEFINED;
    g_frameId = 0;
    g_frameGenerationInputsReady = false;
}

void destroyUpscaleContext() {
    if (g_upscaleContext != nullptr && g_destroyContext != nullptr) {
        g_destroyContext(&g_upscaleContext, nullptr);
    }
    g_upscaleContext = nullptr;
    g_upscaleRenderWidth = 0;
    g_upscaleRenderHeight = 0;
    g_upscaleOutputWidth = 0;
    g_upscaleOutputHeight = 0;
    g_upscaleColorFormat = VK_FORMAT_UNDEFINED;
    g_upscaleOutputFormat = VK_FORMAT_UNDEFINED;
    g_upscalingInputsReady = false;
}

void clearReplacementFunctions() {
    g_createSwapchain = nullptr;
    g_destroySwapchain = nullptr;
    g_getSwapchainImages = nullptr;
    g_acquireNextImage = nullptr;
    g_queuePresent = nullptr;
}

void disableRuntime(const std::string& reason) {
    destroyUpscaleContext();
    destroyFrameGenerationContext();
    if (g_swapchainContext != nullptr && g_destroyContext != nullptr) {
        g_destroyContext(&g_swapchainContext, nullptr);
        g_swapchainContext = nullptr;
    }
    clearReplacementFunctions();
    g_runtimeReady = false;
    g_status = reason;
}

FfxApiResource makeImageResource(
    VkImage image,
    VkFormat format,
    uint32_t width,
    uint32_t height,
    VkImageUsageFlags usage,
    uint32_t state
) {
    VkImageCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    createInfo.imageType = VK_IMAGE_TYPE_2D;
    createInfo.format = format;
    createInfo.extent = { width, height, 1 };
    createInfo.mipLevels = 1;
    createInfo.arrayLayers = 1;
    createInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    createInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    createInfo.usage = usage;
    createInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    createInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    return ffxApiGetResourceVK(
        reinterpret_cast<void*>(image),
        ffxApiGetImageResourceDescriptionVK(image, createInfo, 0),
        state
    );
}

ffxReturnCode_t frameGenerationDispatchCallback(
    ffxDispatchDescFrameGeneration* params,
    void*
) {
    if (params == nullptr || g_dispatch == nullptr ||
        g_frameGenerationContext == nullptr) {
        return FFX_API_RETURN_ERROR_PARAMETER;
    }
    return g_dispatch(
        &g_frameGenerationContext,
        reinterpret_cast<const ffxDispatchDescHeader*>(&params->header)
    );
}

bool ensureFrameGenerationContext(const FrameGenerationInputs& inputs) {
    if (g_frameGenerationContext != nullptr &&
        g_frameGenerationWidth == inputs.width &&
        g_frameGenerationHeight == inputs.height &&
        g_frameGenerationBackbufferFormat == inputs.backbufferFormat &&
        g_frameGenerationHudlessFormat == inputs.hudlessColorFormat) {
        return true;
    }

    destroyFrameGenerationContext();

    ffxCreateBackendVKDesc backend{};
    backend.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_BACKEND_VK;
    backend.vkDevice = g_device;
    backend.vkPhysicalDevice = g_physicalDevice;
    backend.vkDeviceProcAddr = vkGetDeviceProcAddr;

    ffxCreateContextDescFrameGenerationHudless hudless{};
    hudless.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_FRAMEGENERATION_HUDLESS;
    hudless.header.pNext = &backend.header;
    hudless.hudlessBackBufferFormat =
        ffxApiGetSurfaceFormatVK(inputs.hudlessColorFormat);

    ffxCreateContextDescFrameGeneration desc{};
    desc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_FRAMEGENERATION;
    desc.header.pNext = &hudless.header;
    desc.flags =
        FFX_FRAMEGENERATION_ENABLE_DISPLAY_RESOLUTION_MOTION_VECTORS |
        FFX_FRAMEGENERATION_ENABLE_DEPTH_INVERTED;
    desc.displaySize = { inputs.width, inputs.height };
    desc.maxRenderSize = { inputs.width, inputs.height };
    desc.backBufferFormat = ffxApiGetSurfaceFormatVK(inputs.backbufferFormat);

    const ffxReturnCode_t result = g_createContext(
        &g_frameGenerationContext,
        reinterpret_cast<ffxCreateContextDescHeader*>(&desc),
        nullptr
    );
    if (result != FFX_API_RETURN_OK || g_frameGenerationContext == nullptr) {
        g_status = "FidelityFX frame-generation context creation failed (" +
            std::to_string(result) + ")";
        destroyFrameGenerationContext();
        return false;
    }

    g_frameGenerationWidth = inputs.width;
    g_frameGenerationHeight = inputs.height;
    g_frameGenerationBackbufferFormat = inputs.backbufferFormat;
    g_frameGenerationHudlessFormat = inputs.hudlessColorFormat;
    return true;
}

bool ensureUpscaleContext(const UpscalingInputs& inputs) {
    if (g_upscaleContext != nullptr &&
        g_upscaleRenderWidth == inputs.renderWidth &&
        g_upscaleRenderHeight == inputs.renderHeight &&
        g_upscaleOutputWidth == inputs.outputWidth &&
        g_upscaleOutputHeight == inputs.outputHeight &&
        g_upscaleColorFormat == inputs.colorFormat &&
        g_upscaleOutputFormat == inputs.outputFormat) {
        return true;
    }

    destroyUpscaleContext();

    ffxCreateBackendVKDesc backend{};
    backend.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_BACKEND_VK;
    backend.vkDevice = g_device;
    backend.vkPhysicalDevice = g_physicalDevice;
    backend.vkDeviceProcAddr = vkGetDeviceProcAddr;

    ffxCreateContextDescUpscale desc{};
    desc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE;
    desc.header.pNext = &backend.header;
    desc.flags =
        FFX_UPSCALE_ENABLE_DISPLAY_RESOLUTION_MOTION_VECTORS |
        FFX_UPSCALE_ENABLE_DEPTH_INVERTED;
    desc.maxRenderSize = { inputs.renderWidth, inputs.renderHeight };
    desc.maxUpscaleSize = { inputs.outputWidth, inputs.outputHeight };

    const ffxReturnCode_t result = g_createContext(
        &g_upscaleContext,
        reinterpret_cast<ffxCreateContextDescHeader*>(&desc),
        nullptr
    );
    if (result != FFX_API_RETURN_OK || g_upscaleContext == nullptr) {
        g_status = "FidelityFX/OptiScaler upscale context creation failed (" +
            std::to_string(result) + ")";
        destroyUpscaleContext();
        return false;
    }

    g_upscaleRenderWidth = inputs.renderWidth;
    g_upscaleRenderHeight = inputs.renderHeight;
    g_upscaleOutputWidth = inputs.outputWidth;
    g_upscaleOutputHeight = inputs.outputHeight;
    g_upscaleColorFormat = inputs.colorFormat;
    g_upscaleOutputFormat = inputs.outputFormat;
    return true;
}

bool queryReplacementFunctions() {
    ffxQueryDescSwapchainReplacementFunctionsVK query{};
    query.header.type = FFX_API_QUERY_DESC_TYPE_FGSWAPCHAIN_FUNCTIONS_VK;
    const ffxReturnCode_t result = g_query(
        &g_swapchainContext,
        reinterpret_cast<ffxQueryDescHeader*>(&query)
    );
    if (result != FFX_API_RETURN_OK) {
        disableRuntime("FidelityFX proxy function query failed (" + std::to_string(result) + ")");
        return false;
    }

    g_createSwapchain = query.pOutCreateSwapchainFFXAPI;
    g_destroySwapchain = query.pOutDestroySwapchainFFXAPI;
    g_getSwapchainImages = query.pOutGetSwapchainImagesKHR;
    g_acquireNextImage = query.pOutAcquireNextImageKHR;
    g_queuePresent = query.pOutQueuePresentKHR;

    if (g_createSwapchain == nullptr || g_destroySwapchain == nullptr ||
        g_getSwapchainImages == nullptr || g_acquireNextImage == nullptr ||
        g_queuePresent == nullptr) {
        disableRuntime("FidelityFX returned an incomplete Vulkan proxy function table");
        return false;
    }
    return true;
}

} // namespace

bool initialize(
    const std::wstring& runtimeDirectory,
    VkPhysicalDevice physicalDevice,
    VkDevice device,
    VkQueue graphicsQueue,
    uint32_t graphicsQueueFamily
) {
    std::lock_guard lock(g_mutex);
    if (g_runtimeReady) return true;

#if !defined(_WIN32)
    g_status = "FidelityFX Frame Generation proxy is currently bundled for Windows only";
    return false;
#else
    if (physicalDevice == VK_NULL_HANDLE || device == VK_NULL_HANDLE || graphicsQueue == VK_NULL_HANDLE) {
        g_status = "FidelityFX received incomplete Vulkan device handles";
        return false;
    }

    const std::filesystem::path dllPath =
        std::filesystem::path(runtimeDirectory) / L"amd_fidelityfx_vk.dll";
    g_optiScalerLoaded = GetModuleHandleW(L"OptiScaler.dll") != nullptr;
    g_module = LoadLibraryW(dllPath.c_str());
    if (g_module == nullptr) {
        g_status = "amd_fidelityfx_vk.dll could not be loaded (Windows error " +
            std::to_string(GetLastError()) + ")";
        return false;
    }

    g_createContext = reinterpret_cast<PfnFfxCreateContext>(
        GetProcAddress(g_module, "ffxCreateContext")
    );
    g_destroyContext = reinterpret_cast<PfnFfxDestroyContext>(
        GetProcAddress(g_module, "ffxDestroyContext")
    );
    g_configure = reinterpret_cast<PfnFfxConfigure>(
        GetProcAddress(g_module, "ffxConfigure")
    );
    g_query = reinterpret_cast<PfnFfxQuery>(GetProcAddress(g_module, "ffxQuery"));
    g_dispatch = reinterpret_cast<PfnFfxDispatch>(
        GetProcAddress(g_module, "ffxDispatch")
    );
    if (g_createContext == nullptr || g_destroyContext == nullptr ||
        g_configure == nullptr || g_query == nullptr || g_dispatch == nullptr) {
        g_status = "amd_fidelityfx_vk.dll is missing required FFX API exports";
        FreeLibrary(g_module);
        g_module = nullptr;
        return false;
    }

    g_physicalDevice = physicalDevice;
    g_device = device;
    g_graphicsQueue = graphicsQueue;
    g_graphicsQueueFamily = graphicsQueueFamily;
    g_runtimeReady = true;
    g_status =
        std::string(g_optiScalerLoaded
            ? "OptiScaler loaded; "
            : "") +
        "FSR 3.1 Vulkan runtime loaded, waiting for proxy swapchain";
    std::cout << "[DLSS_FG_Native] " << g_status << std::endl;
    return true;
#endif
}

void shutdown() {
    std::lock_guard lock(g_mutex);
    destroyUpscaleContext();
    destroyFrameGenerationContext();
    if (g_swapchainContext != nullptr && g_destroyContext != nullptr) {
        g_destroyContext(&g_swapchainContext, nullptr);
        g_swapchainContext = nullptr;
    }
    clearReplacementFunctions();
    g_runtimeReady = false;
    g_physicalDevice = VK_NULL_HANDLE;
    g_device = VK_NULL_HANDLE;
    g_graphicsQueue = VK_NULL_HANDLE;
#if defined(_WIN32)
    if (g_module != nullptr) {
        FreeLibrary(g_module);
        g_module = nullptr;
    }
#endif
    g_createContext = nullptr;
    g_destroyContext = nullptr;
    g_configure = nullptr;
    g_query = nullptr;
    g_dispatch = nullptr;
    g_upscaleMode = 0;
    g_upscaleSharpness = 0.5f;
    g_optiScalerLoaded = false;
    g_status = "FidelityFX Vulkan runtime shut down";
}

bool proxyAvailable() {
    std::lock_guard lock(g_mutex);
    return g_runtimeReady;
}

bool proxyActive() {
    std::lock_guard lock(g_mutex);
    return g_runtimeReady && g_swapchainContext != nullptr;
}

bool frameGenerationReady() {
    std::lock_guard lock(g_mutex);
    return g_runtimeReady && g_swapchainContext != nullptr &&
        g_frameGenerationContext != nullptr && g_frameGenerationInputsReady;
}

bool upscalingAvailable() {
    std::lock_guard lock(g_mutex);
    return g_runtimeReady && g_createContext != nullptr &&
        g_destroyContext != nullptr && g_dispatch != nullptr;
}

bool upscalingReady() {
    std::lock_guard lock(g_mutex);
    return g_runtimeReady && g_upscaleContext != nullptr &&
        g_upscalingInputsReady;
}

void setUpscalingMode(int mode, float sharpness) {
    std::lock_guard lock(g_mutex);
    // Minecraft currently has a full-resolution combined world/UI target.
    // Mode 1 is therefore a real 1:1 temporal Native-AA pass. Reduced-
    // resolution Quality/Performance modes stay unavailable until the targets
    // are split.
    g_upscaleMode = mode == 1 ? 1 : 0;
    g_upscaleSharpness = std::clamp(sharpness, 0.0f, 1.0f);
    if (g_upscaleMode == 0) {
        g_upscalingInputsReady = false;
    }
}

bool submitUpscalingInputs(const UpscalingInputs& inputs) {
    std::lock_guard lock(g_mutex);
    if (g_upscaleMode == 0) {
        g_upscalingInputsReady = false;
        return false;
    }
    if (!g_runtimeReady || g_createContext == nullptr ||
        g_dispatch == nullptr || inputs.commandBuffer == VK_NULL_HANDLE ||
        inputs.color == VK_NULL_HANDLE ||
        inputs.colorFormat == VK_FORMAT_UNDEFINED ||
        inputs.depth == VK_NULL_HANDLE ||
        inputs.depthFormat == VK_FORMAT_UNDEFINED ||
        inputs.motionVectors == VK_NULL_HANDLE ||
        inputs.motionVectorsFormat == VK_FORMAT_UNDEFINED ||
        inputs.output == VK_NULL_HANDLE ||
        inputs.outputFormat == VK_FORMAT_UNDEFINED ||
        inputs.renderWidth == 0 || inputs.renderHeight == 0 ||
        inputs.outputWidth == 0 || inputs.outputHeight == 0) {
        g_upscalingInputsReady = false;
        g_status = "FidelityFX/OptiScaler upscaling is waiting for complete renderer resources";
        return false;
    }

    if (!ensureUpscaleContext(inputs)) {
        return false;
    }

    ffxDispatchDescUpscale dispatch{};
    dispatch.header.type = FFX_API_DISPATCH_DESC_TYPE_UPSCALE;
    dispatch.commandList = reinterpret_cast<void*>(inputs.commandBuffer);
    dispatch.color = makeImageResource(
        inputs.color,
        inputs.colorFormat,
        inputs.renderWidth,
        inputs.renderHeight,
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        FFX_API_RESOURCE_STATE_COMPUTE_READ
    );
    dispatch.depth = makeImageResource(
        inputs.depth,
        inputs.depthFormat,
        inputs.renderWidth,
        inputs.renderHeight,
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        FFX_API_RESOURCE_STATE_COMPUTE_READ
    );
    dispatch.motionVectors = makeImageResource(
        inputs.motionVectors,
        inputs.motionVectorsFormat,
        inputs.outputWidth,
        inputs.outputHeight,
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
            VK_IMAGE_USAGE_STORAGE_BIT,
        FFX_API_RESOURCE_STATE_COMPUTE_READ
    );
    dispatch.output = makeImageResource(
        inputs.output,
        inputs.outputFormat,
        inputs.outputWidth,
        inputs.outputHeight,
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
        FFX_API_RESOURCE_STATE_UNORDERED_ACCESS
    );
    dispatch.jitterOffset = { inputs.jitterX, inputs.jitterY };
    dispatch.motionVectorScale = { 1.0f, 1.0f };
    dispatch.renderSize = { inputs.renderWidth, inputs.renderHeight };
    dispatch.upscaleSize = { inputs.outputWidth, inputs.outputHeight };
    dispatch.enableSharpening = g_upscaleSharpness > 0.0f;
    dispatch.sharpness = g_upscaleSharpness;
    dispatch.frameTimeDelta = inputs.frameTimeMs;
    dispatch.preExposure = 1.0f;
    dispatch.reset = inputs.reset;
    dispatch.cameraNear = inputs.cameraNear;
    dispatch.cameraFar = inputs.cameraFar;
    dispatch.cameraFovAngleVertical = inputs.verticalFov;
    dispatch.viewSpaceToMetersFactor = 1.0f;

    const ffxReturnCode_t result = g_dispatch(
        &g_upscaleContext,
        reinterpret_cast<const ffxDispatchDescHeader*>(&dispatch.header)
    );
    if (result != FFX_API_RETURN_OK) {
        g_upscalingInputsReady = false;
        g_status = "FidelityFX/OptiScaler Native-AA dispatch failed (" +
            std::to_string(result) + ")";
        return false;
    }

    g_upscalingInputsReady = true;
    g_status = g_optiScalerLoaded
        ? "FFX Vulkan Native-AA dispatch accepted while OptiScaler is loaded"
        : "FSR 3.1 Vulkan Native-AA active";
    return true;
}

const std::string& status() {
    return g_status;
}

bool submitFrameGenerationInputs(const FrameGenerationInputs& inputs) {
    std::lock_guard lock(g_mutex);
    if (!g_runtimeReady || g_swapchainContext == nullptr ||
        g_createContext == nullptr || g_configure == nullptr ||
        g_dispatch == nullptr || inputs.commandBuffer == VK_NULL_HANDLE ||
        inputs.backbuffer == VK_NULL_HANDLE ||
        inputs.backbufferFormat == VK_FORMAT_UNDEFINED ||
        inputs.hudlessColor == VK_NULL_HANDLE ||
        inputs.hudlessColorFormat == VK_FORMAT_UNDEFINED ||
        inputs.depth == VK_NULL_HANDLE ||
        inputs.depthFormat == VK_FORMAT_UNDEFINED ||
        inputs.motionVectors == VK_NULL_HANDLE ||
        inputs.motionVectorsFormat == VK_FORMAT_UNDEFINED ||
        inputs.width == 0 || inputs.height == 0) {
        g_frameGenerationInputsReady = false;
        g_status = "FidelityFX frame generation is waiting for complete renderer resources";
        return false;
    }

    if (!ensureFrameGenerationContext(inputs)) {
        return false;
    }

    const FfxApiResource hudlessColor = makeImageResource(
        inputs.hudlessColor,
        inputs.hudlessColorFormat,
        inputs.width,
        inputs.height,
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        FFX_API_RESOURCE_STATE_COMPUTE_READ
    );
    const FfxApiResource depth = makeImageResource(
        inputs.depth,
        inputs.depthFormat,
        inputs.width,
        inputs.height,
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        FFX_API_RESOURCE_STATE_COMPUTE_READ
    );
    const FfxApiResource motionVectors = makeImageResource(
        inputs.motionVectors,
        inputs.motionVectorsFormat,
        inputs.width,
        inputs.height,
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
            VK_IMAGE_USAGE_STORAGE_BIT,
        FFX_API_RESOURCE_STATE_COMPUTE_READ
    );

    ffxConfigureDescFrameGeneration configure{};
    configure.header.type = FFX_API_CONFIGURE_DESC_TYPE_FRAMEGENERATION;
    configure.swapChain = g_swapchainContext;
    configure.frameGenerationCallback = frameGenerationDispatchCallback;
    configure.frameGenerationEnabled = inputs.enabled;
    configure.allowAsyncWorkloads = false;
    configure.HUDLessColor = hudlessColor;
    configure.generationRect = {
        0, 0, static_cast<int32_t>(inputs.width),
        static_cast<int32_t>(inputs.height)
    };
    configure.frameID = g_frameId;

    const ffxReturnCode_t configureResult = g_configure(
        &g_frameGenerationContext,
        reinterpret_cast<const ffxConfigureDescHeader*>(&configure.header)
    );
    if (configureResult != FFX_API_RETURN_OK) {
        g_frameGenerationInputsReady = false;
        g_status = "FidelityFX frame-generation configuration failed (" +
            std::to_string(configureResult) + ")";
        return false;
    }

    ffxDispatchDescFrameGenerationPrepareCameraInfo camera{};
    camera.header.type =
        FFX_API_DISPATCH_DESC_TYPE_FRAMEGENERATION_PREPARE_CAMERAINFO;
    std::copy_n(inputs.cameraPosition, 3, camera.cameraPosition);
    std::copy_n(inputs.cameraUp, 3, camera.cameraUp);
    std::copy_n(inputs.cameraRight, 3, camera.cameraRight);
    std::copy_n(inputs.cameraForward, 3, camera.cameraForward);

    ffxDispatchDescFrameGenerationPrepare prepare{};
    prepare.header.type = FFX_API_DISPATCH_DESC_TYPE_FRAMEGENERATION_PREPARE;
    prepare.header.pNext = &camera.header;
    prepare.frameID = g_frameId;
    prepare.commandList = reinterpret_cast<void*>(inputs.commandBuffer);
    prepare.renderSize = { inputs.width, inputs.height };
    prepare.jitterOffset = { inputs.jitterX, inputs.jitterY };
    prepare.motionVectorScale = { 1.0f, 1.0f };
    prepare.frameTimeDelta = inputs.frameTimeMs;
    prepare.unused_reset = inputs.reset;
    prepare.cameraNear = inputs.cameraNear;
    prepare.cameraFar = inputs.cameraFar;
    prepare.cameraFovAngleVertical = inputs.verticalFov;
    prepare.viewSpaceToMetersFactor = 1.0f;
    prepare.depth = depth;
    prepare.motionVectors = motionVectors;

    const ffxReturnCode_t dispatchResult = g_dispatch(
        &g_frameGenerationContext,
        reinterpret_cast<const ffxDispatchDescHeader*>(&prepare.header)
    );
    if (dispatchResult != FFX_API_RETURN_OK) {
        g_frameGenerationInputsReady = false;
        g_status = "FidelityFX frame-generation prepare failed (" +
            std::to_string(dispatchResult) + ")";
        return false;
    }

    ++g_frameId;
    g_frameGenerationInputsReady = true;
    g_status = inputs.enabled
        ? std::string("FSR 3.1 Vulkan Frame Generation active with renderer-owned depth, motion vectors and HUD-less color") +
            (g_upscalingInputsReady
                ? (g_optiScalerLoaded ? "; OptiScaler upscaling active" : "; Native-AA active")
                : "")
        : "FSR 3.1 Vulkan inputs valid; Frame Generation is disabled in settings";
    return true;
}

int32_t createSwapchain(
    const VkSwapchainCreateInfoKHR* createInfo,
    const VkAllocationCallbacks* allocator,
    VkSwapchainKHR* swapchain
) {
    std::lock_guard lock(g_mutex);
    if (!g_runtimeReady || createInfo == nullptr || swapchain == nullptr) return kNotHandled;

    if (g_swapchainContext == nullptr) {
        VkSwapchainKHR createdSwapchain = VK_NULL_HANDLE;
        VkQueueInfoFFXAPI queueInfo{};
        queueInfo.queue = g_graphicsQueue;
        queueInfo.familyIndex = g_graphicsQueueFamily;

        ffxCreateContextDescFrameGenerationSwapChainVK desc{};
        desc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_FGSWAPCHAIN_VK;
        desc.physicalDevice = g_physicalDevice;
        desc.device = g_device;
        desc.swapchain = &createdSwapchain;
        desc.allocator = const_cast<VkAllocationCallbacks*>(allocator);
        desc.createInfo = *createInfo;
        desc.gameQueue = queueInfo;
        desc.asyncComputeQueue = queueInfo;
        desc.presentQueue = queueInfo;
        desc.imageAcquireQueue = queueInfo;

        const ffxReturnCode_t result = g_createContext(
            &g_swapchainContext,
            reinterpret_cast<ffxCreateContextDescHeader*>(&desc),
            nullptr
        );
        if (result != FFX_API_RETURN_OK || g_swapchainContext == nullptr ||
            createdSwapchain == VK_NULL_HANDLE) {
            disableRuntime("FidelityFX proxy swapchain creation failed (" +
                std::to_string(result) + "); using regular Vulkan presentation");
            return kNotHandled;
        }
        if (!queryReplacementFunctions()) return kNotHandled;

        *swapchain = createdSwapchain;
        g_status =
            std::string(g_optiScalerLoaded ? "OptiScaler loaded; " : "") +
            "FSR 3.1 Vulkan proxy active, interpolation waits for dense motion vectors";
        std::cout << "[DLSS_FG_Native] " << g_status << std::endl;
        return VK_SUCCESS;
    }

    const VkResult result = g_createSwapchain(
        g_device,
        createInfo,
        allocator,
        swapchain,
        g_swapchainContext
    );
    if (result != VK_SUCCESS) {
        g_status = "FidelityFX proxy swapchain recreation failed (" +
            std::to_string(static_cast<int32_t>(result)) + ")";
    }
    return static_cast<int32_t>(result);
}

void destroySwapchain(
    VkDevice device,
    VkSwapchainKHR swapchain,
    const VkAllocationCallbacks* allocator
) {
    std::lock_guard lock(g_mutex);
    if (g_swapchainContext != nullptr && g_destroySwapchain != nullptr) {
        g_destroySwapchain(device, swapchain, allocator, g_swapchainContext);
        return;
    }
    const auto destroy = reinterpret_cast<PFN_vkDestroySwapchainKHR>(
        vkGetDeviceProcAddr(device, "vkDestroySwapchainKHR")
    );
    if (destroy != nullptr) destroy(device, swapchain, allocator);
}

int32_t getSwapchainImages(
    VkDevice device,
    VkSwapchainKHR swapchain,
    uint32_t* count,
    VkImage* images
) {
    std::lock_guard lock(g_mutex);
    if (g_swapchainContext == nullptr || g_getSwapchainImages == nullptr) return kNotHandled;
    return static_cast<int32_t>(g_getSwapchainImages(device, swapchain, count, images));
}

int32_t acquireNextImage(
    VkDevice device,
    VkSwapchainKHR swapchain,
    uint64_t timeout,
    VkSemaphore semaphore,
    VkFence fence,
    uint32_t* imageIndex
) {
    std::lock_guard lock(g_mutex);
    if (g_swapchainContext == nullptr || g_acquireNextImage == nullptr) return kNotHandled;
    return static_cast<int32_t>(
        g_acquireNextImage(device, swapchain, timeout, semaphore, fence, imageIndex)
    );
}

int32_t queuePresent(VkQueue queue, const VkPresentInfoKHR* presentInfo) {
    std::lock_guard lock(g_mutex);
    if (g_swapchainContext == nullptr || g_queuePresent == nullptr) return kNotHandled;
    return static_cast<int32_t>(g_queuePresent(queue, presentInfo));
}

} // namespace fidelityfx
