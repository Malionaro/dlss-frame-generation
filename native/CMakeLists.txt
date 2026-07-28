cmake_minimum_required(VERSION 3.16)
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
    GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
    GIT_TAG v1.4.313
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(VulkanHeaders)

# Native library
add_library(dlss_fg_native SHARED
    bridge/FidelityFxBridge.cpp
    bridge/FrameGenCompatibilityLayer.cpp
    bridge/StreamlineBridge.cpp
)

# Header includes
target_include_directories(dlss_fg_native PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}/bridge
    ${CMAKE_CURRENT_SOURCE_DIR}/fidelityfx/include
    ${STREAMLINE_SDK_DIR}/include
)

# Link NVIDIA Streamline SDK import library and Windows DirectX 12 libraries
if(WIN32)
    find_library(SL_LIB NAMES sl sl_wrapper sl_interop sl.interposer
        PATHS "${STREAMLINE_SDK_DIR}/lib/x64" "${STREAMLINE_SDK_DIR}/lib"
        REQUIRED
    )
    message(STATUS "Found NVIDIA Streamline import library: ${SL_LIB}")
    target_link_libraries(dlss_fg_native PRIVATE ${SL_LIB} Vulkan::Headers d3d12.lib dxgi.lib)
else()
    # Fallback configuration for non-Windows (if compiled on Linux/macOS)
    find_library(SL_LIB NAMES sl sl_wrapper sl_interop sl.interposer
        PATHS "${STREAMLINE_SDK_DIR}/lib"
    )
    if(SL_LIB)
        target_link_libraries(dlss_fg_native PRIVATE ${SL_LIB} Vulkan::Headers)
    endif()
endif()
