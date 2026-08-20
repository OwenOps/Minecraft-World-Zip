package com.worldzip.archive;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Byte counters and cancel flag for a zip/unzip. The worker thread writes; the client thread reads
 * {@link #done()} / {@link #total()} each tick to draw the bar. No Minecraft types.
 */
public final class ArchiveProgress {

    private final AtomicLong done = new AtomicLong();
    private final AtomicLong total = new AtomicLong();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void reset(long totalBytes) {
        this.done.set(0);
        this.total.set(Math.max(0L, totalBytes));
    }

    public void add(long bytes) {
        if (bytes > 0) {
            this.done.addAndGet(bytes);
        }
    }

    public void cancel() {
        this.cancelled.set(true);
    }

    public boolean isCancelled() {
        return this.cancelled.get();
    }

    public long done() {
        return this.done.get();
    }

    public long total() {
        return this.total.get();
    }

    public float fraction() {
        long t = this.total.get();
        if (t <= 0L) {
            return 0f;
        }
        return Math.min(1f, (float) this.done.get() / (float) t);
    }
}
