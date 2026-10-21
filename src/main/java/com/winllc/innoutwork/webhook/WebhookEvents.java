package com.winllc.innoutwork.webhook;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.model.CheckInOutRecord;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The webhook events ReadyRoom raises, and their {@code data}. Publishing is a no-op without a publisher (tests that
 * build services by hand); the events are queued only once the change commits (WebhookService).
 */
public final class WebhookEvents {

    private WebhookEvents() {
    }

    /** {@code status.changed}, kind {@code presence}: a check-in, lock, unlock or check-out was recorded. */
    public static void presence(ApplicationEventPublisher publisher, CheckInOutRecord record) {
        if (publisher == null || record == null || record.getDn() == null || record.getAction() == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("person", person(record.getDn()));
        data.put("kind", "presence");
        data.put("status", presenceOf(record.getAction()));
        data.put("action", record.getAction().name());
        data.put("at", record.getTimestamp() == null ? null
                : record.getTimestamp().toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        data.put("forced", Boolean.TRUE.equals(record.getForced()));
        publisher.publishEvent(WebhookEvent.of(WebhookEventType.STATUS_CHANGED, data));
    }

    /**
     * {@code status.changed}, kind {@code calendar}: a status was put on someone's calendar for some days.
     *
     * @param source request (an approved request), self (set by the person) or manager (an absence alert's answer)
     */
    public static void calendar(ApplicationEventPublisher publisher, String dn, UserStatusEnum status,
                                Collection<LocalDate> days, String source) {
        if (publisher == null || dn == null || status == null || days == null || days.isEmpty()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("person", person(dn));
        data.put("kind", "calendar");
        data.put("status", status.name());
        data.put("statusName", status.getFriendlyName());
        data.put("dates", days.stream().sorted().map(LocalDate::toString).toList());
        data.put("source", source);
        publisher.publishEvent(WebhookEvent.of(WebhookEventType.STATUS_CHANGED, data));
    }

    /** {@code absence.alert}: someone was not in by their expected time plus grace, and their managers were told. */
    public static void absence(ApplicationEventPublisher publisher, String dn, LocalDate day, LocalTime expectedBy,
                               int managersNotified) {
        if (publisher == null || dn == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("person", person(dn));
        data.put("date", day.toString());
        data.put("expectedBy", expectedBy == null ? null : expectedBy.toString());
        data.put("managersNotified", managersNotified);
        publisher.publishEvent(WebhookEvent.of(WebhookEventType.ABSENCE_ALERT, data));
    }

    static Map<String, Object> person(String dn) {
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("dn", dn);
        person.put("name", LdapDn.cnOf(dn));
        return person;
    }

    static String presenceOf(CheckInOutEnum action) {
        return switch (action) {
            case CHECK_IN, UNLOCK -> "IN";
            case LOCK -> "AWAY";
            case CHECK_OUT -> "OUT";
        };
    }
}
