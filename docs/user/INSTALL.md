# Installation & requirements

---

## Guide

### System Requirements

| Component | Minimum | Recommended |
|---|---|---|
| **OS** | Windows 10/11 (64-bit) | Windows 11 (64-bit) |
| **GPU** | Vulkan 1.3 Compatible GPU | Development/testing hardware; see backend status below |
| **VRAM** | 4 GB VRAM | 8+ GB VRAM |
| **Java** | Java 25 | Java 25 (Corretto / Temurin) |
| **Minecraft** | 26.2 | 26.2 |
| **Fabric** | 0.155.2+ | 0.155.2+ |

### Step-by-Step Installation

#### Step 1: Install Fabric Loader & API
1. Install **Fabric Loader 0.155.2** (or newer) for Minecraft 26.2.
2. Download **Fabric API 0.155.2+** (or newer) and place it into your `.minecraft/mods` directory.

#### Step 2: Download Mod Artifacts
1. Build or download `dlss-frame-generation-1.0.0.jar`.
2. Copy `dlss-frame-generation-1.0.0.jar` into `.minecraft/mods/`.
3. **Automated DLL Extraction:** All native Streamline SDK DLLs (`sl.interposer.dll`, `sl.dlss_g.dll`, etc.) and FidelityFX/OptiScaler runtimes are bundled directly inside the `.jar` and extract automatically upon launching the game!

#### Step 3: In-Game Settings and Controls
1. Launch Minecraft 26.2.
2. Open menu: **Options... -> Video Settings... -> Frame Generation...**
   (or via **ModMenu**, or press **End** in game).
3. Use shortcuts while playing:
   - **F8**: Toggle the Performance OSD HUD (FPS, latency, Reflex and RT status).
   - **F7**: Toggle Frame Generation on/off with actionbar notifications.
   - **End**: Open the Frame Generation settings screen directly.
   - Keybindings are customizable in Minecraft Controls under **"DLSS Frame Generation"**.
4. Check the backend status shown by the settings screen. An unavailable
   capability cannot be enabled by selecting a preset.

> **Backend status:** This revision is experimental. NVIDIA Streamline and its
> Vulkan interposer are bundled; frame generation remains fail-closed until
> Streamline accepts all mandatory render inputs. The FidelityFX FSR 3.1
> Vulkan proxy is bundled for AMD and as a named cross-vendor fallback on Intel
> GPUs. An optional same-adapter Vulkan→D3D12 presenter is included, but the
> bundled files do not contain the official FSR 4 FG or XeSS-FG/XeLL provider
> runtimes. Those two providers therefore remain unavailable. See
> [BACKENDS.md](../architecture/BACKENDS.md).

#### Optional hybrid Vulkan → D3D12 presentation

On Windows, open **Frame Generation → Graphics → Vulkan → D3D12**. The toggle
becomes available only after the mod confirms the exact same GPU by LUID and
all Vulkan external-memory/semaphore requirements. Enabling it requires a game
restart.

This mode keeps Minecraft rendering on Vulkan and moves only presentation to a
DXGI flip-model swapchain using GPU-shared images and fences. It is experimental,
SDR-only and automatically falls back to normal Vulkan WSI when setup fails.
It cannot be combined with a Vulkan frame-generation proxy. The transport alone
does not enable FSR 4 FG or XeSS-FG.

#### Bundled OptiScaler

OptiScaler v0.9.4 and its required Vulkan/upscaler runtime files are already
inside the mod JAR. Copying the single mod JAR is sufficient. On AMD/Intel it is
extracted and configured automatically; NVIDIA does not load it.

For development only, the helper can install an external override:

```powershell
.\tools\install-optiscaler.ps1
```

The current Minecraft render path uses the bridge for real 1:1 Native AA plus
Quality, Balanced, Performance, Ultra Performance and Ultra Quality via split
world/display targets (see [BACKENDS.md](../architecture/BACKENDS.md)).
A manually installed `config/dlss_fg/optiscaler/OptiScaler.dll` overrides the
bundled version.
