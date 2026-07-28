# Capability audit

Last reviewed: 2026-07-28

This document separates three different states which the settings screen must
not confuse:

1. **Supported** — the GPU, driver and vendor runtime expose the feature.
2. **Ready** — Minecraft has supplied every required image, constant and
   presentation hook.
3. **Active** — the user enabled the feature and the runtime accepted the
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

- [FSR 3.1 Super Resolution and Frame Interpolation](https://gpuopen.com/manuals/fidelityfx_sdk/techniques/super-resolution-interpolation/)
- [FSR 3.1 migration guide](https://gpuopen.com/manuals/fidelityfx_sdk/getting-started/migrating-to-fsr-3-1/)
- [FidelityFX SDK repository](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK)

Implemented checks and calls:

- The signed FidelityFX Vulkan runtime is loaded only for AMD or Intel routes.
- The proxy swapchain owns create/destroy, image enumeration, acquire and
  present operations.
- Color, depth, motion vectors, reset state and monotonically increasing frame
  IDs are supplied to prepare/configure dispatches.
- Upscaling uses a separate output image. Only Native AA is exposed because
  Minecraft currently renders scene and UI at the same display resolution.
- Provider failure remains fail-closed and is surfaced in native status text.

FSR 4 is not advertised as the Vulkan frame-generation backend. Its current
public provider route cannot simply replace Minecraft's `VkSwapchainKHR`.

## OptiScaler

Primary references:

- [Official repository](https://github.com/optiscaler/OptiScaler)
- [Official releases](https://github.com/optiscaler/OptiScaler/releases)
- [FSR 4 compatibility notes](https://github.com/optiscaler/OptiScaler/wiki/FSR4-Compatibility-List)

The JAR contains the verified OptiScaler v0.9.4 runtime, exact commit metadata,
hash manifest, GPLv3 text, corresponding-source directions and dependency
licenses. Integrity is checked before extraction/loading.

The v0.9.4 source was inspected at its exact tag. Its Vulkan proxy hooks the
standard `amd_fidelityfx_vk.dll` upscaling API while forwarding FidelityFX
frame-generation/swapchain calls to the real runtime. Therefore the integration
loads OptiScaler first, then FidelityFX, and keeps FidelityFX as the only
frame-generation/present owner.

Default vendor routing is:

- AMD: `VulkanUpscaler=fsr31`
- Intel: `VulkanUpscaler=xess`
- explicit external user settings: preserved

OptiScaler does not turn Intel XeSS Frame Generation into a Vulkan API and does
not remove the need for separate reduced-resolution world rendering.

## Intel XeSS

Primary references:

- [Intel XeSS Frame Generation developer guide](https://www.intel.com/content/www/us/en/developer/articles/technical/xess-fg-developer-guide.html)
- [Intel XeSS platform/API support](https://www.intel.com/content/www/us/en/developer/topic-technology/gamedev/xess.html)
- [Intel XeSS releases](https://github.com/intel/xess/releases)

Intel exposes XeSS Super Resolution on Vulkan, but XeSS Frame Generation uses a
D3D12 proxy swapchain and XeLL. Minecraft's current renderer owns a Vulkan
swapchain, so the mod labels its Intel Vulkan frame-generation route as an FSR
3.1 fallback. OptiScaler may select XeSS for Vulkan reconstruction, but cannot
provide native XeSS-FG presentation.

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
invisible underground blocks.

The compute pass samples Minecraft's reverse-Z depth buffer and rejects an RT
hit when rasterized geometry is closer. HDR/material guide images are generated
for DLSS-RR; raster-only pixels receive depth-derived normals. Per-frame camera
uniforms use three rotating buffers to avoid CPU/GPU overwrite races.

The controls now have distinct effects:

- mode: off, shadows, or shadows plus reflections;
- distance: AABB scene radius, capped at 48 blocks;
- quality: reflection reach;
- samples: soft-shadow ray count;
- stable sampling: minimum four shadow samples.

The runtime status remains “waiting” until a world/camera exists and becomes
“active” only after the first RT command buffer is recorded successfully.

## Verification state

Automated verification covers Java compilation, native C++ compilation, shader
compilation through runtime shaderc tests, config migration, motion-vector
conventions, OptiScaler configuration/hashes and capability selection.

Local runtime testing on a GTX 1660 SUPER on 2026-07-28 confirms normal Vulkan
startup, acceptance of all current Mixins, world loading, swapchain resize,
Streamline/Reflex initialization and correct rejection of unsupported DLSS and
ray-query features. An earlier RTX 4080 SUPER log confirmed GPU detection and
ray-query capability, but came from the old Vulkan-1.2-requesting build and
therefore cannot validate the corrected DLSS/FG/RR output.

The remaining hardware validation is deliberately narrow:

- RTX 40: validate DLSS optimal-resolution output, DLSS-G present interception,
  RT depth rejection and DLSS-RR guide acceptance with Vulkan validation
  layers, plus resize, VSync and fullscreen.
- AMD: validate FidelityFX proxy-swapchain pacing, resize and fullscreen.
- Intel: validate OptiScaler XeSS Native AA plus the explicitly named FSR 3.1
  frame-generation fallback.
