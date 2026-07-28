package org.malionaro.dlss_frame_generation.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.malionaro.dlss_frame_generation.client.raytracing.RayTracingCapabilities;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class MultiVendorSettingsScreen extends Screen {
    private static final int CONTENT_TOP = 56;
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
        super(Component.literal("Frame Generation & Graphics"));
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
        return Math.min(310, Math.max(160, width - 20));
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
        int mainTabY = 58;
        int mainTabGap = 6;
        int mainTabWidth = (settingsContentWidth() - mainTabGap) / 2;
        Button frameGenerationSection = Button.builder(Component.literal("Frame Gen"), button -> {
            activeSection = 0;
            scrollOffset = 0;
            rebuildWidgets();
        }).bounds(leftX, mainTabY, mainTabWidth, 20).build();
        Button graphicsSection = Button.builder(Component.literal("Graphics"), button -> {
            activeSection = 1;
            scrollOffset = 0;
            rebuildWidgets();
        }).bounds(leftX + mainTabWidth + mainTabGap, mainTabY, mainTabWidth, 20).build();
        frameGenerationSection.active = activeSection != 0;
        graphicsSection.active = activeSection != 1;
        addSettingsWidget(frameGenerationSection);
        addSettingsWidget(graphicsSection);

        int lastContentBottom;
        if (activeSection == 0) {
            int presetY = 82;
            int tabY = 106;
            int panelTopY = 132;

            var btnPreset = net.minecraft.client.gui.components.CycleButton.builder(
                    (FrameGenConfig.Preset p) -> Component.literal(p.displayName()), config.activePreset)
                    .withValues(FrameGenConfig.Preset.CUSTOM, FrameGenConfig.Preset.ESPORTS, FrameGenConfig.Preset.CINEMA, FrameGenConfig.Preset.MAX_PERFORMANCE)
                    .create(leftX, presetY, columnWidth, 20,
                            Component.literal("Preset"), (button, value) -> {
                                config.applyPreset(value);
                                scrollOffset = 0;
                                this.rebuildWidgets();
                            });
            this.addSettingsWidget(btnPreset);

            Button btnSelfTest = Button.builder(Component.literal("Run Diagnostics"), button -> {
                org.malionaro.dlss_frame_generation.client.testing.FrameGenTestRunner.runAllDiagnostics();
            }).bounds(rightX, presetY, columnWidth, 20).build();
            this.addSettingsWidget(btnSelfTest);

            int tabGap = 5;
            int tabWidth = (settingsContentWidth() - tabGap * 2) / 3;
            int tabLeft = settingsLeftX();
            Button tabNvidia = Button.builder(Component.literal("NVIDIA DLSS"), button -> {
                this.activeTab = 0;
                this.scrollOffset = 0;
                this.rebuildWidgets();
            }).bounds(tabLeft, tabY, tabWidth, 20).build();

            Button tabAmd = Button.builder(Component.literal("AMD FSR"), button -> {
                this.activeTab = 1;
                this.scrollOffset = 0;
                this.rebuildWidgets();
            }).bounds(tabLeft + tabWidth + tabGap, tabY, tabWidth, 20).build();

            Button tabIntel = Button.builder(Component.literal("Intel / FSR"), button -> {
                this.activeTab = 2;
                this.scrollOffset = 0;
                this.rebuildWidgets();
            }).bounds(tabLeft + (tabWidth + tabGap) * 2, tabY, tabWidth, 20).build();

            tabNvidia.active = activeTab != 0;
            tabAmd.active = activeTab != 1;
            tabIntel.active = activeTab != 2;
            this.addSettingsWidget(tabNvidia);
            this.addSettingsWidget(tabAmd);
            this.addSettingsWidget(tabIntel);

            if (activeTab == 0) {
                NvidiaSettingsPanel.addWidgets(this, config, nvidiaCapabilities, panelTopY);
            } else if (activeTab == 1) {
                AmdSettingsPanel.addWidgets(this, config, amdCapabilities, panelTopY);
            } else {
                IntelSettingsPanel.addWidgets(this, config, intelCapabilities, panelTopY);
            }

            int row4Y = panelTopY + 75;
            var btnMotionBlur = net.minecraft.client.gui.components.CycleButton.onOffBuilder(config.motionBlurEnabled)
                    .create(leftX, row4Y, columnWidth, 20,
                            Component.literal("Motion Blur (planned)"), (button, value) -> {
                                config.motionBlurEnabled = value;
                                config.save();
                            });
            this.addSettingsWidget(btnMotionBlur);
            btnMotionBlur.active = false;

            var btnFallback = net.minecraft.client.gui.components.CycleButton.onOffBuilder(config.autoFallbackChainEnabled)
                    .create(rightX, row4Y, columnWidth, 20,
                            Component.literal("Auto-Fallback (planned)"), (button, value) -> {
                                config.autoFallbackChainEnabled = value;
                                config.save();
                            });
            this.addSettingsWidget(btnFallback);
            btnFallback.active = false;

            var btnOsd = net.minecraft.client.gui.components.CycleButton.onOffBuilder(config.osdEnabled)
                    .create(leftX, panelTopY + 100, columnWidth, 20,
                            Component.literal("Performance OSD"), (button, value) -> {
                                config.osdEnabled = value;
                                config.save();
                            });
            this.addSettingsWidget(btnOsd);

            var btnDrs = net.minecraft.client.gui.components.CycleButton.onOffBuilder(config.drsEnabled)
                    .create(rightX, panelTopY + 100, columnWidth, 20,
                            Component.literal("DRS (planned)"), (button, value) -> {
                                config.drsEnabled = value;
                                config.save();
                            });
            this.addSettingsWidget(btnDrs);
            btnDrs.active = false;

            int bottomRowY = panelTopY + 125;
            var btnMvDebug = net.minecraft.client.gui.components.CycleButton.builder(
                    (org.malionaro.dlss_frame_generation.client.motion.MotionVectorDebugRenderer.DebugMode mode) -> {
                        return switch (mode) {
                            case OFF -> Component.literal("OFF");
                            case VECTOR_FIELD -> Component.literal("ARROWS");
                            case VECTOR_HEATMAP -> Component.literal("HEATMAP");
                            case RAW_MOTION_VECTORS -> Component.literal("RAW PREVIEW");
                        };
                    }, FrameGenMod.motionVectorDebugRenderer().getDebugMode())
                    .withValues(org.malionaro.dlss_frame_generation.client.motion.MotionVectorDebugRenderer.DebugMode.OFF,
                                org.malionaro.dlss_frame_generation.client.motion.MotionVectorDebugRenderer.DebugMode.VECTOR_FIELD,
                                org.malionaro.dlss_frame_generation.client.motion.MotionVectorDebugRenderer.DebugMode.VECTOR_HEATMAP,
                                org.malionaro.dlss_frame_generation.client.motion.MotionVectorDebugRenderer.DebugMode.RAW_MOTION_VECTORS)
                    .create(leftX, bottomRowY, columnWidth, 20,
                            Component.literal("Motion Debug"), (button, value) -> {
                                FrameGenMod.motionVectorDebugRenderer().setDebugMode(value);
                            });
            this.addSettingsWidget(btnMvDebug);

            var btnOptiScaler = net.minecraft.client.gui.components.CycleButton
                    .onOffBuilder(config.optiScalerEnabled)
                    .create(rightX, bottomRowY, columnWidth, 20,
                            Component.literal("OptiScaler (Restart)"),
                            (button, value) -> {
                                config.optiScalerEnabled = value;
                                config.save();
                            });
            btnOptiScaler.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.literal(
                            "OptiScaler v0.9.4 is bundled in the mod JAR and "
                                    + "loads automatically on AMD/Intel. An "
                                    + "external installation in "
                                    + OptiScalerIntegration.installDirectory()
                                    + " overrides the bundled copy."
                    )
            ));
            this.addSettingsWidget(btnOptiScaler);
            lastContentBottom = bottomRowY + 20;
        } else {
            int subTabY = 82;
            Button rayTracingTab = Button.builder(Component.literal("Hardware Raytracing"), button -> {})
                    .bounds(leftX, subTabY, settingsContentWidth(), 20)
                    .build();
            rayTracingTab.active = false;
            addSettingsWidget(rayTracingTab);

            RayTracingCapabilities capabilities = FrameGenMod.rayTracingManager() != null
                    ? FrameGenMod.rayTracingManager().capabilities()
                    : RayTracingCapabilities.waitingForDevice();
            lastContentBottom = RayTracingSettingsPanel.addWidgets(
                    this, config, capabilities, 108);
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

        // Header Title
        extractor.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        if (activeSection == 1) {
            RayTracingCapabilities capabilities = FrameGenMod.rayTracingManager() != null
                    ? FrameGenMod.rayTracingManager().capabilities()
                    : RayTracingCapabilities.waitingForDevice();
            int statusColor = switch (capabilities.state()) {
                case READY -> 0x00FF00;
                case UNSUPPORTED -> 0xFF5555;
                default -> 0xFFAA00;
            };
            extractor.centeredText(this.font, Component.literal(fitHeaderLine(capabilities.statusMessage())), this.width / 2, 34, statusColor);
            String deviceLine = "Device: " + capabilities.deviceName()
                    + " | Ray Query: " + (capabilities.rayQuerySupported() ? "yes" : "no")
                    + " | RT Pipeline: " + (capabilities.rayTracingPipelineSupported() ? "yes" : "no");
            extractor.centeredText(this.font, Component.literal(fitHeaderLine(deviceLine)), this.width / 2, 47, 0xAAAAAA);
        } else {
            FrameGenerationBackend backend = backendForTab();
            boolean active = backend != null && backend.isFrameGenerationActive();
            if (backend != null) {
                int statusColor = active ? 0x00FF00 : 0xFFAA00;
                extractor.centeredText(this.font, Component.literal(fitHeaderLine(backend.statusDescription())), this.width / 2, 34, statusColor);
                double vramMb = (double) vramBytes / (1024.0 * 1024.0);
                extractor.centeredText(this.font, Component.literal("Est. FG VRAM Usage: " + String.format("%.2f", vramMb) + " MB"), this.width / 2, 47, 0xAAAAAA);
            } else {
                extractor.centeredText(this.font, Component.literal(fitHeaderLine("Status: INACTIVE (No compatible RTX / Radeon / Intel Arc GPU or native library loaded)")), this.width / 2, 34, 0xFF5555);
                extractor.centeredText(this.font, Component.literal("Est. FG VRAM Usage: 0.00 MB"), this.width / 2, 47, 0x666666);
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
