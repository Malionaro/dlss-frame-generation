package org.malionaro.dlss_frame_generation.client.ui.settings;
import org.malionaro.dlss_frame_generation.client.backend.AmdBackend;
import org.malionaro.dlss_frame_generation.client.backend.FallbackBackend;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.backend.IntelBackend;
import org.malionaro.dlss_frame_generation.client.backend.NvidiaBackend;
import org.malionaro.dlss_frame_generation.client.compat.CompatibilityManager;
import org.malionaro.dlss_frame_generation.client.compat.ForeignShaderPipeline;
import org.malionaro.dlss_frame_generation.client.compat.RendererDetection;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.diagnostics.FrameGenerationUiStatus;
import org.malionaro.dlss_frame_generation.client.diagnostics.BackendStatusFormatter;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.interop.D3D12PresentationBridge;
import org.malionaro.dlss_frame_generation.client.interop.D3D12ProviderRuntimeAudit;
import org.malionaro.dlss_frame_generation.client.interop.OptiScalerIntegration;
import org.malionaro.dlss_frame_generation.client.motion.MotionVectorDebugRenderer;
import org.malionaro.dlss_frame_generation.client.render.VulkanRegistry;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.malionaro.dlss_frame_generation.client.ui.diagnostics.DiagnosticsScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.malionaro.dlss_frame_generation.client.raytracing.RayTracingCapabilities;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Frame generation and graphics settings, laid out like Minecraft's own option
 * screens: four tabs, a row of status chips, two columns of controls, an info
 * box that explains the control under the cursor, and a fixed footer.
 *
 * <p>The vendor shown is the active GPU's, so there are no NVIDIA/AMD/Intel
 * tabs; the panels add their controls through {@link #place}.</p>
 */
public class MultiVendorSettingsScreen extends Screen {
    private static final int TITLE_Y = 6;
    private static final int TAB_Y = 18;
    private static final int CHIP_Y = 43;
    private static final int CHIP_HEIGHT = 12;
    private static final int CONTENT_TOP = 62;
    private static final int ROW = 24;
    private static final int FOOTER_HEIGHT = 30;
    private static final int INFO_HEIGHT = 44;
    private static final int COLUMN_GAP = 10;

    private static final int TAB_FRAME_GENERATION = 0;
    private static final int TAB_UPSCALING = 1;
    private static final int TAB_GRAPHICS = 2;
    private static final int TAB_STATUS = 3;

    private final Screen parent;
    private final List<AbstractWidget> scrollableWidgets = new ArrayList<>();
    private final Map<AbstractWidget, Integer> widgetBaseY = new IdentityHashMap<>();
    private final Map<AbstractWidget, Component> descriptions = new IdentityHashMap<>();
    private final List<Heading> headings = new ArrayList<>();
    private long vramBytes = 0;
    // DLSS-G reports 0 whenever it is not running, so a raw zero cannot be
    // told apart from "no measurement". Keep the last real reading so the
    // menu does not show a misleading 0.00 MB after the game paused.
    private long lastKnownVramBytes = 0;
    private FrameGenerationUiStatus.Kind fgStatus = FrameGenerationUiStatus.Kind.UNSUPPORTED;
    // Measured present rate. Frame-generation output frames are presented
    // inside the Streamline interposer, below this mod's present hook, so
    // they cannot be counted here - only the input rate is measurable.
    private double presentsPerSecond;
    private long lastPresentSampleNanos;
    private long lastPresentSampleCount;
    private int fgMultiplier = 1;
    private int tickCount = 0;
    private int activeTab = TAB_FRAME_GENERATION;
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private int gridRowY;
    private int gridColumn;
    private Button scrollUpButton;
    private Button scrollDownButton;

    private record Heading(int baseY, Component text) {}

    private record Chip(Component text, int background, int color) {}

    public MultiVendorSettingsScreen(Screen parent) {
        super(Component.translatable("dlss_fg.screen.settings.title"));
        this.parent = parent;
    }

    @Override
    public void tick() {
        super.tick();
        // Sample every tick: the status itself must react to the menu being
        // closed, otherwise it keeps claiming "no world loaded".
        sampleStatus();
    }

    private void sampleStatus() {
        FrameGenerationBackend backend = activeBackend();
        Minecraft minecraft = Minecraft.getInstance();
        boolean levelLoaded = minecraft != null && minecraft.level != null;
        boolean paused = minecraft != null && minecraft.isPaused();
        boolean nativeActive = false;
        boolean supported = false;
        String nativeStatus = "";
        try {
            supported = backend.isSupported();
            nativeStatus = backend.statusDescription();
            nativeActive = backend.isFrameGenerationActive();
        } catch (UnsatisfiedLinkError ignored) {
            // Treat a missing native entry point as unsupported.
        }
        FrameGenConfig config = FrameGenConfig.getInstance();
        boolean enabledInSettings = config.isFrameGenerationEnabled(backend.vendor());
        fgStatus = FrameGenerationUiStatus.resolve(
                supported,
                levelLoaded,
                paused,
                enabledInSettings,
                nativeStatus,
                nativeActive
        );
        fgMultiplier = samplePresentRate(backend.vendor());
        // slDLSSGGetState is the expensive call, so keep it at ~1 Hz. The
        // status above stays per-tick so it reacts to the menu closing.
        if (tickCount++ % 20 != 0) {
            return;
        }
        try {
            long sampled = backend.estimatedVramUsageBytes();
            if (sampled > 0L) {
                lastKnownVramBytes = sampled;
            }
            vramBytes = FrameGenerationUiStatus.vramReadingIsMeaningful(
                    fgStatus, sampled
            ) ? sampled : lastKnownVramBytes;
        } catch (UnsatisfiedLinkError e) {
            vramBytes = lastKnownVramBytes;
        }
    }

    /**
     * Measures completed presents per second and returns the configured
     * frame-generation multiplier.
     *
     * <p>Only the input present rate is measurable from here: generated
     * frames are presented by the Streamline interposer beneath this mod's
     * present hook, so the displayed total is explicitly derived, not
     * counted.</p>
     */
    private int samplePresentRate(FrameGenerationBackend.GpuVendor vendor) {
        long now = System.nanoTime();
        long count = VulkanRegistry.getCompletedPresentCount();
        if (lastPresentSampleNanos != 0L) {
            double seconds = (now - lastPresentSampleNanos) / 1_000_000_000.0;
            if (seconds >= 0.4) {
                presentsPerSecond = (count - lastPresentSampleCount) / seconds;
                lastPresentSampleNanos = now;
                lastPresentSampleCount = count;
            }
        } else {
            lastPresentSampleNanos = now;
            lastPresentSampleCount = count;
        }
        FrameGenConfig config = FrameGenConfig.getInstance();
        return switch (vendor) {
            case NVIDIA -> Math.max(1, config.frameMultiplier);
            case AMD, INTEL -> 2;
            case UNKNOWN -> 1;
        };
    }

    // ------------------------------------------------------------------
    // Layout API used by the panels

    public int settingsColumnWidth() {
        return (settingsContentWidth() - COLUMN_GAP) / 2;
    }

    /** Places a control in the next free cell of the two-column grid. */
    public <T extends AbstractWidget> T place(T widget, Component description) {
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
    public <T extends AbstractWidget> T placeWide(T widget, Component description) {
        finishRow();
        widget.setX(settingsLeftX());
        widget.setY(gridRowY);
        widget.setWidth(settingsContentWidth());
        gridRowY += ROW;
        return addSettingsWidget(widget, description);
    }

    /** A small section title above the next row. */
    public void heading(Component text) {
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
        if (!infoBoxVisible()) {
            // Too little height for the info box: fall back to tooltips.
            widget.setTooltip(Tooltip.create(description));
        }
        scrollableWidgets.add(widget);
        widgetBaseY.put(widget, widget.getY());
        return this.addRenderableWidget(widget);
    }

    /** UI recomposition, shared by every vendor's frame-generation panel. */
    public void addUiRecomposition(
            FrameGenConfig config,
            FrameGenerationBackend.GpuVendor vendor,
            boolean available
    ) {
        var btnRecomp = CycleButton.onOffBuilder(config.uiRecomposition)
                .create(0, 0, settingsColumnWidth(), 20,
                        Component.translatable("dlss_fg.option.ui_recomposition"), (button, value) -> {
                            config.uiRecomposition = value;
                            config.save();
                            // Native push only via backend.
                            FrameGenerationBackend.applyActiveConfig(config);
                        });
        btnRecomp.active = available;
        place(btnRecomp, frameGenerationDependentTooltip(
                vendor, "dlss_fg.tooltip.ui_recomposition"));
    }

    /** FSR 3.1 always inserts one frame: a slider locked at 2x. */
    public SteppedSlider fixedMultiplierSlider() {
        var slider = new SteppedSlider(
                settingsColumnWidth(), 2, 2, 1, 2,
                value -> Component.translatable("dlss_fg.option.frame_multiplier_fixed", value),
                value -> {},
                value -> {});
        slider.active = false;
        return slider;
    }

    /** Reconstruction sharpening 0..100 % in 5 % steps. */
    public SteppedSlider sharpnessSlider(Supplier<Float> getter, Consumer<Float> setter) {
        FrameGenConfig config = FrameGenConfig.getInstance();
        long[] lastApplyNanos = new long[1];
        return new SteppedSlider(
                settingsColumnWidth(), 0, 100, 5,
                Math.round(getter.get() * 100.0f),
                value -> Component.translatable("dlss_fg.option.aa_sharpness", value),
                value -> {
                    setter.accept(value / 100.0f);
                    config.saveSoon();
                    // Native apply is throttled while dragging.
                    long now = System.nanoTime();
                    if (now - lastApplyNanos[0] >= 200_000_000L) {
                        lastApplyNanos[0] = now;
                        FrameGenerationBackend.applyActiveConfig(config);
                    }
                },
                value -> {
                    config.save();
                    FrameGenerationBackend.applyActiveConfig(config);
                });
    }

    // ------------------------------------------------------------------
    // Descriptions (shown in the info box)

    public Component frameGenerationTooltip(
            FrameGenerationBackend.GpuVendor candidate,
            String regularTooltipKey
    ) {
        Component rendererBlock = rendererBlockingDescription();
        if (rendererBlock != null) {
            return Component.translatable(
                    "dlss_fg.tooltip.feature_unavailable",
                    rendererBlock
            );
        }
        if (FrameGenConfig.getInstance()
                .hybridD3D12PresentationEnabled) {
            return Component.translatable(
                    "dlss_fg.tooltip.frame_generation.hybrid_conflict"
            );
        }
        FrameGenerationBackend.GpuVendor conflict =
                FrameGenConfig.getInstance()
                        .conflictingFrameGenerationVendor(candidate);
        if (conflict == FrameGenerationBackend.GpuVendor.UNKNOWN) {
            int capabilities = providerCapabilities(candidate);
            if ((capabilities
                    & StreamlineBridge.CAP_FRAME_GENERATION) == 0) {
                return Component.translatable(
                        "dlss_fg.tooltip.feature_unavailable",
                        providerStatusDescription(
                                candidate,
                                capabilities,
                                false
                        )
                );
            }
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
        if (rendererBlockingDescription() != null
                || (providerCapabilities(candidate)
                        & StreamlineBridge.CAP_FRAME_GENERATION) == 0
                || config.hybridD3D12PresentationEnabled) {
            return frameGenerationTooltip(candidate, regularTooltipKey);
        }
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

    public Component capabilityTooltip(
            FrameGenerationBackend.GpuVendor vendor,
            int capabilities,
            int requiredCapability,
            String regularTooltipKey
    ) {
        Component rendererBlock = rendererBlockingDescription();
        if (rendererBlock != null) {
            return Component.translatable(
                    "dlss_fg.tooltip.feature_unavailable",
                    rendererBlock
            );
        }
        if ((capabilities & requiredCapability) != 0) {
            return Component.translatable(regularTooltipKey);
        }
        return Component.translatable(
                "dlss_fg.tooltip.feature_unavailable",
                providerStatusDescription(vendor, capabilities, false)
        );
    }

    public Component rendererFeatureTooltip(String regularTooltipKey) {
        Component rendererBlock = rendererBlockingDescription();
        return rendererBlock == null
                ? Component.translatable(regularTooltipKey)
                : Component.translatable(
                        "dlss_fg.tooltip.feature_unavailable",
                        rendererBlock
                );
    }

    public Component rayTracingTooltip(
            RayTracingCapabilities capabilities,
            String regularTooltipKey
    ) {
        Component rendererBlock = rendererBlockingDescription();
        if (rendererBlock != null) {
            return Component.translatable(
                    "dlss_fg.tooltip.feature_unavailable",
                    rendererBlock
            );
        }
        if (ForeignShaderPipeline.active()) {
            return Component.translatable(
                    "dlss_fg.tooltip.feature_unavailable",
                    Component.translatable("dlss_fg.rt_block.shader_pack")
            );
        }
        if (capabilities.backendReady()) {
            return Component.translatable(regularTooltipKey);
        }
        String reasonKey = switch (capabilities.state()) {
            case WAITING_FOR_DEVICE ->
                    "dlss_fg.rt_block.waiting_for_device";
            case UNSUPPORTED ->
                    "dlss_fg.rt_block.unsupported";
            case EXTENSIONS_NOT_ENABLED ->
                    "dlss_fg.rt_block.extensions_not_enabled";
            case BACKEND_PENDING ->
                    "dlss_fg.rt_block.backend_pending";
            case READY -> "dlss_fg.rt_block.backend_pending";
        };
        return Component.translatable(
                "dlss_fg.tooltip.feature_unavailable",
                Component.translatable(reasonKey)
        );
    }

    private Component rendererBlockingDescription() {
        CompatibilityManager compatibility =
                FrameGenMod.compatibilityManager();
        if (compatibility == null) {
            return Component.translatable(
                    "dlss_fg.renderer_block.not_initialized"
            );
        }
        RendererDetection detection =
                compatibility.rendererDetection();
        if (detection.allowsVulkanFeatures()) {
            return null;
        }
        return Component.translatable(switch (
                detection.shaderPipelineState()) {
            case IRIS_OPENGL_SHADERPACK_ACTIVE ->
                    "dlss_fg.renderer_block.iris_shaderpack";
            case IRIS_OPENGL_SHADERPACK_OFF ->
                    "dlss_fg.renderer_block.opengl";
            case APERTURE_VULKAN_UNVERIFIED ->
                    "dlss_fg.renderer_block.aperture_unverified";
            case IRIS_API_UNAVAILABLE ->
                    "dlss_fg.renderer_block.iris_api";
            case IRIS_NOT_INSTALLED, IRIS_VULKAN_SHADERPACK_OFF,
                 IRIS_VULKAN_SHADERPACK_ACTIVE ->
                    "dlss_fg.renderer_block.not_vulkan";
        });
    }

    private static int providerCapabilities(
            FrameGenerationBackend.GpuVendor vendor
    ) {
        try {
            return vendor == FrameGenerationBackend.GpuVendor.NVIDIA
                    ? StreamlineBridge.nGetBackendSupportedCapabilities(
                            vendor.vendorId())
                    : StreamlineBridge.nGetBackendCapabilities(
                            vendor.vendorId());
        } catch (UnsatisfiedLinkError ignored) {
            return 0;
        }
    }

    private static Component providerStatusDescription(
            FrameGenerationBackend.GpuVendor vendor,
            int capabilities,
            boolean active
    ) {
        String status;
        try {
            status = StreamlineBridge.nGetBackendStatus(
                    vendor.vendorId());
        } catch (UnsatisfiedLinkError error) {
            status = "native bridge unavailable";
        }
        return BackendStatusFormatter.description(
                status,
                capabilities != 0,
                active
        );
    }

    // ------------------------------------------------------------------
    // Screen lifecycle

    public void refreshSettingsWidgets() {
        rebuildWidgets();
    }

    @Override
    public void removed() {
        super.removed();
        // Flush any coalesced slider save so the last drag position
        // always reaches disk when the screen closes.
        FrameGenConfig.flushPendingSave();
    }

    @Override
    protected void rebuildWidgets() {
        // Keep the scroll position across a rebuild.
        int savedScroll = scrollOffset;
        super.rebuildWidgets();
        scrollOffset = Math.clamp(savedScroll, 0, Math.max(0, maxScroll));
        if (!scrollableWidgets.isEmpty()) {
            applyScrollLayout();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            if (this.minecraft != null) {
                this.minecraft.setScreenAndShow(this.parent);
            }
            return true;
        }
        int key = event.key();
        if (key == 266) { // PAGE_UP
            if (scrollBy(-72)) return true;
        } else if (key == 267) { // PAGE_DOWN
            if (scrollBy(72)) return true;
        }
        return super.keyPressed(event);
    }

    private int settingsLeftX() {
        return (width - settingsContentWidth()) / 2;
    }

    private int settingsContentWidth() {
        return Math.min(420, Math.max(160, width - 24));
    }

    private boolean infoBoxVisible() {
        return height >= 250;
    }

    private int contentBottom() {
        int reserved = FOOTER_HEIGHT + (infoBoxVisible() ? INFO_HEIGHT : 0);
        return Math.max(CONTENT_TOP + 20, height - reserved);
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
        scrollUpButton = null;
        scrollDownButton = null;
        gridRowY = CONTENT_TOP;
        gridColumn = 0;

        FrameGenConfig config = FrameGenConfig.getInstance();
        addTabs();

        switch (activeTab) {
            case TAB_FRAME_GENERATION -> addFrameGenerationTab(config);
            case TAB_UPSCALING -> addUpscalingTab(config);
            case TAB_GRAPHICS -> addGraphicsTab(config);
            default -> {
                // Status is drawn as text in extractRenderState.
            }
        }
        finishRow();

        int footerY = height - 25;
        int buttonWidth = Math.min(150, Math.max(70, (width - 80) / 2));
        int footerLeft = width / 2 - buttonWidth - 3;
        this.addRenderableWidget(Button.builder(
                Component.translatable("dlss_fg.option.run_diagnostics"),
                button -> {
                    var results = org.malionaro.dlss_frame_generation.client.diagnostics
                            .FrameGenTestRunner.runAllDiagnostics();
                    this.minecraft.setScreenAndShow(new DiagnosticsScreen(this, results));
                }).bounds(footerLeft, footerY, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> {
            this.minecraft.setScreenAndShow(this.parent);
        }).bounds(width / 2 + 3, footerY, buttonWidth, 20).build());

        scrollUpButton = this.addRenderableWidget(Button.builder(Component.literal("▲"), button -> {
            scrollBy(-ROW);
        }).bounds(Math.max(4, footerLeft - 26), footerY, 20, 20).build());
        scrollDownButton = this.addRenderableWidget(Button.builder(Component.literal("▼"), button -> {
            scrollBy(ROW);
        }).bounds(Math.min(width - 24, width / 2 + 3 + buttonWidth + 6), footerY, 20, 20).build());

        maxScroll = Math.max(0, gridRowY - 4 - contentBottom());
        scrollOffset = Math.clamp(scrollOffset, 0, maxScroll);
        applyScrollLayout();
    }

    private void addTabs() {
        String[][] tabs = {
                {"dlss_fg.section.frame_generation", "dlss_fg.tooltip.section.frame_generation"},
                {"dlss_fg.tab.upscaling", "dlss_fg.tooltip.tab.upscaling"},
                {"dlss_fg.section.graphics", "dlss_fg.tooltip.section.graphics"},
                {"dlss_fg.tab.status", "dlss_fg.tooltip.tab.status"},
        };
        int gap = 4;
        int tabWidth = (settingsContentWidth() - gap * (tabs.length - 1)) / tabs.length;
        for (int index = 0; index < tabs.length; index++) {
            int tab = index;
            Button button = Button.builder(Component.translatable(tabs[index][0]), pressed -> {
                activeTab = tab;
                scrollOffset = 0;
                rebuildWidgets();
            }).bounds(settingsLeftX() + index * (tabWidth + gap), TAB_Y, tabWidth, 20).build();
            // The selected tab is drawn inactive, like vanilla's tab rows.
            button.active = activeTab != tab;
            button.setTooltip(Tooltip.create(Component.translatable(tabs[index][1])));
            this.addRenderableWidget(button);
        }
    }

    private void addFrameGenerationTab(FrameGenConfig config) {
        FrameGenerationBackend.GpuVendor vendor = activeVendor();
        int capabilities = providerCapabilities(vendor);
        switch (vendor) {
            case AMD -> AmdSettingsPanel.addFrameGeneration(this, config, capabilities);
            case INTEL -> IntelSettingsPanel.addFrameGeneration(this, config, capabilities);
            default -> NvidiaSettingsPanel.addFrameGeneration(this, config, capabilities);
        }

        var btnOsd = CycleButton.onOffBuilder(config.osdEnabled)
                .create(0, 0, settingsColumnWidth(), 20,
                        Component.translatable("dlss_fg.option.performance_osd"), (button, value) -> {
                            config.osdEnabled = value;
                            config.save();
                        });
        place(btnOsd, Component.translatable("dlss_fg.tooltip.performance_osd"));

        var btnPreset = CycleButton.builder(
                        (FrameGenConfig.Preset preset) -> Component.translatable(
                                "dlss_fg.value.preset."
                                        + preset.name().toLowerCase(java.util.Locale.ROOT)
                        ), config.activePreset)
                .withValues(FrameGenConfig.Preset.CUSTOM, FrameGenConfig.Preset.ESPORTS,
                        FrameGenConfig.Preset.CINEMA, FrameGenConfig.Preset.MAX_PERFORMANCE)
                .create(0, 0, settingsColumnWidth(), 20,
                        Component.translatable("dlss_fg.option.preset"), (button, value) -> {
                            config.applyPreset(value);
                            // Presets mutate the config directly, so they
                            // must push it to native like every other control.
                            FrameGenerationBackend.applyActiveConfig(config);
                            rebuildWidgets();
                        });
        place(btnPreset, Component.translatable("dlss_fg.tooltip.preset"));

        boolean vulkanFeatures = vulkanFeaturesAllowed();
        var btnMvDebug = CycleButton.builder(
                        (MotionVectorDebugRenderer.DebugMode mode) -> switch (mode) {
                            case OFF -> Component.translatable("dlss_fg.value.off");
                            case VECTOR_FIELD -> Component.translatable("dlss_fg.value.motion.arrows");
                            case VECTOR_HEATMAP -> Component.translatable("dlss_fg.value.motion.heatmap");
                            case RAW_MOTION_VECTORS -> Component.translatable("dlss_fg.value.motion.raw");
                        }, FrameGenMod.motionVectorDebugRenderer().getDebugMode())
                .withValues(MotionVectorDebugRenderer.DebugMode.OFF,
                        MotionVectorDebugRenderer.DebugMode.VECTOR_FIELD,
                        MotionVectorDebugRenderer.DebugMode.VECTOR_HEATMAP,
                        MotionVectorDebugRenderer.DebugMode.RAW_MOTION_VECTORS)
                .create(0, 0, settingsColumnWidth(), 20,
                        Component.translatable("dlss_fg.option.motion_debug"), (button, value) -> {
                            FrameGenMod.motionVectorDebugRenderer().setDebugMode(value);
                        });
        if (!vulkanFeatures) {
            FrameGenMod.motionVectorDebugRenderer().setDebugMode(
                    MotionVectorDebugRenderer.DebugMode.OFF);
        }
        btnMvDebug.active = vulkanFeatures;
        place(btnMvDebug, rendererFeatureTooltip("dlss_fg.tooltip.motion_debug"));

        var btnFallback = CycleButton.onOffBuilder(config.autoFallbackChainEnabled)
                .create(0, 0, settingsColumnWidth(), 20,
                        Component.translatable("dlss_fg.option.auto_fallback"), (button, value) -> {
                            config.autoFallbackChainEnabled = value;
                            config.save();
                        });
        place(btnFallback, Component.translatable("dlss_fg.tooltip.auto_fallback"));
    }

    private void addUpscalingTab(FrameGenConfig config) {
        FrameGenerationBackend.GpuVendor vendor = activeVendor();
        int capabilities = providerCapabilities(vendor);
        switch (vendor) {
            case AMD -> AmdSettingsPanel.addUpscaling(this, config, capabilities);
            case INTEL -> IntelSettingsPanel.addUpscaling(this, config, capabilities);
            default -> NvidiaSettingsPanel.addUpscaling(this, config, capabilities);
        }

        boolean vulkanFeatures = vulkanFeaturesAllowed();
        int selectedMode = switch (vendor) {
            case NVIDIA -> config.dlssUpscalingMode;
            case AMD -> config.amdFsrMode;
            case INTEL -> config.intelXessMode;
            case UNKNOWN -> 0;
        };
        boolean drsAvailable = vulkanFeatures
                && selectedMode > 0
                && (capabilities & StreamlineBridge.CAP_UPSCALING) != 0;

        var btnDrs = CycleButton.onOffBuilder(config.drsEnabled)
                .create(0, 0, settingsColumnWidth(), 20,
                        Component.translatable("dlss_fg.option.drs"), (button, value) -> {
                            config.drsEnabled = value;
                            config.save();
                            rebuildWidgets();
                        });
        btnDrs.active = drsAvailable;
        place(btnDrs, Component.translatable("dlss_fg.tooltip.drs"));

        var drsTarget = new SteppedSlider(
                settingsColumnWidth(), 30, 360, 6, config.targetFps,
                value -> Component.translatable("dlss_fg.option.drs_target", value),
                value -> {
                    config.targetFps = value;
                    config.saveSoon();
                },
                value -> config.save());
        drsTarget.active = drsAvailable && config.drsEnabled;
        place(drsTarget, Component.translatable("dlss_fg.tooltip.drs_target"));

        var btnMotionBlur = CycleButton.onOffBuilder(config.motionBlurEnabled)
                .create(0, 0, settingsColumnWidth(), 20,
                        Component.translatable("dlss_fg.option.motion_blur"), (button, value) -> {
                            config.motionBlurEnabled = value;
                            config.save();
                            rebuildWidgets();
                        });
        btnMotionBlur.active = vulkanFeatures;
        place(btnMotionBlur, Component.translatable("dlss_fg.tooltip.motion_blur"));

        var blurStrength = new SteppedSlider(
                settingsColumnWidth(), 0, 100, 5,
                Math.round(config.motionBlurIntensity * 100.0f),
                value -> Component.translatable("dlss_fg.option.motion_blur_intensity", value),
                value -> {
                    config.motionBlurIntensity = value / 100.0f;
                    config.saveSoon();
                },
                value -> config.save());
        blurStrength.active = vulkanFeatures && config.motionBlurEnabled;
        place(blurStrength, Component.translatable("dlss_fg.tooltip.motion_blur_intensity"));

        if (vendor != FrameGenerationBackend.GpuVendor.NVIDIA) {
            var btnOptiScaler = CycleButton.onOffBuilder(config.optiScalerEnabled)
                    .create(0, 0, settingsColumnWidth(), 20,
                            Component.translatable("dlss_fg.option.optiscaler"), (button, value) -> {
                                config.optiScalerEnabled = value;
                                config.save();
                            });
            place(btnOptiScaler, Component.translatable(
                    "dlss_fg.tooltip.optiscaler",
                    OptiScalerIntegration.installDirectory()));
        }
    }

    private void addGraphicsTab(FrameGenConfig config) {
        heading(Component.translatable("dlss_fg.section.shader_packs"));
        var btnAutoBoost = CycleButton.onOffBuilder(config.shaderPackAutoBoost)
                .create(0, 0, settingsColumnWidth(), 20,
                        Component.translatable("dlss_fg.option.shader_pack_auto_boost"), (button, value) -> {
                            config.shaderPackAutoBoost = value;
                            config.save();
                        });
        place(btnAutoBoost, Component.translatable("dlss_fg.tooltip.shader_pack_auto_boost"));

        heading(Component.translatable("dlss_fg.tab.ray_tracing"));
        RayTracingCapabilities capabilities = FrameGenMod.rayTracingManager() != null
                ? FrameGenMod.rayTracingManager().capabilities()
                : RayTracingCapabilities.waitingForDevice();
        RayTracingSettingsPanel.addWidgets(this, config, capabilities);

        heading(Component.translatable("dlss_fg.tab.presentation"));
        addPresentationWidgets(config);
    }

    private void addPresentationWidgets(FrameGenConfig config) {
        D3D12PresentationBridge.Status bridge = D3D12PresentationBridge.status();

        var hybridToggle = CycleButton.onOffBuilder(config.hybridD3D12PresentationEnabled)
                .create(0, 0, settingsColumnWidth(), 20,
                        Component.translatable("dlss_fg.option.hybrid_d3d12"),
                        (button, value) -> {
                            config.setHybridD3D12PresentationEnabled(value);
                            config.save();
                            refreshSettingsWidgets();
                        });
        hybridToggle.active = bridge.stage() == D3D12PresentationBridge.Stage.D3D12_DEVICE_READY
                || bridge.stage() == D3D12PresentationBridge.Stage.PRESENTATION_READY;
        place(hybridToggle, hybridToggle.active
                ? Component.translatable("dlss_fg.tooltip.hybrid_d3d12")
                : Component.translatable(
                        "dlss_fg.tooltip.feature_unavailable",
                        Component.translatable(
                                bridge.stage() == D3D12PresentationBridge.Stage.FAILED
                                        ? "dlss_fg.presentation_block.failed"
                                        : "dlss_fg.presentation_block.not_ready")));

        place(statusButton(Component.translatable(
                        "dlss_fg.status.presentation.stage", bridge.stage())),
                Component.literal(bridge.detail()));

        var fsr = D3D12ProviderRuntimeAudit.fsr4FrameGeneration();
        place(statusButton(Component.translatable(
                        "dlss_fg.status.presentation.fsr4",
                        Component.translatable(fsr.complete() ? "dlss_fg.value.yes" : "dlss_fg.value.no"))),
                Component.literal(fsr.detail()));

        var xess = D3D12ProviderRuntimeAudit.xessFrameGeneration();
        place(statusButton(Component.translatable(
                        "dlss_fg.status.presentation.xess",
                        Component.translatable(xess.complete() ? "dlss_fg.value.yes" : "dlss_fg.value.no"))),
                Component.literal(xess.detail()));

        placeWide(statusButton(Component.translatable("dlss_fg.status.presentation.safety")),
                Component.translatable("dlss_fg.tooltip.hybrid_d3d12.safety"));
    }

    private Button statusButton(Component label) {
        Button button = Button.builder(label, pressed -> {})
                .bounds(0, 0, settingsColumnWidth(), 20)
                .build();
        button.active = false;
        return button;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (maxScroll > 0
                && verticalAmount != 0.0
                && mouseY >= CONTENT_TOP
                && mouseY <= contentBottom()) {
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

        if (activeTab == TAB_STATUS) {
            drawStatusTab(extractor);
        }
        if (infoBoxVisible() && activeTab != TAB_STATUS) {
            drawInfoBox(extractor, mouseX, mouseY);
        }

        if (maxScroll > 0) {
            int trackTop = CONTENT_TOP;
            int trackHeight = viewportBottom - trackTop;
            int thumbHeight = Math.max(12, trackHeight * trackHeight / (trackHeight + maxScroll));
            int thumbTravel = trackHeight - thumbHeight;
            int thumbTop = trackTop + scrollOffset * thumbTravel / maxScroll;
            int trackX = settingsLeftX() + settingsContentWidth() + 6;
            extractor.fill(trackX, trackTop, trackX + 3, viewportBottom, 0x40404040);
            extractor.fill(trackX, thumbTop, trackX + 3, thumbTop + thumbHeight, 0xFFC0C0C0);
        }
    }

    /** GPU, frame-generation state and shader-pack state as colored chips. */
    private void drawChips(GuiGraphicsExtractor extractor) {
        List<Chip> chips = new ArrayList<>();
        FrameGenerationBackend.GpuVendor vendor = activeVendor();
        chips.add(new Chip(Component.translatable(
                "dlss_fg.status.chip.gpu",
                VulkanRegistry.getPhysicalDeviceName(),
                vulkanDevicePresent() ? "Vulkan" : "OpenGL"), 0xFF15171A, 0xFFD7DBE0));

        String technology = switch (vendor) {
            case NVIDIA -> "DLSS-G";
            case AMD, INTEL -> "FSR 3.1 FG";
            case UNKNOWN -> "FG";
        };
        Component fgText = fgStatus == FrameGenerationUiStatus.Kind.ACTIVE
                ? Component.translatable("dlss_fg.status.chip.fg_active", technology, fgMultiplier)
                : Component.translatable("dlss_fg.status.chip.fg", technology,
                        Component.translatable(fgStatus.translationKey()));
        int fgBackground = switch (fgStatus) {
            case ACTIVE -> 0xFF15301C;
            case WAITING_FOR_INPUTS -> 0xFF3A2C10;
            case UNSUPPORTED -> 0xFF3A1414;
            case NO_WORLD, PAUSED, DISABLED_IN_SETTINGS -> 0xFF15171A;
        };
        int fgColor = switch (fgStatus) {
            case ACTIVE -> 0xFF9FF0A8;
            case WAITING_FOR_INPUTS -> 0xFFFFD27A;
            case UNSUPPORTED -> 0xFFFF9A9A;
            case NO_WORLD, PAUSED, DISABLED_IN_SETTINGS -> 0xFFC9CCD1;
        };
        chips.add(new Chip(fgText, fgBackground, fgColor));

        String engine = ForeignShaderPipeline.modId();
        if (engine != null) {
            chips.add(new Chip(Component.translatable("dlss_fg.status.chip.shader",
                    shaderEngineName(engine)), 0xFF3A2C10, 0xFFFFD27A));
        } else {
            Component block = rendererBlockingDescription();
            if (block != null) {
                chips.add(new Chip(block, 0xFF3A1414, 0xFFFF9A9A));
            }
        }

        // Drop chips from the front (the GPU first) until the row fits.
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
            String text = fitWidth(chip.text().getString(), available - 12);
            int chipWidth = font.width(text) + 8;
            extractor.fill(x - 1, CHIP_Y - 1, x + chipWidth + 1, CHIP_Y + CHIP_HEIGHT + 1, 0xFF000000);
            extractor.fill(x, CHIP_Y, x + chipWidth, CHIP_Y + CHIP_HEIGHT, chip.background());
            extractor.text(this.font, Component.literal(text), x + 4, CHIP_Y + 2, chip.color());
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
            extractor.text(this.font, Component.translatable("dlss_fg.screen.settings.hint"),
                    left + 6, top + 6, 0xFF9AA3AE);
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

    private void drawStatusTab(GuiGraphicsExtractor extractor) {
        int y = CONTENT_TOP + 2;
        int center = this.width / 2;
        FrameGenerationBackend backend = activeBackend();
        FrameGenerationUiStatus.Kind status = fgStatus;
        boolean active = status == FrameGenerationUiStatus.Kind.ACTIVE;
        int statusColor = switch (status) {
            case ACTIVE -> 0xFF00C000;
            case WAITING_FOR_INPUTS -> 0xFFFFAA00;
            // Menu / pause / switched off are expected states, not faults.
            case NO_WORLD, PAUSED, DISABLED_IN_SETTINGS -> 0xFFB0B0B0;
            case UNSUPPORTED -> 0xFFFF5555;
        };
        y = statusLine(extractor, Component.translatable(
                "dlss_fg.status.header.backend",
                backend.vendor().displayName(),
                Component.translatable(status.translationKey())), center, y, statusColor);

        Component yesNo = Component.translatable(active ? "dlss_fg.value.yes" : "dlss_fg.value.no");
        Component metrics = vramBytes > 0L
                ? Component.translatable("dlss_fg.status.header.frame_metrics", yesNo,
                        String.format(java.util.Locale.ROOT, "%.2f", vramBytes / (1024.0 * 1024.0)))
                : Component.translatable("dlss_fg.status.header.frame_metrics.no_vram", yesNo);
        y = statusLine(extractor, metrics, center, y, 0xFFB0B0B0);

        if (presentsPerSecond > 1.0) {
            // Derived, not measured: generated frames are presented inside
            // the interposer and cannot be observed from this mod.
            double derivedTotal = active && fgMultiplier > 1
                    ? presentsPerSecond * fgMultiplier
                    : presentsPerSecond;
            y = statusLine(extractor, Component.translatable(
                    "dlss_fg.status.header.present_rate",
                    String.format(java.util.Locale.ROOT, "%.0f", presentsPerSecond),
                    fgMultiplier,
                    String.format(java.util.Locale.ROOT, "%.0f", derivedTotal)), center, y, 0xFF80C0FF);
        }
        y += 6;

        RayTracingCapabilities capabilities = FrameGenMod.rayTracingManager() != null
                ? FrameGenMod.rayTracingManager().capabilities()
                : RayTracingCapabilities.waitingForDevice();
        int rtColor = switch (capabilities.state()) {
            case READY -> 0xFF00C000;
            case UNSUPPORTED -> 0xFFFF5555;
            default -> 0xFFFFAA00;
        };
        Component rtState = ForeignShaderPipeline.active()
                ? Component.translatable("dlss_fg.status.rt_runtime.shader_pack")
                : Component.translatable(switch (capabilities.state()) {
                    case READY -> "dlss_fg.status.rt.ready";
                    case UNSUPPORTED -> "dlss_fg.status.rt.unsupported";
                    default -> "dlss_fg.status.rt.waiting";
                });
        y = statusLine(extractor, Component.translatable("dlss_fg.status.header.ray_tracing", rtState),
                center, y, ForeignShaderPipeline.active() ? 0xFFFFD27A : rtColor);
        y = statusLine(extractor, Component.translatable(
                "dlss_fg.status.header.ray_details",
                capabilities.deviceName(),
                Component.translatable(capabilities.rayQuerySupported() ? "dlss_fg.value.yes" : "dlss_fg.value.no"),
                Component.translatable(capabilities.rayTracingPipelineSupported() ? "dlss_fg.value.yes" : "dlss_fg.value.no")),
                center, y, 0xFFB0B0B0);
        y += 6;

        D3D12PresentationBridge.Status bridge = D3D12PresentationBridge.status();
        int bridgeColor = bridge.presentationReady()
                ? 0xFF00C000
                : bridge.stage() == D3D12PresentationBridge.Stage.FAILED ? 0xFFFF5555 : 0xFFFFAA00;
        y = statusLine(extractor, Component.translatable("dlss_fg.status.header.presentation", bridge.stage()),
                center, y, bridgeColor);
        statusLine(extractor, Component.literal(bridge.detail()), center, y, 0xFFB0B0B0);
    }

    private int statusLine(GuiGraphicsExtractor extractor, Component text, int center, int y, int color) {
        if (y + 9 > contentBottom()) {
            return y;
        }
        extractor.centeredText(this.font, Component.literal(fitWidth(text.getString(), width - 12)),
                center, y, color);
        return y + 12;
    }

    // ------------------------------------------------------------------
    // Helpers

    private FrameGenerationBackend.GpuVendor activeVendor() {
        CompatibilityManager compatibility = FrameGenMod.compatibilityManager();
        FrameGenerationBackend backend = compatibility != null
                ? compatibility.activeVendorBackend()
                : null;
        FrameGenerationBackend.GpuVendor vendor = backend != null
                ? backend.vendor()
                : FrameGenerationBackend.GpuVendor.UNKNOWN;
        return vendor == FrameGenerationBackend.GpuVendor.UNKNOWN
                ? FrameGenerationBackend.GpuVendor.NVIDIA
                : vendor;
    }

    private FrameGenerationBackend activeBackend() {
        return switch (activeVendor()) {
            case NVIDIA -> new NvidiaBackend();
            case AMD -> new AmdBackend();
            case INTEL -> new IntelBackend();
            case UNKNOWN -> new FallbackBackend();
        };
    }

    private static boolean vulkanFeaturesAllowed() {
        CompatibilityManager compatibility = FrameGenMod.compatibilityManager();
        return compatibility != null
                && compatibility.rendererDetection().allowsVulkanFeatures();
    }

    private static boolean vulkanDevicePresent() {
        CompatibilityManager compatibility = FrameGenMod.compatibilityManager();
        return compatibility != null
                && compatibility.rendererDetection().actualVulkanDevice();
    }

    private static Component shaderEngineName(String modId) {
        return Component.translatable(switch (modId) {
            case "iris" -> "dlss_fg.value.shader_engine.iris";
            case "vitrail" -> "dlss_fg.value.shader_engine.vitrail";
            case "vulcade" -> "dlss_fg.value.shader_engine.vulcade";
            default -> "dlss_fg.value.unknown";
        });
    }

    private String fitWidth(String text, int availableWidth) {
        int limit = Math.max(40, availableWidth);
        if (font.width(text) <= limit) {
            return text;
        }
        String suffix = "...";
        int contentWidth = Math.max(1, limit - font.width(suffix));
        return font.plainSubstrByWidth(text, contentWidth) + suffix;
    }
}
