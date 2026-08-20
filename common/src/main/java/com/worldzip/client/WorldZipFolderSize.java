package com.worldzip.client;

/**
 * Vanilla {@link net.minecraft.world.level.storage.LevelSummary} mixins implement this so folder
 * disk size can be attached when the world list loads.
 */
public interface WorldZipFolderSize {

    void worldzip$setFolderBytes(long bytes);
}
