package com.worldzip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-agnostic entry. Fabric and NeoForge call {@link #init()} from their own events.
 */
public final class WorldZip {

    public static final String MOD_ID = "worldzip";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private WorldZip() {}

    public static void init() {
        LOGGER.info("World Zip ready");
    }
}
