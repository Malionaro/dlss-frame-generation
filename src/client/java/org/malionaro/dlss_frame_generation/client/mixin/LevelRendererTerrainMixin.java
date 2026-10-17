package org.malionaro.dlss_frame_generation.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.malionaro.dlss_frame_generation.client.raytracing.HardwareRayTracingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * RT engine, primary visibility: terrain sections the RT pass fully covers
 * are left out of vanilla's draw list, which saves their raster cost. The RT
 * pass draws them instead and decides per pixel whether an entity or
 * particle is in front, so occlusion stays right without their depth.
 *
 * <p>Optional (require = 0): if the target ever changes, terrain simply keeps
 * rendering the vanilla way.</p>
 */
@Mixin(LevelRenderer.class)
public class LevelRendererTerrainMixin {
    @Unique
    private BlockPos dlss_fg$currentOrigin;

    @Redirect(
            method = "extractSectionDrawGroups",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;getRenderOrigin()Lnet/minecraft/core/BlockPos;"),
            require = 0
    )
    private BlockPos dlss_fg$rememberOrigin(SectionRenderDispatcher.RenderSection section) {
        BlockPos origin = section.getRenderOrigin();
        dlss_fg$currentOrigin = origin;
        return origin;
    }

    @Redirect(
            method = "extractSectionDrawGroups",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionMesh;getSectionDraw(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/minecraft/client/renderer/chunk/SectionMesh$SectionDraw;"),
            require = 0
    )
    private SectionMesh.SectionDraw dlss_fg$skipRtCoveredSection(SectionMesh mesh, ChunkSectionLayer layer) {
        BlockPos origin = dlss_fg$currentOrigin;
        if (origin != null && HardwareRayTracingManager.replacesRasterSection(origin)) {
            return null;
        }
        return mesh.getSectionDraw(layer);
    }
}
