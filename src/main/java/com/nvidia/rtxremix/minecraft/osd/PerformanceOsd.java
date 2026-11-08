/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 Malionaro
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.osd;

import com.mojang.blaze3d.platform.InputConstants;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The performance overlay from DLSS Frame Generation, on top of the Remix renderer: a
 * card in the top left with the frame rate, the frame time and the state of RTX
 * rendering. F7 toggles it, End opens the RTX Remix settings. Whether it shows is kept
 * in {@code config/rtx-remix-osd.properties}. Also warns once per session when the game
 * runs with too little heap: without the release installer nobody sets the recommended
 * 16 GB, and launchers default to 2 to 4 GB. Render thread.
 */
public final class PerformanceOsd {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("rtx-remix-renderer", "osd"));
    private static final String FILE_NAME = "rtx-remix-osd.properties";
    /** Frame times averaged over this many frames, so the number stays readable. */
    private static final int WINDOW = 60;

    private static KeyMapping toggleKey;
    private static KeyMapping settingsKey;
    private static boolean enabled = true;
    private static final long[] frameNanos = new long[WINDOW];
    private static int frameIndex;
    private static int frameCount;
    private static long lastFrameNanos;
    /** Below this the renderer runs short of heap at normal render distances. */
    public static final long MIN_HEAP_BYTES = 8L * 1024 * 1024 * 1024;
    private static boolean heapWarned;

    private PerformanceOsd() {
    }

    public static void register() {
        enabled = load();
        // F7 and End are unbound in vanilla 26.3 and unused elsewhere in this mod.
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.rtxremix.toggleOsd", InputConstants.Type.KEYBOARD, InputConstants.KEY_F7, CATEGORY));
        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.rtxremix.openSettings", InputConstants.Type.KEYBOARD, InputConstants.KEY_END, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(PerformanceOsd::onTick);
    }

    private static void onTick(Minecraft client) {
        if (!heapWarned && client.player != null) {
            heapWarned = true;
            long heap = Runtime.getRuntime().maxMemory();
            if (heap < MIN_HEAP_BYTES) {
                long mib = heap / (1024 * 1024);
                RtxRemixMod.LOGGER.warn("Only {} MB of Java heap; RTX Remix needs at least 8 GB, 16 GB recommended", mib);
                client.player.sendSystemMessage(Component.translatable("rtxremix.osd.low_memory", mib));
            }
        }
        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            save();
        }
        while (settingsKey.consumeClick()) {
            if (client.gui.screen() == null) {
                RtxRemixMod.openSettingsScreen(null);
            }
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Shows or hides the overlay and remembers the choice. */
    public static void setEnabled(boolean value) {
        enabled = value;
        save();
    }

    /** Called once per HUD frame. */
    public static void render(GuiGraphicsExtractor extractor) {
        long now = System.nanoTime();
        if (lastFrameNanos != 0L) {
            frameNanos[frameIndex] = now - lastFrameNanos;
            frameIndex = (frameIndex + 1) % WINDOW;
            frameCount = Math.min(frameCount + 1, WINDOW);
        }
        lastFrameNanos = now;
        if (!enabled) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.font == null) {
            return;
        }

        record Line(Component text, int color) {
        }
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(Component.translatable("rtxremix.osd.title"), 0xFF00FF88));
        lines.add(new Line(Component.translatable("rtxremix.osd.fps", minecraft.getFps()), 0xFFFFFFFF));
        lines.add(new Line(Component.translatable("rtxremix.osd.frametime",
                String.format(Locale.ROOT, "%.2f", averageFrameMillis())), 0xFFE8E8E8));
        lines.add(new Line(Component.translatable("rtxremix.osd.rtx", Component.translatable(
                RtxRemixMod.isRtxEnabled() ? "rtxremix.osd.on" : "rtxremix.osd.off")), 0xFFFF88FF));
        lines.add(new Line(Component.translatable("rtxremix.osd.keys"), 0xFF9AA0A6));

        // The card follows the longest line, so longer translations still fit.
        int width = 16;
        for (Line line : lines) {
            width = Math.max(width, minecraft.font.width(line.text()) + 16);
        }
        int x = 10;
        int y = 10;
        extractor.fill(x, y, x + width, y + 12 + lines.size() * 13, 0xCC111111);
        int lineY = y + 6;
        for (Line line : lines) {
            extractor.text(minecraft.font, line.text(), x + 8, lineY, line.color());
            lineY += 13;
        }
    }

    static double averageFrameMillis() {
        if (frameCount == 0) {
            return 0.0;
        }
        long sum = 0L;
        for (int i = 0; i < frameCount; i++) {
            sum += frameNanos[i];
        }
        return sum / (double) frameCount / 1.0e6;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    private static boolean load() {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file())) {
            properties.load(input);
        } catch (IOException missing) {
            return true;
        }
        return !"false".equalsIgnoreCase(properties.getProperty("enabled", "true"));
    }

    private static void save() {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        try (OutputStream output = Files.newOutputStream(file())) {
            properties.store(output, "RTX Remix performance overlay");
        } catch (IOException error) {
            RtxRemixMod.LOGGER.warn("Could not save {}: {}", FILE_NAME, error.toString());
        }
    }
}
