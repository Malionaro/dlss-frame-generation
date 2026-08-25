# Feature status and roadmap

Status: August 22, 2026  
Tested Minecraft target: **26.2**

This overview shows which features have already been proven to work,
which still need real hardware tests and which are only prepared or
planned. A visible setting is not automatically available on every GPU:
The mod only enables features when GPU, driver, renderer,
native runtime and all required frame data have been accepted.

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
| Single mod JAR | ✅ Tested | Java code, JNI bridge, shaders and allowed runtime files are packaged together | Define release build and version number for publication |
| JNI binding | ✅ Tested | All 53 Java native declarations have matching JNI exports | Cover new native functions in the same checks |
| Fabric registration | ✅ Tested | Both entrypoints and all 16 mixins are registered | Re-check for new Minecraft versions |
| Provider selection | ✅ Tested | Exactly one FG provider, one upscaler and one presentation path may be active at the same time | Integrate additional providers into the same exclusivity logic |
| Atomic frame data | ✅ Tested | Color, depth, motion, frame ID and swapchain generation are not mixed between frames | Additionally validate with real Sodium/Iris runs |
| Safe fallback | ✅ Tested | Unsupported paths stay disabled or fall back to normal Vulkan WSI | Collect additional driver and error cases |

## NVIDIA

| Feature | Status | Current state | Still to do |
|---|---|---|---|
| GPU and Streamline detection | ✅ Tested | NVIDIA device, Streamline runtime and individual capabilities are checked separately | Test on multiple RTX generations |
| DLSS Super Resolution / DLAA | 🟡 Experimental | Quality modes, optimal render resolution, upscaling before the native HUD and history reset are wired up | Validate image quality, resize, fullscreen and all quality modes on RTX hardware |
| DLSS Frame Generation | 🟡 Experimental | Color without HUD, depth, motion and separate UI layer are passed to Streamline; active status only after provider acceptance | Complete RTX 40 hardware test with VSync, tearing, resize and fullscreen |
| NVIDIA Reflex | 🟡 Experimental | Pacing and Streamline Reflex path are wired up | Measure latency and markers on supported RTX hardware |
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
| OptiScaler 0.9.4 in the JAR | ✅ Tested | Runtime, license, hash manifest and source notes are bundled and extracted automatically | Re-check version, hashes and licenses on updates |
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
| Hybrid Vulkan ray tracing | 🟡 Experimental | BLAS/TLAS, ray queries, block shadows, reflections, raster depth test and scene invalidation are implemented | Validate and optimize on RTX and ray-query-capable AMD/Intel GPUs |
| Full path tracing | 📅 Planned / out of current scope | The raster renderer currently stays active and the RT result is composited on top | Implement only as a separate, larger renderer project |
| Vulkan -> D3D12 presentation | ✅ Tested, limited | Zero-copy transport on the same GPU, shared fences, DXGI flip swapchain, resize and clean fallback were tested on GTX 1660 SUPER | Add RTX/AMD/Intel tests and HDR support |
| HDR in the D3D12 presentation path | 🔒 Not available | The production path currently supports SDR; unsupported HDR formats fall back to Vulkan | Implement HDR formats, color spaces and tone mapping lifecycle |

## UI and diagnostics

| Feature | Status | Current state | Still to do |
|---|---|---|---|
| Menu under video settings | ✅ Tested | Mod settings are accessible via video settings and optionally via Mod Menu | Check additional Mod Menu combinations |
| Small windows and GUI scale | ✅ Tested | Content is scrollable; navigation buttons stay reachable | Test additional resolutions and language lengths |
| Mutual FG locks | ✅ Tested | Incompatible frame generation and presentation options cannot be enabled at the same time | Add new providers to the conflict matrix |
| Diagnostics screen | ✅ Tested | Results have visible text, status colors, wrapping and scrolling | Add further user-friendly hints for rare driver errors |
| Runtime status and rejection reason | ✅ Tested | Hardware support, runtime readiness and real provider acceptance are shown separately | Translate additional provider error codes |
| Language support | 🟡 Experimental | Core controls and descriptions are localized | Translate all new status messages completely into every supported language |

## Renderer and mod compatibility

| Combination | Status | Current state | Still to do |
|---|---|---|---|
| Vanilla Minecraft 26.2 + Vulkan | ✅ Tested target architecture | Currently compiled and tested main path | Test broader GPU/driver matrix |
| Sodium 0.9.1 + Vulkan | 🟡 Experimental | Uses public Mojang Vulkan resources instead of unstable private Sodium classes | Complete real mod JAR run with resize, reload and Vulkan validation |
| Iris installed + Vulkan, shader pack off | 🟡 Experimental | Should use the same Vulkan path as Sodium; Iris state is detected | Validate real Iris 1.11.2 mod JAR run |
| Active Iris shader pack / OpenGL | 🔒 Not available | Required final images are OpenGL resources instead of Vulkan resources | Only possible with a separate OpenGL/Iris resource integration |
| Aperture material data | 🧩 Foundation present | `RendererIntegration` and `MaterialProvider` define a frame-atomic contract | Implement a real Aperture provider and its material/synchronization path |
| Minecraft 26.3 | 🧩 Foundation present | Documented as the next target, but not claimed as currently tested | Check and version renderer classes, mixins, frame boundaries and ABI separately |

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

1. Validate NVIDIA DLSS-G, DLSS-RR, Reflex and the D3D12 path on an RTX 40 GPU
   with resize, VSync, tearing and fullscreen.
2. Validate FSR 3.1 Frame Generation and pacing on an AMD GPU.
3. Validate XeSS Native AA and the named FSR 3.1 fallback on Intel Arc.
4. Check Sodium 0.9.1 and Iris 1.11.2 as real mod JARs in a reproducible
   test instance.
5. Tune dynamic resolution and motion blur on NVIDIA, AMD and Intel hardware.
6. Create separate, version-protected Minecraft 26.3 hooks.
7. Implement FSR 4 FG or XeSS FG only after receiving and checking the license for the official vendor files.

Further technical details are in
[BACKENDS.md](../architecture/BACKENDS.md),
[CAPABILITY_AUDIT.md](../architecture/CAPABILITY_AUDIT.md) and
[SODIUM_IRIS_COMPATIBILITY.md](../compatibility/SODIUM_IRIS_COMPATIBILITY.md).
