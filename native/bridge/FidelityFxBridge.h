#pragma once

#include <cstdint>
#include <string>

#include <vulkan/vulkan.h>

namespace fidelityfx {

// Returned to Java when the call must fall back to the regular Vulkan entry
// point. This is outside the range of VkResult values used by Vulkan.
constexpr int32_t kNotHandled = INT32_MIN;

bool initialize(
    const std::wstring& runtimeDirectory,
    VkPhysicalDevice physicalDevice,
    VkDevice device,
    VkQueue graphicsQueue,
    uint32_t graphicsQueueFamily
);

void shutdown();
bool proxyAvailable();
bool proxyActive();
const std::string& status();
bool frameGenerationReady();
bool upscalingAvailable();
bool upscalingReady();
void setUpscalingMode(int mode, float sharpness);

struct UpscalingInputs {
    VkCommandBuffer commandBuffer;
    VkImage color;
    VkFormat colorFormat;
    VkImage depth;
    VkFormat depthFormat;
    VkImage motionVectors;
    VkFormat motionVectorsFormat;
    VkImage output;
    VkFormat outputFormat;
    uint32_t renderWidth;
    uint32_t renderHeight;
    uint32_t outputWidth;
    uint32_t outputHeight;
    float frameTimeMs;
    float cameraNear;
    float cameraFar;
    float verticalFov;
    float jitterX;
    float jitterY;
    bool reset;
};

bool submitUpscalingInputs(const UpscalingInputs& inputs);

struct FrameGenerationInputs {
    VkCommandBuffer commandBuffer;
    VkImage backbuffer;
    VkFormat backbufferFormat;
    VkImage hudlessColor;
    VkDeviceMemory hudlessColorMemory;
    VkImageView hudlessColorView;
    VkFormat hudlessColorFormat;
    VkImage depth;
    VkDeviceMemory depthMemory;
    VkImageView depthView;
    VkFormat depthFormat;
    VkImage motionVectors;
    VkDeviceMemory motionVectorsMemory;
    VkImageView motionVectorsView;
    VkFormat motionVectorsFormat;
    uint32_t width;
    uint32_t height;
    float frameTimeMs;
    float cameraNear;
    float cameraFar;
    float verticalFov;
    float jitterX;
    float jitterY;
    float cameraPosition[3];
    float cameraUp[3];
    float cameraRight[3];
    float cameraForward[3];
    bool reset;
    bool enabled;
};

bool submitFrameGenerationInputs(const FrameGenerationInputs& inputs);

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

} // namespace fidelityfx
