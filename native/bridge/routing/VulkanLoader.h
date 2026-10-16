#pragma once

#include <vulkan/vulkan.h>

#if defined(_WIN32)
#include <windows.h>
#endif

namespace framegen {

// Entry points of the real Khronos loader, bypassing sl.interposer.
//
// The bridge links against sl.interposer.lib, so the plain vkGetDeviceProcAddr /
// vkGetInstanceProcAddr symbols resolve to Streamline. Streamline only installs
// its dispatch table for an NVIDIA device; on AMD and Intel every function it
// hooks (vkDeviceWaitIdle, for example) jumps through a null pointer. The
// FidelityFX route and the D3D12 presenter therefore resolve their Vulkan
// functions from vulkan-1.dll directly. The handles are the loader's own, since
// the interposer passes them through unwrapped.
//
// Falls back to the linked symbols when the loader cannot be found, which is
// the previous behaviour.
inline PFN_vkGetInstanceProcAddr loaderGetInstanceProcAddr() {
#if defined(_WIN32)
    static const PFN_vkGetInstanceProcAddr resolved = [] {
        HMODULE module = GetModuleHandleW(L"vulkan-1.dll");
        if (module == nullptr) {
            module = LoadLibraryExW(
                L"vulkan-1.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
        }
        auto function = module == nullptr
            ? nullptr
            : reinterpret_cast<PFN_vkGetInstanceProcAddr>(
                GetProcAddress(module, "vkGetInstanceProcAddr"));
        return function != nullptr ? function : &vkGetInstanceProcAddr;
    }();
    return resolved;
#else
    return &vkGetInstanceProcAddr;
#endif
}

inline PFN_vkGetDeviceProcAddr loaderGetDeviceProcAddr() {
#if defined(_WIN32)
    static const PFN_vkGetDeviceProcAddr resolved = [] {
        HMODULE module = GetModuleHandleW(L"vulkan-1.dll");
        if (module == nullptr) {
            module = LoadLibraryExW(
                L"vulkan-1.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
        }
        auto function = module == nullptr
            ? nullptr
            : reinterpret_cast<PFN_vkGetDeviceProcAddr>(
                GetProcAddress(module, "vkGetDeviceProcAddr"));
        return function != nullptr ? function : &vkGetDeviceProcAddr;
    }();
    return resolved;
#else
    return &vkGetDeviceProcAddr;
#endif
}

} // namespace framegen
