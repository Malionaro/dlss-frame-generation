package org.malionaro.dlss_frame_generation.client.integration;

import org.malionaro.dlss_frame_generation.client.frame.FrameIdentity;
import org.malionaro.dlss_frame_generation.client.frame.FrameResourceSet;

import java.util.Optional;

/**
 * Public boundary for future Vulkan renderer integrations such as Aperture.
 *
 * <p>The current mod does not claim an Aperture implementation. A future
 * integration can provide atomic frame resources without exposing or mixing
 * private renderer internals throughout the core pipeline.</p>
 */
public interface RendererIntegration extends AutoCloseable {
    String id();

    String version();

    boolean isAvailable();

    Optional<FrameResourceSet> captureFrame(FrameIdentity identity);

    default Optional<MaterialProvider> materialProvider() {
        return Optional.empty();
    }

    default void onSwapchainGenerationChanged(long generation) {
    }

    @Override
    default void close() {
    }
}
