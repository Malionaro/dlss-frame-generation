# Funktionsstatus und Roadmap

Stand: 22. August 2026  
Getestetes Minecraft-Ziel: **26.2**

Diese Übersicht zeigt, welche Funktionen bereits nachgewiesen funktionieren,
welche noch echte Hardwaretests benötigen und welche nur vorbereitet oder
geplant sind. Eine sichtbare Einstellung ist nicht automatisch auf jeder GPU
verfügbar: Die Mod schaltet Funktionen nur frei, wenn GPU, Treiber, Renderer,
native Laufzeit und alle benötigten Frame-Daten akzeptiert wurden.

## Statuslegende

| Status | Bedeutung |
|---|---|
| ✅ Getestet | Automatisiert oder auf der genannten Hardware erfolgreich getestet |
| 🟡 Experimentell | Implementiert und angebunden, aber noch nicht auf allen Ziel-GPUs und Anzeigemodi validiert |
| 🧩 Grundlage vorhanden | Architektur oder Transport ist vorhanden; der eigentliche Anbieterpfad fehlt noch |
| 🔒 Nicht verfügbar | Wird absichtlich nicht aktiviert, weil eine technische Voraussetzung oder offizielle Laufzeit fehlt |
| 📅 Geplant | Noch nicht implementiert |

## Build, Paket und gemeinsame Infrastruktur

| Funktion | Status | Aktueller Stand | Noch zu tun |
|---|---|---|---|
| Java-, Native- und Shader-Build | ✅ Getestet | Lokaler Windows-Build und GitHub Actions mit Java 25 erfolgreich | Bei Änderungen weiterhin über CI prüfen |
| Einzelne Mod-JAR | ✅ Getestet | Java-Code, JNI-Bridge, Shader und zulässige Laufzeitdateien werden gemeinsam verpackt | Release-Build und Versionsnummer für Veröffentlichung festlegen |
| JNI-Anbindung | ✅ Getestet | Alle 53 Java-Native-Deklarationen besitzen passende JNI-Exports | Neue Native-Funktionen in denselben Prüfungen erfassen |
| Fabric-Registrierung | ✅ Getestet | Beide Entrypoints und alle 16 Mixins sind registriert | Für neue Minecraft-Versionen erneut prüfen |
| Provider-Auswahl | ✅ Getestet | Genau ein FG-Provider, ein Upscaler und ein Präsentationspfad dürfen gleichzeitig aktiv sein | Zusätzliche Provider in dieselbe Exklusivitätslogik integrieren |
| Atomare Frame-Daten | ✅ Getestet | Farbe, Tiefe, Motion, Frame-ID und Swapchain-Generation werden nicht zwischen Frames vermischt | Mit realen Sodium-/Iris-Läufen zusätzlich validieren |
| Sichere Rückfallebene | ✅ Getestet | Nicht unterstützte Pfade bleiben deaktiviert oder fallen auf normales Vulkan-WSI zurück | Weitere Treiber- und Fehlerfälle sammeln |

## NVIDIA

| Funktion | Status | Aktueller Stand | Noch zu tun |
|---|---|---|---|
| GPU- und Streamline-Erkennung | ✅ Getestet | NVIDIA-Gerät, Streamline-Laufzeit und einzelne Fähigkeiten werden getrennt geprüft | Auf mehreren RTX-Generationen testen |
| DLSS Super Resolution / DLAA | 🟡 Experimentell | Qualitätsmodi, optimale Renderauflösung, Upscaling vor dem nativen HUD und History-Reset sind angebunden | Bildqualität, Resize, Vollbild und alle Qualitätsmodi auf RTX-Hardware validieren |
| DLSS Frame Generation | 🟡 Experimentell | Farbe ohne HUD, Tiefe, Motion und getrennte UI-Ebene werden an Streamline übergeben; Aktivstatus erst nach Provider-Annahme | RTX-40-Hardwaretest mit VSync, Tearing, Resize und Vollbild abschließen |
| NVIDIA Reflex | 🟡 Experimentell | Pacing und Streamline-Reflex-Pfad sind angebunden | Latenz und Marker auf unterstützter RTX-Hardware messen |
| DLSS Ray Reconstruction | 🟡 Experimentell | DLSS-RR sowie HDR-, Albedo-, Normal-, Roughness- und Hit-Distance-Guides sind verbunden | Qualität und Stabilität zusammen mit Hardware-RT auf RTX testen |
| GTX-Unterstützung | ✅ Getestet, begrenzt | GTX 1660 SUPER wird korrekt erkannt; nicht vorhandenes DLSS-G und Ray Query werden abgelehnt | Keine Freischaltung nicht unterstützter RTX-Funktionen geplant |

## AMD

| Funktion | Status | Aktueller Stand | Noch zu tun |
|---|---|---|---|
| FSR 3.1 Vulkan Frame Generation | 🟡 Experimentell | Signierte FidelityFX-Vulkan-Laufzeit, Proxy-Swapchain und Prepare-/Configure-Aufrufe sind angebunden | Provider-Annahme, Pacing, Resize, Vollbild und Treiber auf AMD-Hardware validieren |
| FSR 3.1 Native AA | 🟡 Experimentell | Standardisierte FFX-Eingaben und ein separates Ausgabeziel sind vorhanden | Reale AMD-Bildqualitäts- und Stabilitätstests |
| FSR-Upscaling unterhalb 1:1 | 🟡 Experimentell | Qualität, Ausgeglichen, Leistung, Ultra-Leistung und Ultra-Qualität verwenden ein reduziertes Weltziel, FFX-Rekonstruktion und ein HUD in Anzeigeauflösung | Bildqualität, Resize und dynamische Auflösung auf AMD-Hardware validieren |
| FSR 4 Frame Generation | 🔒 Nicht verfügbar | D3D12-Transportgrundlage existiert, aber `amd_fidelityfx_framegeneration_dx12.dll` ist nicht enthalten | Offizielle weiterverteilbare Laufzeit beschaffen, Lizenz prüfen und echten Provider integrieren |

## Intel

| Funktion | Status | Aktueller Stand | Noch zu tun |
|---|---|---|---|
| XeSS / FSR-Upscaling über OptiScaler | 🟡 Experimentell | OptiScaler wählt auf Intel `xess`; Native AA und reduzierte Qualitätsmodi verwenden getrennte Welt-/Anzeigeziele | Auf Intel Arc mit Resize und allen Qualitätsmodi testen |
| FSR 3.1 Fallback auf Intel | 🟡 Experimentell | Explizit benannter, herstellerübergreifender Vulkan-Proxy-Pfad ist angebunden | Provider-Annahme, Pacing und Resize auf Intel Arc validieren |
| Intel XeSS Frame Generation | 🔒 Nicht verfügbar | D3D12-Präsentationsgrundlage existiert, aber `libxess_fg.dll` und `libxell.dll` fehlen | Offizielle Laufzeiten, XeLL-Lifecycle und Intel-Proxy-Swapchain integrieren |

## OptiScaler

| Funktion | Status | Aktueller Stand | Noch zu tun |
|---|---|---|---|
| OptiScaler 0.9.4 in der JAR | ✅ Getestet | Runtime, Lizenz, Hashmanifest und Quellcodehinweise werden mitgeliefert und automatisch entpackt | Bei Updates Version, Hashes und Lizenzen erneut prüfen |
| Automatische Anbieterauswahl | ✅ Getestet | AMD verwendet standardmäßig `fsr31`, Intel `xess`, NVIDIA bleibt auf Streamline | Weitere zulässige Provider nur nach echten Laufzeittests ergänzen |
| OptiScaler Frame Generation | 🔒 Nicht verfügbar | Die eigenen OptiScaler-FG-Hooks bleiben deaktiviert, damit nur ein Präsentationsbesitzer existiert | Nur neu bewerten, wenn ein sicherer, exklusiver Vulkan-Pfad verfügbar wird |

## Rendering, Motion und Raytracing

| Funktion | Status | Aktueller Stand | Noch zu tun |
|---|---|---|---|
| Dichte Motion Vectors | ✅ Getestet, mit Einschränkung | Reversed-Z, Current-to-Previous, positive Y-Richtung nach unten und getrennte Jitter-Daten sind festgelegt | Volle Bewegungsdaten für einzelne Entities statt temporaler Bildschätzung ergänzen |
| Motion-Debug: Pfeile | ✅ Getestet | Kamera-Vorschau und verzögerter GPU-Readback verwenden korrigierte horizontale und vertikale Achsen | Auf weiteren Seitenverhältnissen und GPUs visuell prüfen |
| Motion-Debug: Heatmap | ✅ Getestet | Blau, Grün und Rot zeigen zunehmende lokale Bewegungsstärke | Legende optional direkt im Overlay ergänzen |
| Motion-Debug: Raw Preview | ✅ Getestet | Unveränderte Motion-Werte werden farbcodiert angezeigt | Zusätzliche Zahlenanzeige für Entwickler optional ergänzen |
| Motion-Vector-Unschärfe | 🟡 Experimentell | Ein Vulkan-Compute-Pass tastet das rekonstruierte Bild entlang der dichten Motion Vectors ab; Stärke und Samples sind begrenzt, unbekannte Ausgabeformate fallen sicher zurück | Bildqualität und GPU-Kosten auf mehreren Auflösungen validieren |
| Dynamische Auflösung | 🟡 Experimentell | Geglättete Framezeiten, Hysterese und ausgerichtete Weltziele regeln schrittweise auf die Ziel-FPS; benötigt einen aktiven temporalen Upscaler | Auf NVIDIA-, AMD- und Intel-Hardware abstimmen |
| Sicherer Auto-Fallback | ✅ Getestete Policy | Bei sicher bekannter Nichtverfügbarkeit von FG wird sitzungsbezogen auf Rekonstruktion oder natives Vulkan-Present zurückgefallen, ohne Vendor-ABIs zu vermischen | Zusätzliche echte Provider nur nach Lizenz- und Hardwaretests aufnehmen |
| Tiefendaten | ✅ Getestet | Tiefe gehört atomar zum Frame und verwendet die dokumentierte Reversed-Z-Konvention | Mit allen Renderer-/Resize-Kombinationen weiter prüfen |
| Hybrides Vulkan-Raytracing | 🟡 Experimentell | BLAS/TLAS, Ray Queries, Blockschatten, Reflexionen, Raster-Tiefenprüfung und Szenen-Invalidierung sind implementiert | Auf RTX sowie Ray-Query-fähigen AMD-/Intel-GPUs validieren und optimieren |
| Vollständiges Path Tracing | 📅 Geplant / kein aktueller Umfang | Derzeit bleibt der Raster-Renderer aktiv und das RT-Ergebnis wird darübergelegt | Nur als separates größeres Renderer-Projekt umsetzen |
| Vulkan → D3D12 Präsentation | ✅ Getestet, begrenzt | Zero-Copy-Transport auf derselben GPU, gemeinsame Fences, DXGI-Flip-Swapchain, Resize und sauberer Fallback wurden auf GTX 1660 SUPER getestet | RTX-/AMD-/Intel-Tests sowie HDR-Unterstützung ergänzen |
| HDR im D3D12-Präsentationspfad | 🔒 Nicht verfügbar | Der Produktionspfad unterstützt momentan SDR; nicht unterstützte HDR-Formate fallen auf Vulkan zurück | HDR-Formate, Farbräume und Tone-Mapping-Lifecycle implementieren |

## Oberfläche und Diagnose

| Funktion | Status | Aktueller Stand | Noch zu tun |
|---|---|---|---|
| Menü unter Videoeinstellungen | ✅ Getestet | Die Mod-Einstellungen sind über die Videoeinstellungen und optional über Mod Menu erreichbar | Weitere Mod-Menü-Kombinationen prüfen |
| Kleine Fenster und GUI-Skalierung | ✅ Getestet | Inhalt ist scrollbar; Navigationsknöpfe bleiben erreichbar | Zusätzliche Auflösungen und Sprachlängen testen |
| Gegenseitige FG-Sperren | ✅ Getestet | Unvereinbare Frame-Generation- und Präsentationsoptionen können nicht gleichzeitig aktiviert werden | Neue Provider in die Konfliktmatrix aufnehmen |
| Diagnosebildschirm | ✅ Getestet | Ergebnisse besitzen sichtbaren Text, Statusfarben, Umbruch und Scrollfunktion | Weitere verständliche Lösungshinweise für seltene Treiberfehler ergänzen |
| Laufzeitstatus und Ablehnungsgrund | ✅ Getestet | Hardware-Unterstützung, Runtime-Bereitschaft und echte Provider-Annahme werden getrennt angezeigt | Zusätzliche Provider-Fehlercodes übersetzen |
| Sprachunterstützung | 🟡 Experimentell | Zentrale Bedienelemente und Beschreibungen sind lokalisiert | Alle neuen Statusmeldungen vollständig in jede unterstützte Sprache übertragen |

## Renderer- und Mod-Kompatibilität

| Kombination | Status | Aktueller Stand | Noch zu tun |
|---|---|---|---|
| Vanilla Minecraft 26.2 + Vulkan | ✅ Getestete Zielarchitektur | Aktuell kompilierter und getesteter Hauptpfad | Breitere GPU-/Treiber-Matrix testen |
| Sodium 0.9.1 + Vulkan | 🟡 Experimentell | Verwendet öffentliche Mojang-Vulkan-Ressourcen statt instabiler privater Sodium-Klassen | Echten Mod-JAR-Lauf mit Resize, Reload und Vulkan-Validierung abschließen |
| Iris installiert + Vulkan, Shaderpack aus | 🟡 Experimentell | Soll denselben Vulkan-Pfad wie Sodium verwenden; Iris-Zustand wird erkannt | Echten Iris-1.11.2-Mod-JAR-Lauf validieren |
| Aktives Iris-Shaderpack / OpenGL | 🔒 Nicht verfügbar | Die benötigten finalen Bilder sind OpenGL- statt Vulkan-Ressourcen | Nur mit einer separaten OpenGL-/Iris-Ressourcenintegration möglich |
| Aperture-Materialdaten | 🧩 Grundlage vorhanden | `RendererIntegration` und `MaterialProvider` definieren einen frame-atomaren Vertrag | Einen echten Aperture-Provider und dessen Material-/Synchronisationspfad implementieren |
| Minecraft 26.3 | 🧩 Grundlage vorhanden | Als nächstes Ziel dokumentiert, aber nicht als aktuell getestet behauptet | Rendererklassen, Mixins, Framegrenzen und ABI getrennt prüfen und versionieren |

## 2026-08-22 Fix-Runde

- H1: Drain mit `finally` + Timeout meldet jetzt FAILED statt zu blockieren.
- H2: Jitter ist im Mode 0 konsequent 0.
- H3: Vendor-Switch läuft über eine eigene UI-Layer.
- H4: Composite-Fallback greift, wenn die Komposition verweigert wird.
- H5: Pipeline-Leak behoben.
- K1/K2: Fence-Stubs entfernt bzw. sauber ersetzt.
- OOM-Latch führt jetzt zu Retry statt dauerhafter Sperrung.
- `configVersion=2` plus atomares Speichern der Konfiguration.
- Mixin-Level auf JAVA_21 angehoben.
- JNI Exception-Hygiene nachgezogen.
- D3D12-Deadlock-Fix im Hybrid-Pfad.
- OptiScaler: externer SHA256-Check vor der Nutzung.
- copyNative: Ninja-Pfad-Fix im Build.
- RT: Dirty-Chunk-Budget begrenzt Raytracing-Rebuilds pro Frame.
- DRS: adaptive Schritte für dynamische Auflösung.

## Empfohlene nächste Schritte

1. NVIDIA DLSS-G, DLSS-RR, Reflex und den D3D12-Pfad auf einer RTX-40-GPU
   mit Resize, VSync, Tearing und Vollbild validieren.
2. FSR 3.1 Frame Generation und Pacing auf einer AMD-GPU validieren.
3. XeSS Native AA sowie den benannten FSR-3.1-Fallback auf Intel Arc validieren.
4. Sodium 0.9.1 und Iris 1.11.2 als echte Mod-JARs in einer reproduzierbaren
   Testinstanz prüfen.
5. Dynamische Auflösung und Motion Blur auf NVIDIA-, AMD- und Intel-Hardware abstimmen.
6. Separate, versionsgeschützte Minecraft-26.3-Hooks erstellen.
7. Erst nach Erhalt und Lizenzprüfung der offiziellen Anbieterdateien FSR 4 FG
   oder XeSS-FG implementieren.

Weitere technische Einzelheiten stehen in
[BACKENDS.md](../architecture/BACKENDS.md),
[CAPABILITY_AUDIT.md](../architecture/CAPABILITY_AUDIT.md) und
[SODIUM_IRIS_COMPATIBILITY.md](../compatibility/SODIUM_IRIS_COMPATIBILITY.md).
