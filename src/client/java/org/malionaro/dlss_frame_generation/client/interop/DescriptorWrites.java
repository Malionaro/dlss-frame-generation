package org.malionaro.dlss_frame_generation.client.interop;

import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * LWJGL 3.4 (Minecraft 26.3) no longer derives {@code descriptorCount} from the
 * buffer passed to {@code pImageInfo}/{@code pBufferInfo}. A write left at count
 * 0 updates nothing, so every descriptor stayed empty: the RT shader saw a 1 x 1
 * output image, a zero frame uniform and lost every storage-buffer write.
 */
public final class DescriptorWrites {
    private DescriptorWrites() {
    }

    /**
     * Gives every write that has no count yet a count of one, the size of every
     * write in this mod, and returns the same buffer.
     */
    public static VkWriteDescriptorSet.Buffer singleDescriptors(VkWriteDescriptorSet.Buffer writes) {
        for (int index = writes.position(); index < writes.limit(); index++) {
            VkWriteDescriptorSet write = writes.get(index);
            if (write.descriptorCount() == 0) {
                write.descriptorCount(1);
            }
        }
        return writes;
    }
}
