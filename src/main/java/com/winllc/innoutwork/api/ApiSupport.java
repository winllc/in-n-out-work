package com.winllc.innoutwork.api;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.service.InvalidRequestException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** What the /api/v1 controllers share: paging and date-range checks, time formats, and auditing each read. */
@Component
public class ApiSupport {

    private final ApplicationProperties.Api settings;
    private final AuditLog audit;

    public ApiSupport(ApplicationProperties properties, ObjectProvider<AuditLog> audit) {
        this.settings = properties.getApi();
        this.audit = audit.getIfAvailable(() -> AuditLog.NOOP);
    }

    /** A date range, both days included. */
    public record Range(LocalDate from, LocalDate to) {
    }

    public Range range(String from, String to) {
        LocalDate start = date("from", from);
        LocalDate end = date("to", to);
        if (end.isBefore(start)) {
            throw InvalidRequestException.badRequest("bad_request", "from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > settings.getMaxRangeDays()) {
            throw InvalidRequestException.badRequest("bad_request",
                    "A query may cover at most " + settings.getMaxRangeDays() + " days");
        }
        return new Range(start, end);
    }

    private static LocalDate date(String name, String value) {
        if (value == null || value.isBlank()) {
            throw InvalidRequestException.badRequest("bad_request", name + " is required (YYYY-MM-DD)");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw InvalidRequestException.badRequest("bad_request", name + " must be a date, YYYY-MM-DD");
        }
    }

    public PageRequest page(int page, int size, Sort sort) {
        if (page < 1) {
            throw InvalidRequestException.badRequest("bad_request", "page counts from 1");
        }
        if (size < 1 || size > settings.getMaxPageSize()) {
            throw InvalidRequestException.badRequest("bad_request", "size must be 1 to " + settings.getMaxPageSize());
        }
        return PageRequest.of(page - 1, size, sort);
    }

    /** ISO-8601 with the offset, in {@code zone}; null for null. */
    public static String time(ZonedDateTime time, ZoneId zone) {
        return time == null ? null : time.withZoneSameInstant(zone).toOffsetDateTime()
                .truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** A lower-cased LIKE pattern that contains {@code value}, escaped with '!'; empty for no filter. */
    public static String containing(String value) {
        String v = lower(value);
        if (v.isEmpty()) {
            return "";
        }
        return "%" + v.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    /**
     * Records a read of people's data through the API (data.exported, via api), before it is answered: a read that
     * cannot be audited is not given out.
     */
    public void audited(String report, String filter, long count) {
        audit.record(AuditEntry.of(AuditEntry.Category.DATA, "data.exported")
                .detail("report", report)
                .detail("format", "json")
                .detail("via", "api")
                .detail("filter", filter)
                .detail("count", count));
    }
}
