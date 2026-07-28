package org.malionaro.dlss_frame_generation.client.motion;

/**
 * Abstraction representing Motion Vector resources (VkImage / VkBuffer)
 * and their coordinate conventions across NVIDIA DLSS, AMD FSR, and Intel XeSS.
 */
public class MotionVectorResource {

    public enum ResourceType {
        VK_IMAGE,
        VK_BUFFER,
        CAMERA_MATRIX_APPROXIMATION
    }

    public enum MotionSpace {
        SCREEN_SPACE_NDC,      // [-1, 1]
        SCREEN_SPACE_PIXELS,   // [0, Width] x [0, Height]
        ABSOLUTE_WORLD_SPACE
    }

    private final ResourceType resourceType;
    private final long nativeHandle;
    private final int width;
    private final int height;
    private final MotionSpace motionSpace;
    private final boolean yAxisInverted;
    private final boolean cameraMotionIncluded;
    private final boolean objectMotionIncluded;

    public MotionVectorResource(ResourceType resourceType, long nativeHandle, int width, int height,
                                MotionSpace motionSpace, boolean yAxisInverted,
                                boolean cameraMotionIncluded, boolean objectMotionIncluded) {
        this.resourceType = resourceType;
        this.nativeHandle = nativeHandle;
        this.width = width;
        this.height = height;
        this.motionSpace = motionSpace;
        this.yAxisInverted = yAxisInverted;
        this.cameraMotionIncluded = cameraMotionIncluded;
        this.objectMotionIncluded = objectMotionIncluded;
    }

    public ResourceType resourceType() { return resourceType; }
    public long nativeHandle() { return nativeHandle; }
    public int width() { return width; }
    public int height() { return height; }
    public MotionSpace motionSpace() { return motionSpace; }
    public boolean isYAxisInverted() { return yAxisInverted; }
    public boolean isCameraMotionIncluded() { return cameraMotionIncluded; }
    public boolean isObjectMotionIncluded() { return objectMotionIncluded; }
}
