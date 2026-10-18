package com.winllc.innoutwork.util;

/**
 * Values from a request, made safe to log: line breaks and other control characters are replaced, so a value
 * can't start a forged log line.
 */
public final class LogSafe {

    private LogSafe() {
    }

    public static String of(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("[\\r\\n]", "_").replaceAll("\\p{Cntrl}", "?");
    }
}
