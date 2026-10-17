# RT engine plan

Goal: the RT pass renders the world itself, the way Radiance and AetherRT do,
instead of shading over Minecraft's raster image. Our own code only: Radiance
is GPL-3.0 and AetherRT is All Rights Reserved, so neither is copied.

An own world renderer cannot run together with Iris shader packs; with a pack
active the RT engine stays off (as today).

## Stages

1. **Sky and clouds** *(done)*: vanilla clouds are switched off while
   RT runs in the overworld; volumetric clouds are ray-marched in the RT pass
   and lit by the sun with self-shadowing. Analytic sky for reflections and
   bounce light, light shafts, auto exposure, fog (done).
2. **Real block geometry** *(done, untested)*: triangle BLAS from the baked block models (stairs,
   plants, torches, cross models, rotated faces) instead of one box per
   collision shape; UVs from the model quads, so textures match the raster
   exactly.
3. **Entities in RT** *(done, untested; per-cube texture colours)*: the entity vertex data already captured for motion
   vectors goes into a per-frame BLAS, so mobs and players appear in
   reflections, refraction and cast exact shadows.
4. **RT primary visibility** *(done, untested; option "RT Engine Draws Near Terrain")*: RT draws every pixel inside the RT range;
   vanilla terrain rendering is skipped for those sections to win back the
   raster cost. Raster stays for the far field, the hand, particles and GUI.
5. **Denoising** *(done, untested: own temporal accumulation)*: working Ray Reconstruction, or an own temporal +
   spatial denoiser (reprojection with our motion vectors, variance-guided
   filter) when RR is off.
6. **Particles, weather and translucency** *(done, untested: RT rain, particles in reflections, raster particles kept in front of RT)*: particles and rain as RT
   geometry or composited with correct depth; stained glass and water
   layers in order.
7. **Range and LOD** *(done, untested: up to 96 blocks, coarse meshes beyond 48)*: RT range beyond 48 blocks with coarser sections far
   away.

Each stage ships on its own and is tested on real hardware before the next.
