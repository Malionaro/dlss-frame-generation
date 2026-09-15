cmake_minimum_required(VERSION 3.24)
project(dlss_fg_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

message(STATUS "Configuring DLSS Frame Generation Native JNI Library")

# Locate Streamline SDK
if(DEFINED ENV{STREAMLINE_SDK_DIR})
    set(STREAMLINE_SDK_DIR "$ENV{STREAMLINE_SDK_DIR}")
    message(STATUS "Using Streamline SDK from environment variable: ${STREAMLINE_SDK_DIR}")
else()
    set(STREAMLINE_SDK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/streamline")
    message(STATUS "Using Streamline SDK from default folder: ${STREAMLINE_SDK_DIR}")
endif()

# Find JNI headers & libraries
find_package(JNI REQUIRED)
message(STATUS "Found JNI: ${JNI_INCLUDE_DIRS}")

# Streamline only needs Vulkan headers at compile time; Vulkan entry points are
# resolved through sl.interposer at runtime. Fetching the Khronos headers keeps
# the native build reproducible without requiring a machine-wide Vulkan SDK.
include(FetchContent)
FetchContent_Declare(
    VulkanHeaders
    URL https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/v1.4.362.zip
    URL_HASH SHA256=7a112d66b6bef6527aa7ca56261bedb136a643fcb8d3cd2707dff7e77c4c61d5
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(VulkanHeaders)

# Native library sources
set(NATIVE_SOURCES
    bridge/fidelityfx/FidelityFxBridge.cpp
    bridge/routing/FrameGenCompatibilityLayer.cpp
    bridge/streamline/StreamlineBridge.cpp
)
if(WIN32)
    list(APPEND NATIVE_SOURCES bridge/presentation/WindowsDxgiPresenter.cpp)
endif()

add_library(dlss_fg_native SHARED ${NATIVE_SOURCES})

# Header includes
target_include_directories(dlss_fg_native PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}/bridge/fidelityfx
    ${CMAKE_CURRENT_SOURCE_DIR}/bridge/presentation
    ${CMAKE_CURRENT_SOURCE_DIR}/bridge/routing
    ${CMAKE_CURRENT_SOURCE_DIR}/bridge/streamline
    ${CMAKE_CURRENT_SOURCE_DIR}/fidelityfx/include
    ${STREAMLINE_SDK_DIR}/include
)

# Link NVIDIA Streamline SDK import library and Windows DirectX 12 libraries
if(WIN32)
    target_compile_definitions(
        dlss_fg_native PRIVATE
        VK_USE_PLATFORM_WIN32_KHR
        WIN32_LEAN_AND_MEAN
        NOMINMAX
    )
    find_library(SL_LIB NAMES sl sl_wrapper sl_interop sl.interposer
        PATHS "${STREAMLINE_SDK_DIR}/lib/x64" "${STREAMLINE_SDK_DIR}/lib"
        REQUIRED
    )
    message(STATUS "Found NVIDIA Streamline import library: ${SL_LIB}")
    target_link_libraries(dlss_fg_native PRIVATE ${SL_LIB} Vulkan::Headers d3d12.lib dxgi.lib)
else()
    # Fallback configuration for non-Windows (if compiled on Linux/macOS).
    # Vulkan headers are always required; the Streamline import library is
    # optional (Windows-only redistributable) and must not gate them.
    target_link_libraries(dlss_fg_native PRIVATE Vulkan::Headers)
    find_library(SL_LIB NAMES sl sl_wrapper sl_interop sl.interposer
        PATHS "${STREAMLINE_SDK_DIR}/lib"
    )
    if(SL_LIB)
        message(STATUS "Found NVIDIA Streamline import library: ${SL_LIB}")
        target_link_libraries(dlss_fg_native PRIVATE ${SL_LIB})
    endif()
endif()
