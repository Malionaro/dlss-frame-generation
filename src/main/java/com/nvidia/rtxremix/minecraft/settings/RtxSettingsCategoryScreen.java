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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The RTX Remix settings, laid out like the DLSS Frame Generation settings: a row of
 * tabs, a row of coloured status chips, two columns of controls under small headings,
 * an info box that explains the control under the cursor, and a fixed footer. Switching
 * tabs saves and opens a fresh screen on the other tab.
 */
final class RtxSettingsCategoryScreen extends Screen {
    private static final int TITLE_Y = 6;
    private static final int TAB_Y = 18;
    private static final int CHIP_Y = 43;
    private static final int CHIP_HEIGHT = 12;
    private static final int CONTENT_TOP = 62;
    private static final int ROW = 24;
    private static final int FOOTER_HEIGHT = 30;
    private static final int INFO_HEIGHT = 44;
    private static final int COLUMN_GAP = 10;

    private final Screen parent;
    private final RtxSettings settings;
    private final Runnable saveSettings;
    private final SettingCategory category;

    private final List<AbstractWidget> scrollableWidgets = new ArrayList<>();
    private final Map<AbstractWidget, Integer> widgetBaseY = new IdentityHashMap<>();
    private final Map<AbstractWidget, Component> descriptions = new IdentityHashMap<>();
    private final List<Heading> headings = new ArrayList<>();
    /** Sliders that hold their value until released; applied when the page is left. */
    private final List<OptionInstance.OptionInstanceSliderButton<?>> deferredSliders = new ArrayList<>();
    private int gridRowY;
    private int gridColumn;
    private int scrollOffset;
    private int maxScroll;
    private Button scrollUpButton;
    private Button scrollDownButton;

    private record Heading(int baseY, Component text) {}

    private record Chip(Component text, int background, int color) {}

    RtxSettingsCategoryScreen(Screen parent, RtxSettings settings, Runnable saveSettings,
                              SettingCategory category) {
        super(Component.translatable("rtxremix.settings.title"));
        this.parent = parent;
        this.settings = settings;
        this.saveSettings = saveSettings;
        this.category = category;
    }

    // ------------------------------------------------------------------
    // Layout

    private int settingsLeftX() {
        return (width - settingsContentWidth()) / 2;
    }

    private int settingsContentWidth() {
        return Math.min(420, Math.max(160, width - 24));
    }

    private int settingsColumnWidth() {
        return (settingsContentWidth() - COLUMN_GAP) / 2;
    }

    private boolean infoBoxVisible() {
        return height >= 250;
    }

    private int contentBottom() {
        int reserved = FOOTER_HEIGHT + (infoBoxVisible() ? INFO_HEIGHT : 0);
        return Math.max(CONTENT_TOP + 20, height - reserved);
    }

    /** Places a control in the next free cell of the two-column grid. */
    private <T extends AbstractWidget> T place(T widget, Component description) {
        int leftX = settingsLeftX();
        widget.setX(gridColumn == 0 ? leftX : leftX + settingsColumnWidth() + COLUMN_GAP);
        widget.setY(gridRowY);
        widget.setWidth(settingsColumnWidth());
        if (gridColumn == 0) {
            gridColumn = 1;
        } else {
            gridColumn = 0;
            gridRowY += ROW;
        }
        return addSettingsWidget(widget, description);
    }

    /** Places a control across both columns on its own row. */
    private <T extends AbstractWidget> T placeWide(T widget, Component description) {
        finishRow();
        widget.setX(settingsLeftX());
        widget.setY(gridRowY);
        widget.setWidth(settingsContentWidth());
        gridRowY += ROW;
        return addSettingsWidget(widget, description);
    }

    private void heading(Component text) {
        finishRow();
        if (!headings.isEmpty() || !scrollableWidgets.isEmpty()) {
            gridRowY += 4;
        }
        headings.add(new Heading(gridRowY, text));
        gridRowY += 13;
    }

    private void finishRow() {
        if (gridColumn != 0) {
            gridColumn = 0;
            gridRowY += ROW;
        }
    }

    private <T extends AbstractWidget> T addSettingsWidget(T widget, Component description) {
        descriptions.put(widget, description);
        // The info box explains the control; a tooltip on top would say it twice.
        widget.setTooltip(infoBoxVisible() ? null : Tooltip.create(description));
        scrollableWidgets.add(widget);
        widgetBaseY.put(widget, widget.getY());
        return this.addRenderableWidget(widget);
    }

    private boolean scrollBy(int amount) {
        int oldOffset = scrollOffset;
        scrollOffset = Math.clamp(scrollOffset + amount, 0, maxScroll);
        if (scrollOffset != oldOffset) {
            applyScrollLayout();
            return true;
        }
        return false;
    }

    private void applyScrollLayout() {
        int viewportBottom = contentBottom();
        for (AbstractWidget widget : scrollableWidgets) {
            int y = widgetBaseY.get(widget) - scrollOffset;
            widget.setY(y);
            widget.visible = y >= CONTENT_TOP && y + widget.getHeight() <= viewportBottom;
        }
        if (scrollUpButton != null) {
            scrollUpButton.visible = maxScroll > 0;
            scrollUpButton.active = scrollOffset > 0;
        }
        if (scrollDownButton != null) {
            scrollDownButton.visible = maxScroll > 0;
            scrollDownButton.active = scrollOffset < maxScroll;
        }
    }

    @Override
    protected void init() {
        scrollableWidgets.clear();
        widgetBaseY.clear();
        descriptions.clear();
        headings.clear();
        deferredSliders.clear();
        scrollUpButton = null;
        scrollDownButton = null;
        gridRowY = CONTENT_TOP;
        gridColumn = 0;

        addTabs();
        if (category == SettingCategory.DISPLAY) {
            addDisplayTab();
        } else {
            addCategoryTab();
        }
        finishRow();

        int footerY = height - 25;
        int buttonWidth = Math.min(110, Math.max(60, (width - 90) / 3));
        int footerLeft = width / 2 - buttonWidth - buttonWidth / 2 - 4;
        this.addRenderableWidget(Button.builder(
                        Component.translatable("rtxremix.settings.remixMenuShort"),
                        // Queued so the click that pressed this button finishes
                        // dispatching before opening releases that same press.
                        button -> this.minecraft.schedule(() -> RtxRemixMod.openRemixMenu(
                                RemixInputGate.Menu.GRAPHICS_SETTINGS)))
                .tooltip(Tooltip.create(Component.translatable("rtxremix.settings.openRemixMenu.tip")))
                .bounds(footerLeft, footerY, buttonWidth, 20).build());
        Button reset = Button.builder(Component.translatable("rtxremix.settings.reset"),
                        button -> confirmReset())
                .tooltip(Tooltip.create(Component.translatable("rtxremix.settings.reset.tip")))
                .bounds(footerLeft + buttonWidth + 4, footerY, buttonWidth, 20).build();
        reset.active = category != SettingCategory.DISPLAY;
        this.addRenderableWidget(reset);
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(footerLeft + 2 * (buttonWidth + 4), footerY, buttonWidth, 20).build());

        scrollUpButton = this.addRenderableWidget(Button.builder(Component.literal("▲"),
                button -> scrollBy(-ROW)).bounds(Math.max(4, footerLeft - 26), footerY, 20, 20).build());
        scrollDownButton = this.addRenderableWidget(Button.builder(Component.literal("▼"),
                        button -> scrollBy(ROW))
                .bounds(Math.min(width - 24, footerLeft + 3 * (buttonWidth + 4) + 2), footerY, 20, 20)
                .build());

        maxScroll = Math.max(0, gridRowY - 4 - contentBottom());
        scrollOffset = Math.clamp(scrollOffset, 0, maxScroll);
        applyScrollLayout();
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

    private void addTabs() {
        List<SettingCategory> tabs = tabs();
        int gap = 4;
        int tabWidth = (settingsContentWidth() - gap * (tabs.size() - 1)) / Math.max(1, tabs.size());
        for (int index = 0; index < tabs.size(); index++) {
            SettingCategory tab = tabs.get(index);
            Button button = Button.builder(Component.translatable(tab.labelKey()), pressed -> switchTo(tab))
                    .bounds(settingsLeftX() + index * (tabWidth + gap), TAB_Y, tabWidth, 20)
                    .build();
            // The selected tab is drawn inactive, like vanilla's tab rows.
            button.active = tab != category;
            this.addRenderableWidget(button);
        }
    }

    private void addCategoryTab() {
        for (SettingGroup group : SettingGroup.values()) {
            if (group.category() != category) {
                continue;
            }
            List<RtxSetting<?>> groupSettings = settings.group(group);
            if (groupSettings.isEmpty()) {
                continue;
            }
            heading(Component.translatable(group.labelKey()));
            if (opensSubscreen(group)) {
                place(Button.builder(Component.translatable("rtxremix.settings.configure"),
                                button -> {
                                    commit();
                                    this.minecraft.gui.setScreen(
                                            new RtxBlockLightsScreen(this, settings, saveSettings));
                                }).build(),
                        Component.translatable("rtxremix.settings.blockLights.title"));
                continue;
            }
            for (RtxSetting<?> setting : groupSettings) {
                OptionInstance<?> option = RtxOptionFactory.toOption(setting);
                AbstractWidget widget = option.createButton(this.minecraft.options, 0, 0, settingsColumnWidth());
                if (widget instanceof OptionInstance.OptionInstanceSliderButton<?> slider
                        && !setting.applySliderValueImmediately()) {
                    deferredSliders.add(slider);
                }
                Component description = Component.translatable(setting.tooltipKey());
                if (fullWidthRows(group)) {
                    placeWide(widget, description);
                } else {
                    place(widget, description);
                }
            }
        }
    }

    private void addDisplayTab() {
        heading(Component.translatable("rtxremix.settings.group.display"));
        place(CycleButton.onOffBuilder(PerformanceOsd.isEnabled())
                        .create(0, 0, settingsColumnWidth(), 20, Component.translatable("rtxremix.settings.overlay"),
                                (button, value) -> PerformanceOsd.setEnabled(value)),
                Component.translatable("rtxremix.settings.overlay.tip"));
        place(CycleButton.onOffBuilder(RtxRemixMod.isRtxEnabled())
                        .create(0, 0, settingsColumnWidth(), 20, Component.translatable("rtxremix.settings.pathTracing"),
                                (button, value) -> {
                                    if (value != RtxRemixMod.isRtxEnabled()) {
                                        RtxRemixMod.toggleRtx();
                                    }
                                }),
                Component.translatable("rtxremix.settings.pathTracing.tip"));

        heading(Component.translatable("rtxremix.settings.group.more"));
        place(Button.builder(Component.translatable("rtxremix.settings.openRemixMenu"),
                        button -> this.minecraft.schedule(() -> RtxRemixMod.openRemixMenu(
                                RemixInputGate.Menu.GRAPHICS_SETTINGS))).build(),
                Component.translatable("rtxremix.settings.openRemixMenu.tip"));
        place(Button.builder(Component.translatable("rtxremix.settings.resetAll"),
                        button -> RtxSettingsScreen.confirmResetAll(parent, settings, saveSettings, category))
                        .build(),
                Component.translatable("rtxremix.settings.resetAll.tip"));
    }

    // ------------------------------------------------------------------
    // Actions

    private void switchTo(SettingCategory target) {
        commit();
        this.minecraft.gui.setScreen(new RtxSettingsCategoryScreen(parent, settings, saveSettings, target));
    }

    private void confirmReset() {
        this.minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                for (SettingGroup group : resettableGroups(category)) {
                    settings.resetGroup(group);
                }
                if (saveSettings != null) {
                    saveSettings.run();
                }
                if (commitTerrainEmission()) {
                    RtxRemixMod.rebuildTerrainForMaterialSettings();
                }
                this.minecraft.gui.setScreen(
                        new RtxSettingsCategoryScreen(parent, settings, saveSettings, category));
            } else {
                this.minecraft.gui.setScreen(this);
            }
        }, Component.translatable("rtxremix.settings.reset.confirm.title",
                        Component.translatable(category.labelKey())),
                Component.translatable("rtxremix.settings.reset.confirm.message",
                        Component.translatable(category.labelKey()))));
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

    /** Applies held slider values, saves, and rebuilds terrain when materials changed. */
    private void commit() {
        for (OptionInstance.OptionInstanceSliderButton<?> slider : deferredSliders) {
            slider.applyUnsavedValue();
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
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (maxScroll > 0 && verticalAmount != 0.0 && mouseY >= CONTENT_TOP && mouseY <= contentBottom()) {
            // Some touchpads report tiny fractional deltas: one step per event.
            return scrollBy(verticalAmount > 0.0 ? -ROW : ROW);
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ------------------------------------------------------------------
    // Drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float delta) {
        super.extractRenderState(extractor, mouseX, mouseY, delta);
        extractor.centeredText(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFFFF);
        drawChips(extractor);

        int viewportBottom = contentBottom();
        for (Heading heading : headings) {
            int y = heading.baseY() - scrollOffset;
            if (y >= CONTENT_TOP && y + 9 <= viewportBottom) {
                extractor.text(this.font, heading.text(), settingsLeftX() + 2, y + 2, 0xFFFFE08A);
            }
        }
        if (infoBoxVisible()) {
            drawInfoBox(extractor, mouseX, mouseY);
        }
        if (maxScroll > 0) {
            int trackTop = CONTENT_TOP;
            int trackHeight = viewportBottom - trackTop;
            int thumbHeight = Math.max(12, trackHeight * trackHeight / (trackHeight + maxScroll));
            int thumbTop = trackTop + scrollOffset * (trackHeight - thumbHeight) / maxScroll;
            int trackX = settingsLeftX() + settingsContentWidth() + 6;
            extractor.fill(trackX, trackTop, trackX + 3, viewportBottom, 0x40404040);
            extractor.fill(trackX, thumbTop, trackX + 3, thumbTop + thumbHeight, 0xFFC0C0C0);
        }
    }

    /** Renderer, path tracing, frame rate and Java memory as coloured chips. */
    private void drawChips(GuiGraphicsExtractor extractor) {
        List<Chip> chips = new ArrayList<>();
        chips.add(new Chip(Component.literal("RTX Remix"), 0xFF15171A, 0xFFD7DBE0));
        boolean rtx = RtxRemixMod.isRtxEnabled();
        chips.add(new Chip(Component.translatable("rtxremix.osd.rtx",
                        Component.translatable(rtx ? "rtxremix.osd.on" : "rtxremix.osd.off")),
                rtx ? 0xFF15301C : 0xFF3A1414, rtx ? 0xFF9FF0A8 : 0xFFFF9A9A));
        chips.add(new Chip(Component.translatable("rtxremix.osd.fps", this.minecraft.getFps()),
                0xFF15171A, 0xFFC9CCD1));
        long heap = Runtime.getRuntime().maxMemory();
        boolean lowHeap = heap < PerformanceOsd.MIN_HEAP_BYTES;
        chips.add(new Chip(Component.literal(String.format(Locale.ROOT, "Java %.1f GB", heap / 1073741824.0)),
                lowHeap ? 0xFF3A2C10 : 0xFF15171A, lowHeap ? 0xFFFFD27A : 0xFFC9CCD1));

        int available = Math.max(60, width - 12);
        int total;
        do {
            total = -4;
            for (Chip chip : chips) {
                total += font.width(chip.text()) + 12;
            }
        } while (total > available && chips.size() > 1 && chips.remove(0) != null);

        int x = (width - Math.min(total, available)) / 2;
        for (Chip chip : chips) {
            int chipWidth = font.width(chip.text()) + 8;
            extractor.fill(x - 1, CHIP_Y - 1, x + chipWidth + 1, CHIP_Y + CHIP_HEIGHT + 1, 0xFF000000);
            extractor.fill(x, CHIP_Y, x + chipWidth, CHIP_Y + CHIP_HEIGHT, chip.background());
            extractor.text(this.font, chip.text(), x + 4, CHIP_Y + 2, chip.color());
            x += chipWidth + 4;
        }
    }

    /** Explains the control under the cursor (or with keyboard focus). */
    private void drawInfoBox(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        int left = settingsLeftX();
        int top = contentBottom() + 4;
        int right = left + settingsContentWidth();
        int bottom = top + INFO_HEIGHT - 8;
        extractor.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF000000);
        extractor.fill(left, top, right, bottom, 0xE015171A);

        AbstractWidget described = null;
        for (AbstractWidget widget : scrollableWidgets) {
            if (widget.visible && widget.isMouseOver(mouseX, mouseY)) {
                described = widget;
                break;
            }
        }
        if (described == null) {
            for (AbstractWidget widget : scrollableWidgets) {
                if (widget.visible && widget.isFocused()) {
                    described = widget;
                    break;
                }
            }
        }
        if (described == null) {
            extractor.text(this.font, Component.translatable("rtxremix.settings.hint"), left + 6, top + 6, 0xFF9AA3AE);
            return;
        }
        extractor.text(this.font, described.getMessage(), left + 6, top + 4, 0xFFFFFFFF);
        Component description = descriptions.get(described);
        if (description == null) {
            return;
        }
        List<FormattedCharSequence> lines = font.split(description, settingsContentWidth() - 12);
        int y = top + 15;
        for (int index = 0; index < lines.size() && index < 2; index++) {
            extractor.text(this.font, lines.get(index), left + 6, y, 0xFFC9CCD1);
            y += 10;
        }
    }
}
