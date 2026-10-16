package org.malionaro.dlss_frame_generation.client.ui.settings;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Integer slider snapping to {@code step} between {@code min} and {@code max},
 * the same control as Minecraft's volume sliders.
 *
 * <p>{@code onChange} runs while dragging, only when the snapped value really
 * changes, so it can update the config field and coalesce the save
 * ({@code saveSoon}). {@code onCommit} runs once when the mouse is released
 * (or a key step ends) and is where the config is saved and pushed to native.</p>
 */
public final class SteppedSlider extends AbstractSliderButton {
    private final int min;
    private final int max;
    private final int step;
    private final IntFunction<Component> label;
    private final IntConsumer onChange;
    private final IntConsumer onCommit;
    private int current;

    public SteppedSlider(
            int width,
            int min,
            int max,
            int step,
            int value,
            IntFunction<Component> label,
            IntConsumer onChange,
            IntConsumer onCommit
    ) {
        super(0, 0, width, 20, Component.empty(), 0.0);
        this.min = min;
        this.max = Math.max(min, max);
        this.step = Math.max(1, step);
        this.label = label;
        this.onChange = onChange;
        this.onCommit = onCommit;
        this.current = snap(value);
        this.value = position(current);
        updateMessage();
    }

    public int current() {
        return current;
    }

    @Override
    protected void updateMessage() {
        setMessage(label.apply(current));
    }

    @Override
    protected void applyValue() {
        double raw = Double.isFinite(value) ? value : position(current);
        int snapped = snap((int) Math.round(min + raw * (max - min)));
        // Keep the knob on the grid so it never rests between two steps.
        value = position(snapped);
        if (snapped != current) {
            current = snapped;
            onChange.accept(current);
        }
        updateMessage();
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
        onCommit.accept(current);
    }

    private int snap(int candidate) {
        int clamped = Math.clamp(candidate, min, max);
        int steps = Math.round((clamped - min) / (float) step);
        return Math.min(max, min + steps * step);
    }

    private double position(int stepValue) {
        return max == min ? 0.0 : (stepValue - min) / (double) (max - min);
    }
}
