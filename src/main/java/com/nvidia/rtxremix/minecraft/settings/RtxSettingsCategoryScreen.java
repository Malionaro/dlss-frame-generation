/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 Malionaro
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.input.RemixInputGate;
import com.nvidia.rtxremix.minecraft.osd.PerformanceOsd;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The RTX Remix settings: one screen with a tab per category along the top, a status line
 * under the title, the category's options in the middle and quick actions at the bottom.
 * Switching tabs saves and opens a fresh screen on the other tab, never
 * {@code rebuildWidgets()}: the layout is built once and would stack a second set of
 * widgets.
 */
final class RtxSettingsCategoryScreen extends OptionsSubScreen {

    private static final int HEADER_HEIGHT = 78;
    private static final int TAB_SPACING = 2;
    private static final int FOOTER_BUTTON_WIDTH = 74;

    private final RtxSettings settings;
    private final Runnable saveSettings;
    private final SettingCategory category;

    RtxSettingsCategoryScreen(
            Screen parent,
            RtxSettings settings,
            Runnable saveSettings,
            SettingCategory category) {
        super(parent, Minecraft.getInstance().options, Component.translatable(category.labelKey()));
        this.settings = settings;
        this.saveSettings = saveSettings;
        this.category = category;
    }

    @Override
    protected void init() {
        this.layout.setHeaderHeight(HEADER_HEIGHT);
        super.init();
    }

    @Override
    protected void addTitle() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.addChild(new StringWidget(
                        Component.translatable("rtxremix.settings.title").withStyle(ChatFormatting.BOLD),
                        this.font),
                LayoutSettings::alignHorizontallyCenter);
        header.addChild(new StringWidget(statusLine(), this.font), LayoutSettings::alignHorizontallyCenter);

        List<SettingCategory> tabs = tabs();
        int tabWidth = Math.max(40, Math.min(84,
                (this.width - 20 - TAB_SPACING * (tabs.size() - 1)) / Math.max(1, tabs.size())));
        LinearLayout row = LinearLayout.horizontal().spacing(TAB_SPACING);
        for (SettingCategory tab : tabs) {
            boolean selected = tab == category;
            Component label = selected
                    ? Component.translatable(tab.labelKey()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                    : Component.translatable(tab.labelKey());
            Button button = Button.builder(label, pressed -> switchTo(tab))
                    .width(tabWidth)
                    .build();
            button.active = !selected;
            row.addChild(button);
        }
        header.addChild(row, LayoutSettings::alignHorizontallyCenter);
    }

    /** Categories that have something to show, in their declared order. */
    private List<SettingCategory> tabs() {
        List<SettingCategory> tabs = new ArrayList<>();
        for (SettingCategory candidate : SettingCategory.values()) {
            if (candidate == SettingCategory.DISPLAY || !settings.category(candidate).isEmpty()) {
                tabs.add(candidate);
            }
        }
        return tabs;
    }

    private Component statusLine() {
        Component rtx = Component.translatable(RtxRemixMod.isRtxEnabled()
                        ? "rtxremix.osd.on" : "rtxremix.osd.off")
                .withStyle(RtxRemixMod.isRtxEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED);
        return Component.translatable("rtxremix.settings.status", rtx, this.minecraft.getFps())
                .withStyle(ChatFormatting.GRAY);
    }

    @Override
    protected void addOptions() {
        if (category == SettingCategory.DISPLAY) {
            addDisplayOptions();
            return;
        }
        for (SettingGroup group : SettingGroup.values()) {
            if (group.category() != category) {
                continue;
            }
            List<RtxSetting<?>> groupSettings = settings.group(group);
            if (groupSettings.isEmpty()) {
                continue;
            }
            if (opensSubscreen(group)) {
                this.list.addSmall(
                        new StringWidget(
                                150,
                                20,
                                Component.translatable(group.labelKey()).withStyle(ChatFormatting.BOLD),
                                this.font),
                        Button.builder(
                                        Component.translatable("rtxremix.settings.configure"),
                                        button -> this.minecraft.gui.setScreen(
                                                new RtxBlockLightsScreen(
                                                        this, settings, saveSettings)))
                                .width(150)
                                .build());
                continue;
            }

            this.list.addSmall(new StringWidget(
                    310,
                    20,
                    Component.translatable(group.labelKey()).withStyle(ChatFormatting.BOLD),
                    this.font), null);

            List<OptionInstance<?>> options = new ArrayList<>();
            for (RtxSetting<?> setting : groupSettings) {
                options.add(RtxOptionFactory.toOption(setting));
            }
            if (fullWidthRows(group)) {
                options.forEach(this.list::addBig);
            } else {
                this.list.addSmall(options.toArray(new OptionInstance<?>[0]));
            }
        }
    }

    private void addDisplayOptions() {
        this.list.addSmall(new StringWidget(310, 20,
                Component.translatable("rtxremix.settings.group.display").withStyle(ChatFormatting.BOLD),
                this.font), null);
        CycleButton<Boolean> overlay = CycleButton.onOffBuilder(PerformanceOsd.isEnabled())
                .create(0, 0, 150, 20, Component.translatable("rtxremix.settings.overlay"),
                        (button, value) -> PerformanceOsd.setEnabled(value));
        overlay.setTooltip(Tooltip.create(Component.translatable("rtxremix.settings.overlay.tip")));
        CycleButton<Boolean> rtx = CycleButton.onOffBuilder(RtxRemixMod.isRtxEnabled())
                .create(0, 0, 150, 20, Component.translatable("rtxremix.settings.pathTracing"),
                        (button, value) -> {
                            if (value != RtxRemixMod.isRtxEnabled()) {
                                RtxRemixMod.toggleRtx();
                            }
                        });
        rtx.setTooltip(Tooltip.create(Component.translatable("rtxremix.settings.pathTracing.tip")));
        this.list.addSmall(overlay, rtx);

        long heapMiB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        boolean lowHeap = Runtime.getRuntime().maxMemory() < PerformanceOsd.MIN_HEAP_BYTES;
        this.list.addSmall(new StringWidget(310, 20,
                Component.translatable("rtxremix.settings.memory", heapMiB)
                        .withStyle(lowHeap ? ChatFormatting.RED : ChatFormatting.GRAY),
                this.font), null);

        this.list.addSmall(new StringWidget(310, 20,
                Component.translatable("rtxremix.settings.group.more").withStyle(ChatFormatting.BOLD),
                this.font), null);
        Button remixMenu = remixMenuButton(150);
        Button resetAll = Button.builder(Component.translatable("rtxremix.settings.resetAll"),
                        button -> RtxSettingsScreen.confirmResetAll(
                                this.lastScreen, settings, saveSettings, category))
                .tooltip(Tooltip.create(Component.translatable("rtxremix.settings.resetAll.tip")))
                .width(150)
                .build();
        this.list.addSmall(remixMenu, resetAll);
    }

    private Button remixMenuButton(int width) {
        return Button.builder(
                        Component.translatable("rtxremix.settings.openRemixMenu"),
                        // Queued so the click that pressed this button finishes
                        // dispatching before opening releases that same press.
                        button -> this.minecraft.schedule(() -> RtxRemixMod.openRemixMenu(
                                RemixInputGate.Menu.GRAPHICS_SETTINGS)))
                .tooltip(Tooltip.create(Component.translatable("rtxremix.settings.openRemixMenu.tip")))
                .width(width)
                .build();
    }

    @Override
    protected void addFooter() {
        LinearLayout footer = LinearLayout.horizontal().spacing(6);
        footer.addChild(Button.builder(
                        Component.translatable(RtxRemixMod.isRtxEnabled()
                                ? "rtxremix.settings.rtxButton.on" : "rtxremix.settings.rtxButton.off"),
                        button -> {
                            RtxRemixMod.toggleRtx();
                            reopen(category);
                        })
                .tooltip(Tooltip.create(Component.translatable("rtxremix.settings.pathTracing.tip")))
                .width(FOOTER_BUTTON_WIDTH)
                .build());
        footer.addChild(Button.builder(
                        Component.translatable("rtxremix.settings.remixMenuShort"),
                        button -> this.minecraft.schedule(() -> RtxRemixMod.openRemixMenu(
                                RemixInputGate.Menu.GRAPHICS_SETTINGS)))
                .tooltip(Tooltip.create(Component.translatable("rtxremix.settings.openRemixMenu.tip")))
                .width(FOOTER_BUTTON_WIDTH)
                .build());
        Button reset = Button.builder(
                        Component.translatable("rtxremix.settings.reset"), button -> confirmReset())
                .tooltip(Tooltip.create(Component.translatable("rtxremix.settings.reset.tip")))
                .width(FOOTER_BUTTON_WIDTH)
                .build();
        reset.active = category != SettingCategory.DISPLAY;
        footer.addChild(reset);
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .width(FOOTER_BUTTON_WIDTH)
                .build());
        this.layout.addToFooter(footer);
    }

    /** Saves this tab, then opens a fresh screen on {@code target}. */
    private void switchTo(SettingCategory target) {
        commit();
        reopen(target);
    }

    private void reopen(SettingCategory target) {
        this.minecraft.gui.setScreen(
                new RtxSettingsCategoryScreen(this.lastScreen, settings, saveSettings, target));
    }

    private void confirmReset() {
        this.minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                resetCurrentScreen();
                if (saveSettings != null) {
                    saveSettings.run();
                }
                if (commitTerrainEmission()) {
                    RtxRemixMod.rebuildTerrainForMaterialSettings();
                }
                reopen(category);
            } else {
                this.minecraft.gui.setScreen(this);
            }
        }, Component.translatable("rtxremix.settings.reset.confirm.title",
                        Component.translatable(category.labelKey())),
                Component.translatable("rtxremix.settings.reset.confirm.message",
                        Component.translatable(category.labelKey()))));
    }

    private void resetCurrentScreen() {
        for (SettingGroup group : resettableGroups(category)) {
            settings.resetGroup(group);
        }
    }

    static List<SettingGroup> resettableGroups(SettingCategory category) {
        List<SettingGroup> groups = new ArrayList<>();
        for (SettingGroup group : SettingGroup.values()) {
            if (group.category() == category && !opensSubscreen(group)) {
                groups.add(group);
            }
        }
        return List.copyOf(groups);
    }

    private static boolean opensSubscreen(SettingGroup group) {
        return group == SettingGroup.BLOCK_LIGHTS;
    }

    private static boolean fullWidthRows(SettingGroup group) {
        return group == SettingGroup.FRAMERATE;
    }

    private boolean commitTerrainEmission() {
        BlockTextureManager textures = BlockTextureManager.get();
        return category == SettingCategory.MATERIALS
                && textures != null
                && textures.commitTerrainEmissionMultiplier();
    }

    /** Applies pending slider edits, saves, and rebuilds terrain when materials changed. */
    private void commit() {
        if (this.list != null) {
            this.list.applyUnsavedChanges();
        }
        if (saveSettings != null) {
            saveSettings.run();
        }
        if (commitTerrainEmission()) {
            RtxRemixMod.rebuildTerrainForMaterialSettings();
        }
    }

    @Override
    public void onClose() {
        commit();
        this.minecraft.gui.setScreen(this.lastScreen);
    }
}
