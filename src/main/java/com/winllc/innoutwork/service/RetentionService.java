package com.winllc.innoutwork.service;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.audit.JobAudit;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.reports.UserDayReport;
import com.winllc.innoutwork.metrics.EnsMetrics;
import com.winllc.innoutwork.model.AttendanceSummary;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.RetentionState;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.AttendanceSummaryRepository;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.RetentionStateRepository;
import com.winllc.innoutwork.repository.StatusRequestRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.util.Chunks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retention (RR-12, {@code application.retention.events-days}; 0, the default, keeps everything as before). On
 * {@code application.retention.cron} (daily), deletes what is older than that many days:
 * <ul>
 *   <li>check-in records and calendar statuses, first rolled up into one {@link AttendanceSummary} per person per
 *       month (days checked in, time in, days per status), which reports read for those months;</li>
 *   <li>absence alerts and status requests (a request once its last day is past the cutoff), without a summary.</li>
 * </ul>
 * It works a day at a time, oldest first, and within a day {@value #BATCH} people at a time: each batch is one short
 * transaction that reads, summarises and deletes its rows, so no table is locked for long and a failure loses no
 * summary (the batch rolls back whole). Every batch first locks the {@link RetentionState} row, so purges on several
 * instances take turns instead of counting the same records twice. Each run is audited as {@code data.purged} with
 * its counts. Audit events are not touched: they have their own retention (ENS_AUDIT_RETENTION_DAYS).
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    /** People per batch. */
    static final int BATCH = 500;
    /** Alerts or requests deleted per statement. */
    static final int DELETE_BATCH = 1000;
    /** Where the walk over the records starts: before any record ReadyRoom can hold. */
    private static final LocalDate BEGINNING = LocalDate.of(1900, 1, 1);

    private final ApplicationProperties properties;
    private final CheckInOutRecordRepository checkIns;
    private final UserEventRecordRepository events;
    private final NotificationRepository notifications;
    private final StatusRequestRepository requests;
    private final AttendanceSummaryRepository summaries;
    private final RetentionStateRepository state;
    private final TransactionTemplate transaction;
    private final Clock clock;

    private AuditLog audit = AuditLog.NOOP;

    @Autowired
    public RetentionService(ApplicationProperties properties, CheckInOutRecordRepository checkIns,
                            UserEventRecordRepository events, NotificationRepository notifications,
                            StatusRequestRepository requests, AttendanceSummaryRepository summaries,
                            RetentionStateRepository state, PlatformTransactionManager transactions) {
        this(properties, checkIns, events, notifications, requests, summaries, state, transactions,
                Clock.systemDefaultZone());
    }

    RetentionService(ApplicationProperties properties, CheckInOutRecordRepository checkIns,
                     UserEventRecordRepository events, NotificationRepository notifications,
                     StatusRequestRepository requests, AttendanceSummaryRepository summaries,
                     RetentionStateRepository state, PlatformTransactionManager transactions, Clock clock) {
        this.properties = properties;
        this.checkIns = checkIns;
        this.events = events;
        this.notifications = notifications;
        this.requests = requests;
        this.summaries = summaries;
        this.state = state;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setAuditLog(AuditLog audit) {
        this.audit = audit;
    }

    /** What one purge did. */
    public record Result(LocalDate purgedBefore, int days, long checkIns, long unattributedCheckIns, long statuses,
                         long notifications, long requests, long summariesWritten, long durationMs) {
        public Map<String, Object> counts() {
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("checkIns", checkIns);
            counts.put("unattributedCheckIns", unattributedCheckIns);
            counts.put("statuses", statuses);
            counts.put("notifications", notifications);
            counts.put("requests", requests);
            counts.put("summariesWritten", summariesWritten);
            counts.put("days", days);
            return counts;
        }
    }

    public boolean isEnabled() {
        return properties.getRetention().getEventsDays() > 0;
    }

    /** Records before this day have been summarised and deleted; null if nothing has been purged yet. */
    public LocalDate purgedBefore() {
        return state.findById(RetentionState.ID).map(RetentionState::getPurgedBefore).orElse(null);
    }

    public ZonedDateTime lastRunAt() {
        return state.findById(RetentionState.ID).map(RetentionState::getLastRunAt).orElse(null);
    }

    @Scheduled(cron = "${application.retention.cron:0 17 2 * * *}")
    public void run() {
        if (!isEnabled()) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            purge();
        } catch (RuntimeException e) {
            log.error("Retention purge failed; the next run carries on from where it stopped", e);
            JobAudit.failed(audit, "RetentionPurge", start, e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void describe() {
        int days = properties.getRetention().getEventsDays();
        if (days > 0) {
            log.info("Retention: check-in records, statuses, status requests and absence alerts older than {} days are"
                    + " summarised by month and deleted (application.retention.cron {})", days,
                    properties.getRetention().getCron());
        } else {
            log.info("Retention: check-in records and statuses are kept forever (application.retention.events-days 0)");
        }
    }

    /** Purges once, now; returns what it did, or null when retention is off. */
    public Result purge() {
        int days = properties.getRetention().getEventsDays();
        if (days <= 0) {
            return null;
        }
        long start = System.currentTimeMillis();
        ZoneId zone = clock.getZone();
        LocalDate cutoff = LocalDate.now(clock).minusDays(days);
        Counts counts = new Counts();

        // Check-ins and statuses: the next day with either, oldest first, until the cutoff.
        LocalDate from = BEGINNING;
        while (true) {
            LocalDate day = nextDay(from, cutoff, zone);
            if (day == null) {
                break;
            }
            purgeDay(day, zone, counts);
            counts.days++;
            from = day.plusDays(1);
        }

        // Absence alerts and status requests: no summary, deleted a batch at a time.
        ZonedDateTime cutoffStart = cutoff.atStartOfDay(zone);
        List<Long> ids;
        while (!(ids = notifications.findIdsBefore(cutoffStart, PageRequest.of(0, DELETE_BATCH))).isEmpty()) {
            List<Long> batch = ids;
            transaction.executeWithoutResult(s -> {
                lockState();
                notifications.deleteAllByIdInBatch(batch);
            });
            counts.notifications += batch.size();
        }
        while (!(ids = requests.findIdsEndingBefore(cutoff, PageRequest.of(0, DELETE_BATCH))).isEmpty()) {
            List<Long> batch = ids;
            transaction.executeWithoutResult(s -> {
                lockState();
                requests.deleteByIdIn(batch);
            });
            counts.requests += batch.size();
        }

        transaction.executeWithoutResult(s -> {
            RetentionState row = lockState();
            if (row.getPurgedBefore() == null || row.getPurgedBefore().isBefore(cutoff)) {
                row.setPurgedBefore(cutoff);
            }
            row.setLastRunAt(ZonedDateTime.now(clock));
            state.save(row);
        });

        Result result = new Result(cutoff, counts.days, counts.checkIns, counts.unattributed, counts.statuses,
                counts.notifications, counts.requests, counts.summaries, System.currentTimeMillis() - start);
        EnsMetrics.retentionPurged("checkin", result.checkIns() + result.unattributedCheckIns());
        EnsMetrics.retentionPurged("status", result.statuses());
        EnsMetrics.retentionPurged("notification", result.notifications());
        EnsMetrics.retentionPurged("request", result.requests());
        log.info("Retention purge: before {} ({} days kept), {} check-in record(s), {} status(es), {} absence alert(s),"
                        + " {} request(s) deleted over {} day(s); {} monthly summary write(s), in {}ms", cutoff, days,
                result.checkIns() + result.unattributedCheckIns(), result.statuses(), result.notifications(),
                result.requests(), result.days(), result.summariesWritten(), result.durationMs());
        AuditEntry entry = AuditEntry.of(AuditEntry.Category.DATA, "data.purged")
                .actor(AuditEntry.Actor.system())
                .source(null)
                .target("job", "RetentionPurge", "RetentionPurge")
                .detail("retentionDays", days)
                .detail("purgedBefore", cutoff.toString())
                .detail("durationMs", result.durationMs());
        result.counts().forEach(entry::detail);
        audit.recordQuietly(entry);
        return result;
    }

    /** The earliest day before {@code cutoff}, on or after {@code from}, with a check-in record or a status. */
    private LocalDate nextDay(LocalDate from, LocalDate cutoff, ZoneId zone) {
        if (!from.isBefore(cutoff)) {
            return null;
        }
        ZonedDateTime firstRecord = checkIns.findEarliestBetween(from.atStartOfDay(zone), cutoff.atStartOfDay(zone));
        LocalDate firstStatus = events.findEarliestBetween(from, cutoff);
        LocalDate recordDay = firstRecord == null ? null : firstRecord.withZoneSameInstant(zone).toLocalDate();
        if (recordDay != null && recordDay.isBefore(from)) {
            recordDay = from;
        }
        if (recordDay == null) {
            return firstStatus;
        }
        return firstStatus == null || recordDay.isBefore(firstStatus) ? recordDay : firstStatus;
    }

    /** One day: its people {@value #BATCH} at a time, then the records nobody can be summarised for. */
    private void purgeDay(LocalDate day, ZoneId zone, Counts counts) {
        ZonedDateTime dayStart = day.atStartOfDay(zone);
        ZonedDateTime nextDay = day.plusDays(1).atStartOfDay(zone);
        Set<String> people = new LinkedHashSet<>(checkIns.findDistinctLowercaseDnsBetween(dayStart, nextDay));
        people.addAll(events.findDistinctLowercaseDnsOn(day));

        for (List<String> batch : Chunks.of(people, BATCH)) {
            transaction.executeWithoutResult(s -> {
                lockState();
                purgeBatch(day, dayStart, nextDay, batch, counts);
            });
        }
        Integer unattributed = transaction.execute(s -> {
            lockState();
            return checkIns.deleteWithoutDnBetween(dayStart, nextDay);
        });
        counts.unattributed += unattributed == null ? 0 : unattributed;
    }

    /** Summarises, then deletes, one day's records and statuses of a batch of people; in the caller's transaction. */
    private void purgeBatch(LocalDate day, ZonedDateTime dayStart, ZonedDateTime nextDay, List<String> people,
                            Counts counts) {
        // Half-open, [dayStart, nextDay): the same day the DNs were found on, with no rounding at midnight.
        List<CheckInOutRecord> records = checkIns.findByLowercaseDnInFromUntil(people, dayStart, nextDay);
        List<UserEventRecord> statuses = events.findByLowercaseDnInAndDateBetween(people, day, day);
        if (records.isEmpty() && statuses.isEmpty()) {
            return;
        }

        Map<String, List<CheckInOutRecord>> recordsByDn = new HashMap<>();
        Map<String, String> storedDn = new HashMap<>();
        for (CheckInOutRecord record : records) {
            String key = record.getDn().toLowerCase();
            recordsByDn.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
            storedDn.putIfAbsent(key, record.getDn());
        }
        Map<String, Set<UserStatusEnum>> statusesByDn = new HashMap<>();
        for (UserEventRecord status : statuses) {
            String key = status.getDn().toLowerCase();
            storedDn.putIfAbsent(key, status.getDn());
            if (status.getStatus() != null && status.getStatus() != UserStatusEnum.STANDARD) {
                statusesByDn.computeIfAbsent(key, k -> EnumSet.noneOf(UserStatusEnum.class)).add(status.getStatus());
            }
        }

        LocalDate month = day.withDayOfMonth(1);
        Map<String, AttendanceSummary> monthSummaries = new HashMap<>();
        summaries.findByLowercaseDnInAndMonth(storedDn.keySet(), month)
                .forEach(summary -> monthSummaries.put(summary.getDn().toLowerCase(), summary));

        List<AttendanceSummary> changed = new ArrayList<>();
        ZonedDateTime now = ZonedDateTime.now(clock);
        for (Map.Entry<String, String> person : storedDn.entrySet()) {
            List<CheckInOutRecord> dayRecords = recordsByDn.getOrDefault(person.getKey(), List.of());
            Set<UserStatusEnum> dayStatuses = statusesByDn.getOrDefault(person.getKey(), Set.of());
            boolean checkedIn = dayRecords.stream().anyMatch(r -> r.getAction() == CheckInOutEnum.CHECK_IN);
            int minutes = dayRecords.isEmpty() ? 0 : Math.max(0, UserDayReport.build(day, null, dayRecords).getCheckedInMinutes());
            if (!checkedIn && minutes == 0 && dayStatuses.isEmpty()) {
                continue;
            }
            AttendanceSummary summary = monthSummaries.computeIfAbsent(person.getKey(),
                    k -> new AttendanceSummary(person.getValue(), month));
            if (checkedIn) {
                summary.setDaysCheckedIn(summary.getDaysCheckedIn() + 1);
            }
            summary.setMinutesIn(summary.getMinutesIn() + minutes);
            for (UserStatusEnum status : dayStatuses) {
                summary.getStatusDays().merge(status, 1, Integer::sum);
            }
            summary.setUpdatedAt(now);
            changed.add(summary);
        }
        summaries.saveAll(changed);

        if (!records.isEmpty()) {
            checkIns.deleteAllByIdInBatch(records.stream().map(CheckInOutRecord::getId).toList());
        }
        if (!statuses.isEmpty()) {
            events.deleteAllByIdInBatch(statuses.stream().map(UserEventRecord::getId).toList());
        }
        counts.checkIns += records.size();
        counts.statuses += statuses.size();
        counts.summaries += changed.size();
    }

    private RetentionState lockState() {
        return state.lockState().orElseGet(() -> {
            // V4 inserts the row; recreate it should someone have removed it.
            RetentionState row = new RetentionState();
            row.setId(RetentionState.ID);
            return state.save(row);
        });
    }

    private static final class Counts {
        int days;
        long checkIns;
        long unattributed;
        long statuses;
        long notifications;
        long requests;
        long summaries;
    }
}
