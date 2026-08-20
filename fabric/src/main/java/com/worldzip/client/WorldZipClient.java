package com.worldzip.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorldZipClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("worldzip-client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("World Zip client initialized");
    }
}
