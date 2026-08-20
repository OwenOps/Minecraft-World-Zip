package com.worldzip.archive;

/**
 * Thrown when a zip is not a safe Minecraft world archive, or zip/unzip fails.
 * Also used to unwind a zip/unzip that the user cancelled mid-operation (see {@link #cancelled()}).
 */
public final class WorldArchiveException extends Exception {

    private final boolean cancelled;

    public WorldArchiveException(String message) {
        super(message);
        this.cancelled = false;
    }

    public WorldArchiveException(String message, Throwable cause) {
        super(message, cause);
        this.cancelled = false;
    }

    private WorldArchiveException(String message, boolean cancelled) {
        super(message);
        this.cancelled = cancelled;
    }

    public static WorldArchiveException cancelled() {
        return new WorldArchiveException("Cancelled", true);
    }

    public boolean isCancelled() {
        return this.cancelled;
    }
}
