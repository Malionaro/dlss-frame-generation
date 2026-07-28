package org.malionaro.dlss_frame_generation.client;

public record RendererDetection(RendererBackend backend, String backendDescription, boolean sodiumPresent,
                                boolean irisPresent) {
}
