# Feature status and roadmap

Status: October 16, 2026  
Compiled and tested Minecraft target: **26.3**  
Test hardware so far: **RTX 5080** (driver 616.64), Windows 11

This overview shows which features have already been proven to work,
which still need real hardware tests and which are only prepared or
planned. A visible setting is not automatically available on every GPU:
the mod only enables features when GPU, driver, renderer,
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
| Java, native and shader build | ✅ Tested | Local Windows build and GitHub Actions with Java 25; 95 unit tests in 24 classes, including shaderc compiles of all four compute shaders | Keep CI green |
| Single mod JAR | ✅ Tested | Java code, JNI bridge, shaders and allowed runtime files in `dlss-frame-generation-0.1.0-alpha.jar` (about 148 MB) | Ready for distribution |
| JNI binding | ✅ Tested | Every Java native declaration has a matching JNI export | Cover new native functions in the same checks |
| Fabric registration | ✅ Tested | Both entrypoints, the Sodium config entrypoint and all 20 mixins are registered | Re-check for new Minecraft versions |
| Provider selection | ✅ Tested | Exactly one FG provider, one upscaler and one presentation path may be active at the same time | Integrate additional providers into the same exclusivity logic |
| Atomic frame data | ✅ Tested | Color, depth, motion, frame ID and swapchain generation are not mixed between frames | Keep validating with shader-pack engines |
| Safe fallback | ✅ Tested | Unsupported paths stay disabled or fall back to normal Vulkan WSI; a frame the pipeline skipped is blitted to the screen instead of leaving the last image (camera freeze after alt-tab) | Collect additional driver and error cases |

## NVIDIA

| Feature | Status | Current state | Still to do |
|---|---|---|---|
| GPU and Streamline detection | ✅ Tested | NVIDIA device, Streamline 2.14.1 runtime and individual capabilities are checked separately | Test on RTX 20/30/40 |
| DLSS Frame Generation | ✅ Tested on RTX 5080 | HUD-less colour, depth, motion and a separate UI layer; provider accepted every frame in testing; multiplier slider 2× up to the driver maximum (6× on RTX 50); off while a menu covers the world | Validate VSync, tearing, resize and fullscreen; test RTX 40 (2×) |
| DLSS Super Resolution / DLAA | 🟡 Experimental, runs on RTX 5080 | DLSS 4.5 presets K/M/L; aspect-exact even render sizes; DLSS history kept across dynamic-resolution steps; jitter only while DLSS accepts frames; terrain mip bias `log2(render/output)`; jitter sequence `8 × scale²` | Confirm the wobble and distance-sharpness fixes in game; verify the jitter sign |
| NVIDIA Reflex | 🟡 Experimental | Low latency and Boost; the Reflex frame limiter only applies with Reflex pacing on, and is lifted when pacing is turned off | Measure latency on RTX hardware |
| DLSS Ray Reconstruction | 🟡 Experimental | HDR, albedo, specular, normal/roughness and hit-distance guides are connected | Test quality and stability with hardware RT |
| GTX support | ✅ Tested, limited | GTX 1660 SUPER is correctly detected; missing DLSS-G and ray query are rejected | No plan to enable unsupported RTX features |
| DLSS 5 | 📅 Planned | Needs a public Streamline DLSS 5 plugin; the mod already produces the colour and motion inputs it needs | Integrate once NVIDIA publishes the SDK module |

## AMD

| Feature | Status | Current state | Still to do |
|---|---|---|---|
| FSR 3.1 Vulkan Frame Generation | 🟡 Experimental | Signed FidelityFX Vulkan runtime, proxy swapchain and Prepare/Configure calls are wired up | Validate provider acceptance, pacing, resize, fullscreen and driver on AMD hardware |
| FSR 3.1 Native AA | 🟡 Experimental | Standardized FFX inputs and a separate output target are present | Real AMD image quality and stability tests |
| FSR 4 Frame Generation | 🔒 Not available | D3D12 transport foundation exists, but `amd_fidelityfx_framegeneration_dx12.dll` is not included | Obtain official redistributable runtime, check license and integrate real provider |

## Intel

| Feature | Status | Current state | Still to do |
|---|---|---|---|
| XeSS / FSR Native AA via OptiScaler | 🟡 Experimental | OptiScaler selects `xess` on Intel | Test on Intel Arc |
| FSR 3.1 fallback on Intel | 🟡 Experimental | Explicitly named, cross-vendor Vulkan proxy path is wired up | Validate provider acceptance, pacing and resize on Intel Arc |
| Intel XeSS Frame Generation | 🔒 Not available | D3D12 presentation foundation exists, but `libxess_fg.dll` and `libxell.dll` are missing | Integrate official runtimes, XeLL lifecycle and Intel proxy swapchain |

## OptiScaler

| Feature | Status | Current state | Still to do |
|---|---|---|---|
| OptiScaler 0.9.4 in the JAR | ✅ Tested | Runtime, license, hash manifest and source notes are bundled and extracted automatically | Re-check on future upstream releases |
| Automatic provider selection | ✅ Tested | AMD defaults to `fsr31`, Intel to `xess`, NVIDIA stays on Streamline | Add further providers only after real runtime tests |
| OptiScaler Frame Generation | 🔒 Not available | OptiScaler's own FG hooks stay disabled so only one presentation owner exists | Re-evaluate only with a safe, exclusive Vulkan path |

## Rendering, motion and ray tracing

| Feature | Status | Current state | Still to do |
|---|---|---|---|
| Dense motion vectors | ✅ Tested, with limitation | Reversed-Z, current-to-previous, positive Y downward and separate jitter data; exact translation for up to 64 moving entities (bounding box and movement since the last frame, camera-relative double-precision matrices); camera-only motion after a resize, in menus and with shader packs | Limb animation and entity rotation are not covered; validate in game |
| Motion debug views | ✅ Tested | Arrows, heatmap and raw preview with corrected axes and delayed GPU readback | Optionally add a legend |
| Motion vector blur | 🟡 Experimental | Compute pass along dense motion vectors after reconstruction | Validate quality and GPU cost |
| Dynamic resolution | 🟡 Experimental | Smoothed frame times and hysteresis toward the target FPS; aspect-exact sizes; render-size changes resize only the render images; off while a shader pack runs | Tune on NVIDIA, AMD and Intel hardware |
| Depth data | ✅ Tested | Depth belongs atomically to the frame and uses reversed-Z; Iris keeps reversed-Z for the main pass | Keep verifying with renderer and resize combinations |
| Hybrid Vulkan ray tracing | 🟡 Experimental | Scene streamed per 16³ section on two mesher threads; one BLAS per section; per-slot TLAS and section address table; textured reflections from the block atlas; alpha-tested cutout shadows; sun shadows, RT-AO, point lights, water Fresnel, glass; radius up to 48 blocks around the camera; rays follow the DLSS jitter and the real projection (sprint/spyglass FOV); section-local boxes and a camera-centred scene origin keep it exact far from world origin | Validate on RTX; triangle geometry for stairs/fences; denoising with DLSS-RR |
| Full path tracing | 📅 Planned / out of current scope | The raster renderer stays active and RT is composited on top | Separate, larger renderer project |
| Vulkan -> D3D12 presentation | ✅ Tested, limited | Zero-copy transport on the same GPU, shared fences, DXGI flip swapchain | Add RTX/AMD/Intel tests and HDR support |
| HDR in the D3D12 presentation path | 🔒 Not available | SDR only; unsupported HDR formats fall back to Vulkan | Implement HDR formats, colour spaces and tone mapping |

## UI and diagnostics

| Feature | Status | Current state | Still to do |
|---|---|---|---|
| Settings screen | 🟡 Experimental | Tabs Frame Generation, Upscaling, Graphics and Status; sliders for multiplier, RT distance, samples and sharpness; info box or tooltips depending on window height | Test small windows and long translations |
| Sodium settings page | 🟡 Experimental | Frame Generation page in Sodium 0.9 video settings with the same options; RT options locked while a shader pack runs | Test with further Sodium releases |
| F3 debug lines | 🟡 Experimental | Output FPS with FG, base FPS, frame times, technology/multiplier, upscaling `render → output`, DRS scale and mip bias | Confirm values in game |
| Keybindings | ✅ Tested | F8 OSD, F7 frame-generation toggle, End settings; rebindable | Test custom mappings |
| Performance OSD | ✅ Tested | FPS, base FPS, latency, backend, Reflex and RT status | Planned: rework in the new design |
| Diagnostics screen | ✅ Tested | Visible text, status colours, wrapping and scrolling | More hints for rare driver errors |
| Language support | ✅ Tested | Full English and German | Further community languages |

## Renderer and mod compatibility

| Combination | Status | Current state | Still to do |
|---|---|---|---|
| Minecraft 26.3 + Vulkan | ✅ Runs on RTX 5080 | Ported to `renderpearl` and SDL; DLSS-G and DLSS SR run | Full feature matrix on more GPUs |
| Sodium 0.9 + Vulkan | 🟡 Experimental, runs | Tested with 0.9.3-beta; the terrain mip bias reaches Sodium terrain through the vanilla sampler | Resize and reload tests |
| Iris 1.12 (Vulkan) without a pack | 🟡 Experimental | Normal path with all features | Longer sessions |
| Iris 1.12 (Vulkan) with a shader pack | 🟡 Experimental, runs | Cooperative: frame generation and Reflex on the pack's image, HUD in the UI layer, mod RT off; world target never swapped (Iris crashes on a reduced target), so no upscaling | Confirm the join crash fix; test more packs |
| Packs with their own RT (Rethinking Voxels) | 🟡 Experimental | Same cooperative path as other Iris packs | Test in game |
| Vitrail | 🟡 Experimental | DLSS/FSR drives Vitrail's render scale and replaces its FSR 1 upscale | Its render-pass crash still reproduces; check without this mod |
| Shader-pack auto mode | 🟡 Experimental | On pack start: frame generation on, plus DLSS Quality where the engine can scale; once per start; can be disabled | Gather feedback |
| OpenGL backend | 🔒 Not supported | Everything here needs Vulkan images | Not planned |
| Aperture material data | 🧩 Foundation present | `RendererIntegration` and `MaterialProvider` define a frame-atomic contract | Real Aperture provider |

## 26.3 port notes

Minecraft 26.3 replaced the renderer abstraction this mod hooks into.
`com.mojang.blaze3d.systems` and `com.mojang.blaze3d.vulkan` were extracted into
a new `com.mojang.renderpearl` library, several core GPU types became
interfaces, and the windowing layer moved from GLFW to SDL:

| 26.2 hook | 26.3 equivalent |
|---|---|
| `com.mojang.blaze3d.vulkan.*` | `com.mojang.renderpearl.backend.vulkan.*` |
| `blaze3d.systems.GpuDevice` (class, `backend` field) | `renderpearl.frontend.FrontendGpuDevice` (the `backend` field moved to the implementation) |
| `blaze3d.systems.GpuDeviceBackend` | `renderpearl.backend.api.GpuDeviceBackend` |
| `blaze3d.systems.CommandEncoder`, `textures.GpuTexture(View)`, `shaders.ShaderSource` | `renderpearl.api.commands` / `.api.textures` / `.api.pipeline` |
| `VulkanBackend.createDevice(Collection<String>, VulkanPhysicalDevice, Set<VulkanFeature>)` | private `createDevice(FeatureSet, VulkanPhysicalDevice)`, hooked at its call site inside `createDevice(GpuDebugOptions)` |
| `VulkanBackend.REQUIRED_DEVICE_EXTENSIONS`, `VulkanBackend.VK12_FEATURES_STRUCT` | `VulkanFeatureSets` |
| `VulkanDevice(ShaderSource, …, Set<String>, …)` | `VulkanDevice(VulkanInstance, …, FeatureSet, …)` |
| `GLFWNativeWin32.glfwGetWin32Window` | `SDLVideo.SDL_GetWindowProperties` + `SDL_PROP_WINDOW_WIN32_HWND_POINTER` |
| `InputConstants.Type.KEYSYM` + `GLFW_KEY_*` | `InputConstants.Type.KEYBOARD` + `SDL_SCANCODE_*` |
| `GameRenderer.render(DeltaTracker, boolean)` | `GameRenderer.render()` |
| `Minecraft.screen` | `Minecraft.gui.screen()` |

Iris 1.12 also changes the Vulkan device features (tessellation, geometry
shaders, pipeline libraries) at the `FeatureSet` constructor; this mod adds its
features later at the `createDevice` call, so both sets survive.

## 2026-10-16 changes

- DLSS stability: aspect-exact, even render sizes; history kept across DRS steps; jitter only while DLSS accepts frames.
- DLSS distance sharpness: terrain mip bias and a jitter sequence matched to the upscale factor.
- Frame generation up to 6× (driver maximum) in the settings screen and on the Sodium page.
- F3: frame times and the upscaling line.
- Menus: frame generation, jitter and colour-search motion off while a menu covers the world.
- Alt-tab camera freeze: skipped pipeline frames are blitted to the screen.
- Reflex frame limiter only with pacing on.
- Iris 1.12 Vulkan shader packs: cooperative path, fail-safe ownership check (no reduced target for Iris), no DLSS over full-resolution pack images, camera-only motion.
- Shader-pack auto mode for frame generation and upscaling.
- Ray tracing: per-section streaming scene, textured reflections, alpha-tested shadows; the old full-scan scene that froze the game is gone.
- New settings design with sliders and a Sodium page.

## Recommended next steps

1. Confirm in game: no wobble with DLSS and DRS, sharper distance, calm menu backgrounds, no freeze after alt-tab.
2. Test the per-section RT scene on RTX: reflections show textures, leaf shadows have holes, no stutter when walking or breaking blocks.
3. Test Iris 1.12 with several packs, joining worlds and switching packs at runtime.
4. Validate DLSS-RR together with the new RT scene.
5. Validate FSR 3.1 frame generation on AMD and the FSR 3.1 fallback on Intel Arc.
6. Next RT stage: triangle geometry from block models, DLSS-RR denoising.

Further technical details are in
[BACKENDS.md](../architecture/BACKENDS.md),
[CAPABILITY_AUDIT.md](../architecture/CAPABILITY_AUDIT.md) and
[SODIUM_IRIS_COMPATIBILITY.md](../compatibility/SODIUM_IRIS_COMPATIBILITY.md).
