package org.malionaro.dlss_frame_generation.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.malionaro.dlss_frame_generation.client.testing.FrameGenTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game presentation for the self-test results. The diagnostics continue to
 * be written to latest.log, while this screen makes the same result visible to
 * players without requiring them to inspect a log file.
 */
public final class DiagnosticsScreen extends Screen {
    private static final int CONTENT_TOP = 38;
    private static final int FOOTER_HEIGHT = 34;
    private static final int CARD_GAP = 5;
    private static final int LINE_HEIGHT = 10;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_PASS = 0xFF55FF55;
    private static final int COLOR_FAIL = 0xFFFF5555;
    private static final int COLOR_DETAILS = 0xFFDDDDDD;

    private final Screen parent;
    private List<FrameGenTestRunner.TestResult> results;
    private int scrollOffset;
    private int contentHeight;

    public DiagnosticsScreen(
            Screen parent,
            List<FrameGenTestRunner.TestResult> results
    ) {
        super(Component.translatable("dlss_fg.screen.diagnostics.title"));
        this.parent = parent;
        this.results = List.copyOf(results);
    }

    @Override
    protected void init() {
        int gap = 6;
        int buttonWidth = Math.min(150, Math.max(80, (width - 30 - gap) / 2));
        int totalWidth = buttonWidth * 2 + gap;
        int left = (width - totalWidth) / 2;
        int footerY = height - 27;

        Button runAgainButton = Button.builder(
                Component.translatable("dlss_fg.diagnostics.run_again"),
                button -> {
            results = List.copyOf(FrameGenTestRunner.runAllDiagnostics());
            scrollOffset = 0;
        }).bounds(left, footerY, buttonWidth, 20).build();
        runAgainButton.setTooltip(Tooltip.create(
                Component.translatable("dlss_fg.tooltip.run_diagnostics")
        ));
        addRenderableWidget(runAgainButton);

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> {
            minecraft.setScreenAndShow(parent);
        }).bounds(left + buttonWidth + gap, footerY, buttonWidth, 20).build());
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double horizontalAmount,
            double verticalAmount
    ) {
        if (verticalAmount != 0.0
                && mouseY >= CONTENT_TOP
                && mouseY <= contentBottom()) {
            int maxScroll = maxScroll();
            int oldOffset = scrollOffset;
            scrollOffset = Math.clamp(
                    scrollOffset + (verticalAmount > 0.0 ? -24 : 24),
                    0,
                    maxScroll
            );
            return scrollOffset != oldOffset;
        }
        return super.mouseScrolled(
                mouseX, mouseY, horizontalAmount, verticalAmount
        );
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor extractor,
            int mouseX,
            int mouseY,
            float delta
    ) {
        super.extractRenderState(extractor, mouseX, mouseY, delta);

        int passed = (int) results.stream().filter(
                FrameGenTestRunner.TestResult::passed
        ).count();
        int summaryColor = passed == results.size() ? COLOR_PASS : COLOR_FAIL;
        extractor.centeredText(font, title, width / 2, 7, COLOR_WHITE);
        extractor.centeredText(
                font,
                Component.translatable(
                        "dlss_fg.diagnostics.summary",
                        passed,
                        results.size()
                ),
                width / 2,
                22,
                summaryColor
        );

        int contentWidth = Math.min(520, Math.max(140, width - 24));
        int left = (width - contentWidth) / 2;
        int textWidth = Math.max(40, contentWidth - 16);
        int y = CONTENT_TOP - scrollOffset;

        for (FrameGenTestRunner.TestResult result : results) {
            List<String> messageLines = wrap(result.message(), textWidth);
            int cardHeight = 18 + messageLines.size() * LINE_HEIGHT;
            int clippedTop = Math.max(CONTENT_TOP, y);
            int clippedBottom = Math.min(contentBottom(), y + cardHeight);
            if (clippedBottom > clippedTop) {
                int background = result.passed() ? 0x80204020 : 0x80602020;
                extractor.fill(
                        left,
                        clippedTop,
                        left + contentWidth,
                        clippedBottom,
                        background
                );
            }

            drawVisibleLine(
                    extractor,
                    Component.translatable(
                            "dlss_fg.diagnostics.result",
                            Component.translatable(
                                    result.passed()
                                            ? "dlss_fg.diagnostics.pass"
                                            : "dlss_fg.diagnostics.fail"
                            ),
                            translatedResultName(result.name())
                    ),
                    left + 6,
                    y + 5,
                    result.passed() ? COLOR_PASS : COLOR_FAIL
            );
            int lineY = y + 16;
            for (String line : messageLines) {
                drawVisibleLine(
                        extractor,
                        Component.literal(line),
                        left + 8,
                        lineY,
                        COLOR_DETAILS
                );
                lineY += LINE_HEIGHT;
            }
            y += cardHeight + CARD_GAP;
        }

        contentHeight = Math.max(0, y + scrollOffset - CONTENT_TOP);
        scrollOffset = Math.clamp(scrollOffset, 0, maxScroll());
        drawScrollbar(extractor);
    }

    private void drawVisibleLine(
            GuiGraphicsExtractor extractor,
            Component text,
            int x,
            int y,
            int color
    ) {
        if (y >= CONTENT_TOP && y + font.lineHeight <= contentBottom()) {
            extractor.text(font, text, x, y, color);
        }
    }

    private Component translatedResultName(String name) {
        return switch (name) {
            case "Native capability query" ->
                    Component.translatable("dlss_fg.diagnostics.test.capabilities");
            case "Renderer input pipeline" ->
                    Component.translatable("dlss_fg.diagnostics.test.renderer_inputs");
            case "Reflex Pacer Latency Tracker" ->
                    Component.translatable("dlss_fg.diagnostics.test.reflex");
            case "AI Optical Flow Artifact Corrector" ->
                    Component.translatable("dlss_fg.diagnostics.test.optical_flow");
            case "Ultrawide Lens Distortion Corrector" ->
                    Component.translatable("dlss_fg.diagnostics.test.ultrawide");
            default -> Component.literal(name);
        };
    }

    private List<String> wrap(String value, int maxWidth) {
        String remaining = value == null || value.isBlank()
                ? Component.translatable(
                        "dlss_fg.diagnostics.no_details"
                ).getString()
                : value.trim();
        List<String> lines = new ArrayList<>();
        while (!remaining.isEmpty()) {
            String fitting = font.plainSubstrByWidth(remaining, maxWidth);
            if (fitting.isEmpty()) {
                fitting = remaining.substring(0, 1);
            }
            if (fitting.length() < remaining.length()) {
                int lastSpace = fitting.lastIndexOf(' ');
                if (lastSpace > 0) {
                    fitting = fitting.substring(0, lastSpace);
                }
            }
            lines.add(fitting);
            remaining = remaining.substring(fitting.length()).stripLeading();
        }
        return lines;
    }

    private int contentBottom() {
        return Math.max(CONTENT_TOP + 12, height - FOOTER_HEIGHT);
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (contentBottom() - CONTENT_TOP));
    }

    private void drawScrollbar(GuiGraphicsExtractor extractor) {
        int maxScroll = maxScroll();
        if (maxScroll <= 0) {
            return;
        }
        int trackTop = CONTENT_TOP;
        int trackBottom = contentBottom();
        int trackHeight = trackBottom - trackTop;
        int thumbHeight = Math.max(
                12,
                trackHeight * trackHeight / (trackHeight + maxScroll)
        );
        int thumbTravel = trackHeight - thumbHeight;
        int thumbTop = trackTop + scrollOffset * thumbTravel / maxScroll;
        extractor.fill(width - 7, trackTop, width - 3, trackBottom, 0x40404040);
        extractor.fill(
                width - 7,
                thumbTop,
                width - 3,
                thumbTop + thumbHeight,
                0xFFC0C0C0
        );
    }
}
