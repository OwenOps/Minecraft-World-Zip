package com.worldzip.archive;

import java.util.Locale;

/**
 * Binary size labels ({@code 1.2 GB}) for toasts and the world list.
 */
public final class ByteFormat {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private ByteFormat() {}

    public static String human(long bytes) {
        if (bytes < 0L) {
            return "0 B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024d && unit < UNITS.length - 1) {
            value /= 1024d;
            unit++;
        }
        if (unit == 0) {
            return bytes + " B";
        }
        return String.format(Locale.ROOT, value >= 10d ? "%.1f %s" : "%.2f %s", value, UNITS[unit]);
    }
}
