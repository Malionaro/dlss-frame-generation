# Feature status and roadmap

Status: September 26, 2026  
Compiled Minecraft target: **26.3**  
Hardware-validated Minecraft target: **26.2**

This overview shows which features have already been proven to work,
which still need real hardware tests and which are only prepared or
planned. A visible setting is not automatically available on every GPU:
The mod only enables features when GPU, driver, renderer,
native runtime and all required frame data have been accepted.

## 26.3 port notes

Minecraft 26.3 replaced the renderer abstraction this mod hooks into.
`com.mojang.blaze3d.systems` and `com.mojang.blaze3d.vulkan` were extracted into
a new `com.mojang.renderpearl` library, several core GPU types became
interfaces, and the windowing layer moved from GLFW to SDL. The port maps every
hook onto its 26.3 equivalent:

| 26.2 hook | 26.3 equivalent |
|---|---|
| `com.mojang.blaze3d.vulkan.*` | `com.mojang.renderpearl.backend.vulkan.*` |
| `blaze3d.systems.GpuDevice` (class, `backend` field) | `renderpearl.frontend.FrontendGpuDevice` (the `backend` field moved to the implementation) |
| `blaze3d.systems.GpuDeviceBackend` | `renderpearl.backend.api.GpuDeviceBackend` |
| `blaze3d.systems.CommandEncoder`, `textures.GpuTexture(View)`, `shaders.ShaderSource` | `renderpearl.api.commands` / `.api.textures` / `.api.pipeline` |
| `VulkanBackend.createDevice(Collection<String>, VulkanPhysicalDevice, Set<VulkanFeature>)` | private `createDevice(FeatureSet, VulkanPhysicalDevice)`, hooked at its call site inside `createDevice(GpuDebugOptions)`; the selection result is handed over as a `FeatureSet` |
| `VulkanBackend.REQUIRED_DEVICE_EXTENSIONS`, `VulkanBackend.VK12_FEATURES_STRUCT` | `VulkanFeatureSets` (the constants moved there, they were not removed) |
| `VulkanDevice(ShaderSource, …, Set<String>, …)` | `VulkanDevice(VulkanInstance, …, FeatureSet, …)` |
| `GLFWNativeWin32.glfwGetWin32Window` | `SDLVideo.SDL_GetWindowProperties` + `SDLProperties.SDL_GetPointerProperty(…, SDL_PROP_WINDOW_WIN32_HWND_POINTER)` |
| `InputConstants.Type.KEYSYM` + `GLFW_KEY_*` | `InputConstants.Type.KEYBOARD` + `SDL_SCANCODE_*` |
| `GameRenderer.render(DeltaTracker, boolean)` | `GameRenderer.render()` |
| `GameRenderer.renderLevel(DeltaTracker)` | `GameRenderer.renderLevel()` |

`VulkanGpuSurface` is structurally unchanged: all seven accessed fields, the
five hooked methods and all six LWJGL redirect points are identical in 26.3.
`VulkanCommandEncoder.currentCommandBuffer` also survived; 26.3 only added a
separate `currentInitCommandBuffer` and renamed `textureInitCommandBuffer()` to
`objectInitCommandBuffer()`. Frame timing is self-measured with
`System.nanoTime()`, so the loss of the `DeltaTracker` parameter in
`GameRenderer.render` does not affect it.

The port is compile-verified and all 57 mixin injection points, accessors and
redirects were checked against the 26.3 jar. It has **not** been run on 26.3
hardware yet, so the tables below still describe 26.2-validated behaviour and
nothing here should be read as 26.3-validated.

## Status legend

| Status | Meaning |
|---|---|
| ✅ Tested | Tested successfully in an automated way or on the stated hardware |
| 🟡 Experimental | Implemented and wired up, but not yet validated on all target GPUs and display modes |
| 🧩 Foundation present | Architecture or transport exists; the actual provider path is still missing |
| 🔒 Not available | Intentionally not enabled because a technical requirement or official runtime is missing |
| 📅 Planned | Not yet implemented |

## Build, packaging and shared infrastructure

| Feature | Status | Current state | Still to do |
|---|---|---|---|
| Java, native and shader build | ✅ Tested | Local Windows build and GitHub Actions with Java 25 successful | Continue to verify via CI when changed |
| Single mod JAR | ✅ Tested | Java code, JNI bridge, shaders and allowed runtime files are packaged together in `dlss-frame-generation-1.0.0.jar` (141.5 MB) | Ready for distribution |
| JNI binding | ✅ Tested | All 53 Java native declarations have matching JNI exports | Cover new native functions in the same checks |
| Fabric registration | ✅ Tested | Both entrypoints and all 18 mixins are registered | Re-check for new Minecraft versions |
| Provider selection | ✅ Tested | Exactly one FG provider, one upscaler and one presentation path may be active at the same time | Integrate additional providers into the same exclusivity logic |
| Atomic frame data | ✅ Tested | Color, depth, motion, frame ID and swapchain generation are not mixed between frames | Additionally validate with real Sodium/Iris runs |
| Safe fallback | ✅ Tested | Unsupported paths stay disabled or fall back to normal Vulkan WSI | Collect additional driver and error cases |

## NVIDIA

| Feature | Status | Current state | Still to do |
|---|---|---|---|
| GPU and Streamline detection | ✅ Tested | NVIDIA device, Streamline runtime and individual capabilities are checked separately | Test on multiple RTX generations |
| DLSS Super Resolution / DLAA | 🟡 Experimental | Quality modes, optimal render resolution, upscaling before the native HUD and history reset are wired up | Validate image quality, resize, fullscreen and all quality modes on RTX hardware |
| DLSS Frame Generation | 🟡 Experimental | Color without HUD, depth, motion and separate UI layer are passed to Streamline; active status only after provider acceptance | Complete RTX 40 hardware test with VSync, tearing, resize and fullscreen |
| NVIDIA Reflex | 🟡 Experimental | Pacing, Streamline Reflex path and HUD status indicator are wired up | Measure latency and markers on supported RTX hardware |
| DLSS Ray Reconstruction | 🟡 Experimental | DLSS-RR and HDR, albedo, normal, roughness and hit distance guides are connected | Test quality and stability together with hardware RT on RTX |
| GTX support | ✅ Tested, limited | GTX 1660 SUPER is correctly detected; missing DLSS-G and Ray Query are rejected | No plan to enable unsupported RTX features |

## AMD

| Feature | Status | Current state | Still to do |
|---|---|---|---|
| FSR 3.1 Vulkan Frame Generation | 🟡 Experimental | Signed FidelityFX Vulkan runtime, proxy swapchain and Prepare/Configure calls are wired up | Validate provider acceptance, pacing, resize, fullscreen and driver on AMD hardware |
| FSR 3.1 Native AA | 🟡 Experimental | Standardized FFX inputs and a separate output target are present | Real AMD image quality and stability tests |
| FSR upscaling below 1:1 | 🟡 Experimental | Quality, Balanced, Performance, Ultra Performance and Ultra Quality use a reduced world target, FFX reconstruction and a HUD at display resolution | Validate image quality, resize and dynamic resolution on AMD hardware |
| FSR 4 Frame Generation | 🔒 Not available | D3D12 transport foundation exists, but `amd_fidelityfx_framegeneration_dx12.dll` is not included | Obtain official redistributable runtime, check license and integrate real provider |

## Intel

| Feature | Status | Current state | Still to do |
|---|---|---|---|
| XeSS / FSR upscaling via OptiScaler | 🟡 Experimental | OptiScaler selects `xess` on Intel; Native AA and reduced quality modes use separate world/display targets | Test on Intel Arc with resize and all quality modes |
| FSR 3.1 fallback on Intel | 🟡 Experimental | Explicitly named, cross-vendor Vulkan proxy path is wired up | Validate provider acceptance, pacing and resize on Intel Arc |
| Intel XeSS Frame Generation | 🔒 Not available | D3D12 presentation foundation exists, but `libxess_fg.dll` and `libxell.dll` are missing | Integrate official runtimes, XeLL lifecycle and Intel proxy swapchain |

## OptiScaler

| Feature | Status | Current state | Still to do |
|---|---|---|---|
| OptiScaler 0.9.4 in the JAR | ✅ Tested | Runtime, license, hash manifest and source notes are bundled and extracted automatically; verified as latest stable release | Re-check on future upstream releases |
| Automatic provider selection | ✅ Tested | AMD defaults to `fsr31`, Intel to `xess`, NVIDIA stays on Streamline | Add further allowed providers only after real runtime tests |
| OptiScaler Frame Generation | 🔒 Not available | OptiScaler's own FG hooks stay disabled so only one presentation owner exists | Re-evaluate only when a safe, exclusive Vulkan path becomes available |

## Rendering, motion and ray tracing

| Feature | Status | Current state | Still to do |
|---|---|---|---|
| Dense motion vectors | ✅ Tested, with limitation | Reversed-Z, current-to-previous, positive Y downward and separate jitter data are defined | Add full motion data for individual entities instead of temporal estimation |
| Motion debug: arrows | ✅ Tested | Camera preview and delayed GPU readback use corrected horizontal and vertical axes | Visually verify on additional aspect ratios and GPUs |
| Motion debug: heatmap | ✅ Tested | Blue, green and red show increasing local motion strength | Optionally add legend directly in the overlay |
| Motion debug: raw preview | ✅ Tested | Unmodified motion values are shown color-coded | Optionally add additional numeric display for developers |
| Motion vector blur | 🟡 Experimental | A Vulkan compute pass samples the reconstructed image along dense motion vectors; strength and samples are limited, unknown output formats fall back safely | Validate image quality and GPU cost at multiple resolutions |
| Dynamic resolution | 🟡 Experimental | Smoothed frame times, hysteresis and aligned world targets gradually regulate toward the target FPS; requires an active temporal upscaler | Tune on NVIDIA, AMD and Intel hardware |
| Safe auto fallback | ✅ Tested policy | When FG is known to be unavailable, the session falls back to reconstruction or native Vulkan present without mixing vendor ABIs | Include additional real providers only after license and hardware tests |
| Depth data | ✅ Tested | Depth belongs atomically to the frame and uses the documented reversed-Z convention | Continue to verify with all renderer/resize combinations |
| Render thread latency | ✅ Tested | Spin-waits removed from VulkanGpuSurfaceMixin and VulkanFrameGenerationPipeline; up to 3ms CPU budget restored per frame | Validate across various frame rates |
| Hybrid Vulkan ray tracing | 🟡 Experimental | Point lights (torches, lanterns, lava) with dynamic ray-traced shadows, RT-AO contact shadows, water Fresnel, emissive glow, colored glass shadows, BLAS/TLAS buffer reuse, vkCmdUpdateBuffer synchronization | Validate and optimize on RTX and ray-query-capable AMD/Intel GPUs |
| Full path tracing | 📅 Planned / out of current scope | The raster renderer currently stays active and the RT result is composited on top | Implement only as a separate, larger renderer project |
| Vulkan -> D3D12 presentation | ✅ Tested, limited | Zero-copy transport on the same GPU, shared fences, DXGI flip swapchain, non-blocking Present mutex, full handle cleanup on errors | Add RTX/AMD/Intel tests and HDR support |
| HDR in the D3D12 presentation path | 🔒 Not available | The production path currently supports SDR; unsupported HDR formats fall back to Vulkan | Implement HDR formats, color spaces and tone mapping lifecycle |

## UI and diagnostics

| Feature | Status | Current state | Still to do |
|---|---|---|---|
| In-game keybindings | ✅ Tested | F8 (OSD toggle), F7 (Frame Gen toggle), End (settings screen) registered via Fabric KeyMappingHelper | Test custom key mapping in controls menu |
| Performance OSD HUD | ✅ Tested | Real-time FPS, base FPS, latency, backend status, NVIDIA Reflex status, and RT status with dynamic box sizing | Verify rendering on high-DPI screens |
| Menu under video settings | ✅ Tested | Mod settings are accessible via video settings and optionally via Mod Menu | Check additional Mod Menu combinations |
| Small windows and GUI scale | ✅ Tested | Content is scrollable; navigation buttons stay reachable | Test additional resolutions and language lengths |
| Mutual FG locks | ✅ Tested | Incompatible frame generation and presentation options cannot be enabled at the same time | Add new providers to the conflict matrix |
| Diagnostics screen | ✅ Tested | Results have visible text, status colors, wrapping and scrolling | Add further user-friendly hints for rare driver errors |
| Runtime status and rejection reason | ✅ Tested | Hardware support, runtime readiness and real provider acceptance are shown separately | Translate additional provider error codes |
| Language support | ✅ Tested | Full English (en_us) and German (de_de) localizations for all settings, tooltips, keybindings, and toast messages | Expand to further community languages |

## Renderer and mod compatibility

| Combination | Status | Current state | Still to do |
|---|---|---|---|
| Vanilla Minecraft 26.3 + Vulkan | 🧩 Compiled, not yet run | Current build target; boots through the Vulkan detection and swapchain hooks | Launch on 26.3 hardware and confirm the render path |
| Vanilla Minecraft 26.2 + Vulkan | ✅ Tested | Last fully validated main path | Keep as the fallback until 26.3 is validated |
| Sodium 0.9.1 + Vulkan | 🟡 Experimental | Uses public Mojang Vulkan resources instead of unstable private Sodium classes | Complete real mod JAR run with resize, reload and Vulkan validation |
| Iris installed + Vulkan, shader pack off | 🟡 Experimental | Should use the same Vulkan path as Sodium; Iris state is detected | Validate real Iris 1.11.2 mod JAR run |
| Active Iris shader pack / OpenGL | 🔒 Not available | Required final images are OpenGL resources instead of Vulkan resources | Only possible with a separate OpenGL/Iris resource integration |
| Aperture material data | 🧩 Foundation present | `RendererIntegration` and `MaterialProvider` define a frame-atomic contract | Implement a real Aperture provider and its material/synchronization path |
| Minecraft 26.3 | 🧩 Compiled, not yet run | Fully ported onto the `renderpearl` API and the SDL windowing layer; all 57 mixin points verified against the 26.3 jar | Launch on 26.3 hardware and validate the full feature matrix |
| Minecraft 26.2 | ✅ Tested | Last fully validated target, including RTX and AMD sessions | Keep as the fallback until 26.3 is validated |

## 2026-09-02 Fix round (P0, P1, P2)

- P0-01: Removed render-thread CPU spin-waits (2ms in `VulkanGpuSurfaceMixin.java`, 1ms in `VulkanFrameGenerationPipeline.java`), freeing up to 3ms CPU budget per frame.
- P0-02: Fixed C++ presentation mutex discipline in `WindowsDxgiPresenter.cpp`. Unlocked mutex during blocking DXGI `Present()` to prevent VSync waits from freezing diagnostic/UI threads, and guarded transitions with `Stage::Unavailable`.
- P0-03: Replaced CPU marker sleep and premature fence submissions in `VulkanRayQueryRenderer.java` with direct `vkCmdUpdateBuffer` and GPU pipeline memory barriers.
- P1-01: Added complete Win32 handle closure (`CloseHandle`) and Vulkan/D3D12 resource destruction on all failure paths in `createSharedTimeline` and `createSharedFrame`.
- P1-02: Implemented BLAS/TLAS storage and scratch buffer reuse in `VulkanRayQueryRenderer.java`. Rebuilding scenes now reuses existing allocations when large enough, eliminating severe VRAM leaks on chunk updates.
- P1-03: Fixed `NativeLoader.java` failure hygiene to prevent retry loops and log spam on fatal linkage errors.
- P2-01: Added in-game keybindings (`F8` for OSD HUD, `F7` for Frame Gen Toggle, `End` for Settings) with live actionbar feedback.
- P2-02: Enriched `FrameGenOsdRenderer.java` with dynamic height, NVIDIA Reflex status, and Hardware Raytracing status.
- P2-03: Added complete English and German localization for all keybindings, toast messages, and OSD entries.
- OptiScaler audit: Checked upstream repository; confirmed v0.9.4 as the current latest stable release.

## 2026-08-22 Fix round

- H1: Drain with `finally` + timeout now reports FAILED instead of blocking.
- H2: Jitter is consistently 0 in mode 0.
- H3: Vendor switch runs via a dedicated UI layer.
- H4: Composite fallback applies when composition is refused.
- H5: Pipeline leak fixed.
- K1/K2: Fence stubs removed or cleanly replaced.
- OOM latch now leads to retry instead of permanent lockout.
- `configVersion=2` plus atomic config save.
- Mixin level raised to JAVA_21.
- JNI exception hygiene tightened.
- D3D12 deadlock fix in hybrid path.
- OptiScaler: external SHA256 check before use.
- copyNative: Ninja path fix in build.
- RT: Dirty chunk budget limits ray tracing rebuilds per frame.
- DRS: adaptive steps for dynamic resolution.

## Recommended next steps

1. Launch the 26.3 build on a Vulkan machine and confirm the game boots, the
   renderer is detected as Vulkan, and frame generation, ray tracing and the
   D3D12 transport still come up on an RTX or AMD GPU.
2. Validate NVIDIA DLSS-G, DLSS-RR, Reflex and the D3D12 path on an RTX 40 GPU
   with resize, VSync, tearing and fullscreen.
3. Validate FSR 3.1 Frame Generation and pacing on an AMD GPU.
4. Validate XeSS Native AA and the named FSR 3.1 fallback on Intel Arc.
5. Check Sodium 0.9.1 and Iris 1.11.2 as real mod JARs in a reproducible
   test instance.
6. Tune dynamic resolution and motion blur on NVIDIA, AMD and Intel hardware.
7. Implement FSR 4 FG or XeSS FG only after receiving and checking the license
   for the official vendor files.

Further technical details are in
[BACKENDS.md](../architecture/BACKENDS.md),
[CAPABILITY_AUDIT.md](../architecture/CAPABILITY_AUDIT.md) and
[SODIUM_IRIS_COMPATIBILITY.md](../compatibility/SODIUM_IRIS_COMPATIBILITY.md).
