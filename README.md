# Vulkan Frame Generation

[![Build and test](https://github.com/Malionaro/dlss-frame-generation/actions/workflows/build.yml/badge.svg)](https://github.com/Malionaro/dlss-frame-generation/actions/workflows/build.yml)

[Documentation](docs/README.md) · [Installation](docs/user/INSTALL.md) · [Feature status](docs/status/FEATURE_STATUS.md) · [Backends](docs/architecture/BACKENDS.md) · [Capability audit](docs/architecture/CAPABILITY_AUDIT.md) · [Sodium/Iris](docs/compatibility/SODIUM_IRIS_COMPATIBILITY.md)

**Experimental Fabric client mod for Minecraft 26.3 that connects Minecraft's Vulkan renderer to NVIDIA DLSS, DLSS Frame Generation, Reflex, AMD FSR 3.1 and hardware ray tracing.**

> **Important:** This project is experimental. A feature only turns on when your GPU, driver, Vulkan device and the bundled runtime all report full support. Anything unsupported stays off and says why. It never silently switches to a different technology. Back up your worlds before testing.

## Highlights

- **DLSS Multi Frame Generation up to 6×** on RTX 50 (2× on RTX 40, whatever the driver reports)
- **DLSS Super Resolution and DLAA** with a truly reduced world resolution and a full-resolution output
- **Sharp distance:** texture mip bias and a jitter sequence matched to the upscale factor
- **Native-resolution HUD and menus**, kept out of the generated frames
- **NVIDIA Reflex** low latency, with an optional frame cap
- **Shader packs on Vulkan:** cooperates with Iris 1.12 (Vulkan) and Vitrail
- **Hybrid hardware ray tracing** with textured reflections, alpha-tested shadows and point lights, streamed per chunk section
- **AMD FSR 3.1** Vulkan route, plus a named FSR 3.1 fallback on Intel
- **Settings screen with sliders** and its own page in Sodium's video settings
- **F3 debug lines** for frame generation, frame times and the exact upscaling resolution

## NVIDIA DLSS and Frame Generation

On RTX GPUs the mod integrates NVIDIA Streamline 2.14.1 directly into Minecraft's Vulkan lifecycle.

- **Super Resolution** renders the world into a reduced target, then DLSS writes the full display image before the HUD. Render sizes keep the exact window aspect ratio, because Minecraft's projection uses the window size; independent rounding of width and height used to make the picture wobble at every dynamic-resolution step.
- **Presets:** DLSS 4.5 transformer models: K for DLAA, Quality and Balanced; M for Performance; L for Ultra Performance.
- **Distance detail:** the terrain sampler gets a mip bias of `log2(render / output)`, so textures resolve with the detail of the output resolution. The Halton jitter sequence is `8 × (output / render)²` phases long (8 at DLAA, 32 at Performance, 72 at Ultra Performance), so every output pixel gets sampled.
- **Frame Generation** gets HUD-less colour, depth, dense motion vectors and a transparent UI layer. The multiplier is a slider from 2× up to the driver maximum (`numFramesToGenerate = multiplier − 1`).
- **Menus:** while a menu covers the world (pause, options, Sodium), frame generation and camera jitter are off, and motion comes from the camera only. Chat and inventories keep everything on.
- **Dynamic resolution** holds a target FPS while an upscaler is active, and keeps the DLSS history across steps.
- **Reflex** runs low latency with or without Boost; its frame limiter only applies when Reflex pacing is on.

## Shader packs

| Engine | What the mod does |
|---|---|
| **Iris 1.12 (Vulkan) with a pack** | Frame generation and Reflex run on the pack's finished image; the HUD goes into the DLSS-G UI layer. The mod never swaps the world target: Iris builds its colour and depth buffers from different sources and crashes on a reduced target, and it has no render-scale hook, so there is no upscaling with Iris. Mod RT stays off because the pack does its own lighting. |
| **Packs with their own RT** (e.g. Rethinking Voxels) | Same as above: the pack's ray tracing plus this mod's frame generation. |
| **Vitrail** | DLSS/FSR drives Vitrail's render scale and replaces its FSR 1 upscale, so shaders get real upscaling. |
| **Iris without a pack, or no Iris** | Normal path with everything available. |

When a pack starts, **Shader packs: auto DLSS + FG** switches frame generation on (and DLSS where the engine can render below window size). It only switches features on that are off and supported, once per pack start; the player can change everything afterwards or disable the auto mode in the Graphics tab.

## Hardware ray tracing

Hybrid ray tracing on top of the normal renderer, for GPUs with `VK_KHR_acceleration_structure` and `VK_KHR_ray_query` (RTX 20 or newer, ray-query-capable AMD and Intel GPUs).

- **Streamed per 16³ section.** The render thread copies a few sections' block states per frame; two mesher threads turn them into boxes with per-face sprites from Minecraft's baked models and biome tint. Each section owns one BLAS. Each frame slot owns a TLAS over all resident sections and a table of their box addresses, rebuilt only when the set changed, after the slot's fence proved it idle. Walking adds sections, a block edit rebuilds one section, and nothing waits for the device.
- **Shading:** sun shadows with soft samples, RT ambient occlusion, point lights from emissive blocks with their own shadows, water Fresnel, metal and glass.
- **Textures:** reflections sample the block atlas, so they show real blocks. Cutout blocks (leaves, flowers, grass, panes) are alpha-tested, including the far face seen through a hole.
- **Guides** for DLSS Ray Reconstruction: HDR colour, albedo, specular, normal/roughness and hit distance.
- Radius up to 48 blocks. Off while a shader pack runs. This is hybrid tracing, not full path tracing.

## AMD and Intel

- **AMD FSR 3.1** frame generation through the signed `amd_fidelityfx_vk.dll` and its Vulkan proxy swapchain, plus Native AA.
- **Intel** uses the same FSR 3.1 path as a clearly named cross-vendor fallback.
- **OptiScaler 0.9.4** is bundled for the AMD/Intel upscaling route. NVIDIA never loads it.
- **AMD FSR 4 FG and Intel XeSS FG** stay unavailable: their official runtimes (`amd_fidelityfx_framegeneration_dx12.dll`, `libxess_fg.dll`, `libxell.dll`) are not part of this mod.

## Settings and controls

- **Options → Video Settings → Frame Generation**, the **End** key, or Mod Menu. With Sodium there is a **Frame Generation** page in Sodium's video settings.
- Tabs: **Frame Generation**, **Upscaling**, **Graphics** (shader packs, ray tracing, Vulkan → D3D12) and **Status**. Multipliers, distances and sharpness are sliders.
- **F3** shows the output FPS, base FPS, frame times and an upscaling line, e.g. `Upscaling: DLSS Quality | 1696x954 → 2560x1440 (66%) | DRS 100% | Mip bias -0.50`. If the upscaler did not accept the frame, the line says so.
- **F8** performance overlay · **F7** toggle frame generation · all keys can be rebound under "DLSS Frame Generation".
- **Run Diagnostics** separates info, skipped, pass and fail, and only reports "hardware validated" after a provider accepted a frame and finished present.
- **Motion debug** cycles `OFF`, `ARROWS`, `HEATMAP` and `RAW PREVIEW`.

## Requirements

- Minecraft **26.3** with the **Vulkan** graphics backend
- Fabric Loader **0.19.5+** and Fabric API
- Windows 10/11 64-bit, Java 25
- Optional: Sodium 0.9, Iris 1.12 (Vulkan build), Vitrail, Mod Menu
- DLSS Frame Generation: RTX 40 or 50 · Multi Frame Generation: RTX 50 · Ray tracing: `VK_KHR_ray_query`

All native files (Streamline, DLSS, FidelityFX, OptiScaler and the JNI bridge) are inside the single mod JAR and extract automatically. See [Installation](docs/user/INSTALL.md).

## Feature status

| Feature | Status | Notes |
|---|---|---|
| DLSS Frame Generation | ✅ Runs on RTX 5080 | Provider accepts every frame; 2× to 6×; off in menus. |
| DLSS Super Resolution / DLAA | 🟡 Experimental | Runs on RTX 5080; the aspect, mip bias and jitter fixes need more testing. |
| NVIDIA Reflex | 🟡 Experimental | Low latency works; frame cap only with pacing on. |
| Iris 1.12 (Vulkan) + shader pack | 🟡 Experimental | Frame generation on the pack's image; no upscaling with Iris; mod RT off. |
| Vitrail | 🟡 Experimental | DLSS drives the render scale. |
| Sodium 0.9 + Vulkan | 🟡 Experimental | Own settings page; mip bias reaches Sodium terrain too. |
| Hybrid hardware ray tracing | 🟡 Experimental | Per-section scene with textures is new; needs RTX testing. |
| DLSS Ray Reconstruction | 🟡 Experimental | Guides connected; not validated. |
| AMD FSR 3.1 Frame Generation | 🟡 Experimental | Needs AMD hardware tests. |
| FSR 3.1 fallback on Intel | 🟡 Experimental | Needs Intel Arc tests. |
| Vulkan → D3D12 presentation | 🟡 Experimental | Windows only, SDR, off by default. |
| AMD FSR 4 / Intel XeSS Frame Generation | 🔒 Not available | Official runtimes not bundled. |
| OpenGL backend | 🔒 Not supported | Everything here needs the Vulkan renderer. |

The detailed table with what is left to do per feature is in [docs/status/FEATURE_STATUS.md](docs/status/FEATURE_STATUS.md).

## For developers

### Build

On Windows you need Java 25, CMake 3.25+ and Visual Studio 2022 Build Tools with the C++ workload and a Windows 10/11 SDK. The repo contains the Streamline and FidelityFX headers and the redistributable runtime files. Gradle builds the JNI bridge, compiles the shaders, runs the tests and packs the JAR:

```powershell
.\gradlew.bat build
```

The result is `build/libs/dlss-frame-generation-1.0.0.jar` (about 148 MB with every native DLL bundled). The build currently runs 95 unit tests in 24 classes, including a shaderc compile of all four compute shaders.

### Source layout

Client-only mod with Loom's split source sets: all runtime code is in `src/client/java`, so `compileJava NO-SOURCE` is normal and the real compile is `compileClientJava`.

- `client/backend`, `config`, `frame`, `render`: core pipeline, resolution, jitter, dynamic resolution
- `client/render/adapter`: vanilla, Sodium and OpenGL-fallback adapters
- `client/compat`: shader-pack engines (Iris, Vitrail, Vulcade), Sodium config page, auto mode
- `client/motion`: motion vectors, frame-generation inputs, motion blur
- `client/raytracing` and `client/raytracing/scene`: ray-query renderer, section streaming, meshing, sprite table
- `client/ui`: settings screen, OSD, F3 lines, diagnostics
- `client/mixin`: 20 mixins (see `dlss_fg.mixins.json`)
- `native/bridge`: `fidelityfx`, `presentation`, `routing` and `streamline`
- shaders in `assets/dlss_fg/shaders/source/{motion,raytracing}`

See [docs/README.md](docs/README.md) for the documentation map.

### Run in IntelliJ

Open the project as a Gradle project, pick the **Minecraft Client** run configuration and run it. Do not start `net.fabricmc.devlaunchinjector.Main.main()` from the gutter. If IntelliJ misses `launch.cfg`, run `.\gradlew.bat ideaSyncTask` once and reimport.

### Bundled OptiScaler

The JAR contains the verified OptiScaler 0.9.4 runtime with its GPLv3 text, source notes, third-party licenses and a SHA-256 manifest. On AMD and Intel it is extracted and configured automatically. A `config/dlss_fg/optiscaler/OptiScaler.dll` overrides it (`.\tools\install-optiscaler.ps1` installs one for development). Only use builds from the [official OptiScaler repository](https://github.com/optiscaler/OptiScaler), and take its multiplayer and anti-cheat warnings seriously.

### Licenses

Third-party components and their licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
