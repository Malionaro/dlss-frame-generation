package org.malionaro.dlss_frame_generation.client.interop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class D3D12InteropRequirementsTest {
    @Test
    void enablesAllSupportedWin32InteropExtensions() {
        var selection = D3D12InteropRequirements.selectForTest(
                List.of("VK_KHR_swapchain"),
                D3D12InteropRequirements.REQUIRED_DEVICE_EXTENSIONS,
                true
        );

        assertTrue(selection.zeroCopyInteropPossible());
        assertTrue(selection.missingExtensions().isEmpty());
        assertTrue(selection.extensions().containsAll(
                D3D12InteropRequirements.REQUIRED_DEVICE_EXTENSIONS
        ));
    }

    @Test
    void oneMissingExtensionDisablesInterop() {
        var supported = D3D12InteropRequirements
                .REQUIRED_DEVICE_EXTENSIONS.subList(0, 3);
        var selection = D3D12InteropRequirements.selectForTest(
                List.of(), supported, true
        );

        assertFalse(selection.zeroCopyInteropPossible());
        assertFalse(selection.missingExtensions().isEmpty());
    }
}
