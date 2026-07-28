package org.malionaro.dlss_frame_generation.client;

/**
 * Adapter specifically tailored for Sodium Vulkan.
 * In Minecraft 26.2, Sodium operates on top of the same Vulkan device, queue, and swapchain
 * initialized by the game's native backend. Hence, we reuse the resource extraction logic of
 * the Vanilla Vulkan adapter.
 */
public final class SodiumVulkanAdapter implements RendererBackendAdapter {
    private final VanillaVulkanAdapter delegate = new VanillaVulkanAdapter();

    @Override
    public long getVulkanInstance() { return delegate.getVulkanInstance(); }

    @Override
    public long getPhysicalDevice() { return delegate.getPhysicalDevice(); }

    @Override
    public long getVulkanDevice() { return delegate.getVulkanDevice(); }

    @Override
    public long getGraphicsQueue() { return delegate.getGraphicsQueue(); }

    @Override
    public int getGraphicsQueueFamilyIndex() { return delegate.getGraphicsQueueFamilyIndex(); }

    @Override
    public long getSwapchain() { return delegate.getSwapchain(); }

    @Override
    public long getCurrentCommandBuffer() { return delegate.getCurrentCommandBuffer(); }

    @Override
    public long getColorImage() { return delegate.getColorImage(); }

    @Override
    public long getDepthImage() { return delegate.getDepthImage(); }

    @Override
    public int getRenderWidth() { return delegate.getRenderWidth(); }

    @Override
    public int getRenderHeight() { return delegate.getRenderHeight(); }

    @Override
    public int getCurrentFrameIndex() { return delegate.getCurrentFrameIndex(); }

    @Override
    public boolean isFrameReadyForFG() { return delegate.isFrameReadyForFG(); }

    @Override
    public long getAcquireSemaphore() { return delegate.getAcquireSemaphore(); }

    @Override
    public long getPresentSemaphore() { return delegate.getPresentSemaphore(); }
}
