package org.malionaro.dlss_frame_generation.client.access;

import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;

import java.util.Set;

/**
 * Duck interface implemented by the VulkanDevice mixin. It must live outside
 * the configured mixin package so ordinary game code may reference it.
 */
public interface VulkanDeviceAccess {
    VulkanPhysicalDevice dlss_fg$getPhysicalDevice();
    Set<String> dlss_fg$getEnabledExtensions();
}
