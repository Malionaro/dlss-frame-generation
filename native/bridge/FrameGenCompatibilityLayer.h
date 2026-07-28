#pragma once

#include <cstdint>
#include <string>

#include <vulkan/vulkan.h>

namespace fidelityfx {
struct FrameGenerationInputs;
struct UpscalingInputs;
}

namespace framegen {

enum class RenderApi : int32_t {
    Unknown = 0,
    Vulkan = 1,
    D3D12 = 2
};

enum class ProviderId : int32_t {
    None = 0,
    NvidiaStreamlineVulkan = 1,
    AmdFsr31Vulkan = 2,
    AmdFsr4D3D12 = 3,
    IntelXessD3D12 = 4,
    CrossVendorFsr31Vulkan = 5
};

constexpr int32_t kNotHandled = INT32_MIN;
constexpr int32_t kCapUpscaling = 1;
constexpr int32_t kCapFrameGeneration = 1 << 1;
constexpr int32_t kCapMultiFrameGeneration = 1 << 2;
constexpr int32_t kCapLowLatency = 1 << 3;
constexpr int32_t kCapProxySwapchain = 1 << 4;
constexpr int32_t kCapRayReconstruction = 1 << 5;

bool initializeVulkan(
    const std::wstring& runtimeDirectory,
    uint32_t vendorId,
    VkPhysicalDevice physicalDevice,
    VkDevice device,
    VkQueue graphicsQueue,
    uint32_t graphicsQueueFamily
);

void shutdown();
ProviderId activeProvider();
const char* providerName(ProviderId provider);
std::string statusForVendor(uint32_t vendorId);
int32_t capabilitiesForVendor(uint32_t vendorId);
int32_t maxFrameMultiplierForVendor(uint32_t vendorId);
bool submitFrameGenerationInputs(
    uint32_t vendorId,
    const fidelityfx::FrameGenerationInputs& inputs
);
void setUpscalingMode(uint32_t vendorId, int mode, float sharpness);
bool submitUpscalingInputs(
    uint32_t vendorId,
    const fidelityfx::UpscalingInputs& inputs
);

bool proxyAvailable();
bool proxyActive();
int32_t createSwapchain(
    const VkSwapchainCreateInfoKHR* createInfo,
    const VkAllocationCallbacks* allocator,
    VkSwapchainKHR* swapchain
);
void destroySwapchain(
    VkDevice device,
    VkSwapchainKHR swapchain,
    const VkAllocationCallbacks* allocator
);
int32_t getSwapchainImages(
    VkDevice device,
    VkSwapchainKHR swapchain,
    uint32_t* count,
    VkImage* images
);
int32_t acquireNextImage(
    VkDevice device,
    VkSwapchainKHR swapchain,
    uint64_t timeout,
    VkSemaphore semaphore,
    VkFence fence,
    uint32_t* imageIndex
);
int32_t queuePresent(VkQueue queue, const VkPresentInfoKHR* presentInfo);

} // namespace framegen
