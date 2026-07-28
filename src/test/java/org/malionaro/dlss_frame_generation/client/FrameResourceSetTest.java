package org.malionaro.dlss_frame_generation.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;

class FrameResourceSetTest {
    @Test
    void identityIncludesSwapchainGenerationAndImage() {
        FrameIdentity first = new FrameIdentity(8L, 2L, 1);

        assertTrue(first.belongsToSameFrame(
                new FrameIdentity(8L, 2L, 1)));
        assertFalse(first.belongsToSameFrame(
                new FrameIdentity(8L, 3L, 1)));
        assertFalse(first.belongsToSameFrame(
                new FrameIdentity(8L, 2L, 0)));
    }

    @Test
    void completeAtomicFrameIsAccepted() {
        FrameResourceSet resources = validResources(3, 3, true);

        assertTrue(resources.validate().accepted());
    }

    @Test
    void activeRenderPassAndMixedQueueOwnershipAreRejected() {
        assertFalse(validResources(3, 3, false).validate().accepted());
        assertFalse(validResources(3, 4, true).validate().accepted());
    }

    private static FrameResourceSet validResources(
            int renderQueueFamily,
            int outputQueueFamily,
            boolean outsideRenderPass
    ) {
        FrameResourceSet.ImageResource color =
                new FrameResourceSet.ImageResource(
                        1L, 11L, VK_FORMAT_R8G8B8A8_UNORM,
                        VK_IMAGE_LAYOUT_GENERAL, renderQueueFamily, 1280, 720
                );
        FrameResourceSet.ImageResource depth =
                new FrameResourceSet.ImageResource(
                        2L, 12L, VK_FORMAT_D32_SFLOAT,
                        VK_IMAGE_LAYOUT_GENERAL, renderQueueFamily, 1280, 720
                );
        FrameResourceSet.ImageResource output =
                new FrameResourceSet.ImageResource(
                        3L, 13L, VK_FORMAT_R8G8B8A8_UNORM,
                        VK_IMAGE_LAYOUT_GENERAL, outputQueueFamily, 1920, 1080
                );
        FrameResourceSet.ImageResource backbuffer =
                new FrameResourceSet.ImageResource(
                        4L, 0L, VK_FORMAT_R8G8B8A8_UNORM,
                        VK_IMAGE_LAYOUT_UNDEFINED, outputQueueFamily, 1920, 1080
                );
        return new FrameResourceSet(
                new FrameIdentity(1L, 1L, 0),
                FrameResourceSet.Source.VANILLA_VULKAN,
                5L,
                color,
                depth,
                output,
                backbuffer,
                6L,
                7L,
                outsideRenderPass
        );
    }
}
