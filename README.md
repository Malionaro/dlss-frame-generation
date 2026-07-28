# Vulkan Frame Generation Integration for Minecraft

[English](#english) · [Deutsch](#deutsch) ·
[Installation](docs/INSTALL.md) · [Backend details](docs/BACKENDS.md) ·
[Capability audit](docs/CAPABILITY_AUDIT.md)

## English

Experimental Fabric client mod for Minecraft **26.2**. It connects Minecraft's
Vulkan renderer with NVIDIA Streamline and provides a capability-gated settings
screen for NVIDIA DLSS, AMD FSR and Intel XeSS frame-generation backends.

> This remains experimental software. A backend stays disabled unless its
> native runtime, GPU support and required renderer inputs are all available.
> AMD and Intel requests are never sent to NVIDIA Streamline.

### Current backend status

| Backend | Status |
|---|---|
| NVIDIA Streamline 2.12 | True DLSS Super Resolution renders the world at Streamline's optimal input size and writes a display-resolution result before a native-resolution HUD is drawn. DLSS-G receives rotating HUD-less colour/depth/motion slots plus a separate transparent UI layer. Reflex pacing, resize resets and DLSS Ray Reconstruction with HDR/material guides are connected and remain capability-gated. |
| AMD FSR 4 | The current public FSR 4 provider path is **DirectX 12**, not a native replacement for Minecraft's `VkSwapchainKHR`. The mod therefore keeps FSR 4 out of its Vulkan frame-generation labels. |
| AMD FidelityFX FSR 3.1 | The signed FidelityFX Vulkan runtime is bundled. Proxy-swapchain presentation and the prepare/configure dispatches receive renderer-owned colour, depth and dense motion vectors. Activation remains gated until the provider accepts them; driver and pacing tests on AMD hardware are still required. |
| Intel XeSS-FG / Intel GPUs | Native XeSS-FG remains unavailable because Intel exposes it only through D3D12, `IDXGISwapChain4` and XeLL. On Intel hardware the Vulkan swapchain can instead use the explicitly named cross-vendor **FSR 3.1 fallback**. The UI never labels this fallback as XeSS-FG. |
| Bundled OptiScaler 0.9.4 bridge | On AMD it selects the `fsr31` Vulkan upscaler; on Intel it selects `xess`. It is loaded before FidelityFX and intercepts the mod's standard FFX Vulkan upscaling calls. NVIDIA stays on native Streamline. The render path currently exposes **Native AA (1:1)** only; the Vulkan bridge does not make XeFG available. |
| Expandable compatibility layer | Minecraft's swapchain mixins now call a vendor-neutral provider API. Provider IDs cover NVIDIA Streamline Vulkan, AMD FSR 3.1 Vulkan, cross-vendor FSR 3.1 Vulkan, and future AMD FSR 4 / Intel XeSS-FG D3D12 routes. New providers can be added without rewriting the renderer mixins. |
| Hybrid hardware ray tracing | Implemented with Vulkan acceleration structures and hardware ray queries for block shadows and reflections. Exact block/fluid `VoxelShape` boxes, raster-depth rejection and block-change invalidation are used. It activates only when the GPU exposes `VK_KHR_acceleration_structure` and `VK_KHR_ray_query`. |

### Build

Requirements for a clean source build on Windows:

- Java 25;
- CMake 3.25 or newer;
- Visual Studio 2022 Build Tools with the Desktop development with C++ workload
  and a Windows 10/11 SDK.

The repository includes the required Streamline/FidelityFX headers and
redistributable runtime files. Gradle builds the JNI bridge, compiles and tests
the Vulkan shaders, runs the unit tests and then packages the single mod JAR.

```powershell
.\gradlew.bat build
```

The resulting mod JAR is written to:

`build/libs/dlss-frame-generation-1.0-SNAPSHOT.jar`

### Bundled OptiScaler

No separate OptiScaler download is required. The mod JAR contains the verified
v0.9.4 runtime, its GPLv3 text, corresponding-source directions, dependency
licenses and SHA-256 manifest. On AMD/Intel, the runtime is extracted to the
mod's temporary native directory and configured automatically. It is enabled by
default; NVIDIA never loads it and stays on Streamline.

The helper remains available only for developers who deliberately want an
external override:

```powershell
.\tools\install-optiscaler.ps1
```

An external `config/dlss_fg/optiscaler/OptiScaler.dll` takes precedence over
the embedded copy. The loader resolves library/plugin paths, enables FFX inputs
and disables DXGI and Streamline GPU spoofing when those values are still
`auto`; explicit custom values are preserved.

Only use releases from the
[official OptiScaler repository](https://github.com/optiscaler/OptiScaler).
OptiScaler itself warns against use in multiplayer/anti-cheat environments.

### Development launch (IntelliJ IDEA)

1. Open the project as a Gradle project and let IntelliJ finish its Gradle sync.
2. Select the **Minecraft Client** run configuration in the top toolbar.
3. Start that configuration.

Do not start `net.fabricmc.devlaunchinjector.Main.main()` directly from the
editor gutter. The shared **Minecraft Client** configuration provides the
required Fabric/Loom launch configuration and classpath.

If IntelliJ reports a missing `launch.cfg`, run this once from the project root
and refresh the Gradle project:

```powershell
.\gradlew.bat ideaSyncTask
```

### In-game settings and debug overlay

Open the settings screen via:

`Options... → Video Settings... → Frame Generation...`

The entry is at the bottom of the scrollable Video Settings list. Mod Menu can
also open the same screen when it is installed.

The settings screen adapts to small window sizes. Use the mouse wheel or the
arrow buttons beside **Done** to reach every option.

The mod screen has separate **Frame Gen** and **Graphics** sections. The
Graphics section contains **Hardware Raytracing** with persistent mode,
quality, distance and sampling settings. On supported hardware the mod:

- enables acceleration structures, ray queries and buffer device addresses
  during Vulkan device creation;
- converts exact nearby block and fluid `VoxelShape` boxes into a
  camera-prioritized, block-coloured AABB scene;
- builds a BLAS and TLAS on the GPU;
- rejects ray hits hidden behind closer raster geometry, then traces shadows
  and optional reflections in a compute shader before DLSS/HUD work;
- produces low-resolution HDR noisy colour, albedo, specular albedo,
  normal/roughness and specular-hit-distance guides for DLSS Ray
  Reconstruction on supported NVIDIA hardware;
- leaves the normal raster renderer active and composites the RT result over it.

The scene radius is capped at 48 blocks. `RT Distance` controls that radius,
`RT Quality` controls reflection reach, `RT Samples` controls soft-shadow rays,
and `RT Stable Sampling` raises the minimum sample count to four. The scene
refreshes after movement, nearby block updates and a low-frequency safety
interval.
This is hybrid ray tracing, not full path tracing.

The controls are disabled on GPUs without Vulkan ray-query support. For example,
the GeForce GTX 1660 SUPER does not expose `VK_KHR_ray_query`; an RTX 20-series
or newer NVIDIA GPU, or a Vulkan ray-query capable AMD/Intel GPU, is required.

**Motion Debug** cycles through `OFF`, `ARROWS`, `HEATMAP` and `RAW PREVIEW`.
The overlay is rendered only while a world is loaded; it is intentionally not
shown in the main menu or in settings screens. When the renderer-owned
motion-vector image is available, the modes use a delayed sparse GPU readback
of the real RG16F image after the corresponding present has completed; it no
longer forces `vkDeviceWaitIdle` every debug frame. Before the first readback,
the overlay falls back to a live camera-motion preview. Arrows use the SDK's
current-to-previous vectors
with corrected GUI axes, so they follow camera look direction vertically and
horizontally. RAW Preview color-encodes the same unmodified values. The heatmap
shows local motion magnitude: blue is slow, green is medium and red is fast.

### Diagnostics

The settings screen includes **Run Diagnostics**. Backend availability depends
on the detected GPU, the renderer and the native runtime. A visible option does
not guarantee that vendor frame generation can be activated.

For the detailed backend contracts and remaining implementation work, see
[BACKENDS.md](docs/BACKENDS.md).

The packaged AMD component is the signed `amd_fidelityfx_vk.dll` distributed
with the verified OptiScaler 0.9.4 bundle and used through the public
FidelityFX Vulkan API. Its license is reproduced in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Deutsch

Experimentelle Fabric-Client-Mod für Minecraft **26.2**. Sie verbindet den
Vulkan-Renderer von Minecraft mit NVIDIA Streamline und stellt einen
Capability-basierten Einstellungsbildschirm für NVIDIA DLSS, AMD FSR und Intel
XeSS bereit.

> Die Mod bleibt experimentell. Ein Backend bleibt deaktiviert, solange
> native Laufzeit, GPU-Unterstützung oder erforderliche Renderer-Daten fehlen.
> AMD- und Intel-Anfragen werden niemals an NVIDIA Streamline weitergeleitet.

### Backend-Status

| Backend | Status |
|---|---|
| NVIDIA Streamline 2.12 | Echtes DLSS Super Resolution rendert die Welt in der von Streamline gelieferten optimalen Eingabeauflösung und schreibt das Ergebnis in voller Anzeigeauflösung, bevor das HUD nativ gerendert wird. DLSS-G erhält rotierende HUD-lose Farb-/Tiefen-/Motion-Slots plus eine getrennte transparente UI-Ebene. Reflex-Pacing, Resize-Resets und DLSS Ray Reconstruction mit HDR-/Material-Guides sind angebunden und Capability-gesteuert. |
| AMD FSR 4 | Der aktuelle öffentliche FSR-4-Providerpfad verwendet **DirectX 12** und ist kein nativer Ersatz für Minecrafts `VkSwapchainKHR`. Die Mod bezeichnet ihren Vulkan-FG-Pfad deshalb nicht als FSR 4. |
| AMD FidelityFX FSR 3.1 | Die signierte FidelityFX-Vulkan-Laufzeit ist enthalten. Proxy-Swapchain sowie Prepare-/Configure-Dispatch erhalten Farbe, Tiefe und dichte Motion Vectors aus dem Renderer. Aktiviert wird erst nach Annahme durch den Provider; Treiber- und Pacing-Tests auf AMD-Hardware stehen noch aus. |
| Intel XeSS-FG / Intel-GPUs | Echtes XeSS-FG bleibt nicht verfügbar, weil Intel dafür ausschließlich D3D12, `IDXGISwapChain4` und XeLL bereitstellt. Auf Intel-Hardware kann die Vulkan-Swapchain stattdessen den ausdrücklich benannten **FSR-3.1-Fallback** verwenden. Die UI bezeichnet diesen Fallback nicht als XeSS-FG. |
| Mitgelieferte OptiScaler-0.9.4-Bridge | Auf AMD wählt sie den Vulkan-Upscaler `fsr31`, auf Intel `xess`. Sie wird vor FidelityFX geladen und fängt die standardisierten FFX-Vulkan-Upscaling-Aufrufe der Mod ab. NVIDIA bleibt bei nativem Streamline. Der Renderpfad bietet derzeit ausschließlich **Native AA (1:1)**; XeFG wird dadurch unter Vulkan nicht verfügbar. |
| Erweiterbarer Kompatibilitäts-Layer | Die Minecraft-Swapchain-Mixins verwenden jetzt eine herstellerneutrale Provider-API. Provider-IDs existieren für NVIDIA Streamline Vulkan, AMD FSR 3.1 Vulkan, den herstellerübergreifenden FSR-3.1-Vulkan-Fallback sowie zukünftige AMD-FSR-4-/Intel-XeSS-FG-D3D12-Routen. Neue Provider benötigen keine erneute Änderung der Renderer-Mixins. |
| Hybrides Hardware-Raytracing | Mit Vulkan-Beschleunigungsstrukturen und Hardware-Ray-Queries für Blockschatten und Reflexionen umgesetzt. Exakte Block-/Fluid-`VoxelShape`-Boxen, Raster-Tiefenprüfung und Blockänderungs-Invalidierung werden verwendet. Aktivierung nur mit `VK_KHR_acceleration_structure` und `VK_KHR_ray_query`. |

### Build

Für einen sauberen Quellcode-Build unter Windows werden benötigt:

- Java 25;
- CMake 3.25 oder neuer;
- Visual Studio 2022 Build Tools mit „Desktop development with C++“ und einem
  Windows-10/11-SDK.

Das Repository enthält die benötigten Streamline-/FidelityFX-Header und
weiterverteilbaren Laufzeitdateien. Gradle baut die JNI-Bridge, kompiliert und
testet die Vulkan-Shader, führt die Unit-Tests aus und erstellt anschließend die
einzelne Mod-JAR.

```powershell
.\gradlew.bat build
```

Die fertige Mod-JAR liegt anschließend unter:

`build/libs/dlss-frame-generation-1.0-SNAPSHOT.jar`

### Mitgeliefertes OptiScaler

Ein separater OptiScaler-Download ist nicht mehr erforderlich. Die Mod-JAR
enthält die geprüfte v0.9.4-Laufzeit, den GPLv3-Lizenztext, Hinweise zum
zugehörigen Quellcode, die Lizenzen der Laufzeitabhängigkeiten und ein
SHA-256-Manifest. Auf AMD/Intel wird sie automatisch in den temporären
Native-Ordner der Mod entpackt und eingerichtet. Sie ist standardmäßig
aktiviert; NVIDIA lädt sie nie und bleibt auf Streamline.

Das Hilfsskript bleibt nur für Entwickler erhalten, die bewusst eine externe
Version verwenden möchten:

```powershell
.\tools\install-optiscaler.ps1
```

Eine externe Datei unter
`config/dlss_fg/optiscaler/OptiScaler.dll` überschreibt die eingebettete
Version. Der Loader löst Bibliotheks-/Plugin-Pfade auf, aktiviert FFX-Eingaben
und deaktiviert DXGI- und Streamline-GPU-Spoofing, solange diese Werte noch auf
`auto` stehen. Explizite eigene Werte bleiben erhalten.

Verwende ausschließlich Releases aus dem
[offiziellen OptiScaler-Repository](https://github.com/optiscaler/OptiScaler).
OptiScaler selbst warnt vor der Nutzung in Multiplayer-/Anti-Cheat-Umgebungen.

### Entwicklungsstart mit IntelliJ IDEA

1. Öffne das Projekt als Gradle-Projekt und warte, bis der Gradle-Sync beendet ist.
2. Wähle oben in der Werkzeugleiste die Startkonfiguration **Minecraft Client**.
3. Starte diese Konfiguration.

Starte nicht `net.fabricmc.devlaunchinjector.Main.main()` direkt über den
grünen Pfeil im Editor. Die geteilte Konfiguration **Minecraft Client** setzt
die benötigte Fabric-/Loom-Startkonfiguration und den Klassenpfad.

Falls IntelliJ eine fehlende `launch.cfg` meldet, führe im Projektordner einmal
diesen Befehl aus und aktualisiere danach das Gradle-Projekt:

```powershell
.\gradlew.bat ideaSyncTask
```

### In-Game-Menü und Debug-Overlay

Öffne den Einstellungsbildschirm über:

`Optionen... → Video Settings... → Frame Generation...`

Der Eintrag befindet sich unten in der scrollbaren Liste der
Videoeinstellungen. Wenn Mod Menu installiert ist, kann es denselben Bildschirm
ebenfalls öffnen.

Der Einstellungsbildschirm passt sich kleinen Fenstern an. Mit Mausrad oder den
Pfeiltasten neben **Fertig** erreichst du alle Optionen.

Der Mod-Bildschirm besitzt getrennte Bereiche für **Frame Gen** und
**Graphics**. Unter Graphics liegt **Hardware Raytracing** mit gespeicherten
Einstellungen für Modus, Qualität, Distanz und Sampling. Auf
unterstützter Hardware erledigt die Mod Folgendes:

- Aktivierung von Acceleration Structures, Ray Queries und Buffer Device
  Addresses beim Erstellen des Vulkan-Geräts;
- Umwandlung exakter naher Block- und Fluid-`VoxelShape`-Boxen in eine von der
  Kamera aus priorisierte, blockfarbige AABB-Szene;
- Aufbau einer BLAS und TLAS auf der GPU;
- Verwerfen von Ray-Hits hinter näherer Rastergeometrie und Raytracing von
  Schatten sowie optionalen Reflexionen vor DLSS und HUD;
- Erzeugung niedrig aufgelöster HDR-, Albedo-, Specular-, Normal/Roughness-
  und Specular-Hit-Distance-Guides für DLSS Ray Reconstruction auf
  unterstützter NVIDIA-Hardware;
- Kombination des Ergebnisses mit dem weiterhin aktiven Raster-Renderer.

Der Szenenradius ist auf 48 Blöcke begrenzt. `RT Distance` steuert diesen
Radius, `RT Quality` die Reflexionsreichweite, `RT Samples` die Strahlen für
weiche Schatten und `RT Stable Sampling` erhöht die Mindestzahl auf vier.
Nach Bewegung, nahen Blockänderungen und über ein seltenes Sicherheitsintervall
wird die Szene erneuert. Das ist hybrides Raytracing, kein vollständiges
Pathtracing.

Auf GPUs ohne Vulkan-Ray-Query-Unterstützung bleiben die Schalter deaktiviert.
Die GeForce GTX 1660 SUPER stellt zum Beispiel `VK_KHR_ray_query` nicht bereit;
benötigt wird eine NVIDIA RTX 20-Serie oder neuer beziehungsweise eine
Vulkan-Ray-Query-fähige AMD-/Intel-GPU.

**Motion Debug** wechselt zwischen `OFF`, `ARROWS`, `HEATMAP` und `RAW PREVIEW`.
Das Overlay wird nur innerhalb einer geladenen Welt im HUD gerendert – nicht im
Hauptmenü und nicht im Einstellungsbildschirm. Sobald das renderer-eigene
Motion-Vector-Bild verfügbar ist, verwenden die Modi einen verzögerten, dünn
abgetasteten GPU-Readback des echten RG16F-Bildes, sobald dessen Present
abgeschlossen ist. Ein `vkDeviceWaitIdle` pro Debug-Bild ist nicht mehr nötig.
Vor dem ersten Readback dient die Kamerabewegungs-Vorschau als Fallback. Die
Pfeile verwenden die
Current-to-Previous-Vektoren des SDKs mit korrigierten GUI-Achsen und folgen
dadurch der Blickrichtung vertikal wie horizontal. RAW Preview kodiert dieselben
unveränderten Werte als Farben. Die Heatmap zeigt die Bewegungsstärke: Blau ist
langsam, Grün mittel und Rot schnell.

### Diagnose

Der Einstellungsbildschirm enthält **Run Diagnostics**. Ob ein Backend verfügbar
ist, hängt von erkannter GPU, Renderer und nativer Laufzeit ab. Eine sichtbare
Option bedeutet nicht automatisch, dass die Frame Generation aktiviert werden
kann.

Die genauen Backend-Verträge und offenen Arbeiten stehen in
[BACKENDS.md](docs/BACKENDS.md).

Enthalten ist die signierte `amd_fidelityfx_vk.dll` aus dem geprüften
OptiScaler-0.9.4-Paket. Sie wird über die öffentliche FidelityFX-Vulkan-API
verwendet. Ihre Lizenz steht in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
