package org.malionaro.dlss_frame_generation.client.ui.diagnostics;
import org.malionaro.dlss_frame_generation.client.diagnostics.BackendStatusFormatter;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.malionaro.dlss_frame_generation.client.diagnostics.FrameGenTestRunner;

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
    private static final int COLOR_INFO = 0xFF55DDFF;
    private static final int COLOR_SKIPPED = 0xFFAAAAAA;
    private static final int COLOR_PENDING = 0xFFFFCC55;
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
            // Minecraft is null in headless/edge cases; parent null means close.
            if (minecraft != null) {
                minecraft.setScreenAndShow(parent);
            }
        }).bounds(left + buttonWidth + gap, footerY, buttonWidth, 20).build());

        // Content height up front (init runs on resize too), so scrolling
        // works before the first render instead of starting at maxScroll 0.
        recomputeContentHeight();
    }

    @Override
    public void onClose() {
        // See above.
        if (minecraft != null) {
            minecraft.setScreenAndShow(parent);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Keyboard scrolling mirrors MultiVendorSettingsScreen
        // (UP/DOWN = 24px, PgUp/PgDn = 120px); ESC keeps default handling.
        int key = event.key();
        int amount = key == 265 || event.isUp() ? -24
                : key == 264 || event.isDown() ? 24
                : key == 266 ? -120
                : key == 267 ? 120 : 0;
        if (amount != 0) {
            int maxScroll = maxScroll();
            int oldOffset = scrollOffset;
            scrollOffset = Math.clamp(scrollOffset + amount, 0, maxScroll);
            return scrollOffset != oldOffset;
        }
        return super.keyPressed(event);
    }

    private void recomputeContentHeight() {
        if (font == null) {
            return;
        }
        int contentWidth = Math.min(520, Math.max(140, width - 24));
        int textWidth = Math.max(40, contentWidth - 16);
        int total = 0;
        for (FrameGenTestRunner.TestResult result : results) {
            total += 20 + detailLines(result, textWidth).size() * LINE_HEIGHT + CARD_GAP;
        }
        contentHeight = Math.max(0, total);
        scrollOffset = Math.clamp(scrollOffset, 0, maxScroll());
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
        int failed = (int) results.stream().filter(
                FrameGenTestRunner.TestResult::failed
        ).count();
        int pending = results.size() - passed - failed;
        boolean hardwareValidated =
                FrameGenTestRunner.hardwarePathValidated(results);
        int summaryColor = hardwareValidated
                ? COLOR_PASS
                : failed > 0 ? COLOR_FAIL : COLOR_PENDING;
        extractor.centeredText(font, title, width / 2, 7, COLOR_WHITE);
        extractor.centeredText(
                font,
                Component.translatable(
                        "dlss_fg.diagnostics.summary",
                        passed,
                        failed,
                        pending,
                        Component.translatable(
                                hardwareValidated
                                        ? "dlss_fg.diagnostics.validated"
                                        : "dlss_fg.diagnostics.not_validated")
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
            List<String> messageLines = detailLines(result, textWidth);
            int cardHeight = 20 + messageLines.size() * LINE_HEIGHT;
            int clippedTop = Math.max(CONTENT_TOP, y);
            int clippedBottom = Math.min(contentBottom(), y + cardHeight);
            if (clippedBottom > clippedTop) {
                int background = switch (result.outcome()) {
                    case PASS -> 0x80204020;
                    case FAIL -> 0x80602020;
                    case INFO -> 0x80204060;
                    case SKIPPED -> 0x80404040;
                };
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
                                    outcomeTranslation(result.outcome())
                            ),
                            translatedResultName(result.name())
                    ),
                    left + 6,
                    y + 5,
                    outcomeColor(result.outcome())
            );
            int lineY = y + 17;
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
            case "Runtime environment" -> Component.translatable(
                    "dlss_fg.diagnostics.test.environment");
            case "Renderer integration" -> Component.translatable(
                    "dlss_fg.diagnostics.test.renderer");
            case "Native provider lifecycle" -> Component.translatable(
                    "dlss_fg.diagnostics.test.provider");
            case "Atomic Vulkan frame resources" -> Component.translatable(
                    "dlss_fg.diagnostics.test.resources");
            case "Depth and motion conventions" -> Component.translatable(
                    "dlss_fg.diagnostics.test.conventions");
            case "Hybrid Vulkan to D3D12 presentation" ->
                    Component.translatable(
                            "dlss_fg.diagnostics.test.d3d12");
            case "D3D12 provider runtimes" -> Component.translatable(
                    "dlss_fg.diagnostics.test.provider_runtimes");
            case "Adaptive rendering" -> Component.translatable(
                    "dlss_fg.diagnostics.test.adaptive_rendering");
            case "End-to-end frame generation" -> Component.translatable(
                    "dlss_fg.diagnostics.test.end_to_end");
            default -> Component.literal(name);
        };
    }

    private List<String> detailLines(
            FrameGenTestRunner.TestResult result,
            int maxWidth
    ) {
        List<Component> details = new ArrayList<>();
        if (result.message() != null && !result.message().isBlank()) {
            details.add(Component.literal(result.message().trim()));
        }
        if (!result.backendChecks().isEmpty()) {
            boolean hasSelectedBackend = result.backendChecks().stream()
                    .anyMatch(FrameGenTestRunner.BackendCheck::selected);
            if (!hasSelectedBackend) {
                details.add(Component.translatable(
                        "dlss_fg.diagnostics.backend.none_selected"
                ));
            }
            for (FrameGenTestRunner.BackendCheck check :
                    result.backendChecks()) {
                if (!check.selected()) {
                    details.add(Component.translatable(
                            "dlss_fg.diagnostics.backend.not_selected",
                            check.vendor().displayName()
                    ));
                    continue;
                }

                Component status = BackendStatusFormatter.description(
                        check.nativeStatus(),
                        check.capabilities() != 0,
                        false
                );
                details.add(Component.translatable(
                        "dlss_fg.diagnostics.backend.selected",
                        check.vendor().displayName(),
                        "0x" + Integer.toHexString(check.capabilities()),
                        status
                ));
            }
        }
        if (details.isEmpty()) {
            details.add(Component.translatable(
                    "dlss_fg.diagnostics.no_details"
            ));
        }

        List<String> lines = new ArrayList<>();
        for (Component detail : details) {
            lines.addAll(wrap(detail.getString(), maxWidth));
        }
        return lines;
    }

    private List<String> wrap(String value, int maxWidth) {
        String normalized = value == null || value.isBlank()
                ? Component.translatable(
                        "dlss_fg.diagnostics.no_details"
                ).getString()
                : value.trim();
        List<String> lines = new ArrayList<>();
        for (String explicitLine : normalized.split("\\R", -1)) {
            wrapLine(explicitLine, maxWidth, lines);
        }
        return lines;
    }

    private void wrapLine(
            String value,
            int maxWidth,
            List<String> lines
    ) {
        String remaining = value.strip();
        if (remaining.isEmpty()) {
            lines.add("");
            return;
        }
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
    }

    private int contentBottom() {
        return Math.max(CONTENT_TOP + 12, height - FOOTER_HEIGHT);
    }

    private static String outcomeTranslation(
            FrameGenTestRunner.Outcome outcome
    ) {
        return switch (outcome) {
            case PASS -> "dlss_fg.diagnostics.pass";
            case FAIL -> "dlss_fg.diagnostics.fail";
            case INFO -> "dlss_fg.diagnostics.info";
            case SKIPPED -> "dlss_fg.diagnostics.skipped";
        };
    }

    private static int outcomeColor(FrameGenTestRunner.Outcome outcome) {
        return switch (outcome) {
            case PASS -> COLOR_PASS;
            case FAIL -> COLOR_FAIL;
            case INFO -> COLOR_INFO;
            case SKIPPED -> COLOR_SKIPPED;
        };
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
