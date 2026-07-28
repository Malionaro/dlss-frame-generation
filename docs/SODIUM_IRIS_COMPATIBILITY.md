# Sodium- und Iris-Kompatibilität

> Technische Recherche und Implementierungsübergabe für Menschen und KI-Agenten  
> Stand der Recherche: 28. Juli 2026  
> Projektziel: `dlss_fg` / Vulkan Frame Generation Integration für Minecraft 26.2

## 1. Zweck und verbindliche Grenzen

Dieses Dokument hält den aktuellen Wissensstand zur Kompatibilität dieser Mod mit
Sodium und Iris fest. Der ursprüngliche Rechercheteil dokumentiert die
Ausgangslage vor der Implementierung; der folgende Statusblock beschreibt den
danach tatsächlich umgesetzten Stand.

Die wichtigste Regel für die spätere Arbeit lautet:

> Die Anwesenheit einer Mod ist kein Kompatibilitätsnachweis. Eine Funktion darf
> erst freigeschaltet werden, wenn Backend, Ressourcen, Synchronisation und nativer
> Provider für den konkreten Frame nachweislich verwendbar sind.

Nicht Teil dieses Dokuments sind Map-/Minimap-Support, Distant Horizons und eine
allgemeine Portierung von Iris auf Vulkan. Sie dürfen später als getrennte Vorhaben
geplant werden.

### 1.1 Implementierungsstand nach dem Kompatibilitätsumbau

Statisch umgesetzt und gebaut:

- `RendererDetector` erkennt Mojangs tatsächliches `GpuDevice`, Renderer sowie
  Sodium-/Iris-Versionen und fragt bei vorhandener Iris-API den Shaderpackzustand
  optional ab. Unbekannte oder widersprüchliche Zustände sperren Vulkan-FG, RT und
  Motion Debug mit lokalisiertem Grund.
- `FrameIdentity` und `FrameResourceSet` erfassen Farbe, Tiefe, Ausgabe,
  Backbuffer, Command Buffer, Semaphore, Layouts, Queue-Familie und
  Swapchain-Generation atomar. Stale, unvollständige oder während eines
  Renderpasses erfasste Sätze werden verworfen.
- `SodiumVulkanAdapter` verwendet bewusst Mojangs öffentliche Vulkan-Ressourcen,
  meldet aber nur dann Bereitschaft, wenn der vollständige atomare Vertrag
  erfüllt ist. Es wurden keine privaten Sodium-Interna eingebunden.
- `RendererIntegration` und `MaterialProvider` bilden einen optionalen,
  frame-identifizierten Vertrag für eine spätere Aperture-Integration.
  Aktuell existiert kein Aperture-Provider und keine Behauptung über
  Iris-/Aperture-PBR-Materialsupport.
- Die Diagnose trennt Integrationsstatus, Providerbereitschaft und tatsächliche
  Frame-/Present-Annahme. UI-Schalter zeigen übersetzte Sperrgründe.

Automatisierte Tests decken Renderer-/Iris-Zustände, atomare Ressourcen,
Swapchain-Generationen, Provider-Lifecycle/Exklusivität sowie Depth-/Motion-
Konventionen ab. Noch offen ist der echte Laufzeittest mit den konkreten
Sodium-0.9.1- und Iris-1.11.2-JARs; ohne ihn wird nicht behauptet, dass diese
Kombinationen hardwarevalidiert sind. Minecraft 26.3 bleibt ein vorbereitetes,
aber getrennt zu prüfendes Hook-/Mixin-Ziel.

## 2. Untersuchte Versionen und reproduzierbare Quellen

Die Aussagen wurden gegen folgende konkrete Quellstände geprüft:

| Komponente | Geprüfter Stand |
|---|---|
| Minecraft | 26.2, entsprechend dem aktuellen Projekt-Build |
| Sodium | 0.9.1, Tag `mc26.2-0.9.1`, Commit `2cfb93d332791502446d8226005b0b6baff40355` |
| Iris | 1.11.2, Branch `26.2`, Commit `ff6f5aa1174f3bfcb540e405a9c259d29ff42b21` |
| Iris ShaderDoc | Commit `ddf473726710c6504422d192784c1652f8aece98` |
| Dieses Projekt | lokaler Stand bei Erstellung dieses Dokuments |

Vor einer Implementierung müssen diese Versionen erneut geprüft werden. Insbesondere
interne Sodium- und Iris-Klassen sind nicht als stabile API zu behandeln.

Primärquellen:

- [Sodium-Repository](https://github.com/CaffeineMC/sodium)
- [Sodium 0.9.1](https://github.com/CaffeineMC/sodium/tree/2cfb93d332791502446d8226005b0b6baff40355)
- [Sodium Vulkan-Backend-Auswahl](https://github.com/CaffeineMC/sodium/blob/2cfb93d332791502446d8226005b0b6baff40355/common/src/main/java/net/caffeinemc/mods/sodium/client/gpu/device/backend/DrawBackend.java)
- [Sodium-Metadaten und Versionskonflikte](https://github.com/CaffeineMC/sodium/blob/2cfb93d332791502446d8226005b0b6baff40355/fabric/src/main/resources/fabric.mod.json)
- [Sodium-API-Quellbaum](https://github.com/CaffeineMC/sodium/tree/2cfb93d332791502446d8226005b0b6baff40355/common/src/api/java/net/caffeinemc/mods/sodium/api)
- [Iris-Repository](https://github.com/IrisShaders/Iris)
- [Geprüfter Iris-Stand](https://github.com/IrisShaders/Iris/tree/ff6f5aa1174f3bfcb540e405a9c259d29ff42b21)
- [Öffentliche Iris API v0](https://github.com/IrisShaders/Iris/blob/ff6f5aa1174f3bfcb540e405a9c259d29ff42b21/common/src/api/java/net/irisshaders/iris/api/v0/IrisApi.java)
- [Iris blockiert Shader unter Vulkan](https://github.com/IrisShaders/Iris/blob/ff6f5aa1174f3bfcb540e405a9c259d29ff42b21/common/src/main/java/net/irisshaders/iris/compat/sodium/config/ShaderPackScreenPlaceholder.java)
- [Iris trennt Vulkan- und OpenGL-Mixins](https://github.com/IrisShaders/Iris/blob/ff6f5aa1174f3bfcb540e405a9c259d29ff42b21/common/src/main/java/net/irisshaders/iris/mixin/IrisMixinPlugin.java)
- [Iris OpenGL-Rendering-Pipeline](https://github.com/IrisShaders/Iris/blob/ff6f5aa1174f3bfcb540e405a9c259d29ff42b21/common/src/main/java/net/irisshaders/iris/pipeline/IrisRenderingPipeline.java)
- [Iris ShaderDoc](https://github.com/IrisShaders/ShaderDoc/tree/ddf473726710c6504422d192784c1652f8aece98)
- [Iris ShaderDoc: Entity Motion Vectors](https://github.com/IrisShaders/ShaderDoc/blob/ddf473726710c6504422d192784c1652f8aece98/unsupported-features.md)
- [Iris 26.2 Reversed-Z-Problembericht](https://github.com/IrisShaders/Iris/issues/3241)
- [NVIDIA Streamline](https://github.com/NVIDIAGameWorks/Streamline)
- [AMD FidelityFX FSR 3.1](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK)

## 3. Kurzurteil

### 3.1 Kompatibilitätsmatrix

| Konfiguration | Heutiges Urteil | Begründung |
|---|---|---|
| Vanilla + Vulkan | vorgesehener Hauptpfad | Die Mod greift direkt auf Mojangs Vulkan-Gerät, RenderTargets, Command Buffer und Swapchain zu. |
| Sodium 0.9.1 + Vulkan | Architektur gehärtet, realer Mod-Laufzeittest offen | Sodium verwendet Mojangs Vulkan-Backend. Der Adapter konsumiert nun nur vollständige atomare Mojang-Vulkan-Ressourcen und greift nicht auf private Sodium-Klassen zu; Resize/Reload mit der echten Sodium-JAR muss noch validiert werden. |
| Sodium + OpenGL | keine Vulkan-FG-/RT-Funktionen | Die aktuellen nativen Pfade erwarten Vulkan-Handles und Vulkan-Bilder. |
| Iris installiert + Vulkan | Iris darf installiert bleiben; Shaderpacks sind aus | Iris deaktiviert seine normale Shader-Pipeline unter Vulkan. Danach entspricht der relevante Renderpfad weitgehend „Sodium + Vulkan“. |
| Iris + aktives Shaderpack | derzeit nicht kompatibel mit den Vulkan-FG-/RT-Pfaden | Iris zwingt für Shaderpacks OpenGL. Seine finalen Farb-, Tiefen- und Materialdaten sind OpenGL-Ressourcen, keine `VkImage`-Ressourcen. |
| Iris ohne aktives Shaderpack + OpenGL | keine Vulkan-FG-/RT-Funktionen | Kein verwendbarer Vulkan-Kontext, auch wenn Iris geladen ist. |
| Zukünftiges Iris mit Vulkan-Shadern | neu bewerten | Dafür wären explizite Ressourcen- und Synchronisationsverträge von Iris nötig. |

### 3.2 Was „Sodium-kompatibel“ realistisch bedeuten kann

Sodium ersetzt in Minecraft 26.2 Teile des Welt-Renderers und wählt intern einen
Vulkan-Multidraw-Pfad, wenn Mojangs aktives `GpuDevice` ein Vulkan-Gerät ist. Sodium
erstellt nach dem geprüften Stand aber keine zweite unabhängige öffentliche
Swapchain. Daher kann diese Mod wahrscheinlich weiter die von Mojang verwalteten
Vulkan-Ressourcen benutzen.

Das ist eine gute Ausgangslage, aber noch kein fertiger Support. Nachzuweisen sind:

1. Der in `GameRendererMixin` gewählte Capture-Zeitpunkt liegt nach Sodiums kompletter
   Welt-Ausgabe und außerhalb eines aktiven Renderpasses.
2. Farb- und Tiefenbilder gehören tatsächlich zu dem Frame und RenderTarget, dessen
   Matrizen und Auflösung übergeben werden.
3. Das registrierte `VulkanCommandEncoder`-Objekt besitzt zu diesem Zeitpunkt den
   aufzeichnungsbereiten Command Buffer.
4. Layouts, Zugriffsrechte und Queue-Ownership werden nicht nur angenommen.
5. Swapchain-Rekonfiguration, Fullscreen-Wechsel und Sodium-Reloads invalidieren
   keine gecachten Handles.

### 3.3 Was „Iris-kompatibel“ realistisch bedeuten kann

Es gibt zwei sehr unterschiedliche Fälle:

- **Iris ist installiert, Minecraft läuft mit Vulkan:** Iris lässt keine Shaderpacks
  laufen. Die Mod kann ihren Sodium-/Vulkan-Pfad verwenden.
- **Ein Iris-Shaderpack ist aktiv:** Minecraft läuft mit OpenGL. Die aktuellen
  Vulkan-Funktionen dieser Mod müssen sichtbar und verständlich deaktiviert bleiben.

Vollständige Unterstützung aktiver Iris-Shaderpacks wäre kein kleiner
Kompatibilitätspatch. Sie wäre ein eigenes Renderer-Interop-Projekt.

## 4. Audit der Ausgangslage vor der Implementierung

### 4.1 Bestehende Erkennung

`RendererDetector` prüft:

- `FabricLoader.isModLoaded("sodium")`
- `FabricLoader.isModLoaded("iris")`
- Text aus `RenderSystem.getBackendDescription()`

Anschließend wird anhand der Wörter `vulkan` oder `opengl` ein Backend gewählt.

Probleme:

- Ein Beschreibungstext ist keine stabile Maschinen-API.
- `sodiumPresent` sagt nicht, ob Sodiums Vulkan-Draw-Pfad aktiv ist.
- `irisPresent` sagt nicht, ob ein Shaderpack aktiv ist.
- Eine frühe Abfrage kann stattfinden, bevor das endgültige Grafikgerät aufgebaut
  wurde.
- Eine spätere Vulkan-Mixin-Aktivierung überschreibt den provisorischen Adapter,
  weshalb Startlog und tatsächlicher Zustand auseinanderlaufen können.

### 4.2 Bestehender Sodium-Adapter

`SodiumVulkanAdapter` delegiert jede Methode unverändert an
`VanillaVulkanAdapter`. Er besitzt:

- keinen Sodium-API-Aufruf,
- keine Sodium-Versionsprüfung,
- keinen Nachweis des aktiven Sodium-Draw-Backends,
- keinen eigenen Render-Lifecycle-Hook,
- keine Prüfung auf Mixin-Konflikte.

Die Klasse ist damit momentan ein Name für eine plausible Route, keine vollständige
Sodium-Integration. Das ist nicht automatisch falsch: Wenn Mojangs Vulkan-Ressourcen
weiter die maßgeblichen Ressourcen sind, ist Delegation die gewünschte Lösung. Sie
muss jedoch durch Runtime-Validierung und Tests abgesichert werden.

### 4.3 Bestehender Vanilla-Vulkan-Adapter

Der Adapter liest:

- Instanz, physisches Gerät, logisches Gerät und Graphics Queue aus
  `VulkanRegistry.getActiveDevice()`;
- Swapchain, Bildindex und Semaphore über Accessor-Mixins auf
  `VulkanGpuSurface`;
- den aktuellen Command Buffer über ein Accessor-Mixin auf
  `VulkanCommandEncoder`;
- Farbe primär aus `VulkanRegistry.getActiveColorImage()`, sonst aus dem
  `mainRenderTarget`;
- Tiefe aus dem `mainRenderTarget`;
- Maße aus dem `mainRenderTarget`.

Risiken:

- `activeColorImage` wird beim Surface-Blit registriert und kann älter sein als das
  aktuell übergebene RenderTarget.
- Farbe, Tiefe, Backbuffer und Matrizen werden aus mehreren Quellen zusammengesetzt.
  Es existiert keine gemeinsame Frame-ID oder Ressourcenidentität.
- Der Adapter gibt rohe Handles aus, aber keine Formate, Layouts, Subresources,
  Generationen oder Lebensdauern.
- `isFrameReadyForFG()` prüft Nicht-Null-Werte, nicht deren Zugehörigkeit zum selben
  Frame.
- `getCurrentFrameIndex()` liefert im Fehlerfall `0`; das kann einen unbekannten
  Zustand als gültigen Index erscheinen lassen.

### 4.4 Capture- und Präsentationszeitpunkte

`GameRendererMixin`:

1. merkt sich das Display-RenderTarget;
2. kann ein kleineres `MainTarget` für die Welt anlegen;
3. ersetzt vor `renderLevel` das `mainRenderTarget`;
4. erfasst Szene, Tiefe und Bewegung vor dem Vanilla-Aufruf zum Leeren der Tiefe;
5. kann die UI in ein separates volles RenderTarget zeichnen;
6. erfasst die UI nach `GuiRenderer.endFrame`;
7. blended die UI zurück auf das Display-Target.

`VulkanGpuSurfaceMixin`:

- ersetzt optional Create/GetImages/Acquire/Present/Destroy der Swapchain durch
  Provider-Aufrufe;
- meldet Configure-Start und Configure-Ende;
- markiert Present-Start und Present-Ende;
- registriert beim Blit das verwendete Farbbild.

`VulkanRegistry.captureFrameGenerationInputs()`:

- verlangt Vulkan-Views für Farbe, Tiefe und Ausgabe;
- holt den Command Buffer aus dem registrierten Encoder;
- ermittelt das aktuelle Swapchain-Bild;
- führt optional Ray Reconstruction aus;
- kopiert/erzeugt dichte Motion Vectors;
- übergibt Matrizen, Jitter, Tiefe, UI-Status und Ressourcen an den nativen Provider.

Damit hängt die Kompatibilität nicht nur vom Welt-Renderer ab. Sie hängt von einem
vollständigen Vertrag zwischen Weltpass, Postprocessing, HUD, Command Encoder,
Swapchain und nativem Provider ab.

### 4.5 Aktuelle Mod-Metadaten

`fabric.mod.json` führt Sodium und Iris nur unter `suggests` auf. Das bedeutet:

- Beide Mods sind optional.
- Fabric prüft keine getestete Version.
- Der Eintrag beweist keine technische Kompatibilität.
- Benutzer können eine inkompatible Kombination installieren, ohne dass der Loader
  sie stoppt.

Später sollte entweder ein enger getesteter Versionsbereich dokumentiert oder eine
Runtime-Kompatibilitätstabelle geführt werden. Ein hartes `depends` ist nur nötig,
wenn die Mod ohne Sodium/Iris nicht mehr funktionieren soll; das ist hier nicht
empfohlen.

## 5. Was Sodium 0.9.1 unter Minecraft 26.2 tatsächlich macht

### 5.1 Vulkan ist ein echter Sodium-Pfad

Sodium wählt in `DrawBackend.chooseBackend()` anhand von Mojangs aktivem
`GpuDevice`:

- `VK_MULTIDRAW`, wenn ein `VulkanDevice` und Direct-Interleaved-Multidraw
  verfügbar sind;
- `VK_INDIRECT`, wenn ein `VulkanDevice` und Indirect-Multidraw verfügbar sind;
- sonst `OPENGL`.

Die Vulkan-Draw-Contexts greifen intern auf Mojangs `VulkanRenderPass`, dessen
Command Buffer und Pipeline-Layout zu. Sodium rendert also nicht einfach nur mit
einem OpenGL-Kompatibilitätslayer über Vulkan.

### 5.2 Es gibt keine passende stabile Sodium-Ressourcen-API

Der öffentliche Sodium-API-Quellbaum bietet APIs für Vertexdaten, Block-Entity-
Prädikate, Texturen und Konfiguration. Im geprüften Stand gibt es keine öffentliche
API für:

- aktuelles Welt-Farbbild,
- aktuelles Tiefenbild,
- aktuellen Vulkan-Command-Buffer,
- Renderpass-Ende,
- Swapchain,
- Semaphore oder Timeline-Werte,
- Motion Vectors.

Klassen wie `VKDrawContext`, `VulkanRenderPassAccessor` oder Sodiums interne
Rendererverwaltung sind Implementierungsdetails. Direkte Mixins gegen diese Klassen
müssen als versionsgebundene Notlösung betrachtet werden.

### 5.3 Versionsbeziehung zu Iris

Sodium 0.9.1 deklariert einen Konflikt mit Iris `<=1.11.1`. Für die geprüfte
Minecraft-Version muss die Testbasis daher Iris 1.11.2 oder neuer innerhalb eines
kompatiblen 1.11.x-Bereichs verwenden. Außerdem kollidiert Sodium mit der separaten
Legacy-Mod `vulkanmod`. Dieser Eintrag darf nicht mit Mojangs eingebautem
Vulkan-Backend von Minecraft 26.2 verwechselt werden.

### 5.4 Empfohlene Sodium-Strategie

Bevorzugt wird keine tiefe Sodium-Integration, sondern:

1. Mojangs aktives `GpuDevice` und konkrete Backend-Klasse autoritativ erkennen.
2. Mojangs `RenderTarget`-Vulkan-Views als Ressourcenquelle verwenden.
3. Den Capture-Punkt am Minecraft-Framegraph/Lifecycle absichern.
4. Sodium nur als zusätzliche Renderer-Eigenschaft und getestete Variante melden.
5. Erst wenn ein reproduzierbarer Fehler es erfordert, einen kleinen, separat
   versionierten Sodium-Interop-Hook hinzufügen.

Diese Strategie minimiert Brüche bei Sodium-Updates und respektiert die
PolyForm-Shield-Lizenz: keine Sodium-Implementierung kopieren oder einbetten. Nur
zulässige optionale Verknüpfung beziehungsweise Runtime-Interop verwenden und die
Lizenz vor einer Veröffentlichung erneut prüfen.

## 6. Was Iris 1.11.2 unter Minecraft 26.2 tatsächlich macht

### 6.1 Shaderpacks und Vulkan schließen sich derzeit aus

Iris zeigt unter Vulkan einen Platzhalterbildschirm mit der Aussage, dass Iris nicht
unter Vulkan laufen kann, und bietet den Wechsel zu OpenGL an. Die Sodium-
Grafikeinstellung warnt ebenfalls, dass der Wechsel zu Vulkan die Shader-Fähigkeit
entfernt.

`IrisMixinPlugin` liest das gewünschte Backend und lädt:

- normale Iris-Mixins nur ohne Vulkan;
- speziell benannte `VKOnly`-Mixins nur mit Vulkan.

Das bedeutet nicht, dass die Iris-JAR generell mit Vulkan unladbar ist. Es bedeutet,
dass die eigentliche Shader-Pipeline nicht auf Vulkan läuft.

### 6.2 Die öffentliche Iris-API reicht für sichere Erkennung

Die öffentliche API v0 bietet:

```java
IrisApi.getInstance().isShaderPackInUse()
```

Diese Methode ist die richtige Abfrage, wenn Iris optional installiert ist. Sie
liefert nur dann `true`, wenn ein Shaderpack tatsächlich zum Rendern benutzt wird.
Sie liefert auch `false`, wenn kein Pack aktiv ist oder dessen Kompilierung
fehlgeschlagen ist.

Die API bietet jedoch keine öffentlichen Handles für:

- `colortex`-RenderTargets,
- `depthtex`-Texturen,
- finale Shaderausgabe,
- OpenGL-Synchronisation,
- Motion Vectors,
- Shaderpack-Materialparameter.

Die spätere Integration darf `isShaderPackInUse()` optional verwenden, aber daraus
keine Ressourcenverfügbarkeit ableiten.

### 6.3 Iris’ Rendering ist OpenGL-zentriert

`IrisRenderingPipeline` verwendet unter anderem `GlTexture`, `GlFramebuffer`,
`GlProgram`, OpenGL-Sampler und OpenGL-Compute-Programme. Der Ablauf ist:

1. `beginLevelRendering()`
2. Welt-, Schatten-, Prepare-, Deferred- und Composite-Pässe
3. `finalizeLevelRendering()`
4. `compositeRenderer.renderAll()`
5. `finalPassRenderer.renderFinalPass()`
6. später `finalizeGameRendering()` und Farbraumkonvertierung des
   `mainRenderTarget`

Ein Capture vor Iris’ Final-/Composite-Pässen wäre visuell falsch. Ein Capture
danach liefert aber weiterhin ein OpenGL-Bild, kein `VkImage`.

### 6.4 Shaderpack-Ressourcen sind kein stabiler FG-Vertrag

Shaderpacks können mehrere `colortex`-Ziele, Tiefenkopien und beliebige
Composite-Pässe besitzen. Übliche Namen wie `colortex0`, `depthtex0`,
`depthtex1` und `depthtex2` sind Shaderpack-Semantik, aber keine öffentliche
Java-Interop-API dieser Mod.

Iris’ Implementierung kopiert:

- vor Transluzenz die Tiefe für `depthtex1`;
- vor der Hand die Tiefe für `depthtex2`.

Welches Bild als endgültige FG-Farbe geeignet ist, hängt vom fertigen Final-Pass,
Farbraum und Backbuffer ab. Ein internes `colortex0` darf nicht pauschal als
HUD-lose Endfarbe angenommen werden.

Besonders wichtig: Iris ShaderDoc führt Entity Motion Vectors als nicht
implementiertes Feature. Ein Shaderpack kann eigene Velocity-Daten erzeugen, aber
es gibt keinen allgemeinen Vertrag, auf den diese Mod sich verlassen kann.

### 6.5 Reversed-Z und Projektion

Minecraft 26.2 verwendet umgekehrte Tiefe: Der Tiefenpuffer wird mit `0.0`
geleert, größere Werte liegen näher an der Kamera. Auch dieses Projekt leert seine
Vulkan-Tiefenbilder mit `0.0`.

Iris Issue #3241 zeigt, dass Projektions- oder Culling-Code bei dieser Umstellung
leicht alte Annahmen beibehält. Jeder spätere Ressourcenvertrag muss deshalb
explizit tragen:

- ob die Tiefe reversed/inverted ist;
- NDC-Bereich und Clip-Control;
- Near-/Far-Werte;
- Projektionsmatrix-Konvention;
- Y-Ausrichtung;
- ob die Matrix jittered oder unjittered ist;
- Bewegungsvektorrichtung: aktuell nach vorher oder vorher nach aktuell;
- Einheit: Pixel, UV oder NDC.

Ohne diese Angaben darf ein Provider den Frame nicht akzeptieren.

## 7. Ressourcenvertrag, den die Mod benötigt

Ein späterer Renderer-Interop-Layer muss alle folgenden Daten atomar für denselben
Frame liefern:

| Ressource/Datum | Mindestanforderung | Sodium + Vulkan | Iris + Shader/OpenGL |
|---|---|---|---|
| Weltfarbe ohne HUD | gültiges Bild nach Welt-Postprocessing | wahrscheinlich Mojang Vulkan RenderTarget | nur OpenGL, keine öffentliche Export-API |
| Display-/Backbuffer | Bild, Format, Farbraum, Index | Mojang Vulkan Surface | OpenGL Default/Blit-Ziel |
| Tiefe | Bild, Format, Convention, Layout | Mojang Vulkan RenderTarget | OpenGL, mehrere Iris-Tiefenkopien |
| Motion Vectors | RG16F, definierte Richtung/Einheit, dicht | projektinterne Rekonstruktion möglich | keine standardisierten Entity-Motion-Vectors |
| UI separat | volle Auflösung, Alpha, Farbraum | projektinternes Vulkan `MainTarget` | Iris/GUI-Lifecycle müsste separat abgegriffen werden |
| Command Buffer | aufzeichnungsbereit, außerhalb Renderpass | Mojang Vulkan Encoder | nicht vorhanden |
| Synchronisation | Producer-/Consumer-Stages, Semaphore/Fence | Vulkan-intern lösbar | GL/Vulkan-Interop erforderlich |
| Matrizen/Jitter | exakt zum Bild passend | aus `GameRenderState`, noch zu validieren | Iris kann Projektion/Temporalzustand beeinflussen |
| Material/PBR | definierter Materialkanal | aktuell heuristisch/voxelbasiert | packabhängige GL-Texturen und G-Buffer |

### 7.1 Empfohlenes Datenmodell

Der heutige `RendererBackendAdapter` sollte später durch einen strengeren
Framevertrag ergänzt oder ersetzt werden. Ein mögliches Modell:

```text
RendererIntegration
 ├─ RendererInteropCapabilities queryCapabilities()
 ├─ FrameResourceSet beginCapture(frameIdentity, capturePoint)
 ├─ void recordProviderWork(FrameResourceSet, CommandRecordingContext)
 └─ void onSwapchainGenerationChanged(...)

FrameResourceSet
 ├─ frameId + swapchainGeneration
 ├─ renderSize + outputSize
 ├─ color + depth + output + optional UI
 ├─ formats + subresources + layouts
 ├─ ownership + queue family
 ├─ depthConvention + coordinateConvention
 ├─ matrices + jitter + reset flag
 └─ synchronization contract
```

Die Ressourcen sollten nicht als lose `long`-Handles aus verschiedenen Methoden
abgerufen werden. Ein unveränderliches Set verhindert, dass Farbe von Frame N,
Tiefe von Frame N+1 und Swapchain-Index von Frame N-1 kombiniert werden.

### 7.2 Notwendige Capability-Felder

Mindestens:

- `graphicsApi`: Vulkan/OpenGL/Unbekannt
- `minecraftBackend`: konkrete aktive Backend-Klasse
- `sodiumPresent`, `sodiumVersion`, `sodiumDrawBackend`
- `irisPresent`, `irisVersion`, `irisShaderPackActive`
- `rendererResourceInterop`
- `worldColorAvailable`
- `depthAvailable`
- `motionVectorsAvailable`
- `uiSeparationAvailable`
- `swapchainInterceptionAvailable`
- `nativeProviderReady`
- `supportedReason` beziehungsweise lokalisierbarer Ablehnungsgrund

Capability-Zustände sollten nicht nur boolesch sein. Sinnvoll sind:

- `SUPPORTED`
- `UNSUPPORTED`
- `NOT_READY`
- `VERSION_UNTESTED`
- `CONFLICT`
- `ERROR`

## 8. Konkreter Implementierungsplan

### Phase 0: Wahrheitsgemäße Erkennung und UI

Ziel: Noch keine neue Renderer-Funktion, sondern korrekte Zustände.

1. Nach Erstellung des Grafikgeräts die konkrete `GpuDevice`-/Backend-Klasse
   prüfen; Beschreibungstext nur noch für Diagnose verwenden.
2. Fabric-Modcontainer verwenden, um Sodium-/Iris-Versionen zu protokollieren.
3. Falls Iris geladen ist, die öffentliche `IrisApi` optional abfragen.
4. Aktives Shaderpack + OpenGL als eindeutigen Ablehnungsgrund anzeigen:
   „Iris-Shaderpacks verwenden derzeit OpenGL; diese Funktion benötigt Vulkan.“
5. Iris installiert + Vulkan nicht als Fehler melden, sondern:
   „Iris geladen; Shaderpacks unter Vulkan deaktiviert.“
6. Untestbare/zu neue Sodium-Version nicht still als voll unterstützt melden.
7. Diagnose muss Backend, Modversionen, Shaderpackstatus und den ersten fehlenden
   Ressourcenvertrag zeigen.

Abnahmekriterium: Keine Vulkan-Funktion ist unter OpenGL anklickbar; jeder
deaktivierte Schalter besitzt eine verständliche, lokalisierte Begründung.

### Phase 1: Sodium-Vulkan-Baseline beweisen

1. Testbasis auf Sodium 0.9.1 und gegebenenfalls Iris 1.11.2 festlegen.
2. Mit Vulkan Validation Layers prüfen, ob der Capture-Aufruf außerhalb eines
   aktiven Renderpasses erfolgt.
3. Pro Frame eine Identität für RenderTarget, Farbe, Tiefe, Output,
   Swapchain-Generation und Bildindex protokollieren.
4. Die Herkunft von `activeColorImage` prüfen. Bevorzugt direkt den `colorView`
   des an `captureFrameGenerationInputs` übergebenen RenderTargets verwenden,
   statt einen global registrierten Blit-Handle zu bevorzugen.
5. Den aktiven Command Buffer nicht nur beim Encoder-Konstruktor merken. Seine
   Gültigkeit am Capture-Punkt nachweisen.
6. Sicherstellen, dass Sodiums Vulkan-Weltpass beendet ist, bevor Compute/Copy
   aufgezeichnet wird.
7. Shader-/Resource-Reload, Weltwechsel, Dimensionwechsel, Resize und Fullscreen
   testen.

Abnahmekriterium: Alle Tests laufen ohne Validation-Fehler, falsche Layouts,
Device Loss, flackernde alte Frames oder falsche Tiefe.

### Phase 2: Layout- und Synchronisationsvertrag härten

Aktuell verwendet die Pipeline an mehreren Stellen angenommene Layouts, etwa
`VK_IMAGE_LAYOUT_GENERAL`. Für externe Minecraft-/Sodium-Ressourcen darf das nicht
blind gelten.

Erforderlich:

1. tatsächliches Ausgangslayout oder einen garantierten Übergabepunkt definieren;
2. aktive Renderpässe vor externen Barriers beenden;
3. Source-/Destination-Stage und Access Masks passend zum echten Produzenten
   setzen;
4. Queue-Family-Ownership explizit behandeln;
5. bei Swapchain-Neuanlage alle alten Ressourcen mit Generation markieren und
   erst nach sicherer GPU-Freigabe zerstören;
6. Acquire-/Present-Semaphore nicht anhand potenziell verschiedener Indizes
   kombinieren;
7. Provider-Proxy und Mojang-Surface müssen denselben Swapchain-Lebenszyklus
   sehen.

Wenn Synchronisation nicht bewiesen werden kann, muss der Frame übersprungen
werden. Ein übersprungener Frame ist besser als undefiniertes GPU-Verhalten.

### Phase 3: Iris installiert, Vulkan aktiv

Dieser Fall benötigt voraussichtlich keine Iris-RenderTarget-Integration:

1. bestätigen, dass `isShaderPackInUse()` false ist;
2. Sodium-Vulkan-Vertrag aus Phase 1 verwenden;
3. Iris-Version und Vulkan-Deaktivierung der Shader im Diagnosebild zeigen;
4. Shader-Auswahlknopf nicht manipulieren; Iris selbst erklärt den Backendwechsel;
5. nach einem Backendwechsel einen vollständigen Neustart verlangen und keine alten
   Vulkan-Handles weiterverwenden.

Abnahmekriterium: Iris kann installiert bleiben, während die Mod unter Vulkan wie
ohne aktives Shaderpack arbeitet.

### Phase 4: Iris-Shaderpacks unter OpenGL bewusst nicht unterstützen

Die empfohlene Produktentscheidung lautet zunächst **fail-closed**:

- DLSS/FSR/XeSS-Frame-Generation-Schalter aus;
- Vulkan-RT und Ray Reconstruction aus;
- Vulkan-Motion-Debug aus;
- Diagnose und OSD dürfen weiter funktionieren, sofern sie keine Vulkan-
  Ressourcen benötigen;
- UI erklärt den Grund und den möglichen Wechsel zu Vulkan ohne Shaderpack.

Diese Phase ist vollständig, wenn das Verhalten klar, stabil und crashfrei ist.
Sie ist nicht „unvollständig“, nur weil aktive Shaderpacks keine Vulkan-FG erhalten.

### Phase 5: Optionales Forschungsprojekt OpenGL–Vulkan-Interop

Nur beginnen, wenn aktive Iris-Shaderpacks zwingend unterstützt werden sollen und
Zeit für einen separaten Renderer-Backend-Aufwand vorhanden ist.

Erforderliche Themen:

- Export/Import externer Bildspeicher zwischen OpenGL und Vulkan;
- externe Semaphore/Fences;
- Windows- und Linux-spezifische Handle-Typen;
- NVIDIA-, AMD- und Intel-Treiberunterschiede;
- Exportfähigkeit von Iris’ OpenGL-Texturen;
- finale Farbe nach Iris-Finalpass und Farbraumkonvertierung;
- HUD-lose Farbe und UI-Alpha;
- Tiefenformat und Reversed-Z;
- eigener dichter Motion-Vector-Pass inklusive Entity-Bewegung;
- Resize-, Reload- und Shaderpack-Wechsel;
- Kopierkosten und Latenz;
- Provideranforderungen an gleiche API/Queue/Device.

Eine CPU-Rückkopie OpenGL → RAM → Vulkan ist für Frame Generation nicht
akzeptabel. Sie verursacht hohe Bandbreite, Latenz und Synchronisationsstalls.

Bevor diese Phase implementiert wird, sollte bei Iris ein offizieller
Ressourcen-/Lifecycle-Hook vorgeschlagen werden. Direkter Zugriff auf
`IrisRenderingPipeline`, `RenderTargets`, `GlFramebuffer` oder private
`colortex`-IDs ist nur versionsgebunden und wartungsintensiv.

## 9. Material-, PBR- und Raytracing-Kompatibilität

Falls mit „Material“ die Materialdarstellung der Mod gemeint ist, ist die Antwort
getrennt von reiner Lade-/Crash-Kompatibilität zu betrachten.

### 9.1 Heutiger Zustand

Der Raytracing-Pfad dieses Projekts leitet Weltgeometrie und Materialeigenschaften
über eigene Vulkan-/Voxel-Daten und Heuristiken ab. Iris-Shaderpacks können dagegen:

- eigene Normal- und Specular-Texturen verwenden;
- LabPBR- oder andere Resource-Pack-Konventionen auswerten;
- Block-IDs und Materialkennungen packabhängig interpretieren;
- ihre Beleuchtung vollständig in eigenen G-Buffer-/Composite-Pässen berechnen.

Selbst wenn beide Mods gleichzeitig geladen werden, stimmen Materialien deshalb
nicht automatisch überein. Eine reflektierende oder emissive Oberfläche im
Shaderpack kann im eigenen RT-Pfad anders aussehen.

### 9.2 Spätere saubere Abstraktion

Empfohlen:

```text
MaterialProvider
 ├─ VanillaMaterialProvider
 ├─ ResourcePackPbrMaterialProvider
 └─ FutureIrisMaterialProvider
```

Ein Materialdatensatz sollte mindestens enthalten:

- Basisfarbe und Alpha-Modus;
- Normalenquelle;
- Rauheit/Glätte;
- Metallanteil;
- Emission;
- Brechung/Transluzenz;
- Textur-/Atlas-Koordinaten;
- Block-/Biome-Tint;
- Versions-/Pack-Identität.

Ein zukünftiger Iris-Provider darf nicht voraussetzen, dass jedes Pack dieselben
Kanäle verwendet. Er benötigt eine dokumentierte Pack-Konvention oder eine
explizite Kooperation mit Iris. Bis dahin muss die Diagnose sagen:
„Shaderpack-Materialien werden vom Vulkan-RT-Pfad nicht übernommen.“

## 10. Mixin- und Dependency-Strategie

### 10.1 Öffentliche API bevorzugen

- Iris: optional gegen die öffentliche API v0 kompilieren und ausschließlich
  `isShaderPackInUse()` für den Supportzustand verwenden.
- Sodium: keine passende öffentliche Render-Ressourcen-API vorhanden; Mojangs
  Backend verwenden.

### 10.2 Optionale Abhängigkeiten

Wenn Build-Artefakte verfügbar sind:

- `modCompileOnly`/äquivalente Compile-only-Abhängigkeit;
- keine Iris-/Sodium-Klassen aus dem unverzweigten Basispfad laden;
- Integration in eigene Klasse kapseln;
- Klasse erst aufrufen, nachdem Fabric die Mod-Anwesenheit bestätigt hat;
- `NoClassDefFoundError`, API-Revisionsfehler und Linkage-Fehler fail-closed
  behandeln.

### 10.3 Optionale interne Mixins

Nur wenn Phase 1 einen konkreten Bedarf beweist:

- eigene Mixin-Konfiguration für Sodium-/Iris-Interop;
- Plugin prüft Mod-ID und getesteten Versionsbereich;
- `required: false`;
- Injections mit niedriger/gezielter Erwartung und verständlicher Diagnose;
- keine globale Abschaltung des Spiels bei einem nicht gefundenen optionalen Hook;
- jeder interne Hook bekommt einen Integrationstest und eine Quellstandnotiz.

Keine private Klasse per Reflection „erraten“. Bei nicht passender Version wird die
betroffene Funktion deaktiviert.

### 10.4 Lizenzhinweise

- Sodium: PolyForm Shield 1.0.0 im geprüften Stand.
- Iris: LGPL-3.0-only; Teilkomponenten können weitere Lizenzen besitzen.

Vor Binärverteilung muss eine rechtliche Prüfung klären, welche optionalen
Compile-/Link-/Bundle-Formen zulässig sind. Insbesondere keine Sodium- oder
Iris-JAR und keinen kopierten Quellcode ungeprüft in diese Mod einbetten.

## 11. Vorgeschlagene spätere Dateizuordnung

Diese Liste ist eine Arbeitskarte, keine Anweisung, alle Dateien zwingend zu ändern.

| Bereich | Bestehende Datei | Spätere Aufgabe |
|---|---|---|
| Backenderkennung | `RendererDetector.java` | konkrete Device-Erkennung, Modversionen, Iris-Shaderstatus |
| Supportzustand | `CompatibilityManager.java` | strukturierte Capabilities und lokalisierbare Gründe |
| Ressourcenvertrag | `RendererBackendAdapter.java` | atomisches `FrameResourceSet` statt loser Handles |
| Sodium | `SodiumVulkanAdapter.java` | nur validierte Mojang-Vulkan-Delegation; optionaler Versionsstatus |
| Vulkan-Basis | `VanillaVulkanAdapter.java` | Frameidentität, Layout, Format, Generation, Fehlerzustände |
| Frame-Lifecycle | `GameRendererMixin.java` | Capture-Punkt gegen Sodium und Iris absichern |
| Present/Swapchain | `VulkanGpuSurfaceMixin.java` | Generation und Synchronisation robust verwalten |
| Registry | `VulkanRegistry.java` | keine unabhängigen globalen Ressourcenreste; Diagnose |
| Motion Pipeline | `motion/VulkanFrameGenerationPipeline.java` | explizite Depth-/MV-Konventionen und Layouts |
| UI | Einstellungs-/Diagnose-Screens | verständliche Zustände für Sodium/Iris |
| Übersetzungen | `assets/dlss_fg/lang/*.json` | alle neuen Gründe und Hinweise in jeder Sprache |
| Metadaten | `fabric.mod.json` | getestete Versionen dokumentieren, keine falsche Zusage |
| Tests | vorhandene Testquellen | Matrix- und Capability-Tests ergänzen |

## 12. Testmatrix

### 12.1 Pflichtkombinationen

Mindestens:

| GPU | Backend | Sodium | Iris | Shaderpack |
|---|---|---:|---:|---:|
| NVIDIA RTX 40 | Vulkan | aus | aus | aus |
| NVIDIA RTX 40 | Vulkan | an | aus | aus |
| NVIDIA RTX 40 | Vulkan | an | an | aus/unter Vulkan blockiert |
| NVIDIA RTX 40 | OpenGL | an | an | an |
| AMD RX 6000/7000 | Vulkan | an | aus/an | aus |
| Intel Arc | Vulkan | an | aus/an | aus |
| je ein Hersteller | OpenGL | an | aus | aus |

Für Iris/OpenGL muss der erwartete Erfolg „sauber deaktiviert und gut erklärt“
lauten, nicht „FG aktiv“.

### 12.2 Pflichtszenarien je unterstützter Vulkan-Kombination

- Spielstart und Weltbeitritt;
- Dimensionwechsel;
- Welt verlassen und erneut betreten;
- Fenstergröße mehrfach ändern;
- Fullscreen ein/aus;
- Alt-Tab und minimiertes Fenster;
- Resource-Pack-Reload;
- Sodium-Einstellungen ändern, die Renderer neu aufbauen;
- Iris installiert, danach Backendwechsel und Neustart;
- Pause-Menü und sehr niedrige FPS;
- Teleport/Kamerasprung/FOV-Sprung;
- HUD ein/aus und UI-Recomposition;
- Upscaling an/aus und Qualitätsmodus ändern;
- Frame Generation an/aus und Multiplikator ändern;
- Raytracing/Ray Reconstruction an/aus;
- Motion-Debug in allen Modi.

### 12.3 Technische Werkzeuge

- Vulkan Validation Layers;
- RenderDoc, sofern der Proxy-Swapchain-Pfad Capture zulässt;
- NVIDIA Nsight Graphics;
- AMD Radeon GPU Profiler/Analyzer;
- apitrace oder RenderDoc für einen getrennten Iris/OpenGL-Forschungspfad;
- strukturierte Projektlogs mit Frame- und Swapchain-Generation.

### 12.4 Harte Abnahmekriterien

Support darf erst als „kompatibel“ bezeichnet werden, wenn:

1. keine Vulkan-Validation-Fehler auftreten;
2. zehn Resize-/Fullscreen-Wechsel ohne Device Loss funktionieren;
3. Farbe, Tiefe, Motion und Matrizen dieselbe Frame-ID tragen;
4. Motion-Vektoren bei Kamerabewegung die dokumentierte Richtung haben;
5. UI nicht in generierten Frames verschmiert oder doppelt erscheint;
6. Shader-/Resource-Reload keine alten Handles verwendet;
7. unsupported Kombinationen keine nativen Provider-Aufrufe erhalten;
8. Diagnose und Benutzeroberfläche den echten Zustand melden;
9. mindestens ein System je GPU-Hersteller getestet wurde, bevor
   herstellerübergreifender Support behauptet wird.

## 13. Diagnose- und Logging-Vertrag

Jeder Supportbericht sollte ohne Debugger mindestens folgende Werte liefern:

```text
Minecraft version
Fabric Loader version
dlss_fg version/commit
Sodium present/version
Iris present/version
Iris shader pack active
requested graphics backend
actual GpuDevice class
backend name/description
Sodium draw backend, falls sicher bestimmbar
GPU vendor/device/driver
Vulkan instance/device/queue/family
swapchain handle + generation + image index
render/output dimensions
color/depth/output handles + formats + layouts
command buffer state
depth convention
motion-vector convention
UI separation state
native provider + capabilities + rejection reason
last accepted frame ID
last skipped frame reason
```

Logs dürfen nicht nur „unsupported“ schreiben. Beispiele für präzise Gründe:

- `IRIS_SHADERPACK_REQUIRES_OPENGL`
- `VULKAN_DEVICE_NOT_READY`
- `SODIUM_VERSION_UNTESTED`
- `COMMAND_BUFFER_NOT_RECORDING`
- `ACTIVE_RENDER_PASS_NOT_ENDED`
- `COLOR_DEPTH_FRAME_MISMATCH`
- `UNKNOWN_IMAGE_LAYOUT`
- `SWAPCHAIN_GENERATION_CHANGED`
- `NATIVE_PROVIDER_REJECTED_RESOURCES`
- `MOTION_VECTOR_CONVENTION_UNKNOWN`

## 14. Bekannte Fehlannahmen, die eine KI vermeiden muss

1. **„Sodium war gefunden, also funktioniert Vulkan.“**  
   Falsch. Das konkrete aktive `GpuDevice` und die Ressourcen entscheiden.

2. **„Iris war gefunden, also sind Shader aktiv.“**  
   Falsch. Die öffentliche Iris-API muss den aktiven Packzustand melden.

3. **„Iris + Vulkan bedeutet Iris-Shader unter Vulkan.“**  
   Falsch im geprüften Stand. Iris lässt die JAR geladen, blockiert aber Shaderpacks.

4. **„SodiumVulkanAdapter ist bereits eine vollständige Integration.“**  
   Falsch. Er delegiert momentan nur an den Vanilla-Adapter.

5. **„colortex0 ist immer die finale HUD-lose Farbe.“**  
   Nicht garantiert. Finalpass, Ping-Pong-Zustand und Farbraum sind relevant.

6. **„Ein vorhandener Tiefenpuffer reicht.“**  
   Falsch. Reversed-Z, Near/Far, Layout, Auflösung und Matrix müssen passen.

7. **„Kamerareprojektion ersetzt vollständige Motion Vectors.“**  
   Nur teilweise. Entity-/Partikel-/Vegetationsbewegung benötigt zusätzliche Daten
   oder eine bildbasierte Rekonstruktion mit Artefakten.

8. **„OpenGL-Textur-ID kann an Vulkan/Streamline übergeben werden.“**  
   Falsch. Dafür wäre explizites External-Memory-/Semaphore-Interop nötig.

9. **„Ein CPU-Copy macht Iris schnell kompatibel.“**  
   Technisch als Prototyp denkbar, für FG-Leistung und Latenz ungeeignet.

10. **„`suggests` in fabric.mod.json garantiert Support.“**  
    Falsch. Es ist lediglich eine optionale Empfehlung.

11. **„Minecrafts Projektionskonvention ist noch normales Z.“**  
    Falsch für 26.2; Reversed-Z muss berücksichtigt werden.

12. **„Neuere Sodium-/Iris-Versionen sind automatisch kompatibel.“**  
    Falsch. Interne Renderpfade und Mixins können sich ändern.

## 15. Entscheidungsbaum für die Laufzeit

```text
Ist das endgültige GpuDevice Vulkan?
 ├─ Nein
 │   ├─ Iris-Shaderpack aktiv?
 │   │   ├─ Ja: Vulkan-FG/RT deaktivieren; Iris/OpenGL als Grund anzeigen
 │   │   └─ Nein: Vulkan-FG/RT deaktivieren; OpenGL als Grund anzeigen
 │   └─ Keine Vulkan-Handles oder nativen Vulkan-Provider aufrufen
 └─ Ja
     ├─ Iris geladen?
     │   ├─ Ja und Shaderpack angeblich aktiv:
     │   │   inkonsistenter Zustand; fail-closed und Diagnose
     │   └─ Ja, Shaderpack aus: normal mit Vulkan-Prüfung fortfahren
     ├─ Sodium geladen?
     │   ├─ Ja: Version + Mojang-Vulkan-Ressourcenvertrag validieren
     │   └─ Nein: Vanilla-Vulkan-Ressourcenvertrag validieren
     ├─ Alle Ressourcen gehören zum selben Frame und zur selben Generation?
     │   ├─ Nein: Frame überspringen
     │   └─ Ja: Synchronisation/Layout validieren
     └─ Nativer Provider akzeptiert die Ressourcen?
         ├─ Nein: Funktion deaktivieren bzw. Frame überspringen, Grund anzeigen
         └─ Ja: Providerarbeit aufzeichnen und Present-Vertrag erfüllen
```

## 16. Empfohlene Produktkommunikation

Bis die Testmatrix abgeschlossen ist, sollte die öffentliche Beschreibung nicht
pauschal „Sodium und Iris unterstützt“ sagen. Präziser:

> Sodium wird mit Minecrafts Vulkan-Backend unterstützt, sobald die getestete
> Versionskombination und die Laufzeitdiagnose den Vulkan-Ressourcenpfad bestätigen.
> Iris darf installiert sein, aber Iris-Shaderpacks benötigen derzeit OpenGL und
> können deshalb nicht gleichzeitig mit den Vulkan-basierten Frame-Generation-,
> Upscaling- und Raytracing-Funktionen verwendet werden.

Nach erfolgreicher Phase 1 kann „Sodium 0.9.1 + Vulkan getestet“ ergänzt werden.
Aktive Iris-Shaderpacks dürfen erst nach einem echten, getesteten Interop-Backend
als unterstützt beworben werden.

## 17. Offene Fragen vor der späteren Implementierung

1. Ist der `GameRendererMixin`-Capture-Punkt mit Sodiums beiden Vulkan-Draw-Backends
   garantiert außerhalb eines aktiven Renderpasses?
2. Welches Layout haben Mojangs Hauptfarb- und Tiefenbild exakt an diesem Punkt?
3. Ist der über `VulkanCommandEncoderAccessor` gelesene Buffer immer der Encoder,
   der den aktuellen Frame besitzt?
4. Kann die globale `activeColorImage`-Registrierung vollständig entfernt und durch
   das konkrete Frame-RenderTarget ersetzt werden?
5. Welche Semaphoren erwartet jeder native Provider wirklich: Acquire, Render
   Complete, eigener Timeline-Wert oder Proxy-intern?
6. In welchem Farbraum liegt das Display-Target vor und nach UI-Komposition?
7. Wie werden Sodium-interne Renderer-Rebuilds zuverlässig erkannt?
8. Welche genauen Iris-/Sodium-Artefakte sind für optionale Compile-only-APIs
   veröffentlicht?
9. Soll aktive Iris/OpenGL-Nutzung langfristig nur gut erklärt deaktiviert bleiben,
   oder wird ein eigenes Interop-Projekt finanziert?
10. Welche PBR-Konvention soll ein zukünftiger `MaterialProvider` zuerst
    unterstützen?

## 18. Arbeitsanweisung für den nächsten KI-Agenten

Vor jeder Codeänderung:

1. aktuellen Git-Status prüfen und fremde Änderungen nicht überschreiben;
2. Minecraft-, Sodium- und Iris-Versionen erneut feststellen;
3. die verlinkten Primärquellen auf Änderungen prüfen;
4. zuerst Phase 0 implementieren und testen;
5. danach Phase 1 in kleinen, getrennten Änderungen ausführen;
6. keine Funktion allein aufgrund einer Mod-ID freischalten;
7. keine OpenGL-Ressource als Vulkan-Handle interpretieren;
8. keine internen Sodium-/Iris-Klassen ohne Versionswächter verwenden;
9. bei unbekanntem Layout, Lifecycle oder Framebezug fail-closed bleiben;
10. Dokumentation, Übersetzungen, Diagnose und Testnachweise zusammen mit dem Code
    aktualisieren.

Die bevorzugte Reihenfolge ist:

```text
korrekte Erkennung
→ atomarer Ressourcenvertrag
→ Sodium-Vulkan-Validierung
→ Synchronisation und Resize-Härtung
→ Iris-installiert/Vulkan-Test
→ klare Iris/OpenGL-Ablehnung
→ erst danach optionales OpenGL–Vulkan-Interop
→ anschließend Map-Support als separates Vorhaben
```

## 19. Endfazit

Die Mod ist architektonisch deutlich näher an Sodium-Kompatibilität als an
Iris-Shaderpack-Kompatibilität:

- Sodium 0.9.1 kann auf Mojangs Vulkan-Backend rendern. Die vorhandene Delegation
  kann deshalb die richtige Basis sein, braucht aber echte Lifecycle-, Ressourcen-
  und Synchronisationsbeweise.
- Iris 1.11.2 kann installiert sein, bietet seine Shaderpacks unter Vulkan aber
  bewusst nicht an.
- Aktive Iris-Shaderpacks laufen über OpenGL und liefern nicht die Vulkan-
  Ressourcen, die der aktuelle FG-/Upscaling-/RT-Pfad verlangt.
- Die sinnvollste kurzfristige Umsetzung ist vollständiger, getesteter
  Sodium-Vulkan-Support plus ehrliches fail-closed Verhalten für Iris-Shaderpacks.
- Ein späterer aktiver Iris-Shader-Support ist als eigenständiges
  OpenGL–Vulkan-Interop- oder Iris-Vulkan-Projekt zu behandeln, nicht als kleiner
  Adapter.
