#include "FrameGenCompatibilityLayer.h"

#include "FidelityFxBridge.h"

#include <mutex>

namespace framegen {
namespace {

constexpr uint32_t kNvidiaVendorId = 0x10DE;
constexpr uint32_t kAmdVendorId = 0x1002;
constexpr uint32_t kIntelVendorId = 0x8086;

std::mutex g_mutex;
ProviderId g_activeProvider = ProviderId::None;
uint32_t g_activeVendorId = 0;

bool isFidelityFxVulkanProvider(ProviderId provider) {
    return provider == ProviderId::AmdFsr31Vulkan ||
        provider == ProviderId::CrossVendorFsr31Vulkan;
}

ProviderId selectVulkanProvider(uint32_t vendorId) {
    switch (vendorId) {
        case kNvidiaVendorId:
            return ProviderId::NvidiaStreamlineVulkan;
        case kAmdVendorId:
            return ProviderId::AmdFsr31Vulkan;
        case kIntelVendorId:
            return ProviderId::CrossVendorFsr31Vulkan;
        default:
            return ProviderId::None;
    }
}

} // namespace

bool initializeVulkan(
    const std::wstring& runtimeDirectory,
    uint32_t vendorId,
    VkPhysicalDevice physicalDevice,
    VkDevice device,
    VkQueue graphicsQueue,
    uint32_t graphicsQueueFamily
) {
    const ProviderId selected = selectVulkanProvider(vendorId);
    {
        std::lock_guard lock(g_mutex);
        g_activeVendorId = vendorId;
        g_activeProvider = selected;
    }

    if (!isFidelityFxVulkanProvider(selected)) {
        return selected == ProviderId::NvidiaStreamlineVulkan;
    }

    if (!fidelityfx::initialize(
            runtimeDirectory,
            physicalDevice,
            device,
            graphicsQueue,
            graphicsQueueFamily
        )) {
        std::lock_guard lock(g_mutex);
        g_activeProvider = ProviderId::None;
        return false;
    }
    return true;
}

void shutdown() {
    fidelityfx::shutdown();
    std::lock_guard lock(g_mutex);
    g_activeProvider = ProviderId::None;
    g_activeVendorId = 0;
}

ProviderId activeProvider() {
    std::lock_guard lock(g_mutex);
    return g_activeProvider;
}

const char* providerName(ProviderId provider) {
    switch (provider) {
        case ProviderId::NvidiaStreamlineVulkan:
            return "NVIDIA Streamline (Vulkan)";
        case ProviderId::AmdFsr31Vulkan:
            return "AMD FidelityFX FSR 3.1 (Vulkan)";
        case ProviderId::AmdFsr4D3D12:
            return "AMD FidelityFX FSR 4 (D3D12)";
        case ProviderId::IntelXessD3D12:
            return "Intel XeSS Frame Generation (D3D12)";
        case ProviderId::CrossVendorFsr31Vulkan:
            return "Cross-vendor FidelityFX FSR 3.1 (Vulkan)";
        case ProviderId::None:
        default:
            return "No frame-generation provider";
    }
}

std::string statusForVendor(uint32_t vendorId) {
    const ProviderId provider = activeProvider();
    uint32_t activeVendor;
    {
        std::lock_guard lock(g_mutex);
        activeVendor = g_activeVendorId;
    }

    if (vendorId != activeVendor) {
        if (vendorId == kAmdVendorId) {
            return "AMD route inactive: FSR 4 FG needs D3D12; Vulkan can use the FSR 3.1 provider";
        }
        if (vendorId == kIntelVendorId) {
            return "Intel route inactive: XeSS-FG needs D3D12; Vulkan can use the cross-vendor FSR 3.1 provider";
        }
        return "Provider is not selected for the active GPU";
    }

    if (provider == ProviderId::AmdFsr31Vulkan) {
        return std::string(providerName(provider)) + ": " + fidelityfx::status();
    }
    if (provider == ProviderId::CrossVendorFsr31Vulkan) {
        return std::string(providerName(provider)) +
            ": XeSS-FG needs D3D12; " + fidelityfx::status();
    }
    if (provider == ProviderId::NvidiaStreamlineVulkan) {
        return "NVIDIA provider is handled by Streamline";
    }
    return "No compatible provider initialized for the active Vulkan adapter";
}

int32_t capabilitiesForVendor(uint32_t vendorId) {
    uint32_t activeVendor;
    const ProviderId provider = activeProvider();
    {
        std::lock_guard lock(g_mutex);
        activeVendor = g_activeVendorId;
    }
    if (vendorId != activeVendor || !isFidelityFxVulkanProvider(provider)) return 0;

    // A proxy swapchain is transport readiness, not proof that interpolation
    // inputs (dense motion vectors, depth and HUD-less color) are complete.
    int32_t capabilities = fidelityfx::proxyActive() ? kCapProxySwapchain : 0;
    if (fidelityfx::upscalingAvailable()) {
        capabilities |= kCapUpscaling;
    }
    if (fidelityfx::frameGenerationReady()) {
        capabilities |= kCapFrameGeneration;
    }
    return capabilities;
}

int32_t maxFrameMultiplierForVendor(uint32_t vendorId) {
    return (capabilitiesForVendor(vendorId) & kCapProxySwapchain) != 0 ? 2 : 1;
}

bool submitFrameGenerationInputs(
    uint32_t vendorId,
    const fidelityfx::FrameGenerationInputs& inputs
) {
    uint32_t activeVendor;
    const ProviderId provider = activeProvider();
    {
        std::lock_guard lock(g_mutex);
        activeVendor = g_activeVendorId;
    }
    if (vendorId != activeVendor || !isFidelityFxVulkanProvider(provider)) {
        return false;
    }
    return fidelityfx::submitFrameGenerationInputs(inputs);
}

void setUpscalingMode(uint32_t vendorId, int mode, float sharpness) {
    uint32_t activeVendor;
    const ProviderId provider = activeProvider();
    {
        std::lock_guard lock(g_mutex);
        activeVendor = g_activeVendorId;
    }
    if (vendorId == activeVendor && isFidelityFxVulkanProvider(provider)) {
        fidelityfx::setUpscalingMode(mode, sharpness);
    }
}

bool submitUpscalingInputs(
    uint32_t vendorId,
    const fidelityfx::UpscalingInputs& inputs
) {
    uint32_t activeVendor;
    const ProviderId provider = activeProvider();
    {
        std::lock_guard lock(g_mutex);
        activeVendor = g_activeVendorId;
    }
    if (vendorId != activeVendor || !isFidelityFxVulkanProvider(provider)) {
        return false;
    }
    return fidelityfx::submitUpscalingInputs(inputs);
}

bool proxyAvailable() {
    return isFidelityFxVulkanProvider(activeProvider()) && fidelityfx::proxyAvailable();
}

bool proxyActive() {
    return isFidelityFxVulkanProvider(activeProvider()) && fidelityfx::proxyActive();
}

int32_t createSwapchain(
    const VkSwapchainCreateInfoKHR* createInfo,
    const VkAllocationCallbacks* allocator,
    VkSwapchainKHR* swapchain
) {
    if (!proxyAvailable()) return kNotHandled;
    return fidelityfx::createSwapchain(createInfo, allocator, swapchain);
}

void destroySwapchain(
    VkDevice device,
    VkSwapchainKHR swapchain,
    const VkAllocationCallbacks* allocator
) {
    if (proxyAvailable()) {
        fidelityfx::destroySwapchain(device, swapchain, allocator);
    }
}

int32_t getSwapchainImages(
    VkDevice device,
    VkSwapchainKHR swapchain,
    uint32_t* count,
    VkImage* images
) {
    if (!proxyActive()) return kNotHandled;
    return fidelityfx::getSwapchainImages(device, swapchain, count, images);
}

int32_t acquireNextImage(
    VkDevice device,
    VkSwapchainKHR swapchain,
    uint64_t timeout,
    VkSemaphore semaphore,
    VkFence fence,
    uint32_t* imageIndex
) {
    if (!proxyActive()) return kNotHandled;
    return fidelityfx::acquireNextImage(
        device, swapchain, timeout, semaphore, fence, imageIndex
    );
}

int32_t queuePresent(VkQueue queue, const VkPresentInfoKHR* presentInfo) {
    if (!proxyActive()) return kNotHandled;
    return fidelityfx::queuePresent(queue, presentInfo);
}

} // namespace framegen
