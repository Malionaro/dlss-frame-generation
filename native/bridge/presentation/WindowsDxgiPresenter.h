#pragma once

#include <cstdint>
#include <string>

#include <vulkan/vulkan.h>

namespace dxgipresenter {

enum class Stage : int32_t {
    Unavailable = 0,
    VulkanInteropReady = 1,
    AdapterMatched = 2,
    D3D12DeviceReady = 3,
    PresentationReady = 4,
    Failed = 5
};

constexpr int32_t kCapLuidMatch = 1;
constexpr int32_t kCapExternalMemory = 1 << 1;
constexpr int32_t kCapExternalSemaphore = 1 << 2;
constexpr int32_t kCapD3D12Device = 1 << 3;
constexpr int32_t kCapD3D12Queue = 1 << 4;
constexpr int32_t kCapDxgiSwapchain = 1 << 5;
constexpr int32_t kCapZeroCopyFrames = 1 << 6;

bool probe(
    VkPhysicalDevice physicalDevice,
    VkDevice device,
    VkQueue graphicsQueue,
    uint32_t graphicsQueueFamily
);
bool canCreatePresentationSwapchain();
bool ownsSwapchain(VkSwapchainKHR swapchain);
int32_t createSwapchain(
    void* window,
    const VkSwapchainCreateInfoKHR* createInfo,
    VkSwapchainKHR* swapchain
);
void destroySwapchain(VkSwapchainKHR swapchain);
int32_t getSwapchainImages(
    VkSwapchainKHR swapchain,
    uint32_t* count,
    VkImage* images
);
int32_t acquireNextImage(
    VkSwapchainKHR swapchain,
    VkSemaphore acquireSemaphore,
    uint32_t* imageIndex
);
int32_t queuePresent(
    VkQueue queue,
    const VkPresentInfoKHR* presentInfo
);
void shutdown();
Stage stage();
int32_t capabilities();
std::string status();
std::string adapterLuid();

} // namespace dxgipresenter
