package com.winllc.innoutwork.util;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class DateTimeUtil {

    public static ZonedDateTime getStartOfToday() {
        return LocalDate.now().atStartOfDay(ZoneId.systemDefault());
    }

    public static ZonedDateTime getEndOfToday() {
        return LocalDate.now().atTime(23, 59, 59).atZone(ZoneId.systemDefault());
    }
}
