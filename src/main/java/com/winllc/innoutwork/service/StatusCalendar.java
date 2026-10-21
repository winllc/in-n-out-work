package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Writes statuses onto people's calendars (user_event_records), one entry per day. Callers check who may write what
 * (StatusPolicy, Approvers) and record the audit event, in the transaction they run this in.
 */
@Service
public class StatusCalendar {

    private final UserEventRecordRepository events;

    public StatusCalendar(UserEventRecordRepository events) {
        this.events = events;
    }

    /** Status changes for the webhooks; absent in tests that build this by hand. */
    private org.springframework.context.ApplicationEventPublisher publisher;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEventPublisher(org.springframework.context.ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Puts {@code status} on each of {@code days} for {@code dn}. A day's entry that the person or an approved
     * request put there is replaced; a status a manager recorded in answer to an absence alert is left beside it.
     *
     * @param loginByTime the expected arrival, for LATE_ARRIVAL; ignored otherwise
     * @param requestId   the approved request this comes from, or null for a status set directly
     * @return the number of days written
     */
    public int set(String dn, UserStatusEnum status, Collection<LocalDate> days, LocalTime loginByTime, Long requestId) {
        int written = 0;
        for (LocalDate day : days) {
            List<UserEventRecord> existing = events.findByDnIgnoreCaseAndDate(dn, day);
            Optional<UserEventRecord> replaceable = existing.stream()
                    .filter(r -> r.getRequestId() != null || (r.getStatus() != null && r.getStatus().isSelectable()))
                    .findFirst();

            UserEventRecord record = replaceable.orElseGet(UserEventRecord::new);
            record.setDn(dn);
            record.setDate(day);
            record.setStatus(status);
            record.setLoginByTime(status == UserStatusEnum.LATE_ARRIVAL ? loginByTime : null);
            record.setRequestId(requestId);
            events.save(record);
            written++;
        }
        // status.changed for the webhooks, queued once the caller's transaction commits.
        com.winllc.innoutwork.webhook.WebhookEvents.calendar(publisher, dn, status, days,
                requestId != null ? "request" : status.isSelectable() ? "self" : "manager");
        return written;
    }

    /** Takes an approved request's days off the calendar from {@code from} on; earlier days stay as history. */
    public int removeRequestDays(Long requestId, LocalDate from) {
        List<UserEventRecord> days = events.findByRequestIdFrom(requestId, from);
        events.deleteAll(days);
        return days.size();
    }
}
