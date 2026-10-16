#include "WindowsDxgiPresenter.h"
#include "VulkanLoader.h"

#if defined(_WIN32)
#include <windows.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include <wrl/client.h>
#endif

#include <algorithm>
#include <array>
#include <cstring>
#include <iomanip>
#include <memory>
#include <mutex>
#include <sstream>
#include <vector>

namespace dxgipresenter {
namespace {

constexpr int32_t kNotHandled = INT32_MIN;
// B5: upper bound for JNI-supplied present wait-semaphore counts. Bounds the
// under-lock allocation in submitVulkanReady against corrupt/huge counts.
constexpr uint32_t kMaxPresentWaitSemaphores = 16;

std::mutex g_mutex;
Stage g_stage = Stage::Unavailable;
int32_t g_capabilities = 0;
std::string g_status = "D3D12 presentation probe has not run";
std::array<uint8_t, VK_LUID_SIZE> g_luid{};
VkPhysicalDevice g_vkPhysicalDevice = VK_NULL_HANDLE;
VkDevice g_vkDevice = VK_NULL_HANDLE;
VkQueue g_vkGraphicsQueue = VK_NULL_HANDLE;
uint32_t g_vkGraphicsQueueFamily = VK_QUEUE_FAMILY_IGNORED;
VkSemaphore g_vulkanReadySemaphore = VK_NULL_HANDLE;
VkSemaphore g_d3d12DoneSemaphore = VK_NULL_HANDLE;
uint64_t g_readyValue = 0;
uint64_t g_lastSubmittedDone = 0;
uint32_t g_nextAcquire = 0;
int32_t g_currentImageIndex = -1;
bool g_sessionDisabled = false;
// B1: ownership epoch. Bumped whenever presentation ownership changes
// (swapchain created/destroyed, shutdown). releasePresentationLocked captures
// it before unlocking for GPU idle and aborts if it changed meanwhile, so a
// stale teardown never runs on a newer owner's state (TOCTOU/double-reset).
uint64_t g_presentationEpoch = 0;

PFN_vkGetMemoryWin32HandlePropertiesKHR
    g_getMemoryWin32HandleProperties = nullptr;
PFN_vkImportSemaphoreWin32HandleKHR
    g_importSemaphoreWin32Handle = nullptr;
PFN_vkQueueSubmit2 g_queueSubmit2 = nullptr;

// Every other Vulkan entry point the presenter uses is resolved here as well,
// from the real loader (see VulkanLoader.h). The statically linked exports go
// through sl.interposer, whose dispatch is not installed on non-NVIDIA
// adapters: vkGetPhysicalDeviceProperties2 in the startup probe and
// vkDeviceWaitIdle at shutdown both jumped to a null pointer on Intel GPUs.
struct VulkanFunctions {
    PFN_vkGetPhysicalDeviceProperties2 getPhysicalDeviceProperties2 = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties
        getPhysicalDeviceMemoryProperties = nullptr;
    PFN_vkGetPhysicalDeviceImageFormatProperties2
        getPhysicalDeviceImageFormatProperties2 = nullptr;
    PFN_vkGetPhysicalDeviceExternalSemaphoreProperties
        getPhysicalDeviceExternalSemaphoreProperties = nullptr;
    PFN_vkDeviceWaitIdle deviceWaitIdle = nullptr;
    PFN_vkCreateImage createImage = nullptr;
    PFN_vkDestroyImage destroyImage = nullptr;
    PFN_vkGetImageMemoryRequirements getImageMemoryRequirements = nullptr;
    PFN_vkAllocateMemory allocateMemory = nullptr;
    PFN_vkFreeMemory freeMemory = nullptr;
    PFN_vkBindImageMemory bindImageMemory = nullptr;
    PFN_vkCreateSemaphore createSemaphore = nullptr;
    PFN_vkDestroySemaphore destroySemaphore = nullptr;
};
VulkanFunctions g_vk;

struct SwapchainToken {};
std::unique_ptr<SwapchainToken> g_swapchainToken;
VkSwapchainKHR g_retiredSwapchain = VK_NULL_HANDLE;
// B4: handles handed to the renderer are monotonic ids, never recycled token
// addresses. A freed unique_ptr address can be reused by the next make_unique
// (ABA), letting a stale handle compare equal again; ids grow forever
// (0 = null), so a retired handle can never become valid again.
uint64_t g_activeSwapchainId = 0;
uint64_t g_nextSwapchainId = 0;
VkSwapchainKHR idToHandle(uint64_t id) {
    return reinterpret_cast<VkSwapchainKHR>(
        static_cast<uintptr_t>(id));
}

#if defined(_WIN32)
using Microsoft::WRL::ComPtr;

struct SharedFrame {
    ComPtr<ID3D12Resource> d3d12Resource;
    VkImage vulkanImage = VK_NULL_HANDLE;
    VkDeviceMemory vulkanMemory = VK_NULL_HANDLE;
    uint64_t d3d12DoneValue = 0;
};

struct CopyCommand {
    ComPtr<ID3D12CommandAllocator> allocator;
    ComPtr<ID3D12GraphicsCommandList> list;
};

ComPtr<IDXGIFactory6> g_factory;
ComPtr<IDXGIAdapter4> g_adapter;
ComPtr<ID3D12Device> g_d3d12Device;
ComPtr<ID3D12CommandQueue> g_d3d12Queue;
ComPtr<IDXGISwapChain4> g_dxgiSwapchain;
ComPtr<ID3D12Fence> g_vulkanReadyFence;
ComPtr<ID3D12Fence> g_d3d12DoneFence;
ComPtr<ID3D12InfoQueue> g_infoQueue;
std::vector<ComPtr<ID3D12Resource>> g_backBuffers;
std::vector<SharedFrame> g_sharedFrames;
std::vector<CopyCommand> g_copyCommands;
HANDLE g_vulkanReadyHandle = nullptr;
HANDLE g_d3d12DoneHandle = nullptr;
HWND g_window = nullptr;
uint32_t g_width = 0;
uint32_t g_height = 0;
uint32_t g_bufferCount = 0;
DXGI_FORMAT g_dxgiFormat = DXGI_FORMAT_UNKNOWN;
UINT g_syncInterval = 1;
UINT g_presentFlags = 0;
bool g_tearingSupported = false;
std::string g_lastDebugMessage;

bool debugRequested() {
    wchar_t value[16]{};
    size_t length = 0;
    _wgetenv_s(
        &length, value, sizeof(value) / sizeof(value[0]),
        L"DLSS_FG_D3D12_DEBUG"
    );
    return length > 1 && value[0] == L'1';
}

bool luidEquals(const LUID& dxgiLuid) {
    LUID vulkanLuid{};
    static_assert(sizeof(vulkanLuid) == VK_LUID_SIZE);
    std::memcpy(&vulkanLuid, g_luid.data(), VK_LUID_SIZE);
    return vulkanLuid.HighPart == dxgiLuid.HighPart
        && vulkanLuid.LowPart == dxgiLuid.LowPart;
}

std::string hresultText(HRESULT result) {
    std::ostringstream stream;
    stream << "HRESULT 0x" << std::hex << std::uppercase
           << static_cast<uint32_t>(result);
    return stream.str();
}

bool drainDebugMessages(std::string& detail) {
    if (g_infoQueue == nullptr) {
        return false;
    }
    bool hasError = false;
    std::ostringstream messages;
    UINT64 count =
        g_infoQueue->GetNumStoredMessagesAllowedByRetrievalFilter();
    for (UINT64 index = 0; index < count; ++index) {
        SIZE_T length = 0;
        if (FAILED(g_infoQueue->GetMessage(
                index, nullptr, &length))
            || length == 0) {
            continue;
        }
        std::vector<uint8_t> storage(length);
        auto* message =
            reinterpret_cast<D3D12_MESSAGE*>(storage.data());
        if (FAILED(g_infoQueue->GetMessage(
                index, message, &length))
            || message->Severity > D3D12_MESSAGE_SEVERITY_WARNING) {
            continue;
        }
        if (messages.tellp() > 0) {
            messages << " | ";
        }
        messages << "ID " << message->ID << ": "
                 << (message->pDescription != nullptr
                        ? message->pDescription : "no description");
        hasError = hasError
            || message->Severity
                <= D3D12_MESSAGE_SEVERITY_ERROR;
    }
    g_infoQueue->ClearStoredMessages();
    detail = messages.str();
    if (!detail.empty()) {
        g_lastDebugMessage = detail;
    }
    return hasError;
}

DXGI_FORMAT dxgiFormatForVulkan(VkFormat format) {
    switch (format) {
        case VK_FORMAT_R8G8B8A8_UNORM:
        case VK_FORMAT_R8G8B8A8_SRGB:
            return format == VK_FORMAT_R8G8B8A8_SRGB
                ? DXGI_FORMAT_R8G8B8A8_UNORM_SRGB
                : DXGI_FORMAT_R8G8B8A8_UNORM;
        case VK_FORMAT_B8G8R8A8_UNORM:
        case VK_FORMAT_B8G8R8A8_SRGB:
            return format == VK_FORMAT_B8G8R8A8_SRGB
                ? DXGI_FORMAT_B8G8R8A8_UNORM_SRGB
                : DXGI_FORMAT_B8G8R8A8_UNORM;
        default:
            return DXGI_FORMAT_UNKNOWN;
    }
}

uint32_t selectMemoryType(
    uint32_t allowedTypes,
    VkMemoryPropertyFlags preferred
) {
    VkPhysicalDeviceMemoryProperties properties{};
    g_vk.getPhysicalDeviceMemoryProperties(
        g_vkPhysicalDevice, &properties);
    for (uint32_t index = 0;
         index < properties.memoryTypeCount;
         ++index) {
        if ((allowedTypes & (1u << index)) != 0
            && (properties.memoryTypes[index].propertyFlags & preferred)
                == preferred) {
            return index;
        }
    }
    for (uint32_t index = 0;
         index < properties.memoryTypeCount;
         ++index) {
        if ((allowedTypes & (1u << index)) != 0) {
            return index;
        }
    }
    return UINT32_MAX;
}
#endif

void fail(const std::string& reason) {
    g_stage = Stage::Failed;
    g_status = reason;
}

#if defined(_WIN32)
bool waitForD3D12QueueIdle(std::string& error) {
    if (g_d3d12Device == nullptr || g_d3d12Queue == nullptr) {
        return true;
    }

    // Do not use the shared Vulkan/D3D12 completion fence for teardown.
    // A private fence proves that every command previously submitted to the
    // D3D12 queue has retired without involving external-fence state.
    ComPtr<ID3D12Fence> idleFence;
    HRESULT result = g_d3d12Device->CreateFence(
        0,
        D3D12_FENCE_FLAG_NONE,
        IID_PPV_ARGS(&idleFence)
    );
    if (FAILED(result)) {
        error = "Could not create the D3D12 teardown fence: "
            + hresultText(result);
        return false;
    }
    result = g_d3d12Queue->Signal(idleFence.Get(), 1);
    if (FAILED(result)) {
        error = "Could not signal the D3D12 teardown fence: "
            + hresultText(result);
        return false;
    }

    HANDLE eventHandle = CreateEventW(
        nullptr, FALSE, FALSE, nullptr);
    if (eventHandle == nullptr) {
        error = "Could not create the D3D12 teardown event: "
            + hresultText(HRESULT_FROM_WIN32(GetLastError()));
        return false;
    }
    result = idleFence->SetEventOnCompletion(1, eventHandle);
    if (FAILED(result)) {
        CloseHandle(eventHandle);
        error = "Could not arm the D3D12 teardown fence: "
            + hresultText(result);
        return false;
    }

    DWORD waitResult = WAIT_TIMEOUT;
    for (uint32_t attempt = 0;
         attempt < 100 && waitResult == WAIT_TIMEOUT;
         ++attempt) {
        waitResult = WaitForSingleObject(eventHandle, 100);
        if (waitResult == WAIT_TIMEOUT) {
            HRESULT removed =
                g_d3d12Device->GetDeviceRemovedReason();
            if (FAILED(removed)) {
                CloseHandle(eventHandle);
                error = "D3D12 device was removed while waiting for "
                    "teardown: " + hresultText(removed);
                return false;
            }
        }
    }
    CloseHandle(eventHandle);
    if (waitResult != WAIT_OBJECT_0) {
        error =
            "D3D12 queue did not become idle within 10 seconds";
        return false;
    }
    HRESULT removedAfterWait = g_d3d12Device->GetDeviceRemovedReason();
    if (FAILED(removedAfterWait)) {
        error = "D3D12 device was removed after wait: "
            + hresultText(removedAfterWait);
        return false;
    }
    return true;
}

void abandonPresentationLocked() {
    // Releasing an object still referenced by an in-flight D3D12 command is
    // memory corruption. If a device/queue cannot be made idle, deliberately
    // quarantine the small presentation allocation for the remainder of the
    // process instead of risking a JVM crash.
    for (CopyCommand& command : g_copyCommands) {
        command.list.Detach();
        command.allocator.Detach();
    }
    g_copyCommands.clear();
    for (auto& buffer : g_backBuffers) {
        buffer.Detach();
    }
    g_backBuffers.clear();
    g_dxgiSwapchain.Detach();
    for (SharedFrame& frame : g_sharedFrames) {
        frame.d3d12Resource.Detach();
        frame.vulkanImage = VK_NULL_HANDLE;
        frame.vulkanMemory = VK_NULL_HANDLE;
    }
    g_sharedFrames.clear();
    g_vulkanReadyFence.Detach();
    g_d3d12DoneFence.Detach();
    g_vulkanReadySemaphore = VK_NULL_HANDLE;
    g_d3d12DoneSemaphore = VK_NULL_HANDLE;
}

void releasePresentationLocked(std::unique_lock<std::mutex>& lock) {
    // Deadlock fix: do not hold g_mutex while blocking on GPU idle.
    // B1: capture the ownership epoch first; a concurrent create/destroy
    // bumps it while unlocked, in which case the newer owner is responsible
    // for teardown and this stale invocation must stop after re-locking.
    const uint64_t releaseEpoch = g_presentationEpoch;
    g_stage = Stage::Unavailable;
    lock.unlock();
    VkResult vulkanIdle = VK_SUCCESS;
    if (g_vkDevice != VK_NULL_HANDLE && g_vk.deviceWaitIdle != nullptr) {
        vulkanIdle = g_vk.deviceWaitIdle(g_vkDevice);
    }
    std::string idleError;
    bool d3d12Idle = waitForD3D12QueueIdle(idleError);
    lock.lock();
    if (releaseEpoch != g_presentationEpoch) {
        return;
    }
    if (vulkanIdle != VK_SUCCESS
        || !d3d12Idle) {
        if (idleError.empty()) {
            idleError = "Vulkan device could not become idle: "
                + std::to_string(vulkanIdle);
        }
        abandonPresentationLocked();
        g_sessionDisabled = true;
        fail(idleError
            + "; presentation allocations were quarantined to avoid "
              "releasing GPU resources still in flight");
    }

    g_copyCommands.clear();
    g_backBuffers.clear();
    g_dxgiSwapchain.Reset();
    for (SharedFrame& frame : g_sharedFrames) {
        if (frame.vulkanImage != VK_NULL_HANDLE
            && g_vkDevice != VK_NULL_HANDLE) {
            g_vk.destroyImage(
                g_vkDevice, frame.vulkanImage, nullptr);
        }
        if (frame.vulkanMemory != VK_NULL_HANDLE
            && g_vkDevice != VK_NULL_HANDLE) {
            g_vk.freeMemory(
                g_vkDevice, frame.vulkanMemory, nullptr);
        }
    }
    g_sharedFrames.clear();
    if (g_vulkanReadySemaphore != VK_NULL_HANDLE
        && g_vkDevice != VK_NULL_HANDLE) {
        g_vk.destroySemaphore(
            g_vkDevice, g_vulkanReadySemaphore, nullptr);
    }
    if (g_d3d12DoneSemaphore != VK_NULL_HANDLE
        && g_vkDevice != VK_NULL_HANDLE) {
        g_vk.destroySemaphore(
            g_vkDevice, g_d3d12DoneSemaphore, nullptr);
    }
    g_vulkanReadySemaphore = VK_NULL_HANDLE;
    g_d3d12DoneSemaphore = VK_NULL_HANDLE;
    if (g_vulkanReadyHandle != nullptr) {
        CloseHandle(g_vulkanReadyHandle);
        g_vulkanReadyHandle = nullptr;
    }
    if (g_d3d12DoneHandle != nullptr) {
        CloseHandle(g_d3d12DoneHandle);
        g_d3d12DoneHandle = nullptr;
    }
    g_vulkanReadyFence.Reset();
    g_d3d12DoneFence.Reset();
    if (g_swapchainToken != nullptr) {
        // B4: retire the id, not the token address (see globals).
        g_retiredSwapchain = idToHandle(g_activeSwapchainId);
        g_activeSwapchainId = 0;
        g_swapchainToken.reset();
    }
    g_window = nullptr;
    g_width = 0;
    g_height = 0;
    g_bufferCount = 0;
    g_readyValue = 0;
    g_lastSubmittedDone = 0;
    g_nextAcquire = 0;
    g_currentImageIndex = -1;
    g_lastDebugMessage.clear();
    if (g_d3d12Device != nullptr && !g_sessionDisabled) {
        g_stage = Stage::D3D12DeviceReady;
        g_capabilities &= ~(
            kCapDxgiSwapchain | kCapZeroCopyFrames);
        g_status =
            "D3D12 device ready; DXGI presentation is inactive";
    }
}

bool externalImageSupported(VkFormat format) {
    VkPhysicalDeviceExternalImageFormatInfo externalInfo{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO
    };
    externalInfo.handleType =
        VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT;
    VkPhysicalDeviceImageFormatInfo2 imageInfo{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2
    };
    imageInfo.pNext = &externalInfo;
    imageInfo.format = format;
    imageInfo.type = VK_IMAGE_TYPE_2D;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage =
        VK_IMAGE_USAGE_TRANSFER_DST_BIT
        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    imageInfo.flags = 0;

    VkExternalImageFormatProperties externalProperties{
        VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES
    };
    VkImageFormatProperties2 properties{
        VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2
    };
    properties.pNext = &externalProperties;
    VkResult result = g_vk.getPhysicalDeviceImageFormatProperties2(
        g_vkPhysicalDevice, &imageInfo, &properties);
    if (result != VK_SUCCESS) {
        return false;
    }
    const auto& memory =
        externalProperties.externalMemoryProperties;
    return (memory.externalMemoryFeatures
                & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) != 0
        && (memory.compatibleHandleTypes
                & VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT)
            != 0;
}

bool externalFenceSupported() {
    VkPhysicalDeviceExternalSemaphoreInfo info{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO
    };
    info.handleType =
        VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_D3D12_FENCE_BIT;
    VkExternalSemaphoreProperties properties{
        VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES
    };
    g_vk.getPhysicalDeviceExternalSemaphoreProperties(
        g_vkPhysicalDevice, &info, &properties);
    return (properties.externalSemaphoreFeatures
                & VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT) != 0;
}

bool createSharedTimeline(
    ComPtr<ID3D12Fence>& d3d12Fence,
    HANDLE& sharedHandle,
    VkSemaphore& vulkanSemaphore,
    std::string& error
) {
    HRESULT result = g_d3d12Device->CreateFence(
        0,
        D3D12_FENCE_FLAG_SHARED,
        IID_PPV_ARGS(&d3d12Fence)
    );
    if (FAILED(result)) {
        error = "D3D12 shared fence creation failed: "
            + hresultText(result);
        return false;
    }
    result = g_d3d12Device->CreateSharedHandle(
        d3d12Fence.Get(),
        nullptr,
        GENERIC_ALL,
        nullptr,
        &sharedHandle
    );
    if (FAILED(result)) {
        d3d12Fence.Reset();
        error = "D3D12 fence handle creation failed: "
            + hresultText(result);
        return false;
    }

    VkSemaphoreTypeCreateInfo typeInfo{
        VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO
    };
    typeInfo.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
    typeInfo.initialValue = 0;
    VkSemaphoreCreateInfo createInfo{
        VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO
    };
    createInfo.pNext = &typeInfo;
    VkResult vkResult = g_vk.createSemaphore(
        g_vkDevice, &createInfo, nullptr, &vulkanSemaphore);
    if (vkResult != VK_SUCCESS) {
        CloseHandle(sharedHandle);
        sharedHandle = nullptr;
        d3d12Fence.Reset();
        error = "Vulkan timeline semaphore creation failed: "
            + std::to_string(vkResult);
        return false;
    }

    VkImportSemaphoreWin32HandleInfoKHR importInfo{
        VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_WIN32_HANDLE_INFO_KHR
    };
    importInfo.semaphore = vulkanSemaphore;
    importInfo.flags = 0;
    importInfo.handleType =
        VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_D3D12_FENCE_BIT;
    importInfo.handle = sharedHandle;
    vkResult = g_importSemaphoreWin32Handle(
        g_vkDevice, &importInfo);
    if (vkResult != VK_SUCCESS) {
        g_vk.destroySemaphore(g_vkDevice, vulkanSemaphore, nullptr);
        vulkanSemaphore = VK_NULL_HANDLE;
        CloseHandle(sharedHandle);
        sharedHandle = nullptr;
        d3d12Fence.Reset();
        error = "D3D12 fence import into Vulkan failed: "
            + std::to_string(vkResult);
        return false;
    }
    return true;
}

bool createSharedFrame(
    VkFormat vulkanFormat,
    SharedFrame& frame,
    std::string& error
) {
    D3D12_HEAP_PROPERTIES heap{};
    heap.Type = D3D12_HEAP_TYPE_DEFAULT;
    heap.CPUPageProperty =
        D3D12_CPU_PAGE_PROPERTY_UNKNOWN;
    heap.MemoryPoolPreference =
        D3D12_MEMORY_POOL_UNKNOWN;
    heap.CreationNodeMask = 1;
    heap.VisibleNodeMask = 1;

    D3D12_RESOURCE_DESC description{};
    description.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    description.Alignment = 0;
    description.Width = g_width;
    description.Height = g_height;
    description.DepthOrArraySize = 1;
    description.MipLevels = 1;
    description.Format = g_dxgiFormat;
    description.SampleDesc.Count = 1;
    description.SampleDesc.Quality = 0;
    description.Layout = D3D12_TEXTURE_LAYOUT_UNKNOWN;
    description.Flags =
        D3D12_RESOURCE_FLAG_ALLOW_SIMULTANEOUS_ACCESS;

    HRESULT result = g_d3d12Device->CreateCommittedResource(
        &heap,
        D3D12_HEAP_FLAG_SHARED,
        &description,
        D3D12_RESOURCE_STATE_COMMON,
        nullptr,
        IID_PPV_ARGS(&frame.d3d12Resource)
    );
    if (FAILED(result)) {
        error = "D3D12 shared texture creation failed: "
            + hresultText(result);
        return false;
    }

    HANDLE resourceHandle = nullptr;
    result = g_d3d12Device->CreateSharedHandle(
        frame.d3d12Resource.Get(),
        nullptr,
        GENERIC_ALL,
        nullptr,
        &resourceHandle
    );
    if (FAILED(result)) {
        frame.d3d12Resource.Reset();
        error = "D3D12 shared texture handle creation failed: "
            + hresultText(result);
        return false;
    }

    VkExternalMemoryImageCreateInfo externalInfo{
        VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO
    };
    externalInfo.handleTypes =
        VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT;
    VkImageCreateInfo imageInfo{
        VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO
    };
    imageInfo.pNext = &externalInfo;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = vulkanFormat;
    imageInfo.extent = {g_width, g_height, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage =
        VK_IMAGE_USAGE_TRANSFER_DST_BIT
        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkResult vkResult = g_vk.createImage(
        g_vkDevice, &imageInfo, nullptr, &frame.vulkanImage);
    if (vkResult != VK_SUCCESS) {
        CloseHandle(resourceHandle);
        frame.d3d12Resource.Reset();
        error = "Vulkan shared image creation failed: "
            + std::to_string(vkResult);
        return false;
    }

    VkMemoryRequirements requirements{};
    g_vk.getImageMemoryRequirements(
        g_vkDevice, frame.vulkanImage, &requirements);
    VkMemoryWin32HandlePropertiesKHR handleProperties{
        VK_STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR
    };
    vkResult = g_getMemoryWin32HandleProperties(
        g_vkDevice,
        VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT,
        resourceHandle,
        &handleProperties
    );
    if (vkResult != VK_SUCCESS) {
        CloseHandle(resourceHandle);
        g_vk.destroyImage(g_vkDevice, frame.vulkanImage, nullptr);
        frame.vulkanImage = VK_NULL_HANDLE;
        frame.d3d12Resource.Reset();
        error = "Vulkan could not inspect the D3D12 resource handle: "
            + std::to_string(vkResult);
        return false;
    }
    uint32_t memoryType = selectMemoryType(
        requirements.memoryTypeBits
            & handleProperties.memoryTypeBits,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    );
    if (memoryType == UINT32_MAX) {
        CloseHandle(resourceHandle);
        g_vk.destroyImage(g_vkDevice, frame.vulkanImage, nullptr);
        frame.vulkanImage = VK_NULL_HANDLE;
        frame.d3d12Resource.Reset();
        error =
            "No Vulkan memory type accepts the D3D12 resource";
        return false;
    }

    VkMemoryDedicatedAllocateInfo dedicatedInfo{
        VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO
    };
    dedicatedInfo.image = frame.vulkanImage;
    VkImportMemoryWin32HandleInfoKHR importInfo{
        VK_STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_KHR
    };
    importInfo.pNext = &dedicatedInfo;
    importInfo.handleType =
        VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT;
    importInfo.handle = resourceHandle;
    VkMemoryAllocateInfo allocation{
        VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
    };
    allocation.pNext = &importInfo;
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = memoryType;
    vkResult = g_vk.allocateMemory(
        g_vkDevice, &allocation, nullptr, &frame.vulkanMemory);
    CloseHandle(resourceHandle);
    if (vkResult != VK_SUCCESS) {
        g_vk.destroyImage(g_vkDevice, frame.vulkanImage, nullptr);
        frame.vulkanImage = VK_NULL_HANDLE;
        frame.d3d12Resource.Reset();
        error = "Vulkan import of D3D12 texture memory failed: "
            + std::to_string(vkResult);
        return false;
    }
    vkResult = g_vk.bindImageMemory(
        g_vkDevice,
        frame.vulkanImage,
        frame.vulkanMemory,
        0
    );
    if (vkResult != VK_SUCCESS) {
        g_vk.freeMemory(g_vkDevice, frame.vulkanMemory, nullptr);
        frame.vulkanMemory = VK_NULL_HANDLE;
        g_vk.destroyImage(g_vkDevice, frame.vulkanImage, nullptr);
        frame.vulkanImage = VK_NULL_HANDLE;
        frame.d3d12Resource.Reset();
        error = "Vulkan shared image binding failed: "
            + std::to_string(vkResult);
        return false;
    }
    return true;
}

bool createCopyCommands(std::string& error) {
    g_copyCommands.resize(
        static_cast<size_t>(g_bufferCount) * g_bufferCount);
    for (uint32_t source = 0; source < g_bufferCount; ++source) {
        for (uint32_t destination = 0;
             destination < g_bufferCount;
             ++destination) {
            CopyCommand& command =
                g_copyCommands[
                    static_cast<size_t>(source) * g_bufferCount
                    + destination];
            HRESULT result = g_d3d12Device->CreateCommandAllocator(
                D3D12_COMMAND_LIST_TYPE_DIRECT,
                IID_PPV_ARGS(&command.allocator)
            );
            if (FAILED(result)) {
                error = "D3D12 command allocator creation failed: "
                    + hresultText(result);
                return false;
            }
            result = g_d3d12Device->CreateCommandList(
                0,
                D3D12_COMMAND_LIST_TYPE_DIRECT,
                command.allocator.Get(),
                nullptr,
                IID_PPV_ARGS(&command.list)
            );
            if (FAILED(result)) {
                error = "D3D12 copy command list creation failed: "
                    + hresultText(result);
                return false;
            }

            D3D12_RESOURCE_BARRIER toCopy{};
            toCopy.Type =
                D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
            toCopy.Flags = D3D12_RESOURCE_BARRIER_FLAG_NONE;
            toCopy.Transition.pResource =
                g_backBuffers[destination].Get();
            toCopy.Transition.Subresource =
                D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
            toCopy.Transition.StateBefore =
                D3D12_RESOURCE_STATE_PRESENT;
            toCopy.Transition.StateAfter =
                D3D12_RESOURCE_STATE_COPY_DEST;
            command.list->ResourceBarrier(1, &toCopy);
            command.list->CopyResource(
                g_backBuffers[destination].Get(),
                g_sharedFrames[source].d3d12Resource.Get()
            );
            std::swap(
                toCopy.Transition.StateBefore,
                toCopy.Transition.StateAfter
            );
            command.list->ResourceBarrier(1, &toCopy);
            result = command.list->Close();
            if (FAILED(result)) {
                error = "D3D12 copy command list close failed: "
                    + hresultText(result);
                return false;
            }
        }
    }
    return true;
}

VkResult submitAcquireSignal(
    SharedFrame& frame,
    VkSemaphore acquireSemaphore
) {
    VkSemaphoreSubmitInfo waitInfo{
        VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO
    };
    waitInfo.semaphore = g_d3d12DoneSemaphore;
    waitInfo.value = frame.d3d12DoneValue;
    waitInfo.stageMask =
        VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
    VkSemaphoreSubmitInfo signalInfo{
        VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO
    };
    signalInfo.semaphore = acquireSemaphore;
    signalInfo.value = 0;
    signalInfo.stageMask =
        VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
    VkSubmitInfo2 submit{
        VK_STRUCTURE_TYPE_SUBMIT_INFO_2
    };
    if (frame.d3d12DoneValue > 0) {
        submit.waitSemaphoreInfoCount = 1;
        submit.pWaitSemaphoreInfos = &waitInfo;
    }
    submit.signalSemaphoreInfoCount = 1;
    submit.pSignalSemaphoreInfos = &signalInfo;
    return g_queueSubmit2(
        g_vkGraphicsQueue, 1, &submit, VK_NULL_HANDLE);
}

VkResult submitVulkanReady(
    const VkPresentInfoKHR* presentInfo,
    uint64_t readyValue
) {
    std::vector<VkSemaphoreSubmitInfo> waits(
        presentInfo->waitSemaphoreCount);
    for (uint32_t index = 0;
         index < presentInfo->waitSemaphoreCount;
         ++index) {
        waits[index].sType =
            VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO;
        waits[index].semaphore =
            presentInfo->pWaitSemaphores[index];
        waits[index].value = 0;
        waits[index].stageMask =
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
    }
    VkSemaphoreSubmitInfo signal{
        VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO
    };
    signal.semaphore = g_vulkanReadySemaphore;
    signal.value = readyValue;
    signal.stageMask =
        VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
    VkSubmitInfo2 submit{
        VK_STRUCTURE_TYPE_SUBMIT_INFO_2
    };
    submit.waitSemaphoreInfoCount =
        static_cast<uint32_t>(waits.size());
    submit.pWaitSemaphoreInfos =
        waits.empty() ? nullptr : waits.data();
    submit.signalSemaphoreInfoCount = 1;
    submit.pSignalSemaphoreInfos = &signal;
    return g_queueSubmit2(
        g_vkGraphicsQueue, 1, &submit, VK_NULL_HANDLE);
}
#endif

} // namespace

bool probe(
    VkInstance instance,
    VkPhysicalDevice physicalDevice,
    VkDevice device,
    VkQueue graphicsQueue,
    uint32_t graphicsQueueFamily
) {
    std::unique_lock<std::mutex> lock(g_mutex);
    g_capabilities = 0;
    g_luid.fill(0);
    g_sessionDisabled = false;

#if !defined(_WIN32)
    fail("Hybrid Vulkan/D3D12 presentation is Windows-only");
    return false;
#else
    releasePresentationLocked(lock);
    g_d3d12Queue.Reset();
    g_d3d12Device.Reset();
    g_infoQueue.Reset();
    g_adapter.Reset();
    g_factory.Reset();
    g_vkPhysicalDevice = physicalDevice;
    g_vkDevice = device;
    g_vkGraphicsQueue = graphicsQueue;
    g_vkGraphicsQueueFamily = graphicsQueueFamily;

    if (instance == VK_NULL_HANDLE
        || physicalDevice == VK_NULL_HANDLE
        || device == VK_NULL_HANDLE
        || graphicsQueue == VK_NULL_HANDLE
        || graphicsQueueFamily == VK_QUEUE_FAMILY_IGNORED) {
        fail("Vulkan instance, physical device, logical device or graphics queue is missing");
        return false;
    }

    g_vk = VulkanFunctions{};
    g_vk.getPhysicalDeviceProperties2 = reinterpret_cast<
        PFN_vkGetPhysicalDeviceProperties2>(framegen::loaderGetInstanceProcAddr()(
            instance, "vkGetPhysicalDeviceProperties2"));
    g_vk.getPhysicalDeviceMemoryProperties = reinterpret_cast<
        PFN_vkGetPhysicalDeviceMemoryProperties>(framegen::loaderGetInstanceProcAddr()(
            instance, "vkGetPhysicalDeviceMemoryProperties"));
    g_vk.getPhysicalDeviceImageFormatProperties2 = reinterpret_cast<
        PFN_vkGetPhysicalDeviceImageFormatProperties2>(framegen::loaderGetInstanceProcAddr()(
            instance, "vkGetPhysicalDeviceImageFormatProperties2"));
    g_vk.getPhysicalDeviceExternalSemaphoreProperties = reinterpret_cast<
        PFN_vkGetPhysicalDeviceExternalSemaphoreProperties>(framegen::loaderGetInstanceProcAddr()(
            instance, "vkGetPhysicalDeviceExternalSemaphoreProperties"));
    g_vk.deviceWaitIdle = reinterpret_cast<PFN_vkDeviceWaitIdle>(
        framegen::loaderGetDeviceProcAddr()(device, "vkDeviceWaitIdle"));
    g_vk.createImage = reinterpret_cast<PFN_vkCreateImage>(
        framegen::loaderGetDeviceProcAddr()(device, "vkCreateImage"));
    g_vk.destroyImage = reinterpret_cast<PFN_vkDestroyImage>(
        framegen::loaderGetDeviceProcAddr()(device, "vkDestroyImage"));
    g_vk.getImageMemoryRequirements = reinterpret_cast<
        PFN_vkGetImageMemoryRequirements>(framegen::loaderGetDeviceProcAddr()(
            device, "vkGetImageMemoryRequirements"));
    g_vk.allocateMemory = reinterpret_cast<PFN_vkAllocateMemory>(
        framegen::loaderGetDeviceProcAddr()(device, "vkAllocateMemory"));
    g_vk.freeMemory = reinterpret_cast<PFN_vkFreeMemory>(
        framegen::loaderGetDeviceProcAddr()(device, "vkFreeMemory"));
    g_vk.bindImageMemory = reinterpret_cast<PFN_vkBindImageMemory>(
        framegen::loaderGetDeviceProcAddr()(device, "vkBindImageMemory"));
    g_vk.createSemaphore = reinterpret_cast<PFN_vkCreateSemaphore>(
        framegen::loaderGetDeviceProcAddr()(device, "vkCreateSemaphore"));
    g_vk.destroySemaphore = reinterpret_cast<PFN_vkDestroySemaphore>(
        framegen::loaderGetDeviceProcAddr()(device, "vkDestroySemaphore"));
    if (g_vk.getPhysicalDeviceProperties2 == nullptr
        || g_vk.getPhysicalDeviceMemoryProperties == nullptr
        || g_vk.getPhysicalDeviceImageFormatProperties2 == nullptr
        || g_vk.getPhysicalDeviceExternalSemaphoreProperties == nullptr
        || g_vk.deviceWaitIdle == nullptr
        || g_vk.createImage == nullptr
        || g_vk.destroyImage == nullptr
        || g_vk.getImageMemoryRequirements == nullptr
        || g_vk.allocateMemory == nullptr
        || g_vk.freeMemory == nullptr
        || g_vk.bindImageMemory == nullptr
        || g_vk.createSemaphore == nullptr
        || g_vk.destroySemaphore == nullptr) {
        g_vk = VulkanFunctions{};
        fail("Vulkan entry points for D3D12 interop could not be resolved");
        return false;
    }

    auto getMemoryHandle = reinterpret_cast<
        PFN_vkGetMemoryWin32HandleKHR>(
            framegen::loaderGetDeviceProcAddr()(
                device, "vkGetMemoryWin32HandleKHR"));
    g_getMemoryWin32HandleProperties = reinterpret_cast<
        PFN_vkGetMemoryWin32HandlePropertiesKHR>(
            framegen::loaderGetDeviceProcAddr()(
                device,
                "vkGetMemoryWin32HandlePropertiesKHR"));
    g_importSemaphoreWin32Handle = reinterpret_cast<
        PFN_vkImportSemaphoreWin32HandleKHR>(
            framegen::loaderGetDeviceProcAddr()(
                device,
                "vkImportSemaphoreWin32HandleKHR"));
    auto getSemaphoreHandle = reinterpret_cast<
        PFN_vkGetSemaphoreWin32HandleKHR>(
            framegen::loaderGetDeviceProcAddr()(
                device,
                "vkGetSemaphoreWin32HandleKHR"));
    g_queueSubmit2 = reinterpret_cast<PFN_vkQueueSubmit2>(
        framegen::loaderGetDeviceProcAddr()(device, "vkQueueSubmit2"));
    if (g_queueSubmit2 == nullptr) {
        g_queueSubmit2 = reinterpret_cast<PFN_vkQueueSubmit2>(
            framegen::loaderGetDeviceProcAddr()(device, "vkQueueSubmit2KHR"));
    }
    if (getMemoryHandle == nullptr
        || g_getMemoryWin32HandleProperties == nullptr) {
        fail("VK_KHR_external_memory_win32 was not enabled");
        return false;
    }
    g_capabilities |= kCapExternalMemory;
    if (g_importSemaphoreWin32Handle == nullptr
        || getSemaphoreHandle == nullptr
        || g_queueSubmit2 == nullptr) {
        fail("External D3D12-fence import or synchronization2 is unavailable");
        return false;
    }
    g_capabilities |= kCapExternalSemaphore;
    g_stage = Stage::VulkanInteropReady;

    VkPhysicalDeviceIDProperties idProperties{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES
    };
    VkPhysicalDeviceProperties2 properties{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2
    };
    properties.pNext = &idProperties;
    g_vk.getPhysicalDeviceProperties2(physicalDevice, &properties);
    if (idProperties.deviceLUIDValid != VK_TRUE) {
        fail("Vulkan driver did not expose a valid Windows adapter LUID");
        return false;
    }
    std::memcpy(
        g_luid.data(), idProperties.deviceLUID, VK_LUID_SIZE);

    UINT factoryFlags = 0;
    if (debugRequested()) {
        ComPtr<ID3D12Debug> debug;
        if (SUCCEEDED(D3D12GetDebugInterface(
                IID_PPV_ARGS(&debug)))) {
            debug->EnableDebugLayer();
            factoryFlags |= DXGI_CREATE_FACTORY_DEBUG;
        }
    }
    HRESULT result = CreateDXGIFactory2(
        factoryFlags, IID_PPV_ARGS(&g_factory));
    if (FAILED(result)) {
        fail("CreateDXGIFactory2 failed: "
            + hresultText(result));
        return false;
    }

    for (UINT index = 0; ; ++index) {
        ComPtr<IDXGIAdapter1> candidate;
        result = g_factory->EnumAdapterByGpuPreference(
            index,
            DXGI_GPU_PREFERENCE_HIGH_PERFORMANCE,
            IID_PPV_ARGS(&candidate)
        );
        if (result == DXGI_ERROR_NOT_FOUND) {
            break;
        }
        if (FAILED(result)) {
            fail("DXGI adapter enumeration failed: "
                + hresultText(result));
            return false;
        }
        DXGI_ADAPTER_DESC1 description{};
        if (FAILED(candidate->GetDesc1(&description))
                || (description.Flags
                    & DXGI_ADAPTER_FLAG_SOFTWARE) != 0
                || !luidEquals(description.AdapterLuid)) {
            continue;
        }
        if (FAILED(candidate.As(&g_adapter))) {
            fail("Matched DXGI adapter does not expose IDXGIAdapter4");
            return false;
        }
        break;
    }
    if (g_adapter == nullptr) {
        fail("No DXGI adapter matched the Vulkan device LUID");
        return false;
    }
    g_capabilities |= kCapLuidMatch;
    g_stage = Stage::AdapterMatched;

    result = D3D12CreateDevice(
        g_adapter.Get(),
        D3D_FEATURE_LEVEL_12_0,
        IID_PPV_ARGS(&g_d3d12Device)
    );
    if (FAILED(result)) {
        fail("D3D12CreateDevice failed on the matched adapter: "
            + hresultText(result));
        return false;
    }
    g_capabilities |= kCapD3D12Device;
    if (debugRequested()) {
        g_d3d12Device.As(&g_infoQueue);
        if (g_infoQueue != nullptr) {
            g_infoQueue->SetBreakOnSeverity(
                D3D12_MESSAGE_SEVERITY_CORRUPTION, FALSE);
            g_infoQueue->SetBreakOnSeverity(
                D3D12_MESSAGE_SEVERITY_ERROR, FALSE);
            g_infoQueue->SetBreakOnSeverity(
                D3D12_MESSAGE_SEVERITY_WARNING, FALSE);
        }
    }

    D3D12_COMMAND_QUEUE_DESC queueDescription{};
    queueDescription.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
    queueDescription.Priority =
        D3D12_COMMAND_QUEUE_PRIORITY_NORMAL;
    queueDescription.Flags = D3D12_COMMAND_QUEUE_FLAG_NONE;
    result = g_d3d12Device->CreateCommandQueue(
        &queueDescription, IID_PPV_ARGS(&g_d3d12Queue));
    if (FAILED(result)) {
        fail("D3D12 command queue creation failed: "
            + hresultText(result));
        return false;
    }
    g_capabilities |= kCapD3D12Queue;

    ComPtr<IDXGIFactory5> factory5;
    if (SUCCEEDED(g_factory.As(&factory5))) {
        BOOL tearing = FALSE;
        if (SUCCEEDED(factory5->CheckFeatureSupport(
                DXGI_FEATURE_PRESENT_ALLOW_TEARING,
                &tearing,
                sizeof(tearing)))) {
            g_tearingSupported = tearing == TRUE;
        }
    }

    if (!externalFenceSupported()) {
        fail("The Vulkan driver cannot import D3D12 fence handles");
        return false;
    }
    g_stage = Stage::D3D12DeviceReady;
    g_status =
        "Vulkan/D3D12 LUID match, external-memory functions, "
        "D3D12 device and direct queue are ready; DXGI presentation "
        "is not active";
    return true;
#endif
}

bool canCreatePresentationSwapchain() {
    std::lock_guard lock(g_mutex);
    return !g_sessionDisabled
        && (g_stage == Stage::D3D12DeviceReady
            || g_stage == Stage::PresentationReady);
}

bool ownsSwapchain(VkSwapchainKHR swapchain) {
    std::lock_guard lock(g_mutex);
    // B4: id comparison (no ABA) with explicit parentheses for the || chain.
    return (g_swapchainToken != nullptr
        && g_activeSwapchainId != 0
        && swapchain == idToHandle(g_activeSwapchainId))
        || (g_retiredSwapchain != VK_NULL_HANDLE
            && swapchain == g_retiredSwapchain);
}

int32_t createSwapchain(
    void* window,
    const VkSwapchainCreateInfoKHR* createInfo,
    VkSwapchainKHR* swapchain
) {
    std::unique_lock<std::mutex> lock(g_mutex);
#if !defined(_WIN32)
    return kNotHandled;
#else
    if (g_sessionDisabled
        || g_stage != Stage::D3D12DeviceReady
        || window == nullptr
        || createInfo == nullptr
        || swapchain == nullptr) {
        return kNotHandled;
    }
    releasePresentationLocked(lock);

    if (createInfo->imageColorSpace
            != VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
        || (createInfo->imageUsage
                & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
        fail("DXGI bridge only accepts Minecraft's SDR transfer-destination swapchain");
        g_sessionDisabled = true;
        return kNotHandled;
    }
    g_dxgiFormat = dxgiFormatForVulkan(
        createInfo->imageFormat);
    if (g_dxgiFormat == DXGI_FORMAT_UNKNOWN
        || !externalImageSupported(createInfo->imageFormat)) {
        fail("The selected swapchain format cannot be shared between D3D12 and Vulkan");
        g_sessionDisabled = true;
        return kNotHandled;
    }

    g_window = static_cast<HWND>(window);
    // B13: zero-area extents must be rejected before any committed resource
    // or DXGI swapchain is created from them.
    if (createInfo->imageExtent.width == 0
        || createInfo->imageExtent.height == 0) {
        fail("DXGI bridge received a zero-area swapchain extent");
        g_sessionDisabled = true;
        return kNotHandled;
    }
    g_width = createInfo->imageExtent.width;
    g_height = createInfo->imageExtent.height;
    g_bufferCount = std::clamp(
        createInfo->minImageCount, 2u, 4u);
    g_syncInterval =
        createInfo->presentMode == VK_PRESENT_MODE_FIFO_KHR
        || createInfo->presentMode
            == VK_PRESENT_MODE_FIFO_RELAXED_KHR
            ? 1u : 0u;
    g_presentFlags =
        g_syncInterval == 0 && g_tearingSupported
            ? DXGI_PRESENT_ALLOW_TEARING : 0u;

    DXGI_SWAP_CHAIN_DESC1 description{};
    description.Width = g_width;
    description.Height = g_height;
    description.Format = g_dxgiFormat;
    description.Stereo = FALSE;
    description.SampleDesc.Count = 1;
    description.BufferUsage =
        DXGI_USAGE_RENDER_TARGET_OUTPUT;
    description.BufferCount = g_bufferCount;
    description.Scaling = DXGI_SCALING_STRETCH;
    description.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    description.AlphaMode = DXGI_ALPHA_MODE_IGNORE;
    description.Flags =
        g_tearingSupported
            ? DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING : 0;

    ComPtr<IDXGISwapChain1> swapchain1;
    HRESULT result = g_factory->CreateSwapChainForHwnd(
        g_d3d12Queue.Get(),
        g_window,
        &description,
        nullptr,
        nullptr,
        &swapchain1
    );
    if (FAILED(result)
        || FAILED(swapchain1.As(&g_dxgiSwapchain))) {
        fail("DXGI flip-model swapchain creation failed: "
            + hresultText(result));
        g_sessionDisabled = true;
        releasePresentationLocked(lock);
        return kNotHandled;
    }
    g_factory->MakeWindowAssociation(
        g_window, DXGI_MWA_NO_ALT_ENTER);

    g_backBuffers.resize(g_bufferCount);
    for (uint32_t index = 0;
         index < g_bufferCount;
         ++index) {
        result = g_dxgiSwapchain->GetBuffer(
            index, IID_PPV_ARGS(&g_backBuffers[index]));
        if (FAILED(result)) {
            fail("DXGI backbuffer acquisition failed: "
                + hresultText(result));
            g_sessionDisabled = true;
            releasePresentationLocked(lock);
            return kNotHandled;
        }
    }

    std::string error;
    if (!createSharedTimeline(
            g_vulkanReadyFence,
            g_vulkanReadyHandle,
            g_vulkanReadySemaphore,
            error)
        || !createSharedTimeline(
            g_d3d12DoneFence,
            g_d3d12DoneHandle,
            g_d3d12DoneSemaphore,
            error)) {
        fail(error);
        g_sessionDisabled = true;
        releasePresentationLocked(lock);
        return kNotHandled;
    }

    g_sharedFrames.resize(g_bufferCount);
    for (SharedFrame& frame : g_sharedFrames) {
        if (!createSharedFrame(
                createInfo->imageFormat, frame, error)) {
            fail(error);
            g_sessionDisabled = true;
            releasePresentationLocked(lock);
            return kNotHandled;
        }
    }
    if (!createCopyCommands(error)) {
        fail(error);
        g_sessionDisabled = true;
        releasePresentationLocked(lock);
        return kNotHandled;
    }
    std::string debugDetail;
    if (drainDebugMessages(debugDetail)) {
        fail("D3D12 debug layer rejected swapchain setup: "
            + debugDetail);
        g_sessionDisabled = true;
        releasePresentationLocked(lock);
        return kNotHandled;
    }

    g_swapchainToken = std::make_unique<SwapchainToken>();
    // B4: hand out a fresh monotonic id (never a recyclable address).
    g_activeSwapchainId = ++g_nextSwapchainId;
    *swapchain = idToHandle(g_activeSwapchainId);
    g_stage = Stage::PresentationReady;
    // B1: new ownership generation takes over teardown responsibility.
    ++g_presentationEpoch;
    g_capabilities |=
        kCapDxgiSwapchain | kCapZeroCopyFrames;
    g_status =
        "DXGI flip-model presentation active with D3D12 shared "
        "textures and two GPU-shared fences";
    return VK_SUCCESS;
#endif
}

void destroySwapchain(VkSwapchainKHR swapchain) {
    std::unique_lock<std::mutex> lock(g_mutex);
#if defined(_WIN32)
    if (g_swapchainToken != nullptr
        && g_activeSwapchainId != 0
        && swapchain == idToHandle(g_activeSwapchainId)) {
        releasePresentationLocked(lock);
        // B1: ownership ended; any stale teardown still in its idle wait
        // observes this bump and aborts instead of double-resetting.
        ++g_presentationEpoch;
    } else if (swapchain == g_retiredSwapchain) {
        g_retiredSwapchain = VK_NULL_HANDLE;
    }
#else
    (void)swapchain;
#endif
}

int32_t getSwapchainImages(
    VkSwapchainKHR swapchain,
    uint32_t* count,
    VkImage* images
) {
    std::lock_guard lock(g_mutex);
#if !defined(_WIN32)
    return kNotHandled;
#else
    if (g_swapchainToken == nullptr
        || g_activeSwapchainId == 0
        || swapchain != idToHandle(g_activeSwapchainId)
        || count == nullptr) {
        return kNotHandled;
    }
    uint32_t available =
        static_cast<uint32_t>(g_sharedFrames.size());
    if (images == nullptr) {
        *count = available;
        return VK_SUCCESS;
    }
    uint32_t supplied = *count;
    uint32_t copied = std::min(supplied, available);
    for (uint32_t index = 0; index < copied; ++index) {
        images[index] = g_sharedFrames[index].vulkanImage;
    }
    *count = copied;
    return copied < available ? VK_INCOMPLETE : VK_SUCCESS;
#endif
}

int32_t acquireNextImage(
    VkSwapchainKHR swapchain,
    VkSemaphore acquireSemaphore,
    uint32_t* imageIndex
) {
    std::lock_guard lock(g_mutex);
#if !defined(_WIN32)
    return kNotHandled;
#else
    if (g_stage != Stage::PresentationReady
        || g_swapchainToken == nullptr
        || g_activeSwapchainId == 0
        || swapchain != idToHandle(g_activeSwapchainId)
        || acquireSemaphore == VK_NULL_HANDLE
        || imageIndex == nullptr
        || g_sharedFrames.empty()) {
        return kNotHandled;
    }
    // Round-robin with fence gate: skip frames still in flight.
    uint32_t frameCount = static_cast<uint32_t>(g_sharedFrames.size());
    uint32_t start = g_nextAcquire % frameCount;
    uint32_t index = start;
    bool found = false;
    for (uint32_t offset = 0; offset < frameCount; ++offset) {
        uint32_t candidate = (start + offset) % frameCount;
        uint64_t doneValue = g_sharedFrames[candidate].d3d12DoneValue;
        bool isAvailable = (doneValue == 0)
            || (g_d3d12DoneFence != nullptr
                && g_d3d12DoneFence->GetCompletedValue() >= doneValue);
        if (isAvailable) {
            index = candidate;
            g_nextAcquire = candidate + 1;
            found = true;
            break;
        }
    }
    if (!found) {
        // B2: every frame is still in flight. Report instead of falling back
        // to round-robin on a busy texture — reusing it would overwrite a
        // shared image the GPU is still reading. The renderer retries on the
        // next frame (standard VK_NOT_READY handling, no session damage).
        return VK_NOT_READY;
    }
    VkResult result = submitAcquireSignal(
        g_sharedFrames[index], acquireSemaphore);
    if (result != VK_SUCCESS) {
        fail("Vulkan acquire synchronization submit failed: "
            + std::to_string(result));
        g_sessionDisabled = true;
        return result;
    }
    g_currentImageIndex = static_cast<int32_t>(index);
    *imageIndex = index;
    return VK_SUCCESS;
#endif
}

int32_t queuePresent(
    VkQueue queue,
    const VkPresentInfoKHR* presentInfo
) {
    std::unique_lock<std::mutex> lock(g_mutex);
#if !defined(_WIN32)
    return kNotHandled;
#else
    if (g_stage != Stage::PresentationReady
        || presentInfo == nullptr
        || presentInfo->swapchainCount != 1
        || presentInfo->pSwapchains == nullptr
        || presentInfo->pImageIndices == nullptr
        || g_swapchainToken == nullptr
        || g_activeSwapchainId == 0
        || presentInfo->pSwapchains[0]
            != idToHandle(g_activeSwapchainId)) {
        return kNotHandled;
    }
    if (queue != g_vkGraphicsQueue) {
        fail("DXGI bridge received a different Vulkan presentation queue");
        g_sessionDisabled = true;
        return VK_ERROR_DEVICE_LOST;
    }
    // B5: bound the JNI-supplied wait-semaphore block before submitVulkanReady
    // allocates from it under lock. A corrupt count or a non-null count with a
    // null pointer would otherwise OOM or crash the JVM.
    if (presentInfo->waitSemaphoreCount > kMaxPresentWaitSemaphores
        || (presentInfo->waitSemaphoreCount > 0
            && presentInfo->pWaitSemaphores == nullptr)) {
        fail("DXGI bridge received an invalid present wait-semaphore block");
        g_sessionDisabled = true;
        return VK_ERROR_OUT_OF_DATE_KHR;
    }
    uint32_t sourceIndex = presentInfo->pImageIndices[0];
    if (sourceIndex >= g_sharedFrames.size()
        || g_currentImageIndex
            != static_cast<int32_t>(sourceIndex)) {
        fail("DXGI bridge received an out-of-order presentation image");
        g_sessionDisabled = true;
        return VK_ERROR_OUT_OF_DATE_KHR;
    }

    // B3: the submit/wait/copy/signal sequence below intentionally stays under
    // g_mutex: teardown (destroySwapchain/shutdown) takes the same lock, so
    // releasing mid-sequence would let it free copy commands and shared frames
    // still referenced below. The only unbounded under-lock work was the
    // wait-semaphore vector, now capped by kMaxPresentWaitSemaphores (B5).
    // Present() itself already runs unlocked. No blocking wait holds the lock:
    // submits and fence waits only enqueue; teardown idles after unlocking.
    uint64_t readyValue = ++g_readyValue;
    VkResult vkResult = submitVulkanReady(
        presentInfo, readyValue);
    if (vkResult != VK_SUCCESS) {
        fail("Vulkan-to-D3D12 fence signal failed: "
            + std::to_string(vkResult));
        g_sessionDisabled = true;
        return vkResult;
    }
    HRESULT result = g_d3d12Queue->Wait(
        g_vulkanReadyFence.Get(), readyValue);
    if (FAILED(result)) {
        // B12: a bare HRESULT hex code never explains a dead queue — attach
        // the device-removed reason like the Present path below does.
        HRESULT removed = g_d3d12Device->GetDeviceRemovedReason();
        fail("D3D12 queue wait for Vulkan failed: "
            + hresultText(result)
            + ", device=" + hresultText(removed));
        g_sessionDisabled = true;
        return VK_ERROR_DEVICE_LOST;
    }

    uint32_t destinationIndex =
        g_dxgiSwapchain->GetCurrentBackBufferIndex();
    // B13: the driver-owned back-buffer index is untrusted input for the
    // copy-command lookup below — bound it before indexing.
    if (destinationIndex >= g_bufferCount) {
        fail("DXGI bridge reported an out-of-range back-buffer index");
        g_sessionDisabled = true;
        return VK_ERROR_OUT_OF_DATE_KHR;
    }
    CopyCommand& command =
        g_copyCommands[
            static_cast<size_t>(sourceIndex) * g_bufferCount
            + destinationIndex];
    ID3D12CommandList* lists[] = {command.list.Get()};
    g_d3d12Queue->ExecuteCommandLists(1, lists);
    result = g_d3d12Queue->Signal(
        g_d3d12DoneFence.Get(), readyValue);
    if (FAILED(result)) {
        // B12: see the queue-wait path above.
        HRESULT removed = g_d3d12Device->GetDeviceRemovedReason();
        fail("D3D12 completion fence signal failed: "
            + hresultText(result)
            + ", device=" + hresultText(removed));
        g_sessionDisabled = true;
        return VK_ERROR_DEVICE_LOST;
    }
    g_sharedFrames[sourceIndex].d3d12DoneValue =
        readyValue;
    g_lastSubmittedDone = readyValue;

    Microsoft::WRL::ComPtr<IDXGISwapChain4> swapchain = g_dxgiSwapchain;
    const UINT syncInterval = g_syncInterval;
    const UINT presentFlags = g_presentFlags;
    g_currentImageIndex = -1;

    lock.unlock();
    result = swapchain->Present(syncInterval, presentFlags);
    lock.lock();

    if (g_sessionDisabled || g_dxgiSwapchain == nullptr) {
        return VK_SUCCESS;
    }
    if (result == DXGI_STATUS_OCCLUDED) {
        g_status =
            "DXGI presentation is active but the window is occluded";
        return VK_SUCCESS;
    }
    if (FAILED(result)) {
        HRESULT removed = g_d3d12Device->GetDeviceRemovedReason();
        fail("DXGI Present failed: " + hresultText(result)
            + ", device=" + hresultText(removed));
        g_sessionDisabled = true;
        return result == DXGI_ERROR_DEVICE_REMOVED
                || result == DXGI_ERROR_DEVICE_RESET
            ? VK_ERROR_DEVICE_LOST
            : VK_ERROR_OUT_OF_DATE_KHR;
    }
    if (g_infoQueue != nullptr
        && readyValue % 120 == 0) {
        std::string debugDetail;
        if (drainDebugMessages(debugDetail)) {
            fail("D3D12 debug layer reported a presentation error: "
                + debugDetail);
            g_sessionDisabled = true;
            return VK_ERROR_DEVICE_LOST;
        }
        if (!debugDetail.empty()) {
            g_status =
                "DXGI presentation active; D3D12 debug warning: "
                + debugDetail;
            return VK_SUCCESS;
        }
    }
    g_status =
        "DXGI flip-model presentation active; last shared frame "
        + std::to_string(readyValue) + " completed submission";
    return VK_SUCCESS;
#endif
}

void shutdown() {
    std::unique_lock<std::mutex> lock(g_mutex);
#if defined(_WIN32)
    releasePresentationLocked(lock);
    // B1: see destroySwapchain — ownership ended here as well.
    ++g_presentationEpoch;
    g_d3d12Queue.Reset();
    g_d3d12Device.Reset();
    g_infoQueue.Reset();
    g_adapter.Reset();
    g_factory.Reset();
#endif
    g_vkPhysicalDevice = VK_NULL_HANDLE;
    g_vkDevice = VK_NULL_HANDLE;
    g_vkGraphicsQueue = VK_NULL_HANDLE;
    g_vkGraphicsQueueFamily = VK_QUEUE_FAMILY_IGNORED;
    g_stage = Stage::Unavailable;
    g_capabilities = 0;
    g_status = "D3D12 presentation bridge shut down";
    g_luid.fill(0);
}

Stage stage() {
    std::lock_guard lock(g_mutex);
    return g_stage;
}

int32_t capabilities() {
    std::lock_guard lock(g_mutex);
    return g_capabilities;
}

std::string status() {
    std::lock_guard lock(g_mutex);
    return g_status;
}

std::string adapterLuid() {
    std::lock_guard lock(g_mutex);
    std::ostringstream stream;
    stream << std::hex << std::setfill('0');
    for (uint8_t value : g_luid) {
        stream << std::setw(2)
               << static_cast<unsigned>(value);
    }
    return stream.str();
}

} // namespace dxgipresenter
