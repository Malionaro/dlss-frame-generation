package org.malionaro.dlss_frame_generation.client.integration;

import org.malionaro.dlss_frame_generation.client.FrameIdentity;

import java.util.Optional;

/**
 * Optional contract for renderer-owned PBR material guide buffers.
 *
 * <p>No Iris or Aperture resources are assumed. A renderer integration must
 * explicitly supply all guides for the exact frame identity.</p>
 */
public interface MaterialProvider {
    record MaterialResources(
            FrameIdentity identity,
            long albedoImage,
            long normalRoughnessImage,
            long specularImage,
            int width,
            int height
    ) {
        public boolean isCompleteFor(FrameIdentity expected) {
            return expected != null
                    && identity != null
                    && identity.belongsToSameFrame(expected)
                    && albedoImage != 0L
                    && normalRoughnessImage != 0L
                    && specularImage != 0L
                    && width > 0
                    && height > 0;
        }
    }

    Optional<MaterialResources> resourcesFor(FrameIdentity identity);
}
