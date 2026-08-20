package com.worldzip;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric entry. Gameplay lives in {@link WorldZip} (Minecraft APIs only).
 */
public class WorldZipMod implements ModInitializer {

    @Override
    public void onInitialize() {
        WorldZip.init();
        WorldZip.LOGGER.info("World Zip initialized (Fabric)");
    }
}
