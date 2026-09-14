#include <jni.h>
#include "FrameGenCompatibilityLayer.h"
#include "WindowsDxgiPresenter.h"
#include "FidelityFxBridge.h"
#include <algorithm>
#include <atomic>
#include <iostream>
#include <iterator>
#include <mutex>
#include <string>
#include <vector>

#include <vulkan/vulkan.h>

// Include Streamline SDK files
#include "sl.h"
#include "sl_consts.h"
#include "sl_dlss_g.h"
#include "sl_dlss.h"
#include "sl_dlss_d.h"
#include "sl_reflex.h"
#include "sl_pcl.h"
#include "sl_helpers.h"
#include "sl_helpers_vk.h"

#define DLSS_FG_NATIVE_ABI_VERSION 2

static std::mutex g_streamlineMutex;
// B9: lifecycle flags and device handles are read from JNI entry points that
// do not take g_streamlineMutex (capability queries, mode setters, VRAM
// probes) while nShutdown/nInit mutate them under lock. Plain statics would
// be a data race / use-after-shutdown TOCTOU, so they are atomic. Larger
// state (strings, vectors, tokens, viewport) stays mutex-guarded.
static bool g_coreInitialized = false;
static std::atomic<bool> g_initialized = false;
static std::atomic<bool> g_dlssgSupported = false;
// Hardware support alone is not enough. DLSS-G must remain disabled until the
// renderer supplies every mandatory Vulkan resource (including dense motion
// vectors and complete VkImage/VkImageView/VkDeviceMemory/format metadata).
static std::atomic<bool> g_dlssgInputsReady = false;
static std::atomic<bool> g_dlssgEnabled = false;
static std::atomic<bool> g_dlssgRuntimeReady = false;
static std::atomic<bool> g_dlssSupported = false;
static std::atomic<bool> g_dlssInputsReady = false;
static std::atomic<bool> g_dlssRrSupported = false;
static std::atomic<bool> g_dlssRrInputsReady = false;
static std::atomic<bool> g_dlssRrModeActive = false;
static std::atomic<bool> g_reflexSupported = false;
static sl::Result g_dlssgSupportResult = sl::Result::eErrorNotInitialized;
static sl::Result g_dlssSupportResult = sl::Result::eErrorNotInitialized;
static sl::Result g_dlssRrSupportResult = sl::Result::eErrorNotInitialized;
static sl::Result g_reflexSupportResult = sl::Result::eErrorNotInitialized;
static uint32_t g_maxFrameMultiplier = 1;
static std::string g_nvidiaStatus = "NVIDIA Streamline has not been initialized";
static std::atomic<VkInstance> g_vkInstance = VK_NULL_HANDLE;
static std::atomic<VkPhysicalDevice> g_vkPhysicalDevice = VK_NULL_HANDLE;
static std::atomic<VkDevice> g_streamlineDevice = VK_NULL_HANDLE;
static sl::ViewportHandle g_viewport(0);
static sl::FrameToken* g_currentFrameToken = nullptr;
static bool g_frameTokenRefreshedForCurrentFrame = false;
static uint32_t g_frameIndex = 0;
static std::atomic<int> g_dlssMode = 0;
static std::vector<std::string> g_requiredInstanceExtensions;
static std::vector<std::string> g_requiredDeviceExtensions;
static std::vector<std::string> g_requiredFeatures12;
static std::vector<std::string> g_requiredFeatures13;

static void appendUnique(
    std::vector<std::string>& destination,
    const char** values,
    uint32_t count
) {
    if (values == nullptr) return;
    for (uint32_t index = 0; index < count; ++index) {
        if (values[index] == nullptr || values[index][0] == '\0') continue;
        const std::string value(values[index]);
        if (std::find(destination.begin(), destination.end(), value) ==
                destination.end()) {
            destination.push_back(value);
        }
    }
}

static void refreshVulkanRequirements() {
    g_requiredInstanceExtensions.clear();
    g_requiredDeviceExtensions.clear();
    g_requiredFeatures12.clear();
    g_requiredFeatures13.clear();

    const sl::Feature features[] = {
        sl::kFeatureDLSS_G,
        sl::kFeatureDLSS,
        sl::kFeatureDLSS_RR,
        sl::kFeatureReflex
    };
    for (const sl::Feature feature : features) {
        sl::FeatureRequirements requirements{};
        if (slGetFeatureRequirements(feature, requirements) !=
                sl::Result::eOk) {
            continue;
        }
        appendUnique(
            g_requiredInstanceExtensions,
            requirements.vkInstanceExtensions,
            requirements.vkNumInstanceExtensions
        );
        appendUnique(
            g_requiredDeviceExtensions,
            requirements.vkDeviceExtensions,
            requirements.vkNumDeviceExtensions
        );
        appendUnique(
            g_requiredFeatures12,
            requirements.vkFeatures12,
            requirements.vkNumFeatures12
        );
        appendUnique(
            g_requiredFeatures13,
            requirements.vkFeatures13,
            requirements.vkNumFeatures13
        );
    }
}

static jobjectArray toJavaStringArray(
    JNIEnv* env,
    const std::vector<std::string>& values
) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(values.size()),
        stringClass,
        nullptr
    );
    if (result == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(stringClass);
        return nullptr;
    }
    for (jsize index = 0;
         index < static_cast<jsize>(values.size());
         ++index) {
        jstring value = env->NewStringUTF(values[static_cast<size_t>(index)].c_str());
        if (value == nullptr || env->ExceptionCheck()) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(stringClass);
            return result;
        }
        env->SetObjectArrayElement(result, index, value);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            env->DeleteLocalRef(value);
            env->DeleteLocalRef(stringClass);
            return result;
        }
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(stringClass);
    return result;
}

static std::string featureSupportSummary(
    const char* featureName,
    sl::Result result
) {
    return std::string(featureName) + "=" + sl::getResultAsStr(result);
}

static std::string dlssgStatusSummary(sl::DLSSGStatus status) {
    const uint32_t bits = static_cast<uint32_t>(status);
    if (bits == 0) return "ok";

    std::vector<std::string> failures;
    if ((bits & static_cast<uint32_t>(
            sl::DLSSGStatus::eFailResolutionTooLow)) != 0) {
        failures.emplace_back("resolution too low");
    }
    if ((bits & static_cast<uint32_t>(
            sl::DLSSGStatus::eFailReflexNotDetectedAtRuntime)) != 0) {
        failures.emplace_back("Reflex is not active");
    }
    if ((bits & static_cast<uint32_t>(
            sl::DLSSGStatus::eFailHDRFormatNotSupported)) != 0) {
        failures.emplace_back("HDR format unsupported");
    }
    if ((bits & static_cast<uint32_t>(
            sl::DLSSGStatus::eFailCommonConstantsInvalid)) != 0) {
        failures.emplace_back("frame constants invalid");
    }
    if ((bits & static_cast<uint32_t>(
            sl::DLSSGStatus::eFailGetCurrentBackBufferIndexNotCalled)) != 0) {
        failures.emplace_back("backbuffer index was not observed");
    }
    if (failures.empty()) {
        return "unknown status bits " + std::to_string(bits);
    }

    std::string result;
    for (size_t index = 0; index < failures.size(); ++index) {
        if (index != 0) result += ", ";
        result += failures[index];
    }
    return result;
}

static sl::Result applyDlssgOptions(
    bool enabled,
    int multiplier,
    bool uiRecomposition
) {
    sl::DLSSGOptions options{};
    options.mode = enabled ? sl::DLSSGMode::eOn : sl::DLSSGMode::eOff;
    const uint32_t requestedMultiplier =
        static_cast<uint32_t>(std::max(2, multiplier));
    const uint32_t clampedMultiplier =
        std::min(requestedMultiplier, g_maxFrameMultiplier);
    options.numFramesToGenerate = clampedMultiplier - 1;
    options.enableUserInterfaceRecomposition = uiRecomposition
        ? sl::Boolean::eTrue
        : sl::Boolean::eFalse;
    return slDLSSGSetOptions(g_viewport, options);
}

static bool finalizeDlssgFrame(
    bool enabled,
    int multiplier,
    bool uiRecomposition
) {
    g_dlssgEnabled = enabled;
    g_dlssgRuntimeReady = false;
    const sl::Result optionsResult = applyDlssgOptions(
        g_dlssgEnabled, multiplier, uiRecomposition
    );
    if (optionsResult != sl::Result::eOk) {
        g_nvidiaStatus = "DLSS-G options were rejected (" +
            std::to_string(static_cast<int>(optionsResult)) + ")";
        return false;
    }

    if (g_dlssgEnabled) {
        sl::DLSSGState state{};
        const sl::Result stateResult =
            slDLSSGGetState(g_viewport, state, nullptr);
        if (stateResult != sl::Result::eOk) {
            g_nvidiaStatus = "DLSS-G state query failed (" +
                std::to_string(static_cast<int>(stateResult)) + ")";
            return false;
        }
        g_maxFrameMultiplier =
            std::max(2u, state.numFramesToGenerateMax + 1u);
        if (state.status != sl::DLSSGStatus::eOk) {
            g_nvidiaStatus = "DLSS-G runtime is not active: " +
                dlssgStatusSummary(state.status);
            if (state.minWidthOrHeight != 0) {
                g_nvidiaStatus += "; minimum dimension " +
                    std::to_string(state.minWidthOrHeight);
            }
            return false;
        }
        g_dlssgRuntimeReady = true;
    }

    g_nvidiaStatus = g_dlssgEnabled
        ? uiRecomposition
            ? "DLSS-G active with HUD-less color and a separate UI layer"
            : "DLSS-G active with renderer-owned depth, motion vectors and HUD-less color"
        : "DLSS-G inputs valid; Frame Generation is disabled in settings";
    if (g_dlssgEnabled && g_dlssgRuntimeReady) {
        framegen::markProviderActive(g_nvidiaStatus);
    } else {
        framegen::markProviderReady(g_nvidiaStatus);
    }
    g_frameTokenRefreshedForCurrentFrame = false;
    return true;
}

static sl::DLSSMode dlssModeFromIndex(int mode) {
    switch (mode) {
        case 1: return sl::DLSSMode::eDLAA;
        case 2: return sl::DLSSMode::eMaxQuality;
        case 3: return sl::DLSSMode::eBalanced;
        case 4: return sl::DLSSMode::eMaxPerformance;
        case 5: return sl::DLSSMode::eUltraPerformance;
        case 6: return sl::DLSSMode::eUltraQuality;
        default: return sl::DLSSMode::eOff;
    }
}

static void useRecommendedDlssPresets(sl::DLSSOptions& options) {
    options.dlaaPreset = sl::DLSSPreset::ePresetK;
    options.qualityPreset = sl::DLSSPreset::ePresetK;
    options.balancedPreset = sl::DLSSPreset::ePresetK;
    options.performancePreset = sl::DLSSPreset::ePresetM;
    options.ultraPerformancePreset = sl::DLSSPreset::ePresetL;
    options.ultraQualityPreset = sl::DLSSPreset::ePresetK;
}

static void describeVulkanTexture(
    sl::Resource& resource,
    uint32_t width,
    uint32_t height,
    uint32_t format,
    uint32_t usage
) {
    resource.width = width;
    resource.height = height;
    resource.nativeFormat = format;
    resource.mipLevels = 1;
    resource.arrayLayers = 1;
    resource.usage = usage;
}

static std::wstring toWideString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return {};
    }
    const jsize length = env->GetStringLength(value);
    std::wstring result;
    result.reserve(static_cast<size_t>(length));
    for (jsize i = 0; i < length; ++i) {
        result.push_back(static_cast<wchar_t>(chars[i]));
    }
    env->ReleaseStringChars(value, chars);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    return result;
}

static bool initializeCore(JNIEnv* env, jstring pluginsPath) {
    // B11: JNI string extraction can block at GC safepoints and slInit below
    // blocks for seconds — neither may run under the global frame-path lock.
    // A dedicated init-only mutex serializes concurrent initializers; the
    // global lock is taken only for the state check/update snapshots.
    std::wstring path = toWideString(env, pluginsPath);
    static std::mutex initMutex;
    std::lock_guard<std::mutex> initLock(initMutex);
    {
        std::lock_guard<std::mutex> lock(g_streamlineMutex);
        if (g_coreInitialized) return true;
    }

    const wchar_t* paths[] = { path.c_str() };

    sl::Preferences pref{};
    pref.showConsole = false;
    pref.logLevel = sl::LogLevel::eDefault;
    pref.renderAPI = sl::RenderAPI::eVulkan;
    pref.pathsToPlugins = path.empty() ? nullptr : paths;
    pref.numPathsToPlugins = path.empty() ? 0 : 1;
    pref.pathToLogsAndData = path.empty() ? nullptr : path.c_str();
    // NativeLoader configures LWJGL itself to resolve Vulkan through
    // sl.interposer.dll before org.lwjgl.vulkan.VK is initialized. This gives
    // Streamline the complete automatic-hooking lifecycle, including the
    // Win32 surface and vkDeviceWaitIdle calls that manual hooking would
    // otherwise require us to proxy individually.
    pref.flags = pref.flags
        | sl::PreferenceFlags::eUseFrameBasedResourceTagging;
    pref.engine = sl::EngineType::eCustom;
    pref.engineVersion = "Minecraft 26.2 Fabric Vulkan";

    static std::vector<sl::Feature> features = {
        sl::kFeatureDLSS_G,
        sl::kFeatureReflex,
        sl::kFeatureDLSS,
        sl::kFeatureDLSS_RR
    };
    pref.featuresToLoad = features.data();
    pref.numFeaturesToLoad = static_cast<uint32_t>(features.size());

    const sl::Result result = slInit(pref);
    {
        std::lock_guard<std::mutex> lock(g_streamlineMutex);
        // Re-check: a shutdown (or a racing initializer) may have run while
        // slInit was in flight without the global lock.
        if (g_coreInitialized) return true;
        if (result != sl::Result::eOk) {
            g_nvidiaStatus = "Streamline core initialization failed (" + std::to_string(static_cast<int>(result)) + ")";
            std::cerr << "[DLSS_FG_Native] " << g_nvidiaStatus << std::endl;
            return false;
        }

        g_coreInitialized = true;
        refreshVulkanRequirements();
        g_nvidiaStatus = "Streamline core initialized; waiting for Vulkan device";
        return true;
    }
}

extern "C" JNIEXPORT jboolean JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nInitCore
  (JNIEnv* env, jclass, jstring pluginsPath) {
    return initializeCore(env, pluginsPath) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetVulkanRequirements
  (JNIEnv* env, jclass, jint kind) {
    std::vector<std::string> snapshot;
    {
        std::lock_guard<std::mutex> lock(g_streamlineMutex);
        if (!g_coreInitialized) {
            return toJavaStringArray(env, {});
        }
        switch (kind) {
            case 0:
                snapshot = g_requiredInstanceExtensions;
                break;
            case 1:
                snapshot = g_requiredDeviceExtensions;
                break;
            case 2:
                snapshot = g_requiredFeatures12;
                break;
            case 3:
                snapshot = g_requiredFeatures13;
                break;
            default:
                snapshot.clear();
                break;
        }
    }
    return toJavaStringArray(env, snapshot);
}

extern "C" JNIEXPORT jboolean JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nInit
  (JNIEnv* env, jclass clazz, jlong instance, jlong physicalDevice, jlong device, jlong queue,
   jint queueFamilyIndex, jint vendorId, jstring pluginsPath) {
    if (instance == 0 || physicalDevice == 0 || device == 0 || queue == 0 || queueFamilyIndex < 0) {
        g_nvidiaStatus = "Invalid Vulkan handles supplied to Streamline";
        framegen::markProviderFailed(g_nvidiaStatus);
        return JNI_FALSE;
    }

    // Retained for VK_EXT_memory_budget queries (nQueryMemoryBudget). Both
    // vendor paths store them so budget sampling works for NVIDIA, AMD and
    // Intel adapters alike.
    g_vkInstance = reinterpret_cast<VkInstance>(instance);
    g_vkPhysicalDevice = reinterpret_cast<VkPhysicalDevice>(physicalDevice);

    // The Java Vulkan object already owns a valid properties snapshot. Passing
    // the vendor ID avoids calling a Vulkan loader export through an
    // interposer whose device dispatch table is not installed at this point.
    if (vendorId == 0x1002 || vendorId == 0x8086) {
        return framegen::initializeVulkan(
            toWideString(env, pluginsPath),
            static_cast<uint32_t>(vendorId),
            reinterpret_cast<VkPhysicalDevice>(physicalDevice),
            reinterpret_cast<VkDevice>(device),
            reinterpret_cast<VkQueue>(queue),
            static_cast<uint32_t>(queueFamilyIndex)
        ) ? JNI_TRUE : JNI_FALSE;
    }

    if (g_initialized) return JNI_TRUE;
    framegen::initializeVulkan(
        toWideString(env, pluginsPath),
        static_cast<uint32_t>(vendorId),
        reinterpret_cast<VkPhysicalDevice>(physicalDevice),
        reinterpret_cast<VkDevice>(device),
        reinterpret_cast<VkQueue>(queue),
        static_cast<uint32_t>(queueFamilyIndex)
    );
    if (!initializeCore(env, pluginsPath)) {
        framegen::markProviderFailed(g_nvidiaStatus);
        return JNI_FALSE;
    }

    sl::VulkanInfo vkInfo{};
    vkInfo.instance = reinterpret_cast<VkInstance>(instance);
    vkInfo.physicalDevice = reinterpret_cast<VkPhysicalDevice>(physicalDevice);
    vkInfo.device = reinterpret_cast<VkDevice>(device);
    vkInfo.graphicsQueueFamily = static_cast<uint32_t>(queueFamilyIndex);
    vkInfo.graphicsQueueIndex = 0; // Standard main graphics queue index
    vkInfo.computeQueueFamily = static_cast<uint32_t>(queueFamilyIndex);
    vkInfo.computeQueueIndex = 0;
    // Do not advertise Minecraft's graphics queue as a native optical-flow
    // queue. Streamline explicitly requires that queue to be from an
    // optical-flow-only family. With useNativeOpticalFlowMode=false it uses
    // its supported interoperability path instead.
    vkInfo.useNativeOpticalFlowMode = false;

    sl::Result res = slSetVulkanInfo(vkInfo);
    if (res != sl::Result::eOk) {
        g_nvidiaStatus = "slSetVulkanInfo failed (" + std::to_string(static_cast<int>(res)) + ")";
        framegen::markProviderFailed(g_nvidiaStatus);
        std::cerr << "[DLSS_FG_Native] " << g_nvidiaStatus << std::endl;
        return JNI_FALSE;
    }
    g_streamlineDevice = reinterpret_cast<VkDevice>(device);

    sl::AdapterInfo adapterInfo{};
    adapterInfo.vkPhysicalDevice = reinterpret_cast<void*>(physicalDevice);
    g_dlssgSupportResult =
        slIsFeatureSupported(sl::kFeatureDLSS_G, adapterInfo);
    g_dlssSupportResult =
        slIsFeatureSupported(sl::kFeatureDLSS, adapterInfo);
    g_dlssRrSupportResult =
        slIsFeatureSupported(sl::kFeatureDLSS_RR, adapterInfo);
    g_reflexSupportResult =
        slIsFeatureSupported(sl::kFeatureReflex, adapterInfo);
    g_dlssgSupported = g_dlssgSupportResult == sl::Result::eOk;
    g_dlssSupported = g_dlssSupportResult == sl::Result::eOk;
    g_dlssRrSupported = g_dlssRrSupportResult == sl::Result::eOk;
    g_reflexSupported = g_reflexSupportResult == sl::Result::eOk;

    g_initialized = true;
    if (g_dlssgSupported) {
        sl::DLSSGState state{};
        if (slDLSSGGetState(g_viewport, state, nullptr) == sl::Result::eOk) {
            g_maxFrameMultiplier = std::max(2u, state.numFramesToGenerateMax + 1u);
        } else {
            g_maxFrameMultiplier = 2;
        }
        g_nvidiaStatus =
            "DLSS-G hardware detected, but disabled: Vulkan motion-vector and complete resource tags are missing";
    } else {
        g_nvidiaStatus =
            "NVIDIA feature check failed: " +
            featureSupportSummary("DLSS-G", g_dlssgSupportResult) + ", " +
            featureSupportSummary("DLSS-SR", g_dlssSupportResult) + ", " +
            featureSupportSummary("DLSS-RR", g_dlssRrSupportResult) + ", " +
            featureSupportSummary("Reflex", g_reflexSupportResult);
    }
    std::cout << "[DLSS_FG_Native] " << g_nvidiaStatus << std::endl;
    // The Streamline core can still provide DLSS-SR and Reflex even while the
    // frame-generation input contract is not complete.
    const bool anyFeature = g_dlssSupported || g_dlssRrSupported ||
        g_reflexSupported || g_dlssgSupported;
    if (anyFeature) {
        framegen::markProviderReady(g_nvidiaStatus);
    } else {
        framegen::markProviderFailed(g_nvidiaStatus);
    }
    return anyFeature ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetNativeAbiVersion(JNIEnv*, jclass) {
    return DLSS_FG_NATIVE_ABI_VERSION;
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetBackendCapabilities
  (JNIEnv*, jclass, jint vendorId) {
    if (vendorId == 0x1002 || vendorId == 0x8086) {
        return static_cast<jint>(
            framegen::capabilitiesForVendor(static_cast<uint32_t>(vendorId))
        );
    }
    if (vendorId != 0x10DE || !g_initialized) return 0;
    jint capabilities = 0;
    if (g_dlssSupported && g_dlssInputsReady) capabilities |= framegen::kCapUpscaling;
    if (g_dlssgSupported && g_dlssgInputsReady) capabilities |= framegen::kCapFrameGeneration;
    if (g_dlssgSupported && g_dlssgInputsReady && g_maxFrameMultiplier > 2) capabilities |= framegen::kCapMultiFrameGeneration;
    if (g_reflexSupported) capabilities |= framegen::kCapLowLatency;
    if (g_dlssRrSupported && g_dlssRrInputsReady) {
        capabilities |= framegen::kCapRayReconstruction;
    }
    return capabilities;
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetBackendSupportedCapabilities
  (JNIEnv*, jclass, jint vendorId) {
    if (vendorId == 0x1002 || vendorId == 0x8086) {
        return static_cast<jint>(
            framegen::capabilitiesForVendor(static_cast<uint32_t>(vendorId))
        );
    }
    if (vendorId != 0x10DE || !g_initialized) return 0;
    jint capabilities = 0;
    if (g_dlssSupported) {
        capabilities |= framegen::kCapUpscaling;
    }
    if (g_dlssgSupported) {
        capabilities |= framegen::kCapFrameGeneration;
    }
    if (g_dlssgSupported && g_maxFrameMultiplier > 2) {
        capabilities |= framegen::kCapMultiFrameGeneration;
    }
    if (g_reflexSupported) {
        capabilities |= framegen::kCapLowLatency;
    }
    if (g_dlssRrSupported) {
        capabilities |= framegen::kCapRayReconstruction;
    }
    return capabilities;
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetMaxFrameMultiplier
  (JNIEnv*, jclass, jint vendorId) {
    if (vendorId == 0x10DE) return static_cast<jint>(g_maxFrameMultiplier);
    return static_cast<jint>(
        framegen::maxFrameMultiplierForVendor(static_cast<uint32_t>(vendorId))
    );
}

extern "C" JNIEXPORT jstring JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetBackendStatus
  (JNIEnv* env, jclass, jint vendorId) {
    std::string status;
    {
        std::lock_guard lock(g_streamlineMutex);
        if (vendorId == 0x10DE) {
            status = g_nvidiaStatus;
        } else if (vendorId == 0x1002 || vendorId == 0x8086) {
            status = framegen::statusForVendor(static_cast<uint32_t>(vendorId));
        } else {
            status = "Unknown GPU vendor";
        }
    }
    jstring result = env->NewStringUTF(status.c_str());
    if (result == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nShutdown
  (JNIEnv* env, jclass clazz) {
    std::lock_guard lock(g_streamlineMutex);
    dxgipresenter::shutdown();
    framegen::shutdown();
    if (!g_coreInitialized) return;

    slShutdown();
    g_currentFrameToken = nullptr;
    g_frameTokenRefreshedForCurrentFrame = false;
    g_dlssgSupported = false;
    g_dlssgInputsReady = false;
    g_dlssgEnabled = false;
    g_dlssgRuntimeReady = false;
    g_dlssSupported = false;
    g_dlssInputsReady = false;
    g_dlssRrSupported = false;
    g_dlssRrInputsReady = false;
    g_dlssRrModeActive = false;
    g_reflexSupported = false;
    g_dlssgSupportResult = sl::Result::eErrorNotInitialized;
    g_dlssSupportResult = sl::Result::eErrorNotInitialized;
    g_dlssRrSupportResult = sl::Result::eErrorNotInitialized;
    g_reflexSupportResult = sl::Result::eErrorNotInitialized;
    g_streamlineDevice = VK_NULL_HANDLE;
    g_requiredInstanceExtensions.clear();
    g_requiredDeviceExtensions.clear();
    g_requiredFeatures12.clear();
    g_requiredFeatures13.clear();
    g_coreInitialized = false;
    g_initialized = false;
    std::cout << "[DLSS_FG_Native] NVIDIA Streamline hook shutdown." << std::endl;
}

extern "C" JNIEXPORT void JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nRecreateSwapchain
  (JNIEnv* env, jclass clazz, jlong swapchain, jint width, jint height) {
    if (!g_initialized && framegen::activeProvider() ==
            framegen::ProviderId::NvidiaStreamlineVulkan) {
        return;
    }

    // The caller disables FG before reconfiguration and restores the selected
    // mode after the new swapchain is live. Do not toggle it here: doing so
    // after recreation is the inverse of Streamline's required lifecycle.
    framegen::markProviderReady(
        "Provider swapchain resources were rebuilt"
    );
}

extern "C" JNIEXPORT jboolean JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nSubmitFrameGenerationInputs
  (JNIEnv* env, jclass, jint vendorId, jlong commandBuffer,
   jlong backbufferImage, jint backbufferFormat,
   jlong colorImage, jlong colorMemory, jlong colorView, jint colorFormat,
   jlong depthImage, jlong depthMemory, jlong depthView, jint depthFormat,
   jlong motionImage, jlong motionMemory, jlong motionView, jint motionFormat,
   jint colorWidth, jint colorHeight,
   jint guideWidth, jint guideHeight,
   jfloatArray viewToClip, jfloatArray clipToView,
   jfloatArray clipToPreviousClip, jfloatArray previousClipToClip,
   jfloat frameTimeMs,
   jfloat cameraNear, jfloat cameraFar, jfloat verticalFov, jfloat aspect,
   jfloat jitterX, jfloat jitterY,
   jfloat cameraX, jfloat cameraY, jfloat cameraZ,
   jfloat cameraUpX, jfloat cameraUpY, jfloat cameraUpZ,
   jfloat cameraRightX, jfloat cameraRightY, jfloat cameraRightZ,
   jfloat cameraForwardX, jfloat cameraForwardY, jfloat cameraForwardZ,
   jboolean reset, jboolean enabled, jint multiplier,
   jboolean uiRecomposition) {
    if (vendorId == 0x1002 || vendorId == 0x8086) {
        fidelityfx::FrameGenerationInputs inputs{};
        inputs.commandBuffer = reinterpret_cast<VkCommandBuffer>(commandBuffer);
        inputs.backbuffer = reinterpret_cast<VkImage>(backbufferImage);
        inputs.backbufferFormat = static_cast<VkFormat>(backbufferFormat);
        inputs.hudlessColor = reinterpret_cast<VkImage>(colorImage);
        inputs.hudlessColorMemory =
            reinterpret_cast<VkDeviceMemory>(colorMemory);
        inputs.hudlessColorView = reinterpret_cast<VkImageView>(colorView);
        inputs.hudlessColorFormat = static_cast<VkFormat>(colorFormat);
        inputs.depth = reinterpret_cast<VkImage>(depthImage);
        inputs.depthMemory = reinterpret_cast<VkDeviceMemory>(depthMemory);
        inputs.depthView = reinterpret_cast<VkImageView>(depthView);
        inputs.depthFormat = static_cast<VkFormat>(depthFormat);
        inputs.motionVectors = reinterpret_cast<VkImage>(motionImage);
        inputs.motionVectorsMemory =
            reinterpret_cast<VkDeviceMemory>(motionMemory);
        inputs.motionVectorsView = reinterpret_cast<VkImageView>(motionView);
        inputs.motionVectorsFormat = static_cast<VkFormat>(motionFormat);
        inputs.renderWidth = static_cast<uint32_t>(guideWidth);
        inputs.renderHeight = static_cast<uint32_t>(guideHeight);
        inputs.displayWidth = static_cast<uint32_t>(colorWidth);
        inputs.displayHeight = static_cast<uint32_t>(colorHeight);
        inputs.frameTimeMs = frameTimeMs;
        inputs.cameraNear = cameraNear;
        inputs.cameraFar = cameraFar;
        inputs.verticalFov = verticalFov;
        inputs.jitterX = jitterX;
        inputs.jitterY = jitterY;
        inputs.cameraPosition[0] = cameraX;
        inputs.cameraPosition[1] = cameraY;
        inputs.cameraPosition[2] = cameraZ;
        inputs.cameraUp[0] = cameraUpX;
        inputs.cameraUp[1] = cameraUpY;
        inputs.cameraUp[2] = cameraUpZ;
        inputs.cameraRight[0] = cameraRightX;
        inputs.cameraRight[1] = cameraRightY;
        inputs.cameraRight[2] = cameraRightZ;
        inputs.cameraForward[0] = cameraForwardX;
        inputs.cameraForward[1] = cameraForwardY;
        inputs.cameraForward[2] = cameraForwardZ;
        inputs.reset = reset == JNI_TRUE;
        inputs.enabled = enabled == JNI_TRUE;
        return framegen::submitFrameGenerationInputs(
            static_cast<uint32_t>(vendorId), inputs
        ) ? JNI_TRUE : JNI_FALSE;
    }

    if (vendorId != 0x10DE || !g_initialized || !g_dlssgSupported) {
        return JNI_FALSE;
    }
    // B11: copy every JNI input to stack locals BEFORE taking the global
    // lock. Array-region copies run per frame and must not execute under it.
    const jsize v2cLen = viewToClip ? env->GetArrayLength(viewToClip) : 0;
    const jsize c2vLen = clipToView ? env->GetArrayLength(clipToView) : 0;
    const jsize c2pLen = clipToPreviousClip ? env->GetArrayLength(clipToPreviousClip) : 0;
    const jsize p2cLen = previousClipToClip ? env->GetArrayLength(previousClipToClip) : 0;
    jfloat v2c[16] = {};
    jfloat c2v[16] = {};
    jfloat c2p[16] = {};
    jfloat p2c[16] = {};
    bool matricesCopied = v2cLen >= 16 && c2vLen >= 16 && c2pLen >= 16 && p2cLen >= 16;
    if (matricesCopied) {
        env->GetFloatArrayRegion(viewToClip, 0, 16, v2c);
        env->GetFloatArrayRegion(clipToView, 0, 16, c2v);
        env->GetFloatArrayRegion(clipToPreviousClip, 0, 16, c2p);
        env->GetFloatArrayRegion(previousClipToClip, 0, 16, p2c);
        matricesCopied = !env->ExceptionCheck();
        if (!matricesCopied) {
            env->ExceptionClear();
        }
    }
    std::lock_guard lock(g_streamlineMutex);
    g_dlssgEnabled = enabled == JNI_TRUE;
    g_dlssgRuntimeReady = false;
    if (commandBuffer == 0 || backbufferImage == 0 || colorImage == 0 ||
        colorMemory == 0 || colorView == 0 || depthImage == 0 ||
        depthMemory == 0 || depthView == 0 || motionImage == 0 ||
        motionMemory == 0 || motionView == 0 || backbufferFormat == 0 ||
        colorFormat == 0 || depthFormat == 0 || motionFormat == 0 ||
        colorWidth <= 0 || colorHeight <= 0 ||
        guideWidth <= 0 || guideHeight <= 0 || viewToClip == nullptr ||
        clipToView == nullptr || clipToPreviousClip == nullptr ||
        previousClipToClip == nullptr || !matricesCopied) {
        g_dlssgInputsReady = false;
        g_nvidiaStatus =
            "DLSS-G is waiting for complete Vulkan color, depth and motion-vector resources";
        return JNI_FALSE;
    }

    sl::Constants constants{};
    for (int row = 0; row < 4; ++row) {
        constants.cameraViewToClip[row] = {
            v2c[row], v2c[4 + row], v2c[8 + row], v2c[12 + row]
        };
        constants.clipToCameraView[row] = {
            c2v[row], c2v[4 + row], c2v[8 + row], c2v[12 + row]
        };
        constants.clipToPrevClip[row] = {
            c2p[row], c2p[4 + row], c2p[8 + row], c2p[12 + row]
        };
        constants.prevClipToClip[row] = {
            p2c[row], p2c[4 + row], p2c[8 + row], p2c[12 + row]
        };
    }
    constants.jitterOffset = sl::float2(jitterX, jitterY);
    // The renderer-owned motion texture stores velocity in display pixels.
    // Streamline requires the scale that normalizes those values to [-1, 1].
    constants.mvecScale = sl::float2(
        1.0f / static_cast<float>(guideWidth),
        1.0f / static_cast<float>(guideHeight)
    );
    constants.cameraPos = sl::float3(cameraX, cameraY, cameraZ);
    constants.cameraUp = sl::float3(cameraUpX, cameraUpY, cameraUpZ);
    constants.cameraRight =
        sl::float3(cameraRightX, cameraRightY, cameraRightZ);
    constants.cameraFwd =
        sl::float3(cameraForwardX, cameraForwardY, cameraForwardZ);
    constants.cameraNear = cameraNear;
    constants.cameraFar = cameraFar;
    constants.cameraFOV = verticalFov;
    constants.cameraAspectRatio = aspect;
    constants.depthInverted = sl::Boolean::eTrue;
    constants.cameraMotionIncluded = sl::Boolean::eTrue;
    constants.motionVectors3D = sl::Boolean::eFalse;
    constants.motionVectorsDilated = sl::Boolean::eFalse;
    // The compute shader removes the current/previous jitter delta from the
    // dense pixel-space motion field before it reaches Streamline.
    constants.motionVectorsJittered = sl::Boolean::eFalse;
    constants.reset = reset == JNI_TRUE
        ? sl::Boolean::eTrue
        : sl::Boolean::eFalse;

    if (!g_frameTokenRefreshedForCurrentFrame || g_currentFrameToken == nullptr) {
        if (slGetNewFrameToken(g_currentFrameToken, &g_frameIndex) !=
                sl::Result::eOk ||
            g_currentFrameToken == nullptr) {
            g_nvidiaStatus = "DLSS-G could not acquire a Streamline frame token";
            g_dlssgInputsReady = false;
            return JNI_FALSE;
        }
        ++g_frameIndex;
        g_frameTokenRefreshedForCurrentFrame = true;
    }

    const sl::Result constantsResult =
        slSetConstants(constants, *g_currentFrameToken, g_viewport);
    if (constantsResult != sl::Result::eOk) {
        g_nvidiaStatus = "DLSS-G constants were rejected (" +
            std::to_string(static_cast<int>(constantsResult)) + ")";
        g_dlssgInputsReady = false;
        return JNI_FALSE;
    }

    sl::Resource color(
        sl::ResourceType::eTex2d,
        reinterpret_cast<void*>(colorImage),
        reinterpret_cast<void*>(colorMemory),
        reinterpret_cast<void*>(colorView),
        static_cast<uint32_t>(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
    );
    sl::Resource depth(
        sl::ResourceType::eTex2d,
        reinterpret_cast<void*>(depthImage),
        reinterpret_cast<void*>(depthMemory),
        reinterpret_cast<void*>(depthView),
        static_cast<uint32_t>(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
    );
    sl::Resource motion(
        sl::ResourceType::eTex2d,
        reinterpret_cast<void*>(motionImage),
        reinterpret_cast<void*>(motionMemory),
        reinterpret_cast<void*>(motionView),
        static_cast<uint32_t>(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
    );
    describeVulkanTexture(
        color, colorWidth, colorHeight, colorFormat,
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
    );
    describeVulkanTexture(
        depth, guideWidth, guideHeight, depthFormat,
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
    );
    describeVulkanTexture(
        motion, guideWidth, guideHeight, motionFormat,
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
            VK_IMAGE_USAGE_STORAGE_BIT
    );

    const sl::Extent colorExtent{
        0, 0, static_cast<uint32_t>(colorWidth),
        static_cast<uint32_t>(colorHeight)
    };
    const sl::Extent guideExtent{
        0, 0, static_cast<uint32_t>(guideWidth),
        static_cast<uint32_t>(guideHeight)
    };
    // Streamline intercepts the full swapchain backbuffer automatically. The
    // DLSS-G guide says to tag it only for a sub-rectangle; supplying an
    // incomplete Vulkan backbuffer description here can make the tag conflict
    // with the interposer-owned image.
    sl::ResourceTag tags[] = {
        { &color, sl::kBufferTypeHUDLessColor,
            sl::ResourceLifecycle::eValidUntilPresent, &colorExtent },
        { &depth, sl::kBufferTypeDepth,
            sl::ResourceLifecycle::eValidUntilPresent, &guideExtent },
        { &motion, sl::kBufferTypeMotionVectors,
            sl::ResourceLifecycle::eValidUntilPresent, &guideExtent }
    };
    const sl::Result tagResult = slSetTagForFrame(
        *g_currentFrameToken,
        g_viewport,
        tags,
        static_cast<uint32_t>(std::size(tags)),
        reinterpret_cast<sl::CommandBuffer*>(commandBuffer)
    );
    if (tagResult != sl::Result::eOk) {
        g_nvidiaStatus = "DLSS-G Vulkan resource tagging failed (" +
            std::to_string(static_cast<int>(tagResult)) + ")";
        g_dlssgInputsReady = false;
        return JNI_FALSE;
    }

    g_dlssgInputsReady = true;
    if (uiRecomposition == JNI_TRUE) {
        g_nvidiaStatus =
            "DLSS-G frame resources valid; waiting for the full-resolution UI layer";
        return JNI_TRUE;
    }
    return finalizeDlssgFrame(
        g_dlssgEnabled, multiplier, false
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nSubmitFrameGenerationUi
  (JNIEnv*, jclass, jint vendorId, jlong commandBuffer,
   jlong uiImage, jlong uiMemory, jlong uiView, jint uiFormat,
   jint width, jint height, jboolean enabled, jint multiplier,
   jboolean uiRecomposition) {
    if (vendorId != 0x10DE) return JNI_FALSE;
    std::lock_guard lock(g_streamlineMutex);
    if (!g_initialized || !g_dlssgSupported ||
        !g_dlssgInputsReady || commandBuffer == 0 || uiImage == 0 ||
        uiMemory == 0 || uiView == 0 || uiFormat == 0 ||
        width <= 0 || height <= 0 || g_currentFrameToken == nullptr) {
        g_nvidiaStatus =
            "DLSS-G UI recomposition is waiting for a complete UI resource";
        return JNI_FALSE;
    }

    sl::Resource ui(
        sl::ResourceType::eTex2d,
        reinterpret_cast<void*>(uiImage),
        reinterpret_cast<void*>(uiMemory),
        reinterpret_cast<void*>(uiView),
        static_cast<uint32_t>(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
    );
    describeVulkanTexture(
        ui,
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height),
        static_cast<uint32_t>(uiFormat),
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
    );
    const sl::Extent extent{
        0, 0, static_cast<uint32_t>(width), static_cast<uint32_t>(height)
    };
    sl::ResourceTag uiTag{
        &ui,
        sl::kBufferTypeUIColorAndAlpha,
        sl::ResourceLifecycle::eValidUntilPresent,
        &extent
    };
    const sl::Result tagResult = slSetTagForFrame(
        *g_currentFrameToken,
        g_viewport,
        &uiTag,
        1,
        reinterpret_cast<sl::CommandBuffer*>(commandBuffer)
    );
    if (tagResult != sl::Result::eOk) {
        g_nvidiaStatus = "DLSS-G UI resource tagging failed (" +
            std::to_string(static_cast<int>(tagResult)) + ")";
        return JNI_FALSE;
    }

    return finalizeDlssgFrame(
        enabled == JNI_TRUE,
        multiplier,
        uiRecomposition == JNI_TRUE
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nIsActive
  (JNIEnv* env, jclass clazz) {
    std::lock_guard lock(g_streamlineMutex);
    return (g_initialized && g_dlssgSupported && g_dlssgInputsReady &&
            g_dlssgEnabled && g_dlssgRuntimeReady) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nSetReflexMode
  (JNIEnv* env, jclass clazz, jint mode, jint targetFps) {
    if (!g_initialized || !g_reflexSupported) return;

    sl::ReflexOptions reflexOptions{};
    reflexOptions.mode = static_cast<sl::ReflexMode>(mode); // 0 = Off, 1 = LowLatency, 2 = LowLatencyWithBoost
    reflexOptions.frameLimitUs = targetFps > 0
        ? static_cast<uint32_t>(1'000'000 / std::clamp(static_cast<int>(targetFps), 30, 1000))
        : 0u;
    
    sl::Result res = slReflexSetOptions(reflexOptions);
    if (res != sl::Result::eOk) {
        std::cerr << "[DLSS_FG_Native] Failed to update Reflex Options mode: " << (int)res << std::endl;
    }
}

extern "C" JNIEXPORT void JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nReflexMarker
  (JNIEnv* env, jclass clazz, jint markerType) {
    std::lock_guard lock(g_streamlineMutex);
    if (!g_initialized || !g_reflexSupported || markerType < 0 || markerType > 5) return;

    if (markerType == 0 || g_currentFrameToken == nullptr) {
        if (slGetNewFrameToken(g_currentFrameToken, &g_frameIndex) != sl::Result::eOk) {
            g_currentFrameToken = nullptr;
            g_frameTokenRefreshedForCurrentFrame = false;
            return;
        }
        ++g_frameIndex;
        g_frameTokenRefreshedForCurrentFrame = true;
        // The Reflex sleep call replaces host-side Thread.sleep pacing. It
        // uses NVIDIA's render-queue timing and belongs immediately before
        // the simulation-start marker for the same frame token.
        slReflexSleep(*g_currentFrameToken);
    }
    if (g_currentFrameToken != nullptr) {
        slPCLSetMarker(static_cast<sl::PCLMarker>(markerType), *g_currentFrameToken);
    }
}

extern "C" JNIEXPORT void JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nSetDlssMode
  (JNIEnv* env, jclass clazz, jint mode, jfloat sharpness) {
    g_dlssMode = std::clamp(static_cast<int>(mode), 0, 6);
    (void)sharpness;
    if (!g_initialized || !g_dlssSupported) return;
    // B9: single atomic snapshot — the mode must not change between the
    // branch and the option build below.
    const int selectedDlssMode = g_dlssMode.load();
    if (selectedDlssMode != 0) {
        // Output dimensions are mandatory for an enabled mode and are only
        // authoritative at frame capture time. The per-frame submission
        // applies the complete option set on the next rendered frame.
        g_nvidiaStatus =
            "DLSS-SR mode selected; waiting for the next renderer frame";
        return;
    }

    sl::DLSSOptions dlssOptions{};
    dlssOptions.mode = dlssModeFromIndex(selectedDlssMode);
    useRecommendedDlssPresets(dlssOptions);
    // DLSS sharpness is deprecated by current Streamline. Leave it at the SDK
    // default instead of exposing a control which may have no effect.
    dlssOptions.sharpness = 0.0f;
    
    sl::Result res = slDLSSSetOptions(g_viewport, dlssOptions);
    if (res != sl::Result::eOk) {
        std::cerr << "[DLSS_FG_Native] Failed to update DLSS Options mode: " << (int)res << std::endl;
    }
}

extern "C" JNIEXPORT jintArray JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetOptimalRenderSize
  (JNIEnv* env, jclass, jint mode, jint outputWidth, jint outputHeight,
    jboolean rayReconstruction) {
    if (!g_initialized || outputWidth <= 0 || outputHeight <= 0) {
        jintArray empty = env->NewIntArray(0);
        if (empty == nullptr || env->ExceptionCheck()) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            return nullptr;
        }
        return empty;
    }

    const int selectedMode = std::clamp(static_cast<int>(mode), 0, 6);
    uint32_t renderWidth = static_cast<uint32_t>(outputWidth);
    uint32_t renderHeight = static_cast<uint32_t>(outputHeight);
    sl::Result result = sl::Result::eErrorFeatureNotSupported;

    if (rayReconstruction == JNI_TRUE) {
        if (!g_dlssRrSupported || selectedMode < 2) {
            jintArray empty = env->NewIntArray(0);
            if (empty == nullptr || env->ExceptionCheck()) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                return nullptr;
            }
            return empty;
        }
        sl::DLSSDOptions options{};
        options.mode = dlssModeFromIndex(selectedMode);
        options.outputWidth = static_cast<uint32_t>(outputWidth);
        options.outputHeight = static_cast<uint32_t>(outputHeight);
        options.colorBuffersHDR = sl::Boolean::eTrue;
        sl::DLSSDOptimalSettings settings{};
        result = slDLSSDGetOptimalSettings(options, settings);
        renderWidth = settings.optimalRenderWidth;
        renderHeight = settings.optimalRenderHeight;
    } else {
        if (!g_dlssSupported || selectedMode == 0) {
            jintArray empty = env->NewIntArray(0);
            if (empty == nullptr || env->ExceptionCheck()) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                return nullptr;
            }
            return empty;
        }
        sl::DLSSOptions options{};
        options.mode = dlssModeFromIndex(selectedMode);
        useRecommendedDlssPresets(options);
        options.outputWidth = static_cast<uint32_t>(outputWidth);
        options.outputHeight = static_cast<uint32_t>(outputHeight);
        options.colorBuffersHDR = sl::Boolean::eFalse;
        sl::DLSSOptimalSettings settings{};
        result = slDLSSGetOptimalSettings(options, settings);
        renderWidth = settings.optimalRenderWidth;
        renderHeight = settings.optimalRenderHeight;
    }

    if (result != sl::Result::eOk || renderWidth == 0 || renderHeight == 0) {
        jintArray empty = env->NewIntArray(0);
        if (empty == nullptr || env->ExceptionCheck()) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            return nullptr;
        }
        return empty;
    }
    const jint values[] = {
        static_cast<jint>(renderWidth),
        static_cast<jint>(renderHeight)
    };
    jintArray dimensions = env->NewIntArray(2);
    if (dimensions == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    env->SetIntArrayRegion(dimensions, 0, 2, values);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return dimensions;
}

extern "C" JNIEXPORT void JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nSetFgMode
  (JNIEnv* env, jclass clazz, jboolean enabled, jint multiplier, jboolean uiRecomposition) {
    g_dlssgEnabled = enabled == JNI_TRUE;
    if (!g_dlssgEnabled) {
        g_dlssgRuntimeReady = false;
    }
    if (!g_initialized || !g_dlssgSupported) return;
    if (!g_dlssgInputsReady) {
        g_nvidiaStatus = g_dlssgEnabled
            ? "DLSS-G selected; waiting for the first complete renderer input frame"
            : "DLSS-G is disabled in settings";
        return;
    }

    const sl::Result res = applyDlssgOptions(
        g_dlssgEnabled, multiplier, uiRecomposition == JNI_TRUE
    );
    if (res != sl::Result::eOk) {
        std::cerr << "[DLSS_FG_Native] Failed to update DLSS G mode: " << (int)res << std::endl;
        g_nvidiaStatus = "DLSS-G mode update failed (" +
            std::to_string(static_cast<int>(res)) + ")";
    } else {
        g_nvidiaStatus = g_dlssgEnabled
            ? "DLSS-G enabled; renderer inputs are valid"
            : "DLSS-G inputs valid; Frame Generation is disabled in settings";
    }
}

extern "C" JNIEXPORT jlong JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetDlssgVramUsage
  (JNIEnv* env, jclass clazz) {
    if (!g_initialized || !g_dlssgInputsReady) return 0;
    
    sl::DLSSGState state{};
    sl::Result res = slDLSSGGetState(g_viewport, state, nullptr);
    if (res == sl::Result::eOk) {
        return static_cast<jlong>(state.estimatedVRAMUsageInBytes);
    }
    return 0;
}

// VK_EXT_memory_budget (promoted into the Khronos headers long ago; the local
// fallback only guards against very old fetched Vulkan-Headers revisions).
#ifndef VK_EXT_memory_budget
#define VK_EXT_memory_budget 1
#define VK_EXT_MEMORY_BUDGET_SPEC_VERSION 1
#define VK_EXT_MEMORY_BUDGET_EXTENSION_NAME "VK_EXT_memory_budget"
typedef struct VkPhysicalDeviceMemoryBudgetPropertiesEXT {
    VkStructureType    sType;
    void*              pNext;
    VkDeviceSize       heapBudget[VK_MAX_MEMORY_HEAPS];
    VkDeviceSize       heapUsage[VK_MAX_MEMORY_HEAPS];
} VkPhysicalDeviceMemoryBudgetPropertiesEXT;
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nQueryMemoryBudget(
        JNIEnv* env, jclass, jlong vkPhysicalDeviceHandle,
        jlongArray outBudgetBytes, jlongArray outUsageBytes) {
    if (!outBudgetBytes || !outUsageBytes) return JNI_FALSE;
    if (env->GetArrayLength(outBudgetBytes) < 1 ||
            env->GetArrayLength(outUsageBytes) < 1) {
        return JNI_FALSE;
    }

    // Caller-supplied handle wins; fall back to the handles retained by nInit.
    VkPhysicalDevice physicalDevice =
        reinterpret_cast<VkPhysicalDevice>(vkPhysicalDeviceHandle);
    if (physicalDevice == VK_NULL_HANDLE) {
        physicalDevice = g_vkPhysicalDevice;
    }
    if (physicalDevice == VK_NULL_HANDLE) {
        return JNI_FALSE;
    }

    // VK_EXT_memory_budget: vkGetPhysicalDeviceMemoryProperties2 mit
    // VkPhysicalDeviceMemoryBudgetPropertiesEXT im pNext.
    auto pfnGetPhysicalDeviceMemoryProperties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceMemoryProperties2>(
        vkGetInstanceProcAddr(
            // B9: explicit atomic load — a ternary mixing atomic and plain
            // handles is ambiguous (C2445).
            g_vkInstance != VK_NULL_HANDLE ? g_vkInstance.load() : VkInstance(nullptr),
            "vkGetPhysicalDeviceMemoryProperties2"));
    if (!pfnGetPhysicalDeviceMemoryProperties2) {
        return JNI_FALSE;
    }

    VkPhysicalDeviceMemoryBudgetPropertiesEXT budgetProperties{};
    budgetProperties.sType =
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT;
    VkPhysicalDeviceMemoryProperties2 memoryProperties2{};
    memoryProperties2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_PROPERTIES_2;
    memoryProperties2.pNext = &budgetProperties;
    pfnGetPhysicalDeviceMemoryProperties2(physicalDevice, &memoryProperties2);

    // Report the largest DEVICE_LOCAL heap; fall back to the largest heap
    // overall when the driver marks none as device-local.
    uint32_t selectedHeap = 0;
    VkDeviceSize largestSize = 0;
    bool foundDeviceLocal = false;
    const VkPhysicalDeviceMemoryProperties& properties =
        memoryProperties2.memoryProperties;
    for (uint32_t index = 0; index < properties.memoryHeapCount; ++index) {
        const VkMemoryHeap& heap = properties.memoryHeaps[index];
        const bool deviceLocal =
            (heap.flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0;
        if (foundDeviceLocal && !deviceLocal) continue;
        if (!foundDeviceLocal || heap.size > largestSize) {
            largestSize = heap.size;
            selectedHeap = index;
        }
        foundDeviceLocal = foundDeviceLocal || deviceLocal;
    }

    const jlong budgetBytes =
        static_cast<jlong>(budgetProperties.heapBudget[selectedHeap]);
    const jlong usageBytes =
        static_cast<jlong>(budgetProperties.heapUsage[selectedHeap]);
    env->SetLongArrayRegion(outBudgetBytes, 0, 1, &budgetBytes);
    env->SetLongArrayRegion(outUsageBytes, 0, 1, &usageBytes);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
    return budgetBytes > 0 ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// Vendor-generic entry points stay ABI-compatible with older Java builds.
// They must never route AMD or Intel requests into NVIDIA Streamline.
// ============================================================

extern "C" JNIEXPORT void JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nSetUpscalingMode
  (JNIEnv* env, jclass clazz, jint vendorId, jint mode, jfloat sharpness) {
    if (vendorId == 0x10DE) {
        Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nSetDlssMode(env, clazz, mode, sharpness);
    } else if (vendorId == 0x1002 || vendorId == 0x8086) {
        framegen::setUpscalingMode(
            static_cast<uint32_t>(vendorId),
            static_cast<int>(mode),
            static_cast<float>(sharpness)
        );
    }
}

extern "C" JNIEXPORT jboolean JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nSubmitUpscalingInputs
  (JNIEnv* env, jclass, jint vendorId, jlong commandBuffer,
   jlong colorImage, jlong colorMemory, jlong colorView, jint colorFormat,
   jlong depthImage, jlong depthMemory, jlong depthView, jint depthFormat,
   jlong motionImage, jlong motionMemory, jlong motionView, jint motionFormat,
   jlong outputImage, jlong outputMemory, jlong outputView, jint outputFormat,
   jint renderWidth, jint renderHeight,
   jint outputWidth, jint outputHeight,
   jfloatArray viewToClip, jfloatArray clipToView,
   jfloatArray clipToPreviousClip, jfloatArray previousClipToClip,
   jfloat frameTimeMs, jfloat cameraNear, jfloat cameraFar, jfloat verticalFov,
   jfloat aspect, jfloat jitterX, jfloat jitterY,
   jfloat cameraX, jfloat cameraY, jfloat cameraZ,
   jfloat cameraUpX, jfloat cameraUpY, jfloat cameraUpZ,
   jfloat cameraRightX, jfloat cameraRightY, jfloat cameraRightZ,
   jfloat cameraForwardX, jfloat cameraForwardY, jfloat cameraForwardZ,
   jboolean reset) {
    if (vendorId == 0x10DE) {
        if (!g_initialized || !g_dlssSupported) {
            return JNI_FALSE;
        }
        // B11: JNI array copies before the global lock (see
        // nSubmitFrameGenerationInputs for the rationale).
        const jsize v2cLen = viewToClip ? env->GetArrayLength(viewToClip) : 0;
        const jsize c2vLen = clipToView ? env->GetArrayLength(clipToView) : 0;
        const jsize c2pLen = clipToPreviousClip ? env->GetArrayLength(clipToPreviousClip) : 0;
        const jsize p2cLen = previousClipToClip ? env->GetArrayLength(previousClipToClip) : 0;
        jfloat v2c[16] = {};
        jfloat c2v[16] = {};
        jfloat c2p[16] = {};
        jfloat p2c[16] = {};
        bool matricesCopied = v2cLen >= 16 && c2vLen >= 16 && c2pLen >= 16 && p2cLen >= 16;
        if (matricesCopied) {
            env->GetFloatArrayRegion(viewToClip, 0, 16, v2c);
            env->GetFloatArrayRegion(clipToView, 0, 16, c2v);
            env->GetFloatArrayRegion(clipToPreviousClip, 0, 16, c2p);
            env->GetFloatArrayRegion(previousClipToClip, 0, 16, p2c);
            matricesCopied = !env->ExceptionCheck();
            if (!matricesCopied) {
                env->ExceptionClear();
            }
        }
        std::lock_guard lock(g_streamlineMutex);
        if (commandBuffer == 0 || colorImage == 0 || colorMemory == 0 ||
            colorView == 0 || depthImage == 0 || depthMemory == 0 ||
            depthView == 0 || motionImage == 0 || motionMemory == 0 ||
            motionView == 0 || outputImage == 0 || outputMemory == 0 ||
            outputView == 0 || colorFormat == 0 || depthFormat == 0 ||
            motionFormat == 0 || outputFormat == 0 || renderWidth <= 0 ||
            renderHeight <= 0 || outputWidth <= 0 || outputHeight <= 0 ||
            viewToClip == nullptr || clipToView == nullptr ||
            clipToPreviousClip == nullptr || previousClipToClip == nullptr ||
            !matricesCopied) {
            g_dlssInputsReady = false;
            g_nvidiaStatus =
                "DLSS-SR is waiting for complete Vulkan color, depth, motion-vector and output resources";
            return JNI_FALSE;
        }

        if (!g_frameTokenRefreshedForCurrentFrame || g_currentFrameToken == nullptr) {
            if (slGetNewFrameToken(g_currentFrameToken, &g_frameIndex) !=
                    sl::Result::eOk ||
                g_currentFrameToken == nullptr) {
                g_dlssInputsReady = false;
                g_nvidiaStatus =
                    "DLSS-SR could not acquire a Streamline frame token";
                return JNI_FALSE;
            }
            ++g_frameIndex;
            g_frameTokenRefreshedForCurrentFrame = true;
        }

        sl::Constants constants{};
        for (int row = 0; row < 4; ++row) {
            constants.cameraViewToClip[row] = {
                v2c[row], v2c[4 + row], v2c[8 + row], v2c[12 + row]
            };
            constants.clipToCameraView[row] = {
                c2v[row], c2v[4 + row], c2v[8 + row], c2v[12 + row]
            };
            constants.clipToPrevClip[row] = {
                c2p[row], c2p[4 + row], c2p[8 + row], c2p[12 + row]
            };
            constants.prevClipToClip[row] = {
                p2c[row], p2c[4 + row], p2c[8 + row], p2c[12 + row]
            };
        }
        constants.jitterOffset = sl::float2(jitterX, jitterY);
        constants.mvecScale = sl::float2(
            1.0f / static_cast<float>(renderWidth),
            1.0f / static_cast<float>(renderHeight)
        );
        constants.cameraPos = sl::float3(cameraX, cameraY, cameraZ);
        constants.cameraUp = sl::float3(cameraUpX, cameraUpY, cameraUpZ);
        constants.cameraRight =
            sl::float3(cameraRightX, cameraRightY, cameraRightZ);
        constants.cameraFwd =
            sl::float3(cameraForwardX, cameraForwardY, cameraForwardZ);
        constants.cameraNear = cameraNear;
        constants.cameraFar = cameraFar;
        constants.cameraFOV = verticalFov;
        constants.cameraAspectRatio = aspect;
        constants.depthInverted = sl::Boolean::eTrue;
        constants.cameraMotionIncluded = sl::Boolean::eTrue;
        constants.motionVectors3D = sl::Boolean::eFalse;
        constants.motionVectorsDilated = sl::Boolean::eFalse;
        // Jitter is supplied separately through jitterOffset; the renderer's
        // dense motion-vector image has already had the jitter delta removed.
        constants.motionVectorsJittered = sl::Boolean::eFalse;
        constants.reset =
            reset == JNI_TRUE ? sl::Boolean::eTrue : sl::Boolean::eFalse;

        const sl::Result constantsResult =
            slSetConstants(constants, *g_currentFrameToken, g_viewport);
        if (constantsResult != sl::Result::eOk) {
            g_dlssInputsReady = false;
            g_nvidiaStatus = "DLSS-SR constants were rejected (" +
                std::to_string(static_cast<int>(constantsResult)) + ")";
            return JNI_FALSE;
        }

        sl::DLSSOptions options{};
        options.mode = dlssModeFromIndex(g_dlssMode);
        useRecommendedDlssPresets(options);
        options.outputWidth = static_cast<uint32_t>(outputWidth);
        options.outputHeight = static_cast<uint32_t>(outputHeight);
        options.sharpness = 0.0f;
        options.colorBuffersHDR =
            colorFormat == VK_FORMAT_R16G16B16A16_SFLOAT
                ? sl::Boolean::eTrue
                : sl::Boolean::eFalse;
        if (g_dlssRrSupported && g_dlssRrModeActive) {
            sl::DLSSDOptions rrOff{};
            rrOff.mode = sl::DLSSMode::eOff;
            const sl::Result rrOffResult =
                slDLSSDSetOptions(g_viewport, rrOff);
            if (rrOffResult != sl::Result::eOk) {
                g_nvidiaStatus =
                    "DLSS-RR could not be disabled before DLSS-SR (" +
                    std::to_string(static_cast<int>(rrOffResult)) + ")";
                return JNI_FALSE;
            }
            g_dlssRrModeActive = false;
            g_dlssRrInputsReady = false;
        }
        const sl::Result optionsResult =
            slDLSSSetOptions(g_viewport, options);
        if (optionsResult != sl::Result::eOk) {
            g_dlssInputsReady = false;
            g_nvidiaStatus = "DLSS-SR options were rejected (" +
                std::to_string(static_cast<int>(optionsResult)) + ")";
            return JNI_FALSE;
        }

        sl::Resource color(
            sl::ResourceType::eTex2d,
            reinterpret_cast<void*>(colorImage),
            reinterpret_cast<void*>(colorMemory),
            reinterpret_cast<void*>(colorView),
            static_cast<uint32_t>(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
        );
        sl::Resource depth(
            sl::ResourceType::eTex2d,
            reinterpret_cast<void*>(depthImage),
            reinterpret_cast<void*>(depthMemory),
            reinterpret_cast<void*>(depthView),
            static_cast<uint32_t>(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
        );
        sl::Resource motion(
            sl::ResourceType::eTex2d,
            reinterpret_cast<void*>(motionImage),
            reinterpret_cast<void*>(motionMemory),
            reinterpret_cast<void*>(motionView),
            static_cast<uint32_t>(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
        );
        sl::Resource output(
            sl::ResourceType::eTex2d,
            reinterpret_cast<void*>(outputImage),
            reinterpret_cast<void*>(outputMemory),
            reinterpret_cast<void*>(outputView),
            static_cast<uint32_t>(VK_IMAGE_LAYOUT_GENERAL)
        );
        describeVulkanTexture(
            color, renderWidth, renderHeight, colorFormat,
            VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
        );
        describeVulkanTexture(
            depth, renderWidth, renderHeight, depthFormat,
            VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
        );
        describeVulkanTexture(
            motion, renderWidth, renderHeight, motionFormat,
            VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT
        );
        describeVulkanTexture(
            output, outputWidth, outputHeight, outputFormat,
            VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                VK_IMAGE_USAGE_STORAGE_BIT
        );

        const sl::Extent renderExtent{
            0, 0, static_cast<uint32_t>(renderWidth),
            static_cast<uint32_t>(renderHeight)
        };
        const sl::Extent outputExtent{
            0, 0, static_cast<uint32_t>(outputWidth),
            static_cast<uint32_t>(outputHeight)
        };
        sl::ResourceTag colorTag{
            &color, sl::kBufferTypeScalingInputColor,
            sl::ResourceLifecycle::eValidUntilEvaluate, &renderExtent
        };
        sl::ResourceTag depthTag{
            &depth, sl::kBufferTypeDepth,
            sl::ResourceLifecycle::eValidUntilEvaluate, &renderExtent
        };
        sl::ResourceTag motionTag{
            &motion, sl::kBufferTypeMotionVectors,
            sl::ResourceLifecycle::eValidUntilEvaluate, &renderExtent
        };
        sl::ResourceTag outputTag{
            &output, sl::kBufferTypeScalingOutputColor,
            sl::ResourceLifecycle::eValidUntilEvaluate, &outputExtent
        };

        g_dlssInputsReady = true;
        if (g_dlssMode == 0) {
            g_nvidiaStatus = "DLSS-SR inputs valid; Super Resolution is disabled in settings";
            return JNI_FALSE;
        }

        const sl::BaseStructure* inputs[] = {
            &g_viewport, &colorTag, &depthTag, &motionTag, &outputTag
        };
        const sl::Result evaluateResult = slEvaluateFeature(
            sl::kFeatureDLSS,
            *g_currentFrameToken,
            inputs,
            static_cast<uint32_t>(std::size(inputs)),
            reinterpret_cast<sl::CommandBuffer*>(commandBuffer)
        );
        if (evaluateResult != sl::Result::eOk) {
            g_dlssInputsReady = false;
            g_nvidiaStatus = "DLSS-SR evaluation failed (" +
                std::to_string(static_cast<int>(evaluateResult)) + ")";
            return JNI_FALSE;
        }

        g_nvidiaStatus =
            "DLSS-SR active with renderer-owned Vulkan color, depth, motion-vector and output resources";
        return JNI_TRUE;
    }

    fidelityfx::UpscalingInputs inputs{};
    inputs.commandBuffer = reinterpret_cast<VkCommandBuffer>(commandBuffer);
    inputs.color = reinterpret_cast<VkImage>(colorImage);
    inputs.colorFormat = static_cast<VkFormat>(colorFormat);
    inputs.depth = reinterpret_cast<VkImage>(depthImage);
    inputs.depthFormat = static_cast<VkFormat>(depthFormat);
    inputs.motionVectors = reinterpret_cast<VkImage>(motionImage);
    inputs.motionVectorsFormat = static_cast<VkFormat>(motionFormat);
    inputs.output = reinterpret_cast<VkImage>(outputImage);
    inputs.outputFormat = static_cast<VkFormat>(outputFormat);
    inputs.renderWidth = static_cast<uint32_t>(std::max(0, renderWidth));
    inputs.renderHeight = static_cast<uint32_t>(std::max(0, renderHeight));
    inputs.outputWidth = static_cast<uint32_t>(std::max(0, outputWidth));
    inputs.outputHeight = static_cast<uint32_t>(std::max(0, outputHeight));
    inputs.frameTimeMs = frameTimeMs;
    inputs.cameraNear = cameraNear;
    inputs.cameraFar = cameraFar;
    inputs.verticalFov = verticalFov;
    inputs.jitterX = jitterX;
    inputs.jitterY = jitterY;
    inputs.reset = reset == JNI_TRUE;
    return framegen::submitUpscalingInputs(
        static_cast<uint32_t>(vendorId), inputs
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nSubmitRayReconstructionInputs
  (JNIEnv* env, jclass, jlong commandBuffer,
   jlong noisyImage, jlong noisyMemory, jlong noisyView, jint noisyFormat,
   jlong albedoImage, jlong albedoMemory, jlong albedoView, jint albedoFormat,
   jlong specularImage, jlong specularMemory, jlong specularView,
   jint specularFormat,
   jlong normalRoughnessImage, jlong normalRoughnessMemory,
   jlong normalRoughnessView, jint normalRoughnessFormat,
   jlong specularHitDistanceImage, jlong specularHitDistanceMemory,
   jlong specularHitDistanceView, jint specularHitDistanceFormat,
   jlong depthImage, jlong depthMemory, jlong depthView, jint depthFormat,
   jlong motionImage, jlong motionMemory, jlong motionView, jint motionFormat,
   jlong outputImage, jlong outputMemory, jlong outputView, jint outputFormat,
   jint renderWidth, jint renderHeight, jint outputWidth, jint outputHeight,
   jint dlssMode, jfloatArray viewToClip, jfloatArray clipToView,
   jfloatArray clipToPreviousClip, jfloatArray previousClipToClip,
   jfloatArray worldToView, jfloatArray viewToWorld,
   jfloat frameTimeMs, jfloat cameraNear, jfloat cameraFar,
   jfloat verticalFov, jfloat aspect, jfloat jitterX, jfloat jitterY,
   jfloat cameraX, jfloat cameraY, jfloat cameraZ,
   jfloat cameraUpX, jfloat cameraUpY, jfloat cameraUpZ,
   jfloat cameraRightX, jfloat cameraRightY, jfloat cameraRightZ,
   jfloat cameraForwardX, jfloat cameraForwardY, jfloat cameraForwardZ,
   jboolean reset) {
    const bool handlesValid =
        commandBuffer != 0 && noisyImage != 0 && noisyMemory != 0 &&
        noisyView != 0 && albedoImage != 0 && albedoMemory != 0 &&
        albedoView != 0 && specularImage != 0 && specularMemory != 0 &&
        specularView != 0 && normalRoughnessImage != 0 &&
        normalRoughnessMemory != 0 && normalRoughnessView != 0 &&
        specularHitDistanceImage != 0 &&
        specularHitDistanceMemory != 0 &&
        specularHitDistanceView != 0 && depthImage != 0 &&
        depthMemory != 0 && depthView != 0 && motionImage != 0 &&
        motionMemory != 0 && motionView != 0 && outputImage != 0 &&
        outputMemory != 0 && outputView != 0;
    const bool formatsValid =
        noisyFormat != 0 && albedoFormat != 0 && specularFormat != 0 &&
        normalRoughnessFormat != 0 && specularHitDistanceFormat != 0 &&
        depthFormat != 0 && motionFormat != 0 && outputFormat != 0;
    // B11: JNI array copies before the global lock (same pattern as the
    // DLSS-G/SR submits above). arraysValid also covers the copy outcome.
    const bool arraysPresent =
        viewToClip != nullptr && clipToView != nullptr &&
        clipToPreviousClip != nullptr && previousClipToClip != nullptr &&
        worldToView != nullptr && viewToWorld != nullptr &&
        env->GetArrayLength(viewToClip) >= 16 &&
        env->GetArrayLength(clipToView) >= 16 &&
        env->GetArrayLength(clipToPreviousClip) >= 16 &&
        env->GetArrayLength(previousClipToClip) >= 16 &&
        env->GetArrayLength(worldToView) >= 16 &&
        env->GetArrayLength(viewToWorld) >= 16;
    jfloat v2c[16] = {}, c2v[16] = {}, c2p[16] = {}, p2c[16] = {}, w2v[16] = {}, v2w[16] = {};
    bool arraysValid = arraysPresent;
    if (arraysValid) {
        env->GetFloatArrayRegion(viewToClip, 0, 16, v2c);
        env->GetFloatArrayRegion(clipToView, 0, 16, c2v);
        env->GetFloatArrayRegion(clipToPreviousClip, 0, 16, c2p);
        env->GetFloatArrayRegion(previousClipToClip, 0, 16, p2c);
        env->GetFloatArrayRegion(worldToView, 0, 16, w2v);
        env->GetFloatArrayRegion(viewToWorld, 0, 16, v2w);
        arraysValid = !env->ExceptionCheck();
        if (!arraysValid) {
            env->ExceptionClear();
        }
    }
    std::lock_guard lock(g_streamlineMutex);
    if (!g_initialized || !g_dlssRrSupported || !handlesValid ||
        !formatsValid || !arraysValid || renderWidth <= 0 ||
        renderHeight <= 0 || outputWidth <= renderWidth ||
        outputHeight <= renderHeight || dlssMode < 2 || dlssMode > 6) {
        g_dlssRrInputsReady = false;
        g_nvidiaStatus =
            "DLSS-RR is waiting for low-resolution HDR lighting, material guides, depth, motion and output resources";
        return JNI_FALSE;
    }

    if (!g_frameTokenRefreshedForCurrentFrame || g_currentFrameToken == nullptr) {
        if (slGetNewFrameToken(g_currentFrameToken, &g_frameIndex) !=
                sl::Result::eOk ||
            g_currentFrameToken == nullptr) {
            g_dlssRrInputsReady = false;
            g_nvidiaStatus =
                "DLSS-RR could not acquire a Streamline frame token";
            return JNI_FALSE;
        }
        ++g_frameIndex;
        g_frameTokenRefreshedForCurrentFrame = true;
    }

    sl::Constants constants{};
    for (int row = 0; row < 4; ++row) {
        constants.cameraViewToClip[row] = {
            v2c[row], v2c[4 + row], v2c[8 + row], v2c[12 + row]
        };
        constants.clipToCameraView[row] = {
            c2v[row], c2v[4 + row], c2v[8 + row], c2v[12 + row]
        };
        constants.clipToPrevClip[row] = {
            c2p[row], c2p[4 + row], c2p[8 + row], c2p[12 + row]
        };
        constants.prevClipToClip[row] = {
            p2c[row], p2c[4 + row], p2c[8 + row], p2c[12 + row]
        };
    }
    constants.jitterOffset = sl::float2(jitterX, jitterY);
    constants.mvecScale = sl::float2(
        1.0f / static_cast<float>(renderWidth),
        1.0f / static_cast<float>(renderHeight)
    );
    constants.cameraPos = sl::float3(cameraX, cameraY, cameraZ);
    constants.cameraUp = sl::float3(cameraUpX, cameraUpY, cameraUpZ);
    constants.cameraRight =
        sl::float3(cameraRightX, cameraRightY, cameraRightZ);
    constants.cameraFwd =
        sl::float3(cameraForwardX, cameraForwardY, cameraForwardZ);
    constants.cameraNear = cameraNear;
    constants.cameraFar = cameraFar;
    constants.cameraFOV = verticalFov;
    constants.cameraAspectRatio = aspect;
    constants.depthInverted = sl::Boolean::eTrue;
    constants.cameraMotionIncluded = sl::Boolean::eTrue;
    constants.motionVectors3D = sl::Boolean::eFalse;
    constants.motionVectorsDilated = sl::Boolean::eFalse;
    // Jitter is supplied separately through jitterOffset; the renderer's
    // dense motion-vector image has already had the jitter delta removed.
    constants.motionVectorsJittered = sl::Boolean::eFalse;
    constants.reset =
        reset == JNI_TRUE ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    const sl::Result constantsResult =
        slSetConstants(constants, *g_currentFrameToken, g_viewport);
    if (constantsResult != sl::Result::eOk) {
        g_dlssRrInputsReady = false;
        g_nvidiaStatus = "DLSS-RR constants were rejected (" +
            std::to_string(static_cast<int>(constantsResult)) + ")";
        return JNI_FALSE;
    }

    sl::DLSSDOptions options{};
    options.mode = dlssModeFromIndex(dlssMode);
    options.outputWidth = static_cast<uint32_t>(outputWidth);
    options.outputHeight = static_cast<uint32_t>(outputHeight);
    options.colorBuffersHDR = sl::Boolean::eTrue;
    options.normalRoughnessMode =
        sl::DLSSDNormalRoughnessMode::ePacked;
    options.dlaaPreset = sl::DLSSDPreset::ePresetD;
    options.qualityPreset = sl::DLSSDPreset::ePresetD;
    options.balancedPreset = sl::DLSSDPreset::ePresetD;
    options.performancePreset = sl::DLSSDPreset::ePresetD;
    options.ultraPerformancePreset = sl::DLSSDPreset::ePresetD;
    options.ultraQualityPreset = sl::DLSSDPreset::ePresetD;
    for (int row = 0; row < 4; ++row) {
        options.worldToCameraView[row] = {
            w2v[row], w2v[4 + row], w2v[8 + row], w2v[12 + row]
        };
        options.cameraViewToWorld[row] = {
            v2w[row], v2w[4 + row], v2w[8 + row], v2w[12 + row]
        };
    }
    const sl::Result optionsResult =
        slDLSSDSetOptions(g_viewport, options);
    if (optionsResult != sl::Result::eOk) {
        g_dlssRrInputsReady = false;
        g_nvidiaStatus = "DLSS-RR options were rejected (" +
            std::to_string(static_cast<int>(optionsResult)) + ")";
        return JNI_FALSE;
    }
    g_dlssRrModeActive = true;

    auto makeResource = [](
        jlong image, jlong memory, jlong view, jint format,
        uint32_t width, uint32_t height, uint32_t layout,
        uint32_t usage
    ) {
        sl::Resource resource(
            sl::ResourceType::eTex2d,
            reinterpret_cast<void*>(image),
            reinterpret_cast<void*>(memory),
            reinterpret_cast<void*>(view),
            layout
        );
        describeVulkanTexture(
            resource, width, height, static_cast<uint32_t>(format), usage
        );
        return resource;
    };
    const uint32_t inputUsage =
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    sl::Resource noisy = makeResource(
        noisyImage, noisyMemory, noisyView, noisyFormat,
        renderWidth, renderHeight,
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, inputUsage);
    sl::Resource albedo = makeResource(
        albedoImage, albedoMemory, albedoView, albedoFormat,
        renderWidth, renderHeight,
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, inputUsage);
    sl::Resource specular = makeResource(
        specularImage, specularMemory, specularView, specularFormat,
        renderWidth, renderHeight,
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, inputUsage);
    sl::Resource normalRoughness = makeResource(
        normalRoughnessImage, normalRoughnessMemory,
        normalRoughnessView, normalRoughnessFormat,
        renderWidth, renderHeight,
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, inputUsage);
    sl::Resource specularHitDistance = makeResource(
        specularHitDistanceImage, specularHitDistanceMemory,
        specularHitDistanceView, specularHitDistanceFormat,
        renderWidth, renderHeight,
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, inputUsage);
    sl::Resource depth = makeResource(
        depthImage, depthMemory, depthView, depthFormat,
        renderWidth, renderHeight,
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
    sl::Resource motion = makeResource(
        motionImage, motionMemory, motionView, motionFormat,
        renderWidth, renderHeight,
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT);
    sl::Resource output = makeResource(
        outputImage, outputMemory, outputView, outputFormat,
        outputWidth, outputHeight, VK_IMAGE_LAYOUT_GENERAL,
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);

    const sl::Extent inputExtent{
        0, 0, static_cast<uint32_t>(renderWidth),
        static_cast<uint32_t>(renderHeight)
    };
    const sl::Extent outputExtent{
        0, 0, static_cast<uint32_t>(outputWidth),
        static_cast<uint32_t>(outputHeight)
    };
    sl::ResourceTag tags[] = {
        { &noisy, sl::kBufferTypeScalingInputColor,
            sl::ResourceLifecycle::eValidUntilEvaluate, &inputExtent },
        { &albedo, sl::kBufferTypeAlbedo,
            sl::ResourceLifecycle::eValidUntilEvaluate, &inputExtent },
        { &specular, sl::kBufferTypeSpecularAlbedo,
            sl::ResourceLifecycle::eValidUntilEvaluate, &inputExtent },
        { &normalRoughness, sl::kBufferTypeNormalRoughness,
            sl::ResourceLifecycle::eValidUntilEvaluate, &inputExtent },
        { &specularHitDistance, sl::kBufferTypeSpecularHitDistance,
            sl::ResourceLifecycle::eValidUntilEvaluate, &inputExtent },
        { &depth, sl::kBufferTypeDepth,
            sl::ResourceLifecycle::eValidUntilEvaluate, &inputExtent },
        { &motion, sl::kBufferTypeMotionVectors,
            sl::ResourceLifecycle::eValidUntilEvaluate, &inputExtent },
        { &output, sl::kBufferTypeScalingOutputColor,
            sl::ResourceLifecycle::eValidUntilEvaluate, &outputExtent }
    };
    const sl::BaseStructure* inputs[] = {
        &g_viewport,
        &tags[0], &tags[1], &tags[2], &tags[3],
        &tags[4], &tags[5], &tags[6], &tags[7]
    };
    const sl::Result evaluateResult = slEvaluateFeature(
        sl::kFeatureDLSS_RR,
        *g_currentFrameToken,
        inputs,
        static_cast<uint32_t>(std::size(inputs)),
        reinterpret_cast<sl::CommandBuffer*>(commandBuffer)
    );
    if (evaluateResult != sl::Result::eOk) {
        g_dlssRrInputsReady = false;
        g_nvidiaStatus = "DLSS-RR evaluation failed (" +
            std::to_string(static_cast<int>(evaluateResult)) + ")";
        return JNI_FALSE;
    }

    g_dlssRrInputsReady = true;
    g_nvidiaStatus =
        "DLSS Ray Reconstruction active with HDR noisy lighting and material guide buffers";
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nSetFrameGenerationMode
  (JNIEnv* env, jclass clazz, jint vendorId, jboolean enabled, jint multiplier, jboolean uiRecomposition) {
    if (vendorId == 0x10DE) {
        Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nSetFgMode(env, clazz, enabled, multiplier, uiRecomposition);
    }
}

extern "C" JNIEXPORT void JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nSetReflexOrLowLatencyMode
  (JNIEnv* env, jclass clazz, jint vendorId, jint mode) {
    if (vendorId == 0x10DE) {
        Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nSetReflexMode(
            env, clazz, mode, 0
        );
    }
}

extern "C" JNIEXPORT jlong JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetVramUsage
  (JNIEnv* env, jclass clazz, jint vendorId) {
    if (vendorId == 0x10DE) {
        return Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetDlssgVramUsage(env, clazz);
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetActiveProviderId
  (JNIEnv*, jclass) {
    return static_cast<jint>(framegen::activeProvider());
}

extern "C" JNIEXPORT jstring JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetActiveProviderName
  (JNIEnv* env, jclass) {
    jstring result = env->NewStringUTF(framegen::providerName(framegen::activeProvider()));
    if (result == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetProviderLifecycleState
  (JNIEnv*, jclass) {
    return static_cast<jint>(framegen::providerState());
}

extern "C" JNIEXPORT jstring JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetProviderLifecycleDetail
  (JNIEnv* env, jclass) {
    const std::string detail = framegen::providerStateDetail();
    jstring result = env->NewStringUTF(detail.c_str());
    if (result == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nBeginProviderDrain
  (JNIEnv*, jclass) {
    framegen::markProviderDraining(
        "Provider is draining before swapchain reconfiguration"
    );
}

extern "C" JNIEXPORT jboolean JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nProxySwapchainAvailable
  (JNIEnv*, jclass) {
    // NVIDIA swapchain calls already travel through Streamline because LWJGL's
    // global Vulkan function provider is sl.interposer.dll. Redirecting them a
    // second time here is unnecessary. The explicit proxy remains for the
    // FidelityFX AMD/Intel route.
    return framegen::proxyAvailable()
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nProbeD3D12Presentation
  (JNIEnv*, jclass, jlong physicalDevice, jlong device,
   jlong graphicsQueue, jint graphicsQueueFamily) {
    return dxgipresenter::probe(
        reinterpret_cast<VkPhysicalDevice>(physicalDevice),
        reinterpret_cast<VkDevice>(device),
        reinterpret_cast<VkQueue>(graphicsQueue),
        static_cast<uint32_t>(graphicsQueueFamily)
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetD3D12PresentationStage
  (JNIEnv*, jclass) {
    return static_cast<jint>(dxgipresenter::stage());
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetD3D12PresentationCapabilities
  (JNIEnv*, jclass) {
    return static_cast<jint>(dxgipresenter::capabilities());
}

extern "C" JNIEXPORT jstring JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetD3D12PresentationStatus
  (JNIEnv* env, jclass) {
    const std::string value = dxgipresenter::status();
    jstring result = env->NewStringUTF(value.c_str());
    if (result == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetD3D12AdapterLuid
  (JNIEnv* env, jclass) {
    const std::string value = dxgipresenter::adapterLuid();
    jstring result = env->NewStringUTF(value.c_str());
    if (result == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nCanCreateD3D12PresentationSwapchain
  (JNIEnv*, jclass) {
    return dxgipresenter::canCreatePresentationSwapchain()
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nOwnsD3D12PresentationSwapchain
  (JNIEnv*, jclass, jlong swapchain) {
    return dxgipresenter::ownsSwapchain(
        reinterpret_cast<VkSwapchainKHR>(swapchain)
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nCreateD3D12PresentationSwapchain
  (JNIEnv*, jclass, jlong window, jlong createInfo,
   jlong swapchainOut) {
    return dxgipresenter::createSwapchain(
        reinterpret_cast<void*>(window),
        reinterpret_cast<const VkSwapchainCreateInfoKHR*>(
            createInfo),
        reinterpret_cast<VkSwapchainKHR*>(swapchainOut)
    );
}

extern "C" JNIEXPORT void JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nDestroyD3D12PresentationSwapchain
  (JNIEnv*, jclass, jlong swapchain) {
    dxgipresenter::destroySwapchain(
        reinterpret_cast<VkSwapchainKHR>(swapchain));
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetD3D12PresentationSwapchainImages
  (JNIEnv*, jclass, jlong swapchain, jlong count, jlong images) {
    return dxgipresenter::getSwapchainImages(
        reinterpret_cast<VkSwapchainKHR>(swapchain),
        reinterpret_cast<uint32_t*>(count),
        reinterpret_cast<VkImage*>(images)
    );
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nAcquireD3D12PresentationImage
  (JNIEnv*, jclass, jlong swapchain, jlong semaphore,
   jlong imageIndex) {
    return dxgipresenter::acquireNextImage(
        reinterpret_cast<VkSwapchainKHR>(swapchain),
        reinterpret_cast<VkSemaphore>(semaphore),
        reinterpret_cast<uint32_t*>(imageIndex)
    );
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nQueuePresentD3D12
  (JNIEnv*, jclass, jlong queue, jlong presentInfo) {
    return dxgipresenter::queuePresent(
        reinterpret_cast<VkQueue>(queue),
        reinterpret_cast<const VkPresentInfoKHR*>(presentInfo)
    );
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nCreateProxySwapchain
  (JNIEnv*, jclass, jlong createInfoAddress, jlong allocatorAddress, jlong swapchainAddress) {
    if (g_initialized &&
        g_streamlineDevice != VK_NULL_HANDLE &&
        framegen::activeProvider() ==
            framegen::ProviderId::NvidiaStreamlineVulkan) {
        return static_cast<jint>(vkCreateSwapchainKHR(
            g_streamlineDevice,
            reinterpret_cast<const VkSwapchainCreateInfoKHR*>(
                createInfoAddress
            ),
            reinterpret_cast<const VkAllocationCallbacks*>(allocatorAddress),
            reinterpret_cast<VkSwapchainKHR*>(swapchainAddress)
        ));
    }
    return static_cast<jint>(framegen::createSwapchain(
        reinterpret_cast<const VkSwapchainCreateInfoKHR*>(createInfoAddress),
        reinterpret_cast<const VkAllocationCallbacks*>(allocatorAddress),
        reinterpret_cast<VkSwapchainKHR*>(swapchainAddress)
    ));
}

extern "C" JNIEXPORT void JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nDestroyProxySwapchain
  (JNIEnv*, jclass, jlong device, jlong swapchain, jlong allocatorAddress) {
    if (g_initialized &&
        framegen::activeProvider() ==
            framegen::ProviderId::NvidiaStreamlineVulkan) {
        vkDestroySwapchainKHR(
            reinterpret_cast<VkDevice>(device),
            reinterpret_cast<VkSwapchainKHR>(swapchain),
            reinterpret_cast<const VkAllocationCallbacks*>(allocatorAddress)
        );
        return;
    }
    framegen::destroySwapchain(
        reinterpret_cast<VkDevice>(device),
        reinterpret_cast<VkSwapchainKHR>(swapchain),
        reinterpret_cast<const VkAllocationCallbacks*>(allocatorAddress)
    );
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetProxySwapchainImages
  (JNIEnv*, jclass, jlong device, jlong swapchain, jlong countAddress, jlong imagesAddress) {
    if (g_initialized &&
        framegen::activeProvider() ==
            framegen::ProviderId::NvidiaStreamlineVulkan) {
        return static_cast<jint>(vkGetSwapchainImagesKHR(
            reinterpret_cast<VkDevice>(device),
            reinterpret_cast<VkSwapchainKHR>(swapchain),
            reinterpret_cast<uint32_t*>(countAddress),
            reinterpret_cast<VkImage*>(imagesAddress)
        ));
    }
    return static_cast<jint>(framegen::getSwapchainImages(
        reinterpret_cast<VkDevice>(device),
        reinterpret_cast<VkSwapchainKHR>(swapchain),
        reinterpret_cast<uint32_t*>(countAddress),
        reinterpret_cast<VkImage*>(imagesAddress)
    ));
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nAcquireProxySwapchainImage
  (JNIEnv*, jclass, jlong device, jlong swapchain, jlong timeout, jlong semaphore, jlong fence, jlong imageIndexAddress) {
    if (g_initialized &&
        framegen::activeProvider() ==
            framegen::ProviderId::NvidiaStreamlineVulkan) {
        return static_cast<jint>(vkAcquireNextImageKHR(
            reinterpret_cast<VkDevice>(device),
            reinterpret_cast<VkSwapchainKHR>(swapchain),
            static_cast<uint64_t>(timeout),
            reinterpret_cast<VkSemaphore>(semaphore),
            reinterpret_cast<VkFence>(fence),
            reinterpret_cast<uint32_t*>(imageIndexAddress)
        ));
    }
    return static_cast<jint>(framegen::acquireNextImage(
        reinterpret_cast<VkDevice>(device),
        reinterpret_cast<VkSwapchainKHR>(swapchain),
        static_cast<uint64_t>(timeout),
        reinterpret_cast<VkSemaphore>(semaphore),
        reinterpret_cast<VkFence>(fence),
        reinterpret_cast<uint32_t*>(imageIndexAddress)
    ));
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nQueueProxyPresent
  (JNIEnv*, jclass, jlong queue, jlong presentInfoAddress) {
    if (g_initialized &&
        framegen::activeProvider() ==
            framegen::ProviderId::NvidiaStreamlineVulkan) {
        return static_cast<jint>(vkQueuePresentKHR(
            reinterpret_cast<VkQueue>(queue),
            reinterpret_cast<const VkPresentInfoKHR*>(presentInfoAddress)
        ));
    }
    return static_cast<jint>(framegen::queuePresent(
        reinterpret_cast<VkQueue>(queue),
        reinterpret_cast<const VkPresentInfoKHR*>(presentInfoAddress)
    ));
}

// Deprecated AMD-named ABI wrappers retained for older mod jars. New Java
// builds only use the provider-neutral entry points above.
extern "C" JNIEXPORT jboolean JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nAmdProxyAvailable
  (JNIEnv* env, jclass clazz) {
    return Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nProxySwapchainAvailable(env, clazz);
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nAmdCreateSwapchain
  (JNIEnv* env, jclass clazz, jlong createInfoAddress, jlong allocatorAddress, jlong swapchainAddress) {
    return Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nCreateProxySwapchain(
        env, clazz, createInfoAddress, allocatorAddress, swapchainAddress
    );
}

extern "C" JNIEXPORT void JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nAmdDestroySwapchain
  (JNIEnv* env, jclass clazz, jlong device, jlong swapchain, jlong allocatorAddress) {
    Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nDestroyProxySwapchain(
        env, clazz, device, swapchain, allocatorAddress
    );
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nAmdGetSwapchainImages
  (JNIEnv* env, jclass clazz, jlong device, jlong swapchain, jlong countAddress, jlong imagesAddress) {
    return Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nGetProxySwapchainImages(
        env, clazz, device, swapchain, countAddress, imagesAddress
    );
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nAmdAcquireNextImage
  (JNIEnv* env, jclass clazz, jlong device, jlong swapchain, jlong timeout, jlong semaphore, jlong fence, jlong imageIndexAddress) {
    return Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nAcquireProxySwapchainImage(
        env, clazz, device, swapchain, timeout, semaphore, fence, imageIndexAddress
    );
}

extern "C" JNIEXPORT jint JNICALL Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nAmdQueuePresent
  (JNIEnv* env, jclass clazz, jlong queue, jlong presentInfoAddress) {
    return Java_org_malionaro_dlss_1frame_1generation_client_StreamlineBridge_nQueueProxyPresent(
        env, clazz, queue, presentInfoAddress
    );
}
