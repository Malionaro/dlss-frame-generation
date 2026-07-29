package org.malionaro.dlss_frame_generation.client.render.adapter;
import org.malionaro.dlss_frame_generation.client.frame.FrameIdentity;
import org.malionaro.dlss_frame_generation.client.frame.FrameResourceSet;

import com.mojang.blaze3d.pipeline.RenderTarget;

import java.util.Optional;

/**
 * Interface that abstracts various rendering pipelines (Vanilla Vulkan, Sodium Vulkan, OpenGL Fallback).
 * Provides native Vulkan handles as longs (pointers) to decouple JVM code from platform-specific types
 * and allow clean compile-time fallbacks.
 */
public interface RendererBackendAdapter {
    /**
     * Captures every resource needed for one frame in a single operation.
     * Implementations must return empty rather than combining stale handles.
     */
    default Optional<FrameResourceSet> captureFrameResources(
            RenderTarget renderTarget,
            RenderTarget outputTarget,
            FrameIdentity identity
    ) {
        return Optional.empty();
    }

    long getVulkanInstance();
    long getPhysicalDevice();
    long getVulkanDevice();
    long getGraphicsQueue();
    int getGraphicsQueueFamilyIndex();
    long getSwapchain();
    long getCurrentCommandBuffer();
    
    // Render Targets
    long getColorImage();
    long getDepthImage();
    
    // Frame Metrics
    int getRenderWidth();
    int getRenderHeight();
    int getCurrentFrameIndex();
    
    // Status and Sync
    boolean isFrameReadyForFG();
    default void registerFrameCompletionCallback(Runnable callback) {}
    default long getAcquireSemaphore() { return 0; }
    default long getPresentSemaphore() { return 0; }
}
