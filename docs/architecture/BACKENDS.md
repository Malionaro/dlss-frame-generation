# Backend contracts and implementation status

## Shared renderer contract

Every real frame-generation backend needs a present interception/proxy
swapchain plus synchronized per-frame inputs. At minimum the renderer
integration must provide:

- final/backbuffer extent and presentation lifecycle;
- scene color captured before HUD/UI composition;
- depth from the same rendered frame;
- dense 2D motion vectors with a declared source for camera and object motion;
- current/previous camera transforms, jitter, near/far plane and reset state;
- Vulkan image, image view, device memory, native format, mip/layer data and
  the correct image layout for every tagged image;
- explicit synchronization that keeps all inputs alive and unmodified until
  the SDK has consumed them at present.

The Minecraft hooks now capture renderer-owned pre-HUD colour and depth,
dispatch a dense RG16F motion-vector compute pass, and submit complete image,
view, format, layout and queue-owner metadata to the selected native provider.
`FrameIdentity` and `FrameResourceSet` bind every input to one frame and one
swapchain generation; incomplete, stale, cross-frame or in-render-pass captures
are rejected. Three rotating frame slots are not reused before their
corresponding present has completed. Busy spin-waits on the render thread have
been eliminated; presentation synchronization relies entirely on GPU timeline
semaphores and fences without burning CPU budget. Optional debug readback maps only a slot
whose present has already completed and does not force a device-wide idle.

The validated guide convention is reversed depth (`1` near, `0` far), motion
from current pixel to previous pixel in render-pixel units, positive Y down and
jitter removed from motion (jitter is supplied separately). The current
object-motion source combines camera reprojection with a temporal image
estimate; it is not described as full per-entity motion.

For NVIDIA DLSS, the world and UI targets are now split: Quality, Balanced,
Performance, Ultra Performance and Ultra Quality render the world at the
optimal size returned by Streamline, upscale into the display target, and
render the HUD afterwards at display resolution.

The renderer also records a standard FFX Vulkan upscale context/dispatch using
the captured color, depth and dense motion vectors, writes to a separate
storage image and copies the accepted result back before Minecraft draws the
HUD. The AMD/Intel FFX route now uses the same split world/display ownership:
Native AA remains 1:1, while Quality through Ultra Performance use aligned
reduced world targets. The FFX context enables dynamic resolution and receives
render-resolution motion vectors with the matching resource dimensions.

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
- an AMD FSR 4 route identifier on D3D12;
- an Intel XeSS-FG route identifier on D3D12.

The D3D12 IDs and the hybrid presenter are deliberately separate. The presenter
is a real transport, but neither ID becomes selectable until its own official
provider runtime and complete capability/lifecycle checks succeed. The old
AMD-named JNI exports remain wrappers only for binary compatibility with older
mod jars.

Provider state is explicit (`unavailable`, `detected`, `initializing`, `ready`,
`active`, `draining`, `failed`, `shutdown`). Exactly one frame-generation
provider, one upscaler and one presentation owner are permitted. Selecting the
hybrid presenter disables Vulkan proxy-swapchain FG, and provider changes drain
and rebuild at a safe boundary.

## Windows hybrid Vulkan to D3D12 presentation

The optional `WindowsDxgiPresenter` keeps world rendering on Vulkan and replaces
only WSI presentation. It:

- maps `VkPhysicalDevice` to the same DXGI adapter by Windows LUID;
- creates a D3D12 device, command queue and flip-discard DXGI swapchain;
- creates shared D3D12 textures and imports them as Vulkan images after external
  format/usage checks;
- imports two shared D3D12 fences as Vulkan timeline semaphores;
- transfers image ownership between the Vulkan graphics queue and
  `VK_QUEUE_FAMILY_EXTERNAL`;
- performs the final copy and `Present` only on D3D12, without a CPU frame copy;
- releases the presentation mutex during the blocking DXGI `Present` call so VSync
  waits do not freeze UI, HUD or diagnostic threads;
- cleans up Win32 handles immediately with `CloseHandle` and resets D3D12/Vulkan
  resources on every partial failure path;
- rebuilds on resize and handles occlusion, tearing/VSync, device removal and
  shutdown with a safe fallback to the normal Vulkan swapchain.

The route is Windows-only, disabled by default and requires a restart. Its
current production format is SDR; unsupported/HDR swapchain formats fail closed
to Vulkan WSI. A private teardown fence prevents D3D12 resources from being
released while the queue still references them. The Vulkan validation and D3D12
debug layers can be enabled for development diagnostics.

## NVIDIA Streamline

The bundled Streamline SDK supports Vulkan present interception. The bridge
loads `sl.interposer.dll` before LWJGL initializes Vulkan, requests Vulkan 1.3
when the loader supports it, enables `VK_EXT_debug_utils` when available for
Nsight/RenderDoc markers, offers `VK_NV_low_latency2` on NVIDIA devices so
Reflex can use the extension transport, supplies the Vulkan device, checks DLSS/DLSS-G/
Reflex support, clamps the multiplier to the driver-reported maximum and uses
frame-based constants/tags.

The UI distinguishes hardware support from runtime readiness. Frame generation
reports active only after it is enabled and Streamline accepts current-frame
constants plus all Vulkan resource tags. DLSS exposes Off, DLAA, Quality,
Balanced, Performance, Ultra Performance and Ultra Quality. Matrices are
unjittered, jitter is supplied separately in pixel space, motion vectors have
jitter removed and temporal history is reset for resize, teleport, large camera/FOV
discontinuities and long frame stalls.

DLSS-G receives a full-resolution HUD-less result plus low-resolution depth and
motion guides. When UI recomposition is enabled, Minecraft renders the HUD into
a separate transparent full-resolution target tagged as
`kBufferTypeUIColorAndAlpha`.

DLSS Super Resolution uses the DLSS 4.5 transformer presets: K for DLAA,
Quality, Balanced and Ultra Quality, M for Performance and L for Ultra
Performance. Render sizes keep the exact window aspect ratio, the terrain
sampler gets a mip bias of `log2(render / output)` and the Halton jitter
sequence is `8 × (output / render)²` phases long. With a shader-pack engine
that cannot scale (Iris), the world arrives at window size and DLSS is not
run over it.

DLSS Ray Reconstruction uses Streamline's `kFeatureDLSS_RR` and the bundled
signed `sl.dlss_d.dll`/`nvngx_dlssd.dll` on transformer preset F (the
Streamline 2.14 default). The hybrid RT pass supplies
low-resolution RGBA16F noisy colour, diffuse/specular albedo,
normal/roughness, depth, dense motion and specular hit distance with the
required world/view matrices. Its RGBA16F output is tone-mapped into the SDR
Minecraft display target before HUD rendering.

## AMD FidelityFX

The current public FSR 4 provider path is DirectX 12 rather than a native
Vulkan replacement for Minecraft's `VkSwapchainKHR`. The hybrid presenter now
provides a real D3D12 queue/swapchain and zero-copy Vulkan transport, but the
bundled runtime contains only the generic
`amd_fidelityfx_dx12.dll`/upscaler components and not
`amd_fidelityfx_framegeneration_dx12.dll`. It therefore does not expose an
FSR 4 FG provider. The runtime audit keeps the option unavailable and names the
missing file.

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
Native AA remains 1:1, while Quality through Ultra Performance use aligned reduced
world targets via the split world/display ownership described above. Remaining limits:

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

The hybrid presenter supplies the D3D12 presentation foundation without
rewriting the world renderer. It still cannot activate XeSS-FG: the bundled
package contains the XeSS super-resolution `libxess.dll`, but not
`libxess_fg.dll` or the mandatory `libxell.dll`. A complete future integration
must let Intel create and exclusively own its proxy swapchain, use one frame
counter for XeLL/XeSS-FG, and pass all provider capability checks. On the
existing Vulkan renderer, the available Intel-hardware compatibility route is
the explicitly named cross-vendor FSR 3.1 proxy described above.

## Safe completion order

1. Validate Sodium 0.9.1 and Iris-installed/Vulkan combinations with their real
   mod JARs and Vulkan validation enabled.
2. Validate NVIDIA DLSS-G present interception, resize, VSync and fullscreen on
   supported RTX hardware.
3. Validate FidelityFX FSR 3.1 pacing on AMD hardware.
4. Validate the named FSR 3.1 fallback on Intel hardware.
5. Obtain and license-check official FSR 4 FG or XeSS-FG/XeLL runtimes before
   implementing either provider on top of the completed hybrid presenter.
6. Minecraft 26.3 is the compiled target and the hooks are ported onto the
   `renderpearl` API; the current hardware-tested target is still 26.2. Validate
   the 26.3 build on RTX, AMD and Intel hardware.
