# Per-part entity motion vectors

Status: implemented, not yet tested in game (Minecraft 26.3, Vulkan backend).

## Problem

The motion pass (`frame_generation_motion.comp`) builds vectors from camera
reprojection plus a colour search. `ObjectMotion` adds an exact *translation*
for up to 64 moving entities (bounding box + delta). A walking mob's swinging
legs, a turning head or a body rotating in place were still left to the
colour search, which smears or ghosts on DLSS-FG / FSR frame generation.

## Why not a rasterised velocity buffer

Engines usually draw a velocity target with the current and previous
transforms in the vertex shader. In 26.3 that would mean:

- an extra attachment and a second matrix set in every entity
  `RenderPipeline` / shader (`entity_*`, armour, glint, outline, ...);
- shader packs (Iris, Vitrail) replace those shaders, so the hooks would
  conflict with them;
- renderer mods (Sodium) take over `ModelPart` cube drawing.

## Approach: analytic rigid-part motion in the existing compute pass

Minecraft entity models are rigid cuboids attached to `ModelPart`s. For any
visible model point, `previous = P_prev * inverse(P_cur) * current`, where
`P` is the part's camera-relative pose. That needs only the part poses and
cube bounds — no extra raster pass.

### Capture (CPU, render thread / feature preparation)

1. `EntityRenderDispatcherMixin` tags each `EntityRenderState` returned by
   `extractEntity` with the entity id (`EntityRenderStateMixin` adds the field
   via `EntityIdHolder`).
2. `ModelFeatureRendererMixin` injects into `ModelFeatureRenderer.prepareModel`
   right before `Model.renderToBuffer` — after `setupAnim(state)`, so the parts
   hold this frame's animation. `submit.pose()` is the camera-relative entity
   pose (camera position subtracted, no view rotation).
3. `EntityPartCapture` walks the model like `ModelPart.render` does
   (`visible`, `skipDraw`, `translateAndRotate`, children) on its own
   `PoseStack`. It does not hook `ModelPart`, so Sodium's replacement of cube
   drawing does not matter. Cube bounds come from the cube vertices
   (`Vertex.worldX()` etc., grow included) and are cached per part.
4. `EntityPartMotion` stores `(entityId, ModelPart) -> pose, cube bounds`. The
   first pose of a part per frame wins (outline/duplicate submits).

### Frame boundary and pairing

`ObjectMotion.update` (from `VulkanRegistry.beginWorldFrame`, before the
level is drawn) calls `EntityPartMotion.beginFrame`: last frame's poses become
history, and the camera move (`previous camera - current camera`, double
precision) is kept. When the motion uniform is written (after the level was
drawn) each part seen in both frames yields:

- `toLocal = inverse(P_cur)` — current camera-relative point to part space;
- `toPrevious = T(cameraMove) * P_prev` — part space to the previous position,
  relative to the *current* camera, which is what
  `ObjectMotion.cameraRelativeToPreviousClip` expects.

A reset (teleport, big turn, resize) drops the history.

Entities that stood still but had parts last frame are now candidates for the
64 `ObjectMotion` boxes too (delta 0), so heads turning on idle mobs count.

### GPU data

- New storage buffer, binding 7 of the motion descriptor set, one per frame
  slot, host-visible: `EntityPartMotion.MAX_PARTS` = 2048 entries × 128 bytes
  (`vec4 toLocal[3], vec4 toPrevious[3], vec4 boundsMin, vec4 boundsMax`,
  rows of the affine matrices). One entry per cube.
- The uniform layout is unchanged: `min.w` / `max.w` of each entity box carry
  the first entry and entry count as uint bits.

### Shader

For a pixel whose surface point lies in an entity box, every cube of that
entity is tested in part space. A visible point lies on a face of its own
cube, so the cube with the smallest distance to its surface (within a
depth-precision tolerance `0.03 + 0.004 * distance`) wins; a limb beats the
body it sinks into. The point goes through `toPrevious` and then
`cameraRelativeToPreviousClip`, exactly like the translation path, and is
marked exact (no colour search, reactive 0).

No cube hit: the old box translation, but for an entity with a model only
above the box margin below the feet (that is ground). No translation either
(standing entity): ordinary camera reprojection + colour search.

## Compatibility

- **Shader packs** (`ForeignShaderPipeline.active()`): `VulkanRegistry`
  disables `EntityPartMotion` every frame while a pack runs, which clears all
  poses; the part buffer stays empty and behaviour equals the old one.
- **Sodium**: only `ModelFeatureRenderer.prepareModel` and `extractEntity` are
  hooked; the cube drawing path is untouched and ignored.
- All injections use `require = 0`: if another mod (EMF, etc.) reshapes those
  methods, the mod falls back to box translation instead of failing to start.

## Limits / not covered

- Parts drawn outside `ModelFeatureRenderer` (held items, block models carried
  by endermen, item entities, item/block displays, name tags) keep box
  translation or the colour search.
- Block-entity models (chest lids, bells) carry no entity id, so they are not
  captured.
- First-person hands are a separate pass and not covered.
- Per-vertex deformation does not exist in vanilla models; mods that add it
  (e.g. animation mods with bent limbs) are approximated rigidly.
- 2048 cubes ≈ 64 entities × 32 cubes; beyond that the nearest entities keep
  their parts and the rest fall back to translation.

## Test on the game PC

1. Walk past a zombie / villager herd, frame generation on, debug motion view:
   limbs should show their own vectors, the ground under feet should not.
2. Idle mobs turning their heads: head pixels move, body stays static.
3. Iris with a pack: no change compared to before (log has no errors).
4. Sodium installed: same as 1.
