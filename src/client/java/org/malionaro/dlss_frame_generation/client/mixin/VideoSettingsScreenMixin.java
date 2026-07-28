package org.malionaro.dlss_frame_generation.client.mixin;

import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.malionaro.dlss_frame_generation.client.MultiVendorSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
    protected VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
        super(lastScreen, options, title);
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void addFrameGenerationSettings(CallbackInfo ci) {
        Button frameGenerationButton = Button.builder(
                Component.literal("Frame Generation..."),
                button -> this.minecraft.setScreenAndShow(new MultiVendorSettingsScreen(this))
        ).width(310).build();

        this.list.addBig(frameGenerationButton);
    }
}
