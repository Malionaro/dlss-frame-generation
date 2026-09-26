package org.malionaro.dlss_frame_generation.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.sdl.SDLScancode;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.compat.CompatibilityManager;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.ui.settings.MultiVendorSettingsScreen;

public final class KeyBindings {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("dlss_fg", "general"));

    private static KeyMapping toggleOsdKey;
    private static KeyMapping toggleFrameGenKey;
    private static KeyMapping openSettingsKey;

    private KeyBindings() {
    }

    public static void register() {
        // 26.3 replaced GLFW with SDL and dropped InputConstants.Type.SCANCODE,
        // so the only keyboard type left is KEYBOARD. Its codes are SDL scancodes.
        toggleOsdKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.dlss_fg.toggle_osd",
                InputConstants.Type.KEYBOARD,
                SDLScancode.SDL_SCANCODE_F8,
                CATEGORY
        ));

        toggleFrameGenKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.dlss_fg.toggle_fg",
                InputConstants.Type.KEYBOARD,
                SDLScancode.SDL_SCANCODE_F7,
                CATEGORY
        ));

        openSettingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.dlss_fg.open_settings",
                InputConstants.Type.KEYBOARD,
                SDLScancode.SDL_SCANCODE_END,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(KeyBindings::onClientTick);
    }

    private static void onClientTick(Minecraft client) {
        if (client == null) {
            return;
        }

        while (toggleOsdKey.consumeClick()) {
            FrameGenConfig config = FrameGenConfig.getInstance();
            config.osdEnabled = !config.osdEnabled;
            config.save();
            if (client.gui != null && client.gui.hud != null) {
                client.gui.hud.setOverlayMessage(
                        Component.translatable(config.osdEnabled
                                ? "dlss_fg.message.osd_enabled"
                                : "dlss_fg.message.osd_disabled"),
                        false
                );
            }
        }

        while (toggleFrameGenKey.consumeClick()) {
            FrameGenConfig config = FrameGenConfig.getInstance();
            CompatibilityManager comp = FrameGenMod.compatibilityManager();
            FrameGenerationBackend backend = comp != null ? comp.activeVendorBackend() : null;
            FrameGenerationBackend.GpuVendor vendor = backend != null
                    ? backend.vendor()
                    : FrameGenerationBackend.GpuVendor.NVIDIA;

            boolean currentlyEnabled = config.isFrameGenerationEnabled(vendor);
            boolean newEnabled = !currentlyEnabled;
            config.setFrameGenerationEnabled(vendor, newEnabled);
            config.save();

            if (client.gui != null && client.gui.hud != null) {
                client.gui.hud.setOverlayMessage(
                        Component.translatable(newEnabled
                                ? "dlss_fg.message.fg_enabled"
                                : "dlss_fg.message.fg_disabled"),
                        false
                );
            }
        }

        while (openSettingsKey.consumeClick()) {
            if (client.gui != null && client.gui.screen() == null) {
                client.setScreenAndShow(new MultiVendorSettingsScreen(null));
            }
        }
    }
}
