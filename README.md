<p align="center"><img src="./src/main/resources/assets/dlss_fg/icon.png" alt="Vulkan Frame Generation logo" width="128"></p>
<h1 align="center">Vulkan Frame Generation</h1>

<h1 align="center">
<a href="https://github.com/Malionaro/dlss-frame-generation/releases"><img alt="GitHub release" src="https://img.shields.io/badge/release-0.1.1--alpha-orange?logo=github"></a>
<a href="https://github.com/Malionaro/dlss-frame-generation/actions/workflows/build.yml"><img alt="Build and test" src="https://github.com/Malionaro/dlss-frame-generation/actions/workflows/build.yml/badge.svg"></a>
</h1>

> [!IMPORTANT]
> This is an **alpha**. A feature only turns on when your GPU, driver, Vulkan device and the bundled runtime all report full support; anything unsupported stays off and tells you why. Back up your worlds before testing.

### Welcome to Vulkan Frame Generation!

Minecraft 26.3 finally renders with Vulkan, and that opens the door to the same tech modern games use. This mod hooks into Minecraft's Vulkan renderer and connects it to **NVIDIA DLSS Super Resolution, DLSS Multi Frame Generation, Reflex**, **AMD FSR 3.1** and **hybrid hardware ray tracing**.

The world renders at a lower resolution, DLSS rebuilds it at full size, and frame generation inserts up to five extra frames between every real one, while the HUD and menus stay sharp at native resolution. It also works on top of Vulkan shader packs from Iris 1.12 and Vitrail. Install it, switch Minecraft to Vulkan, and press **End** in game.

| Aspect | Detail |
|---|---|
| ![Minecraft](https://img.shields.io/badge/minecraft-26.3-5CB85C) | Fabric Loader 0.19.5+ and Fabric API, Vulkan backend |
| ![Java](https://img.shields.io/badge/java-25-8A2BE2) | Java 25, Windows 10/11 64-bit |
| ![Client side](https://img.shields.io/badge/client%20side-yes-5CB85C) | No server install, works in single-player and on servers |
| ![Bundled](https://img.shields.io/badge/natives-bundled-blue) | Streamline, DLSS, FidelityFX and OptiScaler are inside the single JAR |

## Supported hardware

| Status | Feature | Hardware |
|---|---|---|
| Runs | DLSS Frame Generation up to 6× | RTX 50 (RTX 40: 2×), tested on RTX 5080 |
| Alpha | DLSS Super Resolution / DLAA, Reflex | RTX 20 and newer |
| Alpha | Hybrid hardware ray tracing | GPUs with `VK_KHR_ray_query` (RTX 20+, ray-query AMD/Intel) |
| Alpha | FSR 3.1 Frame Generation | AMD; Intel through a named FSR 3.1 fallback |
| Not available | FSR 4 FG, XeSS FG | Official runtimes are not redistributable here |

| Status | Mod | What happens |
|---|---|---|
| Alpha | Sodium 0.9 | Own **Frame Generation** page in Sodium's video settings |
| Alpha | Iris 1.12 (Vulkan) with a shader pack | Frame generation on the pack's image; no upscaling, mod RT off |
| Alpha | Vitrail | DLSS drives Vitrail's render scale, so shaders get real upscaling |
| Not supported | OpenGL backend | Everything here needs Minecraft's Vulkan renderer |

## Things worth knowing

- **Switch Minecraft to Vulkan first.** Under OpenGL the mod stays inactive and says so in its Status tab.
- **A visible option is not a promise.** The Status tab and **Run Diagnostics** show exactly which check failed: GPU, driver, runtime or renderer inputs.
- **Shader packs and upscaling.** Iris has no render-scale hook, so with an Iris pack the extra FPS come from frame generation only. When a pack starts, frame generation switches on automatically (Graphics → *Shader packs: auto DLSS + FG*).
- **Menus are calm on purpose.** Frame generation and camera jitter pause while the pause menu, options or Sodium cover the world.
- **F3 tells you what is happening:** output FPS, base FPS, frame times and a line like `Upscaling: DLSS Quality | 1696x954 → 2560x1440 (66%) | DRS 100% | Mip bias -0.50`.
- **Ray tracing is hybrid,** not full path tracing, with a radius of up to 48 blocks. It stays off while a shader pack runs.
- **Keys:** End opens the settings, F7 toggles frame generation, F8 shows the performance overlay. All keys can be rebound.

## What 0.1.1-alpha adds

- Exact motion vectors for moving entities, including limbs, heads and turning bodies, so frame generation and DLSS smear much less around mobs and players.
- A reactive mask for particles, fire and animated water (DLSS bias hint, FSR reactive mask).
- DLSS Ray Reconstruction switches on with ray tracing; RT now follows the DLSS jitter, the real field of view and the camera, and stays exact far from the world origin.
- NVIDIA Streamline 2.14.16 with DLSS 310.9.16.

Full notes: [docs/release-notes/v0.1.1-alpha.md](docs/release-notes/v0.1.1-alpha.md).

## What 0.1.0-alpha fixed and added

The first alpha for 26.3 addressed, among others:

- The picture wobbling with DLSS and dynamic resolution: render sizes now keep the exact window aspect, the DLSS history survives resolution steps, and jitter only runs while DLSS accepts frames.
- Blurry, shimmering distance: a texture mip bias and a jitter sequence matched to the upscale factor.
- Menu backgrounds swimming behind the pause menu and Sodium.
- The camera freezing after alt-tabbing back into the game, and the image freezing at Ultra Performance.
- A crash when joining a world with an Iris shader pack.
- Ray tracing freezing the game while walking: the scene is now streamed per chunk section on background threads, with real block textures in reflections and alpha-tested shadows through leaves.
- The Reflex frame limiter capping FPS even with pacing off.

The full notes are in [docs/release-notes/v0.1.0-alpha.md](docs/release-notes/v0.1.0-alpha.md), the detailed status per feature in [docs/status/FEATURE_STATUS.md](docs/status/FEATURE_STATUS.md).

## Reporting issues

Please include your GPU and driver version, Minecraft version, the full mod list (Sodium, Iris and shader pack name if used), your DLSS and frame-generation settings, and the relevant part of `latest.log`. A screenshot with **F3** open helps a lot, and for crashes attach the file from `crash-reports`.

## Building from source

Windows with Java 25, CMake 3.25+ and Visual Studio 2022 Build Tools (C++ workload, Windows 10/11 SDK):

```powershell
.\gradlew.bat build
# jar lands in build/libs/dlss-frame-generation-<version>.jar
```

Gradle builds the JNI bridge, compiles the shaders, runs 95 unit tests (including a shaderc compile of every compute shader) and packs everything into one JAR. In IntelliJ, open the project as Gradle project and use the **Minecraft Client** run configuration. More in [Installation](docs/user/INSTALL.md), [Backends](docs/architecture/BACKENDS.md) and the [documentation index](docs/README.md).

## Thanks

A big thank you to **KawasakiMaxi** for testing the mod on real hardware and giving detailed feedback. Many of the fixes in 0.1.0-alpha exist because of those test sessions.

## Licence

Custom source-available licence, see [`LICENSE.txt`](LICENSE.txt): you may read, fork and contribute, but not publish builds or the code without permission. See [CONTRIBUTING.md](CONTRIBUTING.md). Third-party components (NVIDIA Streamline, AMD FidelityFX, OptiScaler) keep their own licences, listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
