package org.malionaro.dlss_frame_generation.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import org.malionaro.dlss_frame_generation.client.MultiVendorSettingsScreen;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new MultiVendorSettingsScreen(parent);
    }
}
