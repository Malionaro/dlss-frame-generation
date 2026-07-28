package org.malionaro.dlss_frame_generation.client;

public final class OpenGLFallbackAdapter implements RendererBackendAdapter {
    @Override
    public long getVulkanInstance() { return 0; }

    @Override
    public long getPhysicalDevice() { return 0; }

    @Override
    public long getVulkanDevice() { return 0; }

    @Override
    public long getGraphicsQueue() { return 0; }

    @Override
    public int getGraphicsQueueFamilyIndex() { return -1; }

    @Override
    public long getSwapchain() { return 0; }

    @Override
    public long getCurrentCommandBuffer() { return 0; }

    @Override
    public long getColorImage() { return 0; }

    @Override
    public long getDepthImage() { return 0; }

    @Override
    public int getRenderWidth() { return 0; }

    @Override
    public int getRenderHeight() { return 0; }

    @Override
    public int getCurrentFrameIndex() { return 0; }

    @Override
    public boolean isFrameReadyForFG() { return false; }
}
