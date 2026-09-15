package org.malionaro.dlss_frame_generation.client.interop;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalVulkanExtensionsTest {
    @Test
    void addsAvailableOptionalAndReportsIt() {
        var enabled = new LinkedHashSet<String>();

        var added = OptionalVulkanExtensions.applyOptionalExtensions(
                Set.of("VK_EXT_debug_utils", "VK_KHR_swapchain"),
                enabled,
                OptionalVulkanExtensions.INSTANCE_EXTENSIONS,
                "instance"
        );

        assertEquals(Set.of("VK_EXT_debug_utils"), added);
        assertTrue(enabled.contains("VK_EXT_debug_utils"));
    }

    @Test
    void skipsUnavailableOptionalSilently() {
        var enabled = new LinkedHashSet<>(Set.of("VK_KHR_swapchain"));

        var added = OptionalVulkanExtensions.applyOptionalExtensions(
                Set.of("VK_KHR_swapchain"),
                enabled,
                OptionalVulkanExtensions.INSTANCE_EXTENSIONS,
                "instance"
        );

        assertTrue(added.isEmpty());
        assertEquals(Set.of("VK_KHR_swapchain"), enabled);
    }

    @Test
    void duplicateEnableIsNotReportedAgain() {
        var enabled = new LinkedHashSet<>(Set.of("VK_EXT_debug_utils"));

        var added = OptionalVulkanExtensions.applyOptionalExtensions(
                Set.of("VK_EXT_debug_utils"),
                enabled,
                OptionalVulkanExtensions.INSTANCE_EXTENSIONS,
                "instance"
        );

        assertTrue(added.isEmpty());
        assertEquals(1, enabled.size());
    }

    @Test
    void lowLatency2FlowsThroughNvidiaDeviceHelper() {
        var enabled = new HashSet<String>();

        var added = OptionalVulkanExtensions.applyNvidiaDeviceOptionals(
                Set.of("VK_NV_low_latency2"), enabled);

        assertEquals(Set.of("VK_NV_low_latency2"), added);
        assertEquals(Set.of("VK_NV_low_latency2"),
                OptionalVulkanExtensions.lastDeviceOptionals());
    }

    @Test
    void missingLowLatency2LeavesDeviceSetUntouched() {
        var enabled = new HashSet<>(Set.of("VK_KHR_swapchain"));

        var added = OptionalVulkanExtensions.applyNvidiaDeviceOptionals(
                Set.of(), enabled);

        assertTrue(added.isEmpty());
        assertEquals(Set.of("VK_KHR_swapchain"), enabled);
        assertTrue(OptionalVulkanExtensions.lastDeviceOptionals().isEmpty());
    }
}
