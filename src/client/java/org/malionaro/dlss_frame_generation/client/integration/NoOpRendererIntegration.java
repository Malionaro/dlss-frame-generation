package org.malionaro.dlss_frame_generation.client.integration;

import org.malionaro.dlss_frame_generation.client.frame.FrameIdentity;
import org.malionaro.dlss_frame_generation.client.frame.FrameResourceSet;

import java.util.Optional;

/**
 * D8: reference no-op for the future renderer-integration boundary. Exists so
 * the default methods of {@link RendererIntegration} are covered by tests and
 * cannot silently rot; production code continues to use no integration until a
 * real Aperture-style provider ships.
 */
public final class NoOpRendererIntegration implements RendererIntegration {
    public static final String ID = "noop";
    public static final String VERSION = "1.0.0";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public Optional<FrameResourceSet> captureFrame(FrameIdentity identity) {
        return Optional.empty();
    }
}
