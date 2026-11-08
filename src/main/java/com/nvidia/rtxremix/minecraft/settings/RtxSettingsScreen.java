/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 Malionaro
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Entry point of the RTX Remix settings. The settings are one tabbed screen
 * ({@link RtxSettingsCategoryScreen}); this opens it on its first tab and holds
 * the reset of every setting, which the Display tab offers.
 */
public final class RtxSettingsScreen {

    private RtxSettingsScreen() {
    }

    /** The settings screen on its first tab. */
    public static Screen create(Screen parent, RtxSettings settings, Runnable saveSettings) {
        return new RtxSettingsCategoryScreen(parent, settings, saveSettings, SettingCategory.PERFORMANCE);
    }

    /**
     * Asks, then resets every setting and opens the settings again on
     * {@code returnTo}. A refreshed page is always a fresh screen: re-running
     * {@code init()} on one would stack a second set of widgets on its layout.
     */
    static void confirmResetAll(Screen parent, RtxSettings settings, Runnable saveSettings,
                                SettingCategory returnTo) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                boolean refreshBlockLights = settings.hasOverrides(SettingGroup.BLOCK_LIGHTS);
                settings.resetAll();
                BlockTextureManager textures = BlockTextureManager.get();
                boolean rebuildMaterials = textures != null && textures.commitTerrainEmissionMultiplier();
                if (saveSettings != null) {
                    saveSettings.run();
                }
                if (rebuildMaterials) {
                    RtxRemixMod.rebuildTerrainForMaterialSettings();
                } else if (refreshBlockLights) {
                    RtxRemixMod.refreshBlockLightIntensities();
                }
            }
            minecraft.gui.setScreen(new RtxSettingsCategoryScreen(parent, settings, saveSettings, returnTo));
        }, Component.translatable("rtxremix.settings.resetAll.confirm.title"),
                Component.translatable("rtxremix.settings.resetAll.confirm.message")));
    }
}
