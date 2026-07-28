# Backend contracts and implementation status

## Shared renderer contract

Every real frame-generation backend needs a present interception/proxy
swapchain plus synchronized per-frame inputs. At minimum the renderer
integration must provide:

- final/backbuffer extent and presentation lifecycle;
- scene color captured before HUD/UI composition;
- depth from the same rendered frame;
- dense 2D motion vectors including camera and dynamic-object motion;
- current/previous camera transforms, jitter, near/far plane and reset state;
- Vulkan image, image view, device memory, native format, mip/layer data and
  the correct image layout for every tagged image;
- explicit synchronization that keeps all inputs alive and unmodified until
  the SDK has consumed them at present.

The Minecraft hooks now capture renderer-owned pre-HUD colour and depth,
dispatch a dense RG16F motion-vector compute pass, and submit complete image,
view, memory and format metadata to the selected native provider. Three
rotating frame slots are not reused before their corresponding present has
completed. Optional debug readback maps only a slot whose present has already
completed and does not force a device-wide idle.

For NVIDIA DLSS, the world and UI targets are now split: Quality, Balanced,
Performance, Ultra Performance and Ultra Quality render the world at the
optimal size returned by Streamline, upscale into the display target, and
render the HUD afterwards at display resolution.

The renderer also records a standard FFX Vulkan upscale context/dispatch using
the captured color, depth and dense motion vectors, writes to a separate
storage image and copies the accepted result back before Minecraft draws the
HUD. The AMD/Intel FFX route remains 1:1 Native AA because its renderer-owned
reduced-resolution policy has not yet been implemented.

## Compatibility-layer architecture

`FrameGenerationCompatibilityLayer` is the Java-facing router and
`FrameGenCompatibilityLayer` is its native counterpart. Minecraft's Vulkan
surface mixin only calls the provider-neutral create, destroy, image-query,
acquire and present operations. The native router owns the active provider and
delegates those operations to its implementation.

Stable provider IDs currently describe:

- NVIDIA Streamline on Vulkan;
- AMD FSR 3.1 on Vulkan;
- cross-vendor FSR 3.1 on Vulkan for Intel adapters;
- a future AMD FSR 4 route on D3D12;
- a future Intel XeSS-FG route on D3D12.

The D3D12 IDs are routing extension points, not claims that Minecraft currently
has a D3D12 renderer. The old AMD-named JNI exports remain wrappers only for
binary compatibility with older mod jars.

## NVIDIA Streamline

The bundled Streamline SDK supports Vulkan present interception. The bridge
loads `sl.interposer.dll` before LWJGL initializes Vulkan, requests Vulkan 1.3
when the loader supports it, supplies the Vulkan device, checks DLSS/DLSS-G/
Reflex support, clamps the multiplier to the driver-reported maximum and uses
frame-based constants/tags.

The UI distinguishes hardware support from runtime readiness. Frame generation
reports active only after it is enabled and Streamline accepts current-frame
constants plus all Vulkan resource tags. DLSS exposes Off, DLAA, Quality,
Balanced, Performance, Ultra Performance and Ultra Quality. Matrices are
unjittered, jitter is supplied in pixel space, motion vectors are marked
jittered and temporal history is reset for resize, teleport, large camera/FOV
discontinuities and long frame stalls.

DLSS-G receives a full-resolution HUD-less result plus low-resolution depth and
motion guides. When UI recomposition is enabled, Minecraft renders the HUD into
a separate transparent full-resolution target tagged as
`kBufferTypeUIColorAndAlpha`.

DLSS Ray Reconstruction uses Streamline's `kFeatureDLSS_RR` and the bundled
signed `sl.dlss_d.dll`/`nvngx_dlssd.dll`. The hybrid RT pass supplies
low-resolution RGBA16F noisy colour, diffuse/specular albedo,
normal/roughness, depth, dense motion and specular hit distance with the
required world/view matrices. Its RGBA16F output is tone-mapped into the SDR
Minecraft display target before HUD rendering.

## AMD FidelityFX

The current public FSR 4 provider path is DirectX 12 rather than a native
Vulkan replacement for Minecraft's `VkSwapchainKHR`. Consequently the UI does
not label the implemented Vulkan frame-generation path as FSR 4.

The signed `amd_fidelityfx_vk.dll` from the verified OptiScaler 0.9.4 runtime
bundle is included together with the public FidelityFX API headers. The native
bridge loads it dynamically and creates the FSR 3.1 proxy swapchain.
Minecraft's create, destroy, image-enumeration, acquire and present calls are
redirected to the replacement functions returned by FidelityFX.

The proxy is initialized on AMD and Intel adapters. The Intel route is an
explicitly named cross-vendor FSR fallback, not XeSS-FG.

The proxy transport and FidelityFX prepare/configure submission are connected.
Interpolation remains fail-closed until the provider accepts the resources.
Driver validation, frame pacing and resize/fullscreen testing on AMD and Intel
hardware remain required. The generic JNI calls never forward AMD or Intel
work to Streamline.

## Bundled OptiScaler bridge

OptiScaler 0.9.4 can intercept standardized FFX Vulkan upscaling inputs. Its
verified runtime and redistribution notices are embedded in the mod JAR. After
Vulkan identifies an AMD or Intel device, the mod extracts and loads it before
the FidelityFX runtime and supplies a real upscale context plus per-frame
dispatch. NVIDIA does not load this bridge and remains on its native Streamline
route. A config-directory installation remains an explicit external override.
OptiScaler remains a separate GPLv3 work collected in the same JAR. The JAR
includes its license, exact version/commit, hashes, corresponding-source
directions and the licenses of the additional runtime libraries.

The integration resolves `OptiDllPath`/plugin paths and enables FFX inputs in
`OptiScaler.ini`. An untouched `VulkanUpscaler=auto` is changed to `fsr31` on
AMD and `xess` on Intel. Explicit user choices are preserved. DXGI and
Streamline spoofing are disabled when still set to `auto`, because the
Minecraft bridge does not need to impersonate another GPU to submit FFX
inputs. OptiScaler's own frame-generation input/output hooks stay disabled;
the FidelityFX Vulkan proxy remains the sole owner of presentation.

This bridge can select supported Vulkan upscalers for the standard FFX input.
It does not change these renderer/API limits:

- Native AA is currently the only valid mode because input and output have the
  same dimensions.
- XeFG remains unsupported on Vulkan.
- FSR4/XeFG frame generation cannot replace the existing FSR 3.1 Vulkan proxy
  path merely by loading OptiScaler.
- AMD and Intel hardware validation is still required.

## Intel XeSS-FG

The Intel XeSS-FG 3.x API exposes a D3D12 proxy swapchain and requires XeLL.
It does not expose a Vulkan XeSS-FG swapchain. Sharing individual Vulkan image
handles with D3D12 does not convert the application's present chain into the
required D3D12 proxy swapchain, and Intel documents that the XeSS-FG swapchain
cannot be shared with another frame-generation implementation.

True XeSS-FG therefore requires a separate D3D12 Minecraft renderer and present
path. On the existing Vulkan renderer, the implemented Intel-hardware
compatibility route is the cross-vendor FSR 3.1 proxy described above.

## Safe completion order

1. Validate image layouts, resource lifetime and provider acceptance with
   Vulkan validation layers on supported GPUs.
2. Validate NVIDIA DLSS-G present interception, resize, VSync and fullscreen.
3. Validate FidelityFX FSR 3.1 pacing on AMD hardware.
4. Validate the named FSR 3.1 fallback on Intel hardware.
5. Add true XeSS-FG or FSR 4 only together with a D3D12 renderer/present path.
