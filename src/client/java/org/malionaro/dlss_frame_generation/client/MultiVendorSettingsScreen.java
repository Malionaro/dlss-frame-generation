package org.malionaro.dlss_frame_generation.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.malionaro.dlss_frame_generation.client.raytracing.RayTracingCapabilities;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class MultiVendorSettingsScreen extends Screen {
    private static final int TITLE_Y = 6;
    private static final int STATUS_Y = 19;
    private static final int CONTENT_TOP = 32;
    private static final int FOOTER_HEIGHT = 32;
    private static final int COLUMN_GAP = 10;

    private final Screen parent;
    private final List<AbstractWidget> scrollableWidgets = new ArrayList<>();
    private final Map<AbstractWidget, Integer> widgetBaseY = new IdentityHashMap<>();
    private long vramBytes = 0;
    private int tickCount = 0;
    private int activeSection = 0; // 0 = Frame Generation, 1 = Graphics
    private int activeTab = 0; // 0 = NVIDIA DLSS, 1 = AMD FSR, 2 = Intel XeSS
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private Button scrollUpButton;
    private Button scrollDownButton;

    public MultiVendorSettingsScreen(Screen parent) {
        super(Component.translatable("dlss_fg.screen.settings.title"));
        this.parent = parent;
    }

    @Override
    public void tick() {
        super.tick();
        if (tickCount++ % 20 == 0) {
            if (activeSection != 0) {
                vramBytes = 0;
                return;
            }
            try {
                FrameGenerationBackend backend = backendForTab();
                vramBytes = backend != null ? backend.estimatedVramUsageBytes() : 0;
            } catch (UnsatisfiedLinkError e) {
                vramBytes = 0;
            }
        }
    }

    public <T extends AbstractWidget> T addSettingsWidget(T widget) {
        scrollableWidgets.add(widget);
        widgetBaseY.put(widget, widget.getY());
        return this.addRenderableWidget(widget);
    }

    public <T extends AbstractWidget> T addSettingsWidget(
            T widget,
            String tooltipKey
    ) {
        return addSettingsWidget(
                widget,
                Component.translatable(tooltipKey)
        );
    }

    public <T extends AbstractWidget> T addSettingsWidget(
            T widget,
            Component tooltip
    ) {
        widget.setTooltip(Tooltip.create(tooltip));
        return addSettingsWidget(widget);
    }

    public Component frameGenerationTooltip(
            FrameGenerationBackend.GpuVendor candidate,
            String regularTooltipKey
    ) {
        FrameGenerationBackend.GpuVendor conflict =
                FrameGenConfig.getInstance()
                        .conflictingFrameGenerationVendor(candidate);
        if (conflict == FrameGenerationBackend.GpuVendor.UNKNOWN) {
            return Component.translatable(regularTooltipKey);
        }
        return Component.translatable(
                "dlss_fg.tooltip.frame_generation.conflict",
                conflict.displayName()
        );
    }

    public Component frameGenerationDependentTooltip(
            FrameGenerationBackend.GpuVendor candidate,
            String regularTooltipKey
    ) {
        FrameGenConfig config = FrameGenConfig.getInstance();
        if (config.conflictingFrameGenerationVendor(candidate)
                != FrameGenerationBackend.GpuVendor.UNKNOWN) {
            return frameGenerationTooltip(candidate, regularTooltipKey);
        }
        if (!config.isFrameGenerationEnabled(candidate)) {
            return Component.translatable(
                    "dlss_fg.tooltip.frame_generation.enable_first"
            );
        }
        return Component.translatable(regularTooltipKey);
    }

    public void refreshSettingsWidgets() {
        rebuildWidgets();
    }

    public int settingsLeftX() {
        return (width - settingsContentWidth()) / 2;
    }

    public int settingsRightX() {
        return settingsLeftX() + settingsColumnWidth() + COLUMN_GAP;
    }

    public int settingsColumnWidth() {
        return (settingsContentWidth() - COLUMN_GAP) / 2;
    }

    private int settingsContentWidth() {
        return Math.min(420, Math.max(160, width - 24));
    }

    private int contentBottom() {
        return Math.max(CONTENT_TOP + 20, height - FOOTER_HEIGHT);
    }

    private void finishScrollableLayout(int lastWidgetBottom) {
        maxScroll = Math.max(0, lastWidgetBottom - contentBottom());
        scrollOffset = Math.clamp(scrollOffset, 0, maxScroll);
        applyScrollLayout();
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
        scrollUpButton = null;
        scrollDownButton = null;

        FrameGenConfig config = FrameGenConfig.getInstance();

        int nvidiaCapabilities = 0;
        int amdCapabilities = 0;
        int intelCapabilities = 0;
        try {
            nvidiaCapabilities = StreamlineBridge.nGetBackendSupportedCapabilities(
                    FrameGenerationBackend.GpuVendor.NVIDIA.vendorId());
            amdCapabilities = StreamlineBridge.nGetBackendCapabilities(FrameGenerationBackend.GpuVendor.AMD.vendorId());
            intelCapabilities = StreamlineBridge.nGetBackendCapabilities(FrameGenerationBackend.GpuVendor.INTEL.vendorId());
        } catch (UnsatisfiedLinkError e) {
            // Fall back to the older readiness query when a stale native bridge
            // is present during development.
            try {
                nvidiaCapabilities = StreamlineBridge.nGetBackendCapabilities(
                        FrameGenerationBackend.GpuVendor.NVIDIA.vendorId());
            } catch (UnsatisfiedLinkError ignored) {
                nvidiaCapabilities = 0;
            }
        }

        int leftX = settingsLeftX();
        int rightX = settingsRightX();
        int columnWidth = settingsColumnWidth();
        int mainTabY = CONTENT_TOP;
        int mainTabGap = 6;
        int mainTabWidth = (settingsContentWidth() - mainTabGap) / 2;
        Button frameGenerationSection = Button.builder(Component.translatable("dlss_fg.section.frame_generation"), button -> {
            activeSection = 0;
            scrollOffset = 0;
            rebuildWidgets();
        }).bounds(leftX, mainTabY, mainTabWidth, 20).build();
        Button graphicsSection = Button.builder(Component.translatable("dlss_fg.section.graphics"), button -> {
            activeSection = 1;
            scrollOffset = 0;
            rebuildWidgets();
        }).bounds(leftX + mainTabWidth + mainTabGap, mainTabY, mainTabWidth, 20).build();
        frameGenerationSection.active = activeSection != 0;
        graphicsSection.active = activeSection != 1;
        addSettingsWidget(frameGenerationSection, "dlss_fg.tooltip.section.frame_generation");
        addSettingsWidget(graphicsSection, "dlss_fg.tooltip.section.graphics");

        int lastContentBottom;
        if (activeSection == 0) {
            int presetY = mainTabY + 24;
            int tabY = presetY + 24;
            int panelTopY = tabY + 26;

            var btnPreset = net.minecraft.client.gui.components.CycleButton.builder(
                    (FrameGenConfig.Preset p) -> Component.translatable(
                            "dlss_fg.value.preset."
                                    + p.name().toLowerCase(java.util.Locale.ROOT)
                    ), config.activePreset)
                    .withValues(FrameGenConfig.Preset.CUSTOM, FrameGenConfig.Preset.ESPORTS, FrameGenConfig.Preset.CINEMA, FrameGenConfig.Preset.MAX_PERFORMANCE)
                    .create(leftX, presetY, columnWidth, 20,
                            Component.translatable("dlss_fg.option.preset"), (button, value) -> {
                                config.applyPreset(value);
                                scrollOffset = 0;
                                this.rebuildWidgets();
                            });
            this.addSettingsWidget(btnPreset, "dlss_fg.tooltip.preset");

            Button btnSelfTest = Button.builder(Component.translatable("dlss_fg.option.run_diagnostics"), button -> {
                var results = org.malionaro.dlss_frame_generation.client.testing.FrameGenTestRunner
                        .runAllDiagnostics();
                this.minecraft.setScreenAndShow(new DiagnosticsScreen(this, results));
            }).bounds(rightX, presetY, columnWidth, 20).build();
            this.addSettingsWidget(btnSelfTest, "dlss_fg.tooltip.run_diagnostics");

            int tabGap = 5;
            int tabWidth = (settingsContentWidth() - tabGap * 2) / 3;
            int tabLeft = settingsLeftX();
            Button tabNvidia = Button.builder(Component.translatable("dlss_fg.tab.nvidia"), button -> {
                this.activeTab = 0;
                this.scrollOffset = 0;
                this.rebuildWidgets();
            }).bounds(tabLeft, tabY, tabWidth, 20).build();

            Button tabAmd = Button.builder(Component.translatable("dlss_fg.tab.amd"), button -> {
                this.activeTab = 1;
                this.scrollOffset = 0;
                this.rebuildWidgets();
            }).bounds(tabLeft + tabWidth + tabGap, tabY, tabWidth, 20).build();

            Button tabIntel = Button.builder(Component.translatable("dlss_fg.tab.intel"), button -> {
                this.activeTab = 2;
                this.scrollOffset = 0;
                this.rebuildWidgets();
            }).bounds(tabLeft + (tabWidth + tabGap) * 2, tabY, tabWidth, 20).build();

            tabNvidia.active = activeTab != 0;
            tabAmd.active = activeTab != 1;
            tabIntel.active = activeTab != 2;
            this.addSettingsWidget(tabNvidia, "dlss_fg.tooltip.tab.nvidia");
            this.addSettingsWidget(tabAmd, "dlss_fg.tooltip.tab.amd");
            this.addSettingsWidget(tabIntel, "dlss_fg.tooltip.tab.intel");

            if (activeTab == 0) {
                NvidiaSettingsPanel.addWidgets(this, config, nvidiaCapabilities, panelTopY);
            } else if (activeTab == 1) {
                AmdSettingsPanel.addWidgets(this, config, amdCapabilities, panelTopY);
            } else {
                IntelSettingsPanel.addWidgets(this, config, intelCapabilities, panelTopY);
            }

            // Put working global tools directly below the vendor-specific rows.
            int toolsRowY = panelTopY + 75;
            var btnOsd = net.minecraft.client.gui.components.CycleButton.onOffBuilder(config.osdEnabled)
                    .create(leftX, toolsRowY, columnWidth, 20,
                            Component.translatable("dlss_fg.option.performance_osd"), (button, value) -> {
                                config.osdEnabled = value;
                                config.save();
                            });
            this.addSettingsWidget(btnOsd, "dlss_fg.tooltip.performance_osd");

            var btnMvDebug = net.minecraft.client.gui.components.CycleButton.builder(
                    (org.malionaro.dlss_frame_generation.client.motion.MotionVectorDebugRenderer.DebugMode mode) -> {
                        return switch (mode) {
                            case OFF -> Component.translatable("dlss_fg.value.off");
                            case VECTOR_FIELD -> Component.translatable("dlss_fg.value.motion.arrows");
                            case VECTOR_HEATMAP -> Component.translatable("dlss_fg.value.motion.heatmap");
                            case RAW_MOTION_VECTORS -> Component.translatable("dlss_fg.value.motion.raw");
                        };
                    }, FrameGenMod.motionVectorDebugRenderer().getDebugMode())
                    .withValues(org.malionaro.dlss_frame_generation.client.motion.MotionVectorDebugRenderer.DebugMode.OFF,
                                org.malionaro.dlss_frame_generation.client.motion.MotionVectorDebugRenderer.DebugMode.VECTOR_FIELD,
                                org.malionaro.dlss_frame_generation.client.motion.MotionVectorDebugRenderer.DebugMode.VECTOR_HEATMAP,
                                org.malionaro.dlss_frame_generation.client.motion.MotionVectorDebugRenderer.DebugMode.RAW_MOTION_VECTORS)
                    .create(rightX, toolsRowY, columnWidth, 20,
                            Component.translatable("dlss_fg.option.motion_debug"), (button, value) -> {
                                FrameGenMod.motionVectorDebugRenderer().setDebugMode(value);
                            });
            this.addSettingsWidget(btnMvDebug, "dlss_fg.tooltip.motion_debug");

            int compatibilityRowY = panelTopY + 100;
            var btnOptiScaler = net.minecraft.client.gui.components.CycleButton
                    .onOffBuilder(config.optiScalerEnabled)
                    .create(leftX, compatibilityRowY, columnWidth, 20,
                            Component.translatable("dlss_fg.option.optiscaler"),
                            (button, value) -> {
                                config.optiScalerEnabled = value;
                                config.save();
                            });
            btnOptiScaler.setTooltip(Tooltip.create(
                    Component.translatable(
                            "dlss_fg.tooltip.optiscaler",
                            OptiScalerIntegration.installDirectory()
                    )
            ));
            this.addSettingsWidget(btnOptiScaler);

            var btnFallback = net.minecraft.client.gui.components.CycleButton.onOffBuilder(config.autoFallbackChainEnabled)
                    .create(rightX, compatibilityRowY, columnWidth, 20,
                            Component.translatable("dlss_fg.option.auto_fallback"), (button, value) -> {
                                config.autoFallbackChainEnabled = value;
                                config.save();
                            });
            this.addSettingsWidget(btnFallback, "dlss_fg.tooltip.auto_fallback");
            btnFallback.active = false;

            // Keep unfinished options together at the bottom instead of mixing
            // them with controls that already have a runtime implementation.
            int plannedRowY = panelTopY + 125;
            var btnMotionBlur = net.minecraft.client.gui.components.CycleButton.onOffBuilder(config.motionBlurEnabled)
                    .create(leftX, plannedRowY, columnWidth, 20,
                            Component.translatable("dlss_fg.option.motion_blur"), (button, value) -> {
                                config.motionBlurEnabled = value;
                                config.save();
                            });
            this.addSettingsWidget(btnMotionBlur, "dlss_fg.tooltip.motion_blur");
            btnMotionBlur.active = false;

            var btnDrs = net.minecraft.client.gui.components.CycleButton.onOffBuilder(config.drsEnabled)
                    .create(rightX, plannedRowY, columnWidth, 20,
                            Component.translatable("dlss_fg.option.drs"), (button, value) -> {
                                config.drsEnabled = value;
                                config.save();
                            });
            this.addSettingsWidget(btnDrs, "dlss_fg.tooltip.drs");
            btnDrs.active = false;
            lastContentBottom = plannedRowY + 20;
        } else {
            int subTabY = mainTabY + 24;
            Button rayTracingTab = Button.builder(Component.translatable("dlss_fg.tab.ray_tracing"), button -> {})
                    .bounds(leftX, subTabY, settingsContentWidth(), 20)
                    .build();
            rayTracingTab.active = false;
            addSettingsWidget(rayTracingTab, "dlss_fg.tooltip.tab.ray_tracing");

            RayTracingCapabilities capabilities = FrameGenMod.rayTracingManager() != null
                    ? FrameGenMod.rayTracingManager().capabilities()
                    : RayTracingCapabilities.waitingForDevice();
            lastContentBottom = RayTracingSettingsPanel.addWidgets(
                    this, config, capabilities, subTabY + 26);
        }

        int doneWidth = Math.min(150, Math.max(80, width - 76));
        int doneX = (width - doneWidth) / 2;
        int footerY = height - 26;

        // Fixed footer actions stay visible even when the settings area scrolls.
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> {
            this.minecraft.setScreenAndShow(this.parent);
        }).bounds(doneX, footerY, doneWidth, 20).build());

        scrollUpButton = this.addRenderableWidget(Button.builder(Component.literal("▲"), button -> {
            scrollBy(-24);
        }).bounds(Math.max(4, doneX - 26), footerY, 20, 20).build());

        scrollDownButton = this.addRenderableWidget(Button.builder(Component.literal("▼"), button -> {
            scrollBy(24);
        }).bounds(Math.min(width - 24, doneX + doneWidth + 6), footerY, 20, 20).build());

        finishScrollableLayout(lastContentBottom);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (maxScroll > 0
                && verticalAmount != 0.0
                && mouseY >= CONTENT_TOP
                && mouseY <= contentBottom()) {
            // Some touchpads and automation APIs report very small fractional deltas.
            // Treat every non-zero event as one deterministic scrolling step.
            return scrollBy(verticalAmount > 0.0 ? -24 : 24);
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float delta) {
        super.extractRenderState(extractor, mouseX, mouseY, delta);

        extractor.centeredText(
                this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFFFF
        );

        if (activeSection == 1) {
            RayTracingCapabilities capabilities = FrameGenMod.rayTracingManager() != null
                    ? FrameGenMod.rayTracingManager().capabilities()
                    : RayTracingCapabilities.waitingForDevice();
            int statusColor = switch (capabilities.state()) {
                case READY -> 0xFF00FF00;
                case UNSUPPORTED -> 0xFFFF5555;
                default -> 0xFFFFAA00;
            };
            String statusLine = capabilities.statusMessage()
                    + " | " + capabilities.deviceName()
                    + " | RQ " + (capabilities.rayQuerySupported() ? "yes" : "no")
                    + " | RTP " + (capabilities.rayTracingPipelineSupported() ? "yes" : "no");
            extractor.centeredText(
                    this.font,
                    Component.literal(fitHeaderLine(statusLine)),
                    this.width / 2,
                    STATUS_Y,
                    statusColor
            );
        } else {
            FrameGenerationBackend backend = backendForTab();
            boolean active = backend != null && backend.isFrameGenerationActive();
            if (backend != null) {
                int statusColor = active ? 0xFF00FF00 : 0xFFFFAA00;
                double vramMb = (double) vramBytes / (1024.0 * 1024.0);
                String statusLine = Component.translatable(
                        "dlss_fg.status.frame_generation",
                        backend.statusDescription(),
                        String.format(java.util.Locale.ROOT, "%.2f", vramMb)
                ).getString();
                extractor.centeredText(
                        this.font,
                        Component.literal(fitHeaderLine(statusLine)),
                        this.width / 2,
                        STATUS_Y,
                        statusColor
                );
            } else {
                extractor.centeredText(
                        this.font,
                        Component.literal(fitHeaderLine(
                                Component.translatable(
                                        "dlss_fg.status.frame_generation.inactive"
                                ).getString()
                        )),
                        this.width / 2,
                        STATUS_Y,
                        0xFFFF5555
                );
            }
        }

        if (maxScroll > 0) {
            int trackTop = CONTENT_TOP;
            int trackBottom = contentBottom();
            int trackHeight = trackBottom - trackTop;
            int thumbHeight = Math.max(12, trackHeight * trackHeight / (trackHeight + maxScroll));
            int thumbTravel = trackHeight - thumbHeight;
            int thumbTop = trackTop + (maxScroll == 0 ? 0 : scrollOffset * thumbTravel / maxScroll);
            extractor.fill(this.width - 6, trackTop, this.width - 3, trackBottom, 0x40404040);
            extractor.fill(this.width - 6, thumbTop, this.width - 3, thumbTop + thumbHeight, 0xFFC0C0C0);
        }
    }

    private FrameGenerationBackend backendForTab() {
        return switch (activeTab) {
            case 0 -> new NvidiaBackend();
            case 1 -> new AmdBackend();
            case 2 -> new IntelBackend();
            default -> new FallbackBackend();
        };
    }

    private String fitHeaderLine(String text) {
        int availableWidth = Math.max(40, width - 12);
        if (font.width(text) <= availableWidth) {
            return text;
        }
        String suffix = "...";
        int contentWidth = Math.max(1, availableWidth - font.width(suffix));
        return font.plainSubstrByWidth(text, contentWidth) + suffix;
    }
}
