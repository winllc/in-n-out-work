package com.winllc.innoutwork.data;

import com.winllc.innoutwork.service.InvalidRequestException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The days an API call sets a status (or an organization calendar event) on: {@code from} up to, not including,
 * {@code toExclusive}, the way the calendar sends a selection. Every API that creates events parses its range here,
 * so each is held to {@code application.events.max-range-days}.
 *
 * <p>A date is either a plain ISO date ({@code 2026-10-19}) or an ISO date-time with a zone or offset, whose date
 * part is taken as written ({@code 2026-10-19T04:00:00.000Z} is the 19th), as the calendar pages have always sent.
 */
public record EventRange(LocalDate from, LocalDate toExclusive) {

    public EventRange {
        if (from == null || toExclusive == null || !toExclusive.isAfter(from)) {
            throw InvalidRequestException.badRequest("invalid_range",
                    "toDate must be after fromDate: it is the day after the last day");
        }
    }

    /**
     * @param maxDays the most days one range may cover (application.events.max-range-days)
     * @throws InvalidRequestException (400) for a missing or unreadable date, a range that ends before it starts,
     *                                 or one longer than {@code maxDays}
     */
    public static EventRange parse(String fromDate, String toDate, int maxDays) {
        EventRange range = new EventRange(date(fromDate, "fromDate"), date(toDate, "toDate"));
        if (range.days() > maxDays) {
            throw InvalidRequestException.badRequest("range_too_long",
                    "The range covers %d days; at most %d can be set at once (application.events.max-range-days)"
                            .formatted(range.days(), maxDays));
        }
        return range;
    }

    /** A range of whole days, {@code first} to {@code last} inclusive. */
    public static EventRange inclusive(LocalDate first, LocalDate last) {
        return new EventRange(first, last == null ? null : last.plusDays(1));
    }

    static LocalDate date(String value, String field) {
        if (value == null || value.isBlank()) {
            throw InvalidRequestException.badRequest("invalid_range", field + " is required");
        }
        String trimmed = value.strip();
        try {
            if (trimmed.length() == 10) {
                return LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE);
            }
            return LocalDate.parse(trimmed, DateTimeFormatter.ISO_ZONED_DATE_TIME);
        } catch (DateTimeParseException e) {
            throw InvalidRequestException.badRequest("invalid_range",
                    field + " is not an ISO date (2026-10-19) or date-time (2026-10-19T00:00:00Z)");
        }
    }

    /** How many days the range covers. */
    public int days() {
        return (int) ChronoUnit.DAYS.between(from, toExclusive);
    }

    /** The last day covered. */
    public LocalDate lastDay() {
        return toExclusive.minusDays(1);
    }

    /** Every day covered, in order. */
    public List<LocalDate> dates() {
        List<LocalDate> dates = new ArrayList<>(days());
        for (LocalDate day = from; day.isBefore(toExclusive); day = day.plusDays(1)) {
            dates.add(day);
        }
        return dates;
    }
}
