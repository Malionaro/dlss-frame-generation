# Capability audit

Last reviewed: 2026-08-22

This document separates three different states which the settings screen must
not confuse:

1. **Supported.** The GPU, driver and vendor runtime expose the feature.
2. **Ready.** Minecraft has supplied every required image, constant and
   presentation hook.
3. **Active.** The user enabled the feature and the runtime accepted the
   current frame.

## NVIDIA Streamline

Primary references:

- [Streamline Programming Guide](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuide.md)
- [Manual Hooking Guide](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideManualHooking.md)
- [DLSS Super Resolution Guide](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideDLSS.md)
- [DLSS Frame Generation Guide](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideDLSS_G.md)
- [DLSS Ray Reconstruction Guide](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideDLSS_RR.md)

Implemented checks and calls:

- `sl.interposer.dll` is selected as LWJGL's Vulkan loader before Vulkan
  instance creation. This gives Streamline the automatic Vulkan lifecycle
  instead of an incomplete subset of manual hooks.
- Plugin-reported instance extensions, device extensions and Vulkan 1.2/1.3
  feature bits are added before object creation. Vulkan 1.3 is requested when
  the installed Vulkan loader supports it.
- The physical device is passed in `sl::AdapterInfo` to
  `slIsFeatureSupported` separately for DLSS, DLSS-G, DLSS-RR and Reflex.
- DLSS receives render-resolution color/depth/dense RG16F motion vectors,
  unjittered matrices, pixel jitter and a display-resolution output image.
  Streamline's optimal input size drives a separate world target while the HUD
  stays at display resolution.
- The UI exposes Off, DLAA, Quality, Balanced, Performance, Ultra Performance
  and Ultra Quality. Transformer presets K/M/L are selected according to the
  Streamline 2.12 recommendations.
- DLSS-G receives HUD-less color, depth, motion vectors and per-frame
  constants. The full-screen backbuffer is left to Streamline's Vulkan present
  interception, as required by the guide.
- DLSS-G is disabled before swapchain resize/fullscreen reconfiguration and
  restored afterwards.
- DLSS-G UI recomposition tags a separately rendered transparent
  full-resolution HUD layer.
- Frame Generation is reported active only when it is selected and complete
  inputs have been accepted.
- DLSS-RR receives low-resolution HDR noisy lighting, diffuse/specular albedo,
  packed normal/roughness, depth, dense motion, specular hit distance and
  world/view matrices. Its output is full-resolution RGBA16F.

Important runtime requirements:

- DLSS-G can require Windows Hardware-Accelerated GPU Scheduling (HAGS).
- Streamline reports the supported frame multiplier; RTX 40 hardware normally
  exposes 2x Frame Generation, while Multi Frame Generation depends on newer
  supported hardware/runtime.
- The Vulkan DLSS-G guide does not support the same VSync path as D3D12.
- A driver reporting Vulkan 1.4 is not proof that an application created a
  Vulkan 1.4 instance/device. The old RTX log showed a 1.4-capable driver while
  Minecraft itself requested a 1.2 path. The integration now explicitly
  requests Vulkan 1.3 when available.

Streamline 2.12's own `sl.common` JSON still lists three legacy command-state
tracking hooks which are not present in that release's public
`FunctionHookID` table. It consequently prints generic “hook not supported”
warnings for `CmdBindPipeline`, `CmdBindDescriptorSets` and
`BeginCommandBuffer`. The integration retains Streamline's default
`eDisableCLStateTracking` mode and does not depend on those hooks. The mandatory
Vulkan present hooks must still report `OK`; missing present hooks are a real
failure.

## AMD FidelityFX and FSR

Primary references:

- [FidelityFX SDK manual](https://gpuopen.com/manuals/fsr_sdk/)
- [FidelityFX API setup](https://gpuopen.com/manuals/fsr_sdk/getting-started/ffx-api/)
- [Frame Interpolation ML](https://gpuopen.com/manuals/fsr_sdk/techniques/frame-interpolation-ml/)
- [Frame-interpolation swapchain](https://gpuopen.com/manuals/fsr_sdk/techniques/frame-interpolation-swap-chain/)
- [FidelityFX SDK repository](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK)

Implemented checks and calls:

- The signed FidelityFX Vulkan runtime is loaded only for AMD or Intel routes.
- The proxy swapchain owns create/destroy, image enumeration, acquire and
  present operations.
- Color, depth, motion vectors, reset state and monotonically increasing frame
  IDs are supplied to prepare/configure dispatches.
- Upscaling uses a separate output image. Native AA plus reduced-resolution
  quality modes (Quality, Balanced, Performance, Ultra Performance, Ultra Quality)
  are supported with split world/display targets.
- Provider failure remains fail-closed and is surfaced in native status text.

FSR 4 is not advertised as the Vulkan frame-generation backend. The hybrid
D3D12 presenter is present, but the runtime audit still requires both
`amd_fidelityfx_dx12.dll` and
`amd_fidelityfx_framegeneration_dx12.dll`. The latter is not in the verified
OptiScaler 0.9.4 bundle, so no FSR 4 FG context or proxy swapchain is claimed.

## Hybrid Vulkan/D3D12 presenter

Primary references:

- [Vulkan external memory on Windows](https://docs.vulkan.org/refpages/latest/refpages/source/VK_KHR_external_memory_win32.html)
- [Vulkan external semaphore handle types](https://docs.vulkan.org/refpages/latest/refpages/source/VkExternalSemaphoreHandleTypeFlagBits.html)
- [D3D12 shared handles](https://learn.microsoft.com/en-us/windows/win32/api/d3d12/nf-d3d12-id3d12device-createsharedhandle)
- [D3D12 command-queue synchronization](https://learn.microsoft.com/en-us/windows/win32/direct3d12/executing-and-synchronizing-command-lists)
- [DXGI swapchains with D3D12](https://learn.microsoft.com/en-us/windows/win32/direct3d12/swap-chains)

Implemented capability gates:

- Windows and a valid Vulkan physical/logical device;
- `VK_KHR_external_memory`,
  `VK_KHR_external_memory_win32`, `VK_KHR_external_semaphore` and
  `VK_KHR_external_semaphore_win32` enabled on the logical device;
- exact Vulkan/DXGI adapter LUID match;
- shareable D3D12 texture format/usage and Vulkan import;
- two shared D3D12 fences imported as Vulkan timeline semaphores;
- D3D12 command queue, DXGI flip-discard swapchain and zero-copy frame path.

`D3D12_DEVICE_READY` is not reported as presentation success.
`PRESENTATION_READY` requires all shared images, synchronization
objects and swapchain resources. Any failed stage preserves the reason and
returns to normal Vulkan WSI instead of enabling a nonfunctional button.

## OptiScaler

Primary references:

- [Official repository](https://github.com/optiscaler/OptiScaler)
- [Official releases](https://github.com/optiscaler/OptiScaler/releases)
- [FSR 4 compatibility notes](https://github.com/optiscaler/OptiScaler/wiki/FSR4-Compatibility-List)

The JAR contains the verified OptiScaler v0.9.4 runtime, exact commit metadata,
hash manifest, GPLv3 text, corresponding-source directions and dependency
licenses. An upstream check on 2026-09-02 confirmed that v0.9.4 remains the
latest official stable release. Integrity is checked before extraction/loading.

The v0.9.4 source was inspected at its exact tag. Its Vulkan proxy hooks the
standard `amd_fidelityfx_vk.dll` upscaling API while forwarding FidelityFX
frame-generation/swapchain calls to the real runtime. Therefore the integration
loads OptiScaler first, then FidelityFX, and keeps FidelityFX as the only
frame-generation/present owner.

Default vendor routing is:

- AMD: `VulkanUpscaler=fsr31`
- Intel: `VulkanUpscaler=xess`
- explicit external user settings: preserved

OptiScaler does not turn Intel XeSS Frame Generation into a Vulkan API. The mod
now supplies the required separate reduced-resolution world target for temporal
upscaling, while Minecraft's UI is still composed at display resolution.

## Intel XeSS

Primary references:

- [Intel XeSS-FG developer guide source](https://github.com/intel/xess/blob/main/doc/xess_fg_developer_guide_english.md)
- [Intel XeLL developer guide](https://www.intel.com/content/www/us/en/developer/articles/technical/xell-developer-guide.html)
- [Intel XeSS releases](https://github.com/intel/xess/releases)

Intel exposes XeSS Super Resolution on Vulkan, but XeSS Frame Generation uses a
D3D12 proxy swapchain and XeLL. The hybrid bridge can provide a same-adapter
D3D12 queue and presentation chain, but the bundled files contain only
`libxess.dll`; `libxess_fg.dll` and `libxell.dll` are absent. The runtime audit
therefore rejects native XeSS-FG before initialization. OptiScaler may select
XeSS for Vulkan reconstruction, but cannot provide native XeSS-FG presentation.

## Vulkan hardware ray tracing

Primary references:

- [Khronos Vulkan ray tracing guide](https://docs.vulkan.org/guide/latest/extensions/ray_tracing.html)
- [Khronos buffer device address guide](https://docs.vulkan.org/guide/latest/buffer_device_address.html)
- [Current Vulkan specification](https://registry.khronos.org/vulkan/specs/latest/html/vkspec.html)

The logical device enables ray query, acceleration structure, deferred host
operations and buffer device address only when both extension availability and
feature bits are confirmed. The renderer builds an AABB BLAS/TLAS and runs a
ray-query compute pass before Minecraft blits its main color image.

The scene builder prioritizes visible surfaces near the camera, omits enclosed
opaque blocks and expands exact block/fluid `VoxelShape` sub-boxes. It refreshes
after movement, nearby client block updates and a low-frequency safety
interval. This prevents its fixed 65,536-AABB budget from being consumed by
invisible underground blocks. Existing BLAS and TLAS storage and scratch buffers
are reused when rebuilding the scene, eliminating severe VRAM re-allocation spikes.

The compute pass samples Minecraft's reverse-Z depth buffer and rejects an RT
hit when rasterized geometry is closer. HDR/material guide images are generated
for DLSS-RR; raster-only pixels receive depth-derived normals. Per-frame camera
uniforms are uploaded directly via `vkCmdUpdateBuffer` and synchronized with an
explicit `VkBufferMemoryBarrier` (`TRANSFER_WRITE` to `UNIFORM_READ`) without CPU sleeps.

The controls now have distinct effects:

- mode: off, shadows, or shadows plus reflections;
- distance: AABB scene radius, capped at 48 blocks;
- quality: reflection reach;
- samples: soft-shadow ray count;
- stable sampling: minimum four shadow samples.

The runtime status remains “waiting” until a world/camera exists and becomes
“active” only after the first RT command buffer is recorded successfully.

## Verification state

### 1. Statically implemented and built

- actual-device renderer detection, optional Iris API probing and fail-closed
  shader/backend states;
- atomic frame identity/resources with layout, queue owner and swapchain
  generation;
- exclusive provider lifecycle and safe swapchain transitions;
- Windows Vulkan/D3D12 same-adapter presenter with non-blocking Present mutex,
  full error handle cleanup, and provider-runtime audit;
- in-game keybindings (F8 for OSD, F7 for FG toggle, End for settings) and
  enriched HUD with Reflex/RT status;
- explicit depth/motion conventions and future renderer/material contracts;
- localized disabled-control reasons and scrollable diagnostics.

### 2. Automatically tested

Gradle tests compile Java, native C++ and shaders. All 62 unit tests across 18
test classes pass, covering renderer-state routing, provider exclusivity/lifecycle,
stale frame/swapchain generations, missing Vulkan interop extensions, missing
provider DLLs, language-key parity, reversed-Z/motion conventions and capability behavior.

### 3. Locally runtime tested

On a GTX 1660 SUPER on 2026-07-28 the current build completed Vulkan startup,
all Mixins, world loading and a live resize. The optional hybrid path matched the
same adapter LUID, reached all seven presenter capabilities (`0x7f`), rebuilt
its shared images/fences, presented thousands of frames through DXGI and exited
cleanly with the D3D12 debug layer enabled. No CPU frame copy, D3D12 debug
message, Vulkan validation error or device removal was observed.

The same GPU correctly rejects unsupported DLSS-G and ray-query features. An
earlier RTX 4080 SUPER log confirmed detection/ray-query capability, but came
from an older Vulkan-1.2-requesting build and cannot validate current
DLSS/FG/RR output.

### 4. Hardware validation still required

- RTX 40: validate DLSS optimal-resolution output, DLSS-G present interception,
  RT depth rejection and DLSS-RR guide acceptance with Vulkan validation
  layers, plus resize, VSync and fullscreen.
- AMD: validate FidelityFX proxy-swapchain pacing, resize and fullscreen.
- Intel: validate OptiScaler XeSS Native AA plus the explicitly named FSR 3.1
  frame-generation fallback.
- Sodium 0.9.1/Iris 1.11.2: run their real 26.2 mod JARs through world load,
  renderer reload, resize/fullscreen and shaderpack fail-closed cases.
- Minecraft 26.3: inspect its final renderer ABI and add a separate guarded
  source set/Mixin configuration if class or frame-boundary hooks changed.
- FSR 4 FG and XeSS-FG: cannot be provider-tested until the respective official,
  redistributable D3D12 FG runtime (and XeLL for Intel) is available.

## 2026-08-22 Fix-Runde

- H1: Drain mit `finally` + Timeout meldet jetzt FAILED statt zu blockieren.
- H2: Jitter ist im Mode 0 konsequent 0.
- H3: Vendor-Switch läuft über eine eigene UI-Layer.
- H4: Composite-Fallback greift, wenn die Komposition verweigert wird.
- H5: Pipeline-Leak behoben.
- K1/K2: Fence-Stubs entfernt bzw. sauber ersetzt.
- OOM-Latch führt jetzt zu Retry statt dauerhafter Sperrung.
- `configVersion=2` plus atomares Speichern der Konfiguration.
- Mixin-Level auf JAVA_21 angehoben.
- JNI Exception-Hygiene nachgezogen.
- D3D12-Deadlock-Fix im Hybrid-Pfad.
- OptiScaler: externer SHA256-Check vor der Nutzung.
- copyNative: Ninja-Pfad-Fix im Build.
- RT: Dirty-Chunk-Budget begrenzt Raytracing-Rebuilds pro Frame.
- DRS: adaptive Schritte für dynamische Auflösung.
