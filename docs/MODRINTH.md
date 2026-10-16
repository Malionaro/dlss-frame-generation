# Vulkan Frame Generation

**Experimental Fabric client mod for Minecraft 26.3 that connects Minecraft's Vulkan renderer to NVIDIA DLSS, DLSS Frame Generation, Reflex, AMD FSR 3.1 and hardware ray tracing.**

> **Important:** This project is experimental. A feature only turns on when your GPU, driver, Vulkan device and the bundled runtime all report full support. Anything unsupported stays off and says why. It never silently switches to a different technology.

## Highlights

- **DLSS Multi Frame Generation up to 6×** on RTX 50 (2× on RTX 40, whatever your driver reports)
- **DLSS Super Resolution and DLAA** with a truly reduced world resolution and a full-resolution output
- **Sharp distance:** texture mip bias and a jitter sequence matched to the upscale factor, as NVIDIA's DLSS guide asks for
- **Native-resolution HUD and menus**, kept out of the generated frames
- **NVIDIA Reflex** low latency, with an optional frame cap
- **Shader packs on Vulkan:** works together with Iris 1.12 (Vulkan) and Vitrail, so frame generation runs on top of your shader pack
- **Hybrid hardware ray tracing** with block-textured reflections, alpha-tested shadows through leaves and flowers, and coloured point lights
- **AMD FSR 3.1** Vulkan route, plus a named FSR 3.1 fallback on Intel
- **Settings screen with sliders** and its own page inside Sodium's video settings
- **F3 debug lines** for frame generation, frame times and the exact upscaling resolution

## NVIDIA DLSS and Frame Generation

On RTX GPUs the mod integrates NVIDIA Streamline 2.14 directly into Minecraft's Vulkan lifecycle.

- **Super Resolution** renders the world at the size DLSS asks for and keeps the exact window aspect ratio, so the image does not wobble when dynamic resolution steps.
- **Presets:** uses the DLSS 4.5 transformer models (K for DLAA/Quality/Balanced, M for Performance, L for Ultra Performance).
- **Frame Generation** gets HUD-less colour, depth, dense motion vectors and a separate UI layer. The multiplier is a slider from 2× up to your driver's maximum.
- **In menus**, the pause menu, options and Sodium turn frame generation and camera jitter off, so the blurred background no longer swims. Chat and inventories keep it on.
- **Dynamic resolution** can hold a target FPS while an upscaler is active.

## Shader packs

Iris 1.12 runs shader packs on Vulkan. This mod cooperates with it instead of fighting it.

- **Iris + shader pack:** frame generation and Reflex run on the pack's finished image; the mod's own ray tracing stays off because the pack lights the world itself. Iris has no render-scale hook, so DLSS cannot render the pack below window size. The extra FPS come from frame generation.
- **Packs with their own ray tracing** (for example Rethinking Voxels) work the same way: the pack's RT, plus frame generation from this mod.
- **Vitrail:** DLSS drives Vitrail's own render scale and replaces its FSR 1 upscale, so here you get real upscaling with shaders.
- **Auto mode:** when a pack starts, frame generation (and DLSS, where the engine allows it) switches on automatically. You can change everything afterwards, or turn the auto mode off.

## Hardware ray tracing

Hybrid ray tracing on top of the normal renderer, for GPUs with `VK_KHR_ray_query` (RTX 20 or newer, ray-query-capable AMD and Intel GPUs).

- **Shadows** from the sun, with soft samples and RT ambient occlusion
- **Reflections** on water, ice and metal that show the reflected blocks' real textures
- **Alpha-tested shadows:** light passes through holes in leaves, flowers, grass and panes
- **Point lights** from torches, lanterns and lava, each with its own ray-traced shadow
- **Streamed per chunk section:** the scene is built on background threads, 16×16×16 blocks at a time, each section with its own acceleration structure. Walking only adds new sections and breaking a block rebuilds only its section, so there are no full-world rebuild stutters.
- Radius up to 48 blocks.

This is hybrid tracing, not full path tracing.

## Settings and controls

- **Options → Video Settings → Frame Generation**, the **End** key, or Mod Menu
- With Sodium: a **Frame Generation** page inside Sodium's video settings
- Tabs for Frame Generation, Upscaling, Graphics and Status; multipliers, distances and sharpness are sliders
- **F3**: output FPS with frame generation, base FPS, frame times, and a line like `Upscaling: DLSS Quality | 1696x954 → 2560x1440 (66%) | DRS 100% | Mip bias -0.50`
- **F8** performance overlay · **F7** toggle frame generation
- Built-in diagnostics and motion-vector debug views

## Requirements

- Minecraft **26.3** with the **Vulkan** graphics backend
- Fabric Loader **0.19.5+** and Fabric API
- Windows 10/11 64-bit, Java 25
- Optional: Sodium 0.9, Iris 1.12 (Vulkan build), Vitrail
- For DLSS Frame Generation: RTX 40 or RTX 50 · For Multi Frame Generation: RTX 50 · For ray tracing: a GPU with `VK_KHR_ray_query`

All native files (Streamline, DLSS, FidelityFX, OptiScaler) are inside the single mod JAR and extract automatically.

## Feature status

| Feature | Status | Notes |
|---|---|---|
| DLSS Frame Generation | ✅ Runs on RTX 5080 | Provider accepts every frame; 2× to 6× slider; off in menus. |
| DLSS Super Resolution / DLAA | 🟡 Experimental | Runs on RTX 5080; the stability and sharpness fixes are new and need more testing. |
| NVIDIA Reflex | ✅ Runs | Low-latency mode works; the frame cap only applies with pacing on. |
| Iris 1.12 (Vulkan) + shader pack | ✅ Runs | Frame generation on the pack's image; no upscaling with Iris; mod RT off. |
| Vitrail shader packs | 🟡 Experimental | DLSS drives Vitrail's render scale. |
| Sodium 0.9 + Vulkan | ✅ Runs | Own settings page; terrain texture fixes apply with Sodium too. |
| Hybrid hardware ray tracing | 🟡 Experimental | New per-section scene with textures; needs RTX testing. |
| DLSS Ray Reconstruction | 🟡 Experimental | Guides are connected; not validated yet. |
| AMD FSR 3.1 Frame Generation | 🟡 Experimental | Vulkan proxy swapchain connected; needs AMD hardware tests. |
| FSR 3.1 fallback on Intel | 🟡 Experimental | Named cross-vendor fallback; needs Intel Arc tests. |
| Vulkan → D3D12 presentation | 🟡 Experimental | Windows only, SDR, off by default. |
| AMD FSR 4 / Intel XeSS Frame Generation | 🔒 Not available | The official runtimes are not redistributable in this mod. |
| OpenGL backend | 🔒 Not supported | Everything here needs Minecraft's Vulkan renderer. |

> A visible option does not guarantee activation. A feature becomes active only after the GPU, driver, native runtime, renderer inputs and presentation path all pass their checks. The Status tab and the diagnostics tell you exactly what is missing.

## Thanks

A big thank you to **KawasakiMaxi** for testing the mod on real hardware and giving detailed feedback. Many of the fixes in this release exist because of those test sessions.

Back up your worlds before testing. Source, build instructions and the detailed status are on GitHub.
