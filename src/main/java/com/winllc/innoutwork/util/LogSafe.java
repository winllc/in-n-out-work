package com.winllc.innoutwork.util;

/**
 * Values from a request, made safe to log: each line break (the regex {@code \R}: LF, CR LF, CR and the Unicode
 * ones) and every other control character is replaced, so a value can't start a forged log line.
 */
public final class LogSafe {

    private LogSafe() {
    }

    public static String of(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("\\R", "_").replaceAll("\\p{Cntrl}", "?");
    }
}
