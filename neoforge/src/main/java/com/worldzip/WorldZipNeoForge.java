package com.worldzip;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entry. Gameplay lives in {@link WorldZip} (Minecraft APIs only).
 */
@Mod(WorldZip.MOD_ID)
public class WorldZipNeoForge {

    public WorldZipNeoForge(IEventBus modEventBus) {
        WorldZip.init();
        WorldZip.LOGGER.info("World Zip initialized (NeoForge)");
    }
}
