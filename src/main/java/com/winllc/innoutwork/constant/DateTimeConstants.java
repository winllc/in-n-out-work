package com.winllc.innoutwork.constant;

import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Set;

public class DateTimeConstants {

    public static final String DATE_TIME_FORMAT = "MM/dd/yyyy HH:mm a z";
    public static final String DATE_FORMAT = "MM/dd/yyyy";
    public static final String TIME_FORMAT = "HH:mm z";
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT);
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_FORMAT);
    public static final DateTimeFormatter ISO_DATE_TIME_FORMATTER = DateTimeFormatter.ISO_ZONED_DATE_TIME;
    public static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern(TIME_FORMAT);

    /** Days nobody is expected in: skipped by the absence check and left out of average login times. */
    public static final Set<DayOfWeek> WEEKEND_DAYS = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
}
