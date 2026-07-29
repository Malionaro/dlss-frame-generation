package org.malionaro.dlss_frame_generation.client.diagnostics;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Converts verbose native provider diagnostics into short, localizable UI
 * states. The unmodified provider string is still written to latest.log.
 */
public final class BackendStatusFormatter {
    public enum StatusKind {
        ACTIVE,
        AVAILABLE,
        NO_SUPPORTED_ADAPTER,
        D3D12_REQUIRED,
        INPUTS_PENDING,
        NATIVE_BRIDGE_UNAVAILABLE,
        UNAVAILABLE
    }

    private BackendStatusFormatter() {
    }

    public static StatusKind classify(
            String nativeStatus,
            boolean supported,
            boolean active
    ) {
        if (active) {
            return StatusKind.ACTIVE;
        }

        String normalized = nativeStatus == null
                ? ""
                : nativeStatus.toLowerCase(Locale.ROOT);
        if (normalized.contains("nosupportedadapterfound")
                || normalized.contains("no supported adapter")) {
            return StatusKind.NO_SUPPORTED_ADAPTER;
        }
        if (normalized.contains("needs d3d12")
                || normalized.contains("requires d3d12")
                || normalized.contains("d3d12-only")) {
            return StatusKind.D3D12_REQUIRED;
        }
        if (normalized.contains("native compatibility layer unavailable")
                || normalized.contains("native bridge mismatch")
                || normalized.contains("unsatisfiedlinkerror")) {
            return StatusKind.NATIVE_BRIDGE_UNAVAILABLE;
        }
        if (normalized.contains("did not accept")
                || normalized.contains("waiting")
                || normalized.contains("pending")
                || normalized.contains("not ready")
                || normalized.contains("inactive")) {
            return StatusKind.INPUTS_PENDING;
        }
        return supported ? StatusKind.AVAILABLE : StatusKind.UNAVAILABLE;
    }

    public static Component description(
            String nativeStatus,
            boolean supported,
            boolean active
    ) {
        String key = switch (classify(nativeStatus, supported, active)) {
            case ACTIVE -> "dlss_fg.backend_status.active";
            case AVAILABLE -> "dlss_fg.backend_status.available";
            case NO_SUPPORTED_ADAPTER ->
                    "dlss_fg.backend_status.no_supported_adapter";
            case D3D12_REQUIRED -> "dlss_fg.backend_status.d3d12_required";
            case INPUTS_PENDING -> "dlss_fg.backend_status.inputs_pending";
            case NATIVE_BRIDGE_UNAVAILABLE ->
                    "dlss_fg.backend_status.native_bridge_unavailable";
            case UNAVAILABLE -> "dlss_fg.backend_status.unavailable";
        };
        return Component.translatable(key);
    }

    public static boolean isUsable(
            String nativeStatus,
            boolean supported
    ) {
        StatusKind kind = classify(nativeStatus, supported, false);
        return kind == StatusKind.AVAILABLE
                || kind == StatusKind.INPUTS_PENDING;
    }
}
