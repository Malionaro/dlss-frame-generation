package org.malionaro.dlss_frame_generation.client.raytracing;

/**
 * Renderer-owned HDR lighting and material guides consumed by Streamline
 * DLSS Ray Reconstruction. Every image is at the low world-render resolution
 * and remains valid until the current frame is presented.
 */
public record RayReconstructionResources(
        ImageResource noisyColor,
        ImageResource diffuseAlbedo,
        ImageResource specularAlbedo,
        ImageResource normalRoughness,
        ImageResource specularHitDistance,
        int width,
        int height
) {
    public record ImageResource(
            long image,
            long memory,
            long view,
            int format
    ) {
        public boolean valid() {
            return image != 0 && memory != 0 && view != 0 && format != 0;
        }
    }

    public boolean valid() {
        return width > 0
                && height > 0
                && noisyColor.valid()
                && diffuseAlbedo.valid()
                && specularAlbedo.valid()
                && normalRoughness.valid()
                && specularHitDistance.valid();
    }
}
