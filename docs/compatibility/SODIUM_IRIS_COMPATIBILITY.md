# Sodium and Iris compatibility

> Technical research and implementation handover for humans and AI agents  
> Research status: July 28, 2026  
> Project goal: `dlss_fg` / Vulkan frame generation integration for Minecraft 26.2

## 1. Purpose and binding limits

This document records the current state of knowledge regarding this mod's compatibility with
Sodium and Iris. The initial research section documents the
situation before implementation; the following status block describes the
state actually implemented afterwards.

The most important rule for later work is:

> The presence of a mod is not proof of compatibility. A feature may
> only be enabled when the backend, resources, synchronization, and native
> provider have been proven usable for the specific frame.

Map/minimap support, Distant Horizons, and a general port of Iris to Vulkan are not part of this document. They may be planned later as separate efforts.

### 1.1 Implementation status after the compatibility refactor

Statically implemented and built:

- `RendererDetector` detects Mojang's actual `GpuDevice`, renderer, and
  Sodium/Iris versions and, when the Iris API is present, optionally queries the shader pack state.
  Unknown or contradictory states block Vulkan FG, RT, and
  motion debug with a localized reason.
- `FrameIdentity` and `FrameResourceSet` capture color, depth, output,
  backbuffer, command buffer, semaphore, layouts, queue family, and
  swapchain generation atomically. Stale, incomplete, or
  sets captured during a render pass are discarded.
- `SodiumVulkanAdapter` intentionally uses Mojang's public Vulkan resources,
  but only reports readiness when the full atomic contract
  is satisfied. No private Sodium internals were included.
- `RendererIntegration` and `MaterialProvider` form an optional,
  frame-identified contract for later Aperture integration.
  No Aperture provider currently exists and no claim is made about
  Iris/Aperture PBR material support.
- Diagnostics separate integration status, provider readiness, and actual
  frame/present acceptance. UI toggles show translated block reasons.

Automated tests cover renderer/Iris states, atomic resources,
swapchain generations, provider lifecycle/exclusivity, and depth/motion
conventions. Still open is the real runtime test with the actual
Sodium 0.9.1 and Iris 1.11.2 JARs; without it, no claim is made that these
combinations are hardware-validated. Minecraft 26.3 remains a prepared
but separately tested hook/mixin target.

## 2. Examined versions and reproducible sources

The statements were checked against the following specific source states:

| Component | Checked state |
|---|---|
| Minecraft | 26.2, corresponding to the current project build |
| Sodium | 0.9.1, tag `mc26.2-0.9.1`, commit `2cfb93d332791502446d8226005b0b6baff40355` |
| Iris | 1.11.2, branch `26.2`, commit `ff6f5aa1174f3bfcb540e405a9c259d29ff42b21` |
| Iris ShaderDoc | Commit `ddf473726710c6504422d192784c1652f8aece98` |
| This project | local state at the time this document was created |

These versions must be checked again before implementation. In particular,
internal Sodium and Iris classes must not be treated as a stable API.

Primary sources:

- [Sodium repository](https://github.com/CaffeineMC/sodium)
- [Sodium 0.9.1](https://github.com/CaffeineMC/sodium/tree/2cfb93d332791502446d8226005b0b6baff40355)
- [Sodium Vulkan backend selection](https://github.com/CaffeineMC/sodium/blob/2cfb93d332791502446d8226005b0b6baff40355/common/src/main/java/net/caffeinemc/mods/sodium/client/gpu/device/backend/DrawBackend.java)
- [Sodium metadata and version conflicts](https://github.com/CaffeineMC/sodium/blob/2cfb93d332791502446d8226005b0b6baff40355/fabric/src/main/resources/fabric.mod.json)
- [Sodium API source tree](https://github.com/CaffeineMC/sodium/tree/2cfb93d332791502446d8226005b0b6baff40355/common/src/api/java/net/caffeinemc/mods/sodium/api)
- [Iris repository](https://github.com/IrisShaders/Iris)
- [Checked Iris state](https://github.com/IrisShaders/Iris/tree/ff6f5aa1174f3bfcb540e405a9c259d29ff42b21)
- [Public Iris API v0](https://github.com/IrisShaders/Iris/blob/ff6f5aa1174f3bfcb540e405a9c259d29ff42b21/common/src/api/java/net/irisshaders/iris/api/v0/IrisApi.java)
- [Iris blocks shaders under Vulkan](https://github.com/IrisShaders/Iris/blob/ff6f5aa1174f3bfcb540e405a9c259d29ff42b21/common/src/main/java/net/irisshaders/iris/compat/sodium/config/ShaderPackScreenPlaceholder.java)
- [Iris separates Vulkan and OpenGL mixins](https://github.com/IrisShaders/Iris/blob/ff6f5aa1174f3bfcb540e405a9c259d29ff42b21/common/src/main/java/net/irisshaders/iris/mixin/IrisMixinPlugin.java)
- [Iris OpenGL rendering pipeline](https://github.com/IrisShaders/Iris/blob/ff6f5aa1174f3bfcb540e405a9c259d29ff42b21/common/src/main/java/net/irisshaders/iris/pipeline/IrisRenderingPipeline.java)
- [Iris ShaderDoc](https://github.com/IrisShaders/ShaderDoc/tree/ddf473726710c6504422d192784c1652f8aece98)
- [Iris ShaderDoc: Entity motion vectors](https://github.com/IrisShaders/ShaderDoc/blob/ddf473726710c6504422d192784c1652f8aece98/unsupported-features.md)
- [Iris 26.2 reversed-Z issue report](https://github.com/IrisShaders/Iris/issues/3241)
- [NVIDIA Streamline](https://github.com/NVIDIA-RTX/Streamline)
- [AMD FidelityFX FSR 3.1](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK)

## 3. Short verdict

### 3.1 Compatibility matrix

| Configuration | Current verdict | Reason |
|---|---|---|
| Vanilla + Vulkan | intended main path | The mod accesses Mojang's Vulkan device, render targets, command buffer, and swapchain directly. |
| Sodium 0.9.1 + Vulkan | architecture hardened, real mod runtime test still open | Sodium uses Mojang's Vulkan backend. The adapter now only consumes complete atomic Mojang Vulkan resources and does not access private Sodium classes; resize/reload with the real Sodium JAR still needs validation. |
| Sodium + OpenGL | no Vulkan FG/RT features | The current native paths expect Vulkan handles and Vulkan images. |
| Iris installed + Vulkan | Iris may stay installed; shader packs are off | Iris disables its normal shader pipeline under Vulkan. After that, the relevant render path largely corresponds to "Sodium + Vulkan". |
| Iris + active shader pack | currently not compatible with the Vulkan FG/RT paths | Iris forces OpenGL for shader packs. Its final color, depth, and material data are OpenGL resources, not `VkImage` resources. |
| Iris without active shader pack + OpenGL | no Vulkan FG/RT features | No usable Vulkan context, even though Iris is loaded. |
| Future Iris with Vulkan shaders | re-evaluate | This would require explicit resource and synchronization contracts from Iris. |

### 3.2 What "Sodium-compatible" can realistically mean

Sodium replaces parts of the world renderer in Minecraft 26.2 and internally selects a
Vulkan multidraw path when Mojang's active `GpuDevice` is a Vulkan device. As of the checked state, Sodium
does not create a second independent public
swapchain. Therefore this mod can likely continue to use the Vulkan resources managed by Mojang.

This is a good starting point, but not finished support. The following must be proven:

1. The capture point chosen in `GameRendererMixin` lies after Sodium's complete
   world output and outside an active render pass.
2. Color and depth images actually belong to the frame and render target whose
   matrices and resolution are passed in.
3. The registered `VulkanCommandEncoder` object owns the
   command buffer ready for recording at that point.
4. Layouts, access rights, and queue ownership are not merely assumed.
5. Swapchain reconfiguration, fullscreen toggles, and Sodium reloads do not invalidate
   cached handles.

### 3.3 What "Iris-compatible" can realistically mean

There are two very different cases:

- **Iris is installed, Minecraft runs with Vulkan:** Iris does not run shader packs.
  The mod can use its Sodium/Vulkan path.
- **An Iris shader pack is active:** Minecraft runs with OpenGL. The current
  Vulkan features of this mod must remain visibly and understandably disabled.

Full support for active Iris shader packs would not be a small
compatibility patch. It would be a separate renderer interop project.

## 4. Audit of the initial state before implementation

### 4.1 Existing detection

`RendererDetector` checks:

- `FabricLoader.isModLoaded("sodium")`
- `FabricLoader.isModLoaded("iris")`
- Text from `RenderSystem.getBackendDescription()`

It then selects a backend based on the words `vulkan` or `opengl`.

Problems:

- A description text is not a stable machine API.
- `sodiumPresent` does not say whether Sodium's Vulkan draw path is active.
- `irisPresent` does not say whether a shader pack is active.
- An early query can run before the final graphics device has been
  built.
- A later Vulkan mixin activation overwrites the provisional adapter,
  so the startup log and the actual state can diverge.

### 4.2 Existing Sodium adapter

`SodiumVulkanAdapter` delegates every method unchanged to
`VanillaVulkanAdapter`. It has:

- no Sodium API call,
- no Sodium version check,
- no proof of the active Sodium draw backend,
- no separate render lifecycle hook,
- no check for mixin conflicts.

The class is therefore currently a name for a plausible route, not a complete
Sodium integration. That is not automatically wrong: if Mojang's Vulkan resources
remain the authoritative resources, delegation is the desired solution. It
must, however, be backed by runtime validation and tests.

### 4.3 Existing vanilla Vulkan adapter

The adapter reads:

- Instance, physical device, logical device, and graphics queue from
  `VulkanRegistry.getActiveDevice()`;
- Swapchain, image index, and semaphore via accessor mixins on
  `VulkanGpuSurface`;
- The current command buffer via an accessor mixin on
  `VulkanCommandEncoder`;
- Color primarily from `VulkanRegistry.getActiveColorImage()`, otherwise from the
  `mainRenderTarget`;
- Depth from the `mainRenderTarget`;
- Dimensions from the `mainRenderTarget`.

Risks:

- `activeColorImage` is registered during the surface blit and may be older than the
  render target currently passed in.
- Color, depth, backbuffer, and matrices are assembled from multiple sources.
  There is no shared frame ID or resource identity.
- The adapter exposes raw handles but no formats, layouts, subresources,
  generations, or lifetimes.
- `isFrameReadyForFG()` checks for non-null values, not whether they belong to the same
  frame.
- `getCurrentFrameIndex()` returns `0` on failure; that can make an unknown
  state look like a valid index.

### 4.4 Capture and presentation timings

`GameRendererMixin`:

1. remembers the display render target;
2. can create a smaller `MainTarget` for the world;
3. replaces the `mainRenderTarget` before `renderLevel`;
4. captures scene, depth, and motion before the vanilla call that clears depth;
5. can render the UI into a separate full-size render target;
6. captures the UI after `GuiRenderer.endFrame`;
7. blends the UI back onto the display target.

`VulkanGpuSurfaceMixin`:

- optionally replaces create/getImages/acquire/present/destroy of the swapchain with
  provider calls;
- reports configure start and configure end;
- marks present start and present end;
- registers the color image used during blit.

`VulkanRegistry.captureFrameGenerationInputs()`:

- requires Vulkan views for color, depth, and output;
- fetches the command buffer from the registered encoder;
- determines the current swapchain image;
- optionally runs ray reconstruction;
- copies/generates dense motion vectors;
- passes matrices, jitter, depth, UI state, and resources to the native provider.

Compatibility therefore depends not only on the world renderer. It depends on a
complete contract between the world pass, post-processing, HUD, command encoder,
swapchain, and native provider.

### 4.5 Current mod metadata

`fabric.mod.json` lists Sodium and Iris only under `suggests`. That means:

- Both mods are optional.
- Fabric does not check a tested version.
- The entry does not prove technical compatibility.
- Users can install an incompatible combination without the loader
  stopping them.

Later, either a narrow tested version range should be documented or a
runtime compatibility table should be maintained. A hard `depends` is only needed
if the mod should no longer work without Sodium/Iris; that is not
recommended here.

## 5. What Sodium 0.9.1 actually does under Minecraft 26.2

### 5.1 Vulkan is a real Sodium path

Sodium selects in `DrawBackend.chooseBackend()` based on Mojang's active
`GpuDevice`:

- `VK_MULTIDRAW` when a `VulkanDevice` and direct interleaved multidraw
  are available;
- `VK_INDIRECT` when a `VulkanDevice` and indirect multidraw are available;
- otherwise `OPENGL`.

The Vulkan draw contexts internally access Mojang's `VulkanRenderPass`, its
command buffer, and pipeline layout. So Sodium does not simply render with
an OpenGL compatibility layer over Vulkan.

### 5.2 There is no suitable stable Sodium resource API

The public Sodium API source tree provides APIs for vertex data, block entity
predicates, textures, and configuration. As of the checked state, there is no public
API for:

- current world color image,
- current depth image,
- current Vulkan command buffer,
- render pass end,
- swapchain,
- semaphores or timeline values,
- motion vectors.

Classes such as `VKDrawContext`, `VulkanRenderPassAccessor`, or Sodium's internal
renderer management are implementation details. Direct mixins against these classes
must be treated as version-bound workarounds.

### 5.3 Version relationship to Iris

Sodium 0.9.1 declares a conflict with Iris `<=1.11.1`. For the checked
Minecraft version the test baseline must therefore use Iris 1.11.2 or newer within a
compatible 1.11.x range. Sodium also conflicts with the separate
legacy mod `vulkanmod`. This entry must not be confused with Mojang's built-in
Vulkan backend in Minecraft 26.2.

### 5.4 Recommended Sodium strategy

Prefer no deep Sodium integration, but instead:

1. Authoritatively detect Mojang's active `GpuDevice` and concrete backend class.
2. Use Mojang's `RenderTarget` Vulkan views as the resource source.
3. Secure the capture point against the Minecraft frame graph/lifecycle.
4. Report Sodium only as an additional renderer property and tested variant.
5. Only add a small, separately
   versioned Sodium interop hook when a reproducible error requires it.

This strategy minimizes breakage on Sodium updates and respects the
PolyForm Shield license: do not copy or embed any Sodium implementation. Use only
permissible optional linking or runtime interop and
re-check the license before any release.

## 6. What Iris 1.11.2 actually does under Minecraft 26.2

### 6.1 Shader packs and Vulkan are currently mutually exclusive

Iris shows a placeholder screen under Vulkan stating that Iris cannot run
under Vulkan and offers to switch to OpenGL. The Sodium
graphics setting also warns that switching to Vulkan removes shader capability.

`IrisMixinPlugin` reads the desired backend and loads:

- normal Iris mixins only without Vulkan;
- specially named `VKOnly` mixins only with Vulkan.

That does not mean the Iris JAR is generally unloadable with Vulkan. It means
that the actual shader pipeline does not run on Vulkan.

### 6.2 The public Iris API is sufficient for reliable detection

The public API v0 provides:

```java
IrisApi.getInstance().isShaderPackInUse()
```

This method is the correct query when Iris is optionally installed. It
returns `true` only when a shader pack is actually used for rendering.
It also returns `false` when no pack is active or its compilation
has failed.

However, the API does not provide public handles for:

- `colortex` render targets,
- `depthtex` textures,
- final shader output,
- OpenGL synchronization,
- motion vectors,
- shader pack material parameters.

Later integration may optionally use `isShaderPackInUse()`, but must not
derive resource availability from it.

### 6.3 Iris rendering is OpenGL-centric

`IrisRenderingPipeline` uses `GlTexture`, `GlFramebuffer`,
`GlProgram`, OpenGL samplers, and OpenGL compute programs, among others. The sequence is:

1. `beginLevelRendering()`
2. World, shadow, prepare, deferred, and composite passes
3. `finalizeLevelRendering()`
4. `compositeRenderer.renderAll()`
5. `finalPassRenderer.renderFinalPass()`
6. Later `finalizeGameRendering()` and color space conversion of the
   `mainRenderTarget`

A capture before Iris's final/composite passes would be visually wrong. A capture
afterward still yields an OpenGL image, not a `VkImage`.

### 6.4 Shader pack resources are not a stable FG contract

Shader packs can own multiple `colortex` targets, depth copies, and arbitrary
composite passes. Common names such as `colortex0`, `depthtex0`,
`depthtex1`, and `depthtex2` are shader pack semantics, but not a public
Java interop API for this mod.

Iris's implementation copies:

- depth for `depthtex1` before translucency;
- depth for `depthtex2` before the hand.

Which image qualifies as the final FG color depends on the finished final pass,
color space, and backbuffer. An internal `colortex0` must not be assumed
to be the HUD-free final color across the board.

Particularly important: Iris ShaderDoc lists entity motion vectors as an unimplemented
feature. A shader pack can generate its own velocity data, but
there is no general contract this mod can rely on.

### 6.5 Reversed-Z and projection

Minecraft 26.2 uses reversed depth: the depth buffer is cleared with `0.0`,
larger values lie closer to the camera. This project also clears its
Vulkan depth images with `0.0`.

Iris issue #3241 shows that projection or culling code easily retains old assumptions
when this switch happens. Every later resource contract must therefore
explicitly carry:

- whether depth is reversed/inverted;
- NDC range and clip control;
- near/far values;
- projection matrix convention;
- Y orientation;
- whether the matrix is jittered or unjittered;
- motion vector direction: current to previous or previous to current;
- unit: pixels, UV, or NDC.

Without this information, a provider must not accept the frame.

## 7. Resource contract required by the mod

A later renderer interop layer must deliver all of the following data atomically for the same
frame:

| Resource/data | Minimum requirement | Sodium + Vulkan | Iris + shader/OpenGL |
|---|---|---|---|
| World color without HUD | valid image after world post-processing | likely Mojang Vulkan render target | OpenGL only, no public export API |
| Display/backbuffer | image, format, color space, index | Mojang Vulkan surface | OpenGL default/blit target |
| Depth | image, format, convention, layout | Mojang Vulkan render target | OpenGL, multiple Iris depth copies |
| Motion vectors | RG16F, defined direction/unit, dense | project-internal reconstruction possible | no standardized entity motion vectors |
| UI separate | full resolution, alpha, color space | project-internal Vulkan `MainTarget` | Iris/GUI lifecycle would need to be tapped separately |
| Command buffer | ready for recording, outside render pass | Mojang Vulkan encoder | not present |
| Synchronization | producer/consumer stages, semaphore/fence | solvable internally in Vulkan | GL/Vulkan interop required |
| Matrices/jitter | exactly matching the image | from `GameRenderState`, still to be validated | Iris can affect projection/temporal state |
| Material/PBR | defined material channel | currently heuristic/voxel-based | pack-dependent GL textures and G-buffer |

### 7.1 Recommended data model

The current `RendererBackendAdapter` should later be supplemented or replaced by a stricter
frame contract. A possible model:

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

Resources should not be fetched as loose `long` handles from different methods.
An immutable set prevents combining color from frame N,
depth from frame N+1, and swapchain index from frame N-1.

### 7.2 Required capability fields

At minimum:

- `graphicsApi`: Vulkan/OpenGL/unknown
- `minecraftBackend`: concrete active backend class
- `sodiumPresent`, `sodiumVersion`, `sodiumDrawBackend`
- `irisPresent`, `irisVersion`, `irisShaderPackActive`
- `rendererResourceInterop`
- `worldColorAvailable`
- `depthAvailable`
- `motionVectorsAvailable`
- `uiSeparationAvailable`
- `swapchainInterceptionAvailable`
- `nativeProviderReady`
- `supportedReason` or localizable rejection reason

Capability states should not be purely boolean. Useful states are:

- `SUPPORTED`
- `UNSUPPORTED`
- `NOT_READY`
- `VERSION_UNTESTED`
- `CONFLICT`
- `ERROR`

## 8. Concrete implementation plan

### Phase 0: Truthful detection and UI

Goal: no new renderer feature yet, just correct states.

1. After the graphics device is created, check the concrete `GpuDevice`/backend class;
   use description text only for diagnostics.
2. Use the Fabric mod container to log Sodium/Iris versions.
3. If Iris is loaded, optionally query the public `IrisApi`.
4. Show an active shader pack + OpenGL as an unambiguous rejection reason:
   "Iris shader packs currently use OpenGL; this feature requires Vulkan."
5. Do not report Iris installed + Vulkan as an error, but instead:
   "Iris loaded; shader packs disabled under Vulkan."
6. Do not silently report an untested/newer Sodium version as fully supported.
7. Diagnostics must show the backend, mod versions, shader pack status, and the first missing
   resource contract.

Acceptance criterion: no Vulkan feature is clickable under OpenGL; every
disabled toggle has an understandable, localized reason.

### Phase 1: Prove the Sodium-Vulkan baseline

1. Fix the test baseline to Sodium 0.9.1 and, if applicable, Iris 1.11.2.
2. Use Vulkan validation layers to check whether the capture call happens outside an
   active render pass.
3. Log a per-frame identity for render target, color, depth, output,
   swapchain generation, and image index.
4. Check the origin of `activeColorImage`. Prefer using the `colorView`
   of the render target passed to `captureFrameGenerationInputs` directly,
   instead of preferring a globally registered blit handle.
5. Do not only remember the active command buffer at encoder construction. Prove its
   validity at the capture point.
6. Ensure that Sodium's Vulkan world pass has finished before recording compute/copy.
7. Test shader/resource reload, world change, dimension change, resize, and fullscreen.

Acceptance criterion: all tests run without validation errors, wrong layouts,
device loss, flickering old frames, or wrong depth.

### Phase 2: Harden the layout and synchronization contract

The pipeline currently uses assumed layouts in several places, such as
`VK_IMAGE_LAYOUT_GENERAL`. For external Minecraft/Sodium resources this must not
apply blindly.

Required:

1. define the actual initial layout or a guaranteed handover point;
2. end active render passes before external barriers;
3. set source/destination stages and access masks to match the real producer;
4. handle queue family ownership explicitly;
5. tag all old resources with their generation on swapchain recreation and
   destroy them only after safe GPU release;
6. do not combine acquire/present semaphores based on potentially different indices;
7. the provider proxy and the Mojang surface must see the same swapchain lifecycle.

If synchronization cannot be proven, the frame must be skipped.
A skipped frame is better than undefined GPU behavior.

### Phase 3: Iris installed, Vulkan active

This case likely needs no Iris render target integration:

1. confirm that `isShaderPackInUse()` is false;
2. use the Sodium-Vulkan contract from phase 1;
3. show the Iris version and Vulkan deactivation of shaders in diagnostics;
4. do not manipulate the shader selection button; Iris itself explains the backend switch;
5. require a full restart after a backend switch and do not reuse old
   Vulkan handles.

Acceptance criterion: Iris can stay installed while the mod runs under Vulkan as
without an active shader pack.

### Phase 4: Deliberately not supporting Iris shader packs under OpenGL

The recommended product decision for now is **fail-closed**:

- DLSS/FSR/XeSS frame generation switches off;
- Vulkan RT and ray reconstruction off;
- Vulkan motion debug off;
- Diagnostics and OSD may continue to work as long as they do not need Vulkan
  resources;
- UI explains the reason and the possible switch to Vulkan without a shader pack.

This phase is complete when the behavior is clear, stable, and crash-free.
It is not "incomplete" just because active shader packs do not get Vulkan FG.

### Phase 5: Optional research project OpenGL–Vulkan interop

Start only if active Iris shader packs must be supported and
time for a separate renderer backend effort is available.

Required topics:

- Export/import of external image memory between OpenGL and Vulkan;
- External semaphores/fences;
- Windows- and Linux-specific handle types;
- NVIDIA, AMD, and Intel driver differences;
- Exportability of Iris's OpenGL textures;
- Final color after the Iris final pass and color space conversion;
- HUD-free color and UI alpha;
- Depth format and reversed-Z;
- Separate dense motion vector pass including entity motion;
- Resize, reload, and shader pack switch;
- Copy cost and latency;
- Provider requirements for the same API/queue/device.

A CPU copy OpenGL -> RAM -> Vulkan is not
acceptable for frame generation. It causes high bandwidth, latency, and synchronization stalls.

Before implementing this phase, propose an official
resource/lifecycle hook to Iris. Direct access to
`IrisRenderingPipeline`, `RenderTargets`, `GlFramebuffer`, or private
`colortex` IDs is only version-bound and costly to maintain.

## 9. Material, PBR, and ray tracing compatibility

If "material" refers to the material representation of the mod, the answer
must be considered separately from pure load/crash compatibility.

### 9.1 Current state

This project's ray tracing path derives world geometry and material properties
via its own Vulkan/voxel data and heuristics. Iris shader packs, by contrast, can:

- use their own normal and specular textures;
- evaluate LabPBR or other resource pack conventions;
- interpret block IDs and material identifiers in a pack-dependent way;
- compute their lighting entirely in their own G-buffer/composite passes.

Even if both mods load at the same time, materials therefore
do not automatically match. A reflective or emissive surface in the
shader pack can look different in the project's own RT path.

### 9.2 Future clean abstraction

Recommended:

```text
MaterialProvider
 ├─ VanillaMaterialProvider
 ├─ ResourcePackPbrMaterialProvider
 └─ FutureIrisMaterialProvider
```

A material record should at least contain:

- base color and alpha mode;
- normal source;
- roughness/smoothness;
- metalness;
- emission;
- refraction/translucency;
- texture/atlas coordinates;
- block/biome tint;
- version/pack identity.

A future Iris provider must not assume that every pack uses the same
channels. It needs a documented pack convention or
explicit cooperation with Iris. Until then, diagnostics must state:
"Shader pack materials are not carried over to the Vulkan RT path."

## 10. Mixin and dependency strategy

### 10.1 Prefer the public API

- Iris: optionally compile against the public API v0 and use exclusively
  `isShaderPackInUse()` for the support state.
- Sodium: no suitable public render resource API exists; use Mojang's
  backend.

### 10.2 Optional dependencies

When build artifacts are available:

- `modCompileOnly`/equivalent compile-only dependency;
- do not load any Iris/Sodium classes from the unbranched base path;
- encapsulate integration in its own class;
- only call the class after Fabric has confirmed the mod presence;
- handle `NoClassDefFoundError`, API revision errors, and linkage errors as fail-closed.

### 10.3 Optional internal mixins

Only if phase 1 proves a concrete need:

- own mixin configuration for Sodium/Iris interop;
- plugin checks mod ID and tested version range;
- `required: false`;
- injections with low/targeted expectations and understandable diagnostics;
- do not shut down the game globally when an optional hook is not found;
- every internal hook gets an integration test and a source state note.

Do not "guess" a private class via reflection. If the version does not match,
disable the affected feature.

### 10.4 License notes

- Sodium: PolyForm Shield 1.0.0 as of the checked state.
- Iris: LGPL-3.0-only; subcomponents may have additional licenses.

Before binary distribution, a legal review must clarify which optional
compile/link/bundle forms are permissible. In particular, do not embed an unchecked Sodium or
Iris JAR or copied source code in this mod.

## 11. Proposed future file mapping

This list is a working map, not an instruction to change all files mandatorily.

| Area | Existing file | Future task |
|---|---|---|
| Backend detection | `RendererDetector.java` | concrete device detection, mod versions, Iris shader status |
| Support state | `CompatibilityManager.java` | structured capabilities and localizable reasons |
| Resource contract | `RendererBackendAdapter.java` | atomic `FrameResourceSet` instead of loose handles |
| Sodium | `SodiumVulkanAdapter.java` | only validated Mojang Vulkan delegation; optional version status |
| Vulkan base | `VanillaVulkanAdapter.java` | frame identity, layout, format, generation, error states |
| Frame lifecycle | `GameRendererMixin.java` | secure capture point against Sodium and Iris |
| Present/swapchain | `VulkanGpuSurfaceMixin.java` | manage generation and synchronization robustly |
| Registry | `VulkanRegistry.java` | no independent global resource leftovers; diagnostics |
| Motion pipeline | `motion/VulkanFrameGenerationPipeline.java` | explicit depth/MV conventions and layouts |
| UI | Settings/diagnostics screens | understandable states for Sodium/Iris |
| Translations | `assets/dlss_fg/lang/*.json` | all new reasons and hints in every language |
| Metadata | `fabric.mod.json` | document tested versions, no false promise |
| Tests | existing test sources | add matrix and capability tests |

## 12. Test matrix

### 12.1 Required combinations

At minimum:

| GPU | Backend | Sodium | Iris | Shader pack |
|---|---|---:|---:|---:|
| NVIDIA RTX 40 | Vulkan | off | off | off |
| NVIDIA RTX 40 | Vulkan | on | off | off |
| NVIDIA RTX 40 | Vulkan | on | on | off/blocked under Vulkan |
| NVIDIA RTX 40 | OpenGL | on | on | on |
| AMD RX 6000/7000 | Vulkan | on | off/on | off |
| Intel Arc | Vulkan | on | off/on | off |
| one vendor each | OpenGL | on | off | off |

For Iris/OpenGL the expected success must be "cleanly disabled and well explained",
not "FG active".

### 12.2 Required scenarios per supported Vulkan combination

- Game launch and world join;
- Dimension change;
- Leave world and re-enter;
- Resize window multiple times;
- Fullscreen on/off;
- Alt-tab and minimized window;
- Resource pack reload;
- Change Sodium settings that rebuild the renderer;
- Iris installed, then backend switch and restart;
- Pause menu and very low FPS;
- Teleport/camera jump/FOV jump;
- HUD on/off and UI recomposition;
- Upscaling on/off and quality mode change;
- Frame generation on/off and multiplier change;
- Ray tracing/ray reconstruction on/off;
- Motion debug in all modes.

### 12.3 Technical tooling

- Vulkan validation layers;
- RenderDoc, if the proxy swapchain path allows capture;
- NVIDIA Nsight Graphics;
- AMD Radeon GPU Profiler/Analyzer;
- apitrace or RenderDoc for a separate Iris/OpenGL research path;
- structured project logs with frame and swapchain generation.

### 12.4 Hard acceptance criteria

Support may only be called "compatible" when:

1. no Vulkan validation errors occur;
2. ten resize/fullscreen toggles work without device loss;
3. color, depth, motion, and matrices carry the same frame ID;
4. motion vectors have the documented direction for camera movement;
5. UI does not smear or appear duplicated in generated frames;
6. shader/resource reload does not use old handles;
7. unsupported combinations do not receive native provider calls;
8. diagnostics and UI report the true state;
9. at least one system per GPU vendor was tested before
   cross-vendor support is claimed.

## 13. Diagnostics and logging contract

Every support report should provide at least the following values without a debugger:

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
Sodium draw backend, if reliably determinable
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

Logs must not just write "unsupported". Examples of precise reasons:

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

## 14. Known false assumptions an AI must avoid

1. **"Sodium was found, so Vulkan works."**  
   Wrong. The specific active `GpuDevice` and resources decide.

2. **"Iris was found, so shaders are active."**  
   Wrong. The public Iris API must report the active pack state.

3. **"Iris + Vulkan means Iris shaders under Vulkan."**  
   Wrong as of the checked state. Iris leaves the JAR loaded but blocks shader packs.

4. **"SodiumVulkanAdapter is already a complete integration."**  
   Wrong. It currently only delegates to the vanilla adapter.

5. **"colortex0 is always the final HUD-free color."**  
   Not guaranteed. Final pass, ping-pong state, and color space matter.

6. **"A present depth buffer is enough."**  
   Wrong. Reversed-Z, near/far, layout, resolution, and matrix must match.

7. **"Camera reprojection replaces complete motion vectors."**  
   Only partially. Entity/particle/vegetation motion needs additional data
   or an image-based reconstruction with artifacts.

8. **"An OpenGL texture ID can be passed to Vulkan/Streamline."**  
   Wrong. That would require explicit external memory/semaphore interop.

9. **"A CPU copy makes Iris quickly compatible."**  
   Technically conceivable as a prototype, unsuitable for FG performance and latency.

10. **"suggests in fabric.mod.json guarantees support."**  
     Wrong. It is merely an optional recommendation.

11. **"Minecraft's projection convention is still regular Z."**  
     Wrong for 26.2; reversed-Z must be taken into account.

12. **"Newer Sodium/Iris versions are automatically compatible."**  
     Wrong. Internal render paths and mixins can change.

## 15. Runtime decision tree

```text
Is the final GpuDevice Vulkan?
 ├─ No
 │   ├─ Iris shader pack active?
 │   │   ├─ Yes: disable Vulkan FG/RT; show Iris/OpenGL as reason
 │   │   └─ No: disable Vulkan FG/RT; show OpenGL as reason
 │   └─ Do not call any Vulkan handles or native Vulkan providers
 └─ Yes
     ├─ Iris loaded?
     │   ├─ Yes and shader pack allegedly active:
     │   │   inconsistent state; fail-closed and diagnose
     │   └─ Yes, shader pack off: continue normally with Vulkan check
     ├─ Sodium loaded?
     │   ├─ Yes: validate version + Mojang Vulkan resource contract
     │   └─ No: validate vanilla Vulkan resource contract
     ├─ Do all resources belong to the same frame and generation?
     │   ├─ No: skip frame
     │   └─ Yes: validate synchronization/layout
     └─ Does the native provider accept the resources?
         ├─ No: disable feature or skip frame, show reason
         └─ Yes: record provider work and satisfy present contract
```

## 16. Recommended product messaging

Until the test matrix is complete, the public description should not
say "Sodium and Iris supported" across the board. More precise:

> Sodium is supported with Minecraft's Vulkan backend once the tested
> version combination and runtime diagnostics confirm the Vulkan resource path.
> Iris may be installed, but Iris shader packs currently require OpenGL and
> therefore cannot be used at the same time as the Vulkan-based frame generation,
> upscaling, and ray tracing features.

After successful phase 1, "Sodium 0.9.1 + Vulkan tested" can be added.
Active Iris shader packs may only be advertised as supported after a real, tested interop backend
exists.

## 17. Open questions before future implementation

1. Is the `GameRendererMixin` capture point guaranteed to be outside an active render pass with both of Sodium's Vulkan draw backends?
2. What layout do Mojang's main color and depth images have exactly at that point?
3. Is the buffer read via `VulkanCommandEncoderAccessor` always the encoder
   that owns the current frame?
4. Can the global `activeColorImage` registration be fully removed and replaced by
   the concrete frame render target?
5. What semaphores does each native provider actually expect: acquire, render
   complete, custom timeline value, or proxy-internal?
6. What color space does the display target have before and after UI composition?
7. How are Sodium-internal renderer rebuilds reliably detected?
8. What exact Iris/Sodium artifacts are published for optional compile-only APIs?
9. Should active Iris/OpenGL usage remain cleanly disabled with a good explanation long-term,
   or will a separate interop project be funded?
10. Which PBR convention should a future `MaterialProvider` support
    first?

## 18. Work instructions for the next AI agent

Before any code change:

1. check the current git status and do not overwrite external changes;
2. re-establish Minecraft, Sodium, and Iris versions;
3. check the linked primary sources for changes;
4. implement and test phase 0 first;
5. then execute phase 1 in small, separate changes;
6. do not enable any feature based solely on a mod ID;
7. do not interpret any OpenGL resource as a Vulkan handle;
8. do not use any internal Sodium/Iris classes without a version guard;
9. stay fail-closed for unknown layout, lifecycle, or frame association;
10. update documentation, translations, diagnostics, and test evidence together with the code.

The preferred order is:

```text
correct detection
→ atomic resource contract
→ Sodium-Vulkan validation
→ synchronization and resize hardening
→ Iris-installed/Vulkan test
→ clear Iris/OpenGL rejection
→ only then optional OpenGL–Vulkan interop
→ then map support as a separate effort
```

## 19. Final conclusion

The mod is architecturally much closer to Sodium compatibility than to
Iris shader pack compatibility:

- Sodium 0.9.1 can render on Mojang's Vulkan backend. The existing delegation
  can therefore be the right foundation, but it needs real lifecycle, resource,
  and synchronization proof.
- Iris 1.11.2 can be installed but deliberately does not offer its shader packs
  under Vulkan.
- Active Iris shader packs run via OpenGL and do not provide the Vulkan
  resources required by the current FG/upscaling/RT path.
- The most sensible near-term implementation is complete, tested
  Sodium-Vulkan support plus honest fail-closed behavior for Iris shader packs.
- Later active Iris shader support should be treated as a standalone
  OpenGL–Vulkan interop or Iris-Vulkan project, not as a small
  adapter.
