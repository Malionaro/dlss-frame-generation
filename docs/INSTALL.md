# Installation & Requirements / Installation & Anforderungen

[**English Guide**](#english-guide) | [**Deutsche Anleitung**](#deutsche-anleitung)

---

## English Guide

### 📋 System Requirements

| Component | Minimum | Recommended |
|---|---|---|
| **OS** | Windows 10/11 (64-bit) | Windows 11 (64-bit) |
| **GPU** | Vulkan 1.3 Compatible GPU | Development/testing hardware; see backend status below |
| **VRAM** | 4 GB VRAM | 8+ GB VRAM |
| **Java** | Java 25 | Java 25 (Corretto / Temurin) |
| **Minecraft** | 1.26.2 | 1.26.2 |
| **Fabric** | 0.155.2+ | 0.155.2+ |

### 🛠️ Step-by-Step Installation

#### Step 1: Install Fabric Loader & API
1. Install **Fabric Loader 0.155.2** (or newer) for Minecraft 1.26.2.
2. Download **Fabric API** and place it into your `.minecraft/mods` directory.

#### Step 2: Download Mod Artifacts
1. Build or download `dlss-frame-generation-1.0-SNAPSHOT.jar`.
2. Copy `dlss-frame-generation-1.0-SNAPSHOT.jar` into `.minecraft/mods/`.
3. **Automated DLL Extraction:** All native Streamline SDK DLLs (`sl.interposer.dll`, `sl.dlss_g.dll`, etc.) are bundled directly inside the `.jar` and extract automatically upon launching the game!

#### Step 3: In-Game Settings Access
1. Launch Minecraft 1.26.2.
2. Open menu: **Options... -> Video Settings... -> Frame Generation...**
   (or via **ModMenu**).
3. Check the backend status shown by the settings screen. An unavailable
   capability cannot be enabled by selecting a preset.

> **Backend status:** This revision is experimental. NVIDIA Streamline and its
> Vulkan interposer are bundled; frame generation remains fail-closed until
> Streamline accepts all mandatory render inputs. The FidelityFX FSR 3.1
> Vulkan proxy is bundled for AMD and as a named cross-vendor fallback on Intel
> GPUs. FSR 4 frame generation and native XeSS-FG currently require a separate
> D3D12 renderer. See
> [BACKENDS.md](BACKENDS.md).

#### Bundled OptiScaler

OptiScaler v0.9.4 and its required Vulkan/upscaler runtime files are already
inside the mod JAR. Copying the single mod JAR is sufficient. On AMD/Intel it is
extracted and configured automatically; NVIDIA does not load it.

For development only, the helper can install an external override:

```powershell
.\tools\install-optiscaler.ps1
```

The current Minecraft render path uses the bridge for real 1:1 Native AA;
performance upscaling modes are not exposed yet. A manually installed
`config/dlss_fg/optiscaler/OptiScaler.dll` overrides the bundled version.

---

## Deutsche Anleitung

### 📋 Systemanforderungen

| Komponente | Minimum | Empfohlen |
|---|---|---|
| **Betriebssystem** | Windows 10/11 (64-Bit) | Windows 11 (64-Bit) |
| **Grafikkarte** | Vulkan-1.3-kompatible GPU | Entwicklungs-/Testhardware; siehe Backend-Status |
| **VRAM** | 4 GB VRAM | 8+ GB VRAM |
| **Java** | Java 25 | Java 25 (Corretto / Temurin) |
| **Minecraft** | 1.26.2 | 1.26.2 |
| **Fabric** | 0.155.2+ | 0.155.2+ |

### 🛠️ Schritt-für-Schritt Installation

#### Schritt 1: Fabric Loader & API installieren
1. Installiere den **Fabric Loader 0.155.2** (oder neuer) für Minecraft 1.26.2.
2. Lade die **Fabric API** herunter und platziere sie in deinen `.minecraft/mods` Ordner.

#### Schritt 2: Mod-Datei hinzufügen
1. Erstelle oder lade die `dlss-frame-generation-1.0-SNAPSHOT.jar` herunter.
2. Kopiere `dlss-frame-generation-1.0-SNAPSHOT.jar` in deinen `.minecraft/mods` Ordner.
3. **Automatische DLL-Extraktion:** Alle erforderlichen Streamline SDK DLLs (`sl.interposer.dll`, `sl.dlss_g.dll`, etc.) sind direkt in der JAR enthalten und entpacken sich beim Spielstart automatisch!

#### Schritt 3: In-Game Menü & Konfiguration
1. Starte Minecraft 1.26.2.
2. Öffne das Menü: **Optionen... -> Videoeinstellungen... -> Frame Generation...**
   (oder über **ModMenu**).
3. Prüfe den im Einstellungsmenü angezeigten Backend-Status. Ein nicht
   verfügbares Capability kann nicht durch ein Profil aktiviert werden.

> **Backend-Status:** Diese Revision ist experimentell. NVIDIA Streamline samt
> Vulkan-Interposer ist enthalten; Frame Generation bleibt fail-closed, bis
> Streamline alle Pflichtdaten des Renderers annimmt. Der
> FidelityFX-FSR-3.1-Vulkan-Proxy ist für AMD und als ausdrücklich benannter
> FSR-Fallback auf Intel-GPUs enthalten. FSR-4-Frame-Generation und echtes
> XeSS-FG benötigen derzeit einen separaten D3D12-Renderer. Details stehen in
> [BACKENDS.md](BACKENDS.md).

#### Mitgeliefertes OptiScaler

OptiScaler v0.9.4 und die für den Vulkan-/Upscaler-Pfad benötigten
Laufzeitdateien befinden sich bereits in der Mod-JAR. Es genügt, nur diese eine
JAR in den Mods-Ordner zu kopieren. Auf AMD/Intel wird OptiScaler automatisch
entpackt und konfiguriert; NVIDIA lädt es nicht.

Nur für die Entwicklung kann das Hilfsskript eine externe Version installieren:

```powershell
.\tools\install-optiscaler.ps1
```

Der aktuelle Minecraft-Renderpfad nutzt die Bridge für echtes 1:1-Native-AA;
Performance-Upscaling-Modi werden noch nicht angeboten. Eine manuell
installierte Datei unter `config/dlss_fg/optiscaler/OptiScaler.dll`
überschreibt die mitgelieferte Version.
