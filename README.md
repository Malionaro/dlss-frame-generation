# Vulkan frame generation for Minecraft

[![Build and test](https://github.com/Malionaro/dlss-frame-generation/actions/workflows/build.yml/badge.svg)](https://github.com/Malionaro/dlss-frame-generation/actions/workflows/build.yml)

[English](#english) · [Documentation](docs/README.md) · [Installation](docs/user/INSTALL.md) · [Backend details](docs/architecture/BACKENDS.md) · [Capability audit](docs/architecture/CAPABILITY_AUDIT.md) · [Sodium/Iris status](docs/compatibility/SODIUM_IRIS_COMPATIBILITY.md) · [Feature status](docs/status/FEATURE_STATUS.md)

## English

Fabric client mod for Minecraft 26.3. It hooks Minecraft's Vulkan renderer into NVIDIA Streamline and exposes DLSS, FSR and XeSS behind a settings screen that only enables what your hardware can actually do.

I built it because the new Vulkan renderer finally makes this kind of thing possible. It is still experimental. Expect rough edges. Back up a world before you test it.

> A backend stays off unless its runtime, GPU and renderer inputs are all present. I route AMD and Intel calls away from Streamline on purpose.

> **Ported to 26.3.** Minecraft 26.3 moved its renderer into a new
> `com.mojang.renderpearl` library, turned the core GPU types into interfaces and
> replaced GLFW with SDL. Every hook is mapped onto its 26.3 equivalent, including
> the device-extension request, which now hands Minecraft a `FeatureSet` instead of
> mutating two method arguments. The full old-to-new mapping is in
> [the feature status](docs/status/FEATURE_STATUS.md). This build compiles and all
> 57 mixin points are verified against the 26.3 jar, but it has **not** been run
> on 26.3 hardware — Minecraft 26.2 is still the last fully validated target.

### What works right now

| Backend | Status |
|---|---|
| NVIDIA Streamline 2.14.1 | DLSS super resolution renders the world at the size Streamline asks for, then writes the full display image before the HUD. DLSS-G gets HUD-less color, depth and motion plus a separate transparent UI layer. Reflex pacing (Vulkan via `VK_NV_low_latency2` where exposed), resize handling and ray reconstruction with HDR guides on the latest default transformer preset F are wired up. Dynamic Multi Frame Generation with VSync is supported by the bundled runtime but not yet exposed in the UI. All of it stays behind capability checks. |
| Vulkan to D3D12 present | Windows only, and off by default. Minecraft still renders with Vulkan. Two shared fences move the finished image to a D3D12 queue and a DXGI flip swapchain. No CPU copy in the hot path. Needs a restart. If the checks fail it falls back to normal Vulkan present. This is transport, not a frame gen provider by itself. |
| AMD FSR 4 | Needs `amd_fidelityfx_framegeneration_dx12.dll`. That file is not in the bundled OptiScaler set, so FSR 4 stays off. I would rather show unavailable than fake it. |
| AMD FSR 3.1 | Bundled and signed Vulkan runtime. Proxy swapchain and prepare/configure get real color, depth and dense motion vectors from the renderer. It only goes live after the provider accepts the inputs. I still need driver and pacing tests on real AMD hardware. |
| Intel XeSS-FG / Intel GPUs | Same D3D12 transport, but `libxess_fg.dll` and `libxell.dll` are missing. So native XeSS-FG stays off. On Intel the Vulkan path can use the FSR 3.1 fallback instead. It is named clearly as a fallback. |
| OptiScaler 0.9.4 bridge | Bundled. On AMD it picks `fsr31`, on Intel `xess`. It loads before FidelityFX and catches the mod's FFX Vulkan calls. NVIDIA never touches it and stays on Streamline. All quality modes use the split world and display targets. This still does not make XeSS-FG appear on Vulkan. |
| Compatibility layer | Mixins talk to a vendor-neutral provider API with a clear lifecycle and one owner for present. Frame identities are atomic, so color, depth, motion and swapchain generation from different frames cannot mix. There are optional renderer and material hooks for Aperture. They are empty on purpose, not forgotten. |
| Hardware ray tracing | Vulkan acceleration structures and ray queries. Real `VoxelShape` boxes for blocks and fluids, depth rejection, and invalidation on block changes. Only runs if the GPU exposes `VK_KHR_acceleration_structure` and `VK_KHR_ray_query`. I think the 48 block radius is the sweet spot. More just hurts. |

### Build

You need on Windows:

- Java 25
- CMake 3.25 or newer
- Visual Studio 2022 Build Tools with C++ workload and a Windows 10/11 SDK

The repo already contains the Streamline and FidelityFX headers and the runtime files you need to distribute. Gradle builds the JNI bridge, compiles the shaders, runs tests and packs the jar.

```powershell
.\gradlew.bat build
```

You get `build/libs/dlss-frame-generation-1.0.0.jar` (141.5 MB with all native DLLs bundled).

### Source layout

This is a client only mod with Loom's split source sets. All runtime code lives in `src/client/java`. `compileJava NO-SOURCE` is normal. The real compile is `compileClientJava`.

The tree is small and grouped by job:

- `client/backend`, `config`, `frame` and `render` hold the core
- `client/render/adapter` holds adapters for vanilla, Sodium and the OpenGL fallback
- `client/interop` holds native and vendor code
- `client/motion` and `raytracing` hold the GPU work
- `client/ui` holds settings, keybindings, OSD and diagnostics
- `native/bridge` is split into `fidelityfx`, `presentation`, `routing` and `streamline`
- shaders live in `motion` and `raytracing`
- docs live in `user`, `architecture`, `compatibility` and `status`

See [docs/README.md](docs/README.md) for the full map.

Audited on 2026-09-02:

- all 79 client files are in the jar
- both entrypoints and all 16 mixins are registered
- all 53 native methods have a matching JNI export
- every `.cpp` file is part of the CMake target
- all four shaders are loaded by real code and by tests
- all 62 unit tests across 18 test classes pass
- render-thread busy spin-waits removed (saves up to 3ms CPU time per frame)
- DXGI Present mutex unlocked during VSync wait, eliminating UI thread deadlocks
- ray query uniforms use vkCmdUpdateBuffer with memory barriers instead of CPU sleeps
- BLAS and TLAS storage and scratch buffers are reused across chunk rebuilds, fixing VRAM leaks
- no duplicate zip entries
- dead class `UltrawideLensCorrector` removed

`RendererIntegration` and `MaterialProvider` have no Aperture code yet. That is intentional. They are the typed boundary described in the [Sodium/Iris plan](docs/compatibility/SODIUM_IRIS_COMPATIBILITY.md), not leftovers. Build output, worlds, IDE files and logs are ignored via `.gitignore`.

### OptiScaler that ships inside

You do not need to download OptiScaler separately. The jar contains the verified 0.9.4 runtime, its GPLv3 text, source notes, third party licenses and a SHA-256 manifest. Checked on 2026-09-02: v0.9.4 is the latest official stable release. On AMD or Intel the mod extracts it to a temp folder and sets it up. It is on by default. NVIDIA never loads it.

Only for developers who want to override it:

```powershell
.\tools\install-optiscaler.ps1
```

If `config/dlss_fg/optiscaler/OptiScaler.dll` exists, the mod uses that file instead. The loader fixes paths, turns on FFX inputs and turns off DXGI and Streamline spoofing when the ini still says `auto`. Your own values stay untouched.

Only use builds from the [official OptiScaler repo](https://github.com/optiscaler/OptiScaler). OptiScaler itself warns about multiplayer and anti-cheat. Take that seriously.

### Run it in IntelliJ

1. Open as Gradle project, wait for sync.
2. Pick the **Minecraft Client** run config at the top.
3. Hit run.

Do not run `net.fabricmc.devlaunchinjector.Main.main()` from the gutter. The shared run config sets the right launch args and classpath.

If IntelliJ complains about missing `launch.cfg`, run once:

```powershell
.\gradlew.bat ideaSyncTask
```

Then reimport.

### In game controls and shortcuts

You can configure settings through the menu or use direct in-game shortcuts:

- **F8**: Toggles the Performance OSD HUD. Shows real-time FPS, base FPS, frame multiplier, pacing latency, backend status, NVIDIA Reflex mode, and Hardware Raytracing status.
- **F7**: Toggles Frame Generation on and off immediately with live actionbar feedback.
- **End**: Opens the Multi-Vendor Settings Screen directly without opening pause menus.
- All shortcuts can be customized in the Minecraft Controls menu under **"DLSS Frame Generation"**.

Menu access: Open `Options... -> Video Settings... -> Frame Generation...` (or via Mod Menu).

The screen works in small windows. Use the wheel or the arrows next to **Done**.

AMD and Intel now show Native AA plus the scaled quality modes. Dynamic resolution only touches the world target of an active temporal upscaler. It keeps a 1/3 floor and a target FPS you can set. Motion blur runs after reconstruction and has a strength slider. Safe auto fallback turns off a FG request that is known to be unavailable for this session and keeps rendering through reconstruction or plain Vulkan present. It never mixes vendor ABIs.

Frame gen and graphics are separate sections. Graphics has two subtabs, hardware ray tracing and Vulkan to D3D12. The second shows the real bridge stage, adapter LUID and runtime audit. The switch only becomes active after the same-adapter D3D12 and Vulkan external memory checks pass.

On hardware that supports it, ray tracing does this:

- turns on acceleration structures, ray queries and buffer device address when the Vulkan device is created
- turns nearby block and fluid `VoxelShape` boxes into a block-colored AABB scene sorted by camera distance
- builds BLAS and TLAS on the GPU, reusing existing storage and scratch buffers across scene rebuilds to prevent VRAM allocation spikes
- synchronizes uniform frame data via vkCmdUpdateBuffer and pipeline barriers directly in the command stream
- drops hits that sit behind raster geometry, then traces shadows and optional reflections in a compute shader before DLSS and HUD
- writes low res HDR guides, albedo, specular, normal and roughness and hit distance for ray reconstruction on NVIDIA
- keeps the raster renderer running and composites on top

Radius is capped at 48 blocks. `RT Distance` sets it, `RT Quality` sets reflection reach, `RT Samples` sets shadow rays, `RT Stable Sampling` bumps the minimum to four. The scene rebuilds after you move, after nearby block changes and on a slow timer. This is hybrid tracing, not full path tracing. It is impressive when it lines up, but also a bit fragile. I like it, I also do not fully trust it yet.

The switches stay disabled without `VK_KHR_ray_query`. A GTX 1660 SUPER does not have it. You need RTX 20 or newer, or an AMD or Intel GPU with ray query.

Motion debug cycles through `OFF`, `ARROWS`, `HEATMAP` and `RAW PREVIEW`. It only shows in a loaded world, in the HUD. Not in menus. When the real RG16F image is ready, the overlay reads it with a sparse delayed readback after present. No more `vkDeviceWaitIdle` every frame. Before the first readback you get a live camera preview. Arrows use current to previous vectors with fixed GUI axes, so they follow where you look. Raw preview shows the same values as colors. Heatmap is simple, blue is slow, green is mid, red is fast.

### Diagnostics

The settings screen has **Run Diagnostics**. What you see depends on GPU, renderer and native runtime. Seeing an option does not mean the vendor can actually do frame generation. The report separates info and skipped from pass and fail, wraps long native messages, and only says hardware validated after a provider accepted a frame and finished present.

See [docs/architecture/BACKENDS.md](docs/architecture/BACKENDS.md) for the real contracts and what is still open.

The AMD part is the signed `amd_fidelityfx_vk.dll` from the verified OptiScaler 0.9.4 bundle, used through the public FidelityFX Vulkan API. License is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

