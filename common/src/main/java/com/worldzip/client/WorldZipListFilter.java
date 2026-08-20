package com.worldzip.client;

import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;

/**
 * Select World list filter. Files stay in {@code saves/}; this only hides rows.
 * The last choice is kept for the rest of the client session.
 */
public enum WorldZipListFilter {
    ALL,
    FOLDERS,
    ZIPPED;

    private static WorldZipListFilter current = ALL;

    public static WorldZipListFilter current() {
        return current;
    }

    public static void set(WorldZipListFilter filter) {
        current = filter == null ? ALL : filter;
    }

    public boolean accepts(LevelSummary summary) {
        boolean zipped = summary instanceof ZippedLevelSummary;
        return switch (this) {
            case ALL -> true;
            case FOLDERS -> !zipped;
            case ZIPPED -> zipped;
        };
    }

    public Component label() {
        return Component.translatable("worldzip.filter." + this.name().toLowerCase(Locale.ROOT));
    }
}
