package org.malionaro.dlss_frame_generation.client;

/**
 * Interface that abstracts various rendering pipelines (Vanilla Vulkan, Sodium Vulkan, OpenGL Fallback).
 * Provides native Vulkan handles as longs (pointers) to decouple JVM code from platform-specific types
 * and allow clean compile-time fallbacks.
 */
public interface RendererBackendAdapter {
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
