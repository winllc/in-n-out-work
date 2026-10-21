package com.winllc.innoutwork.cron;

import com.winllc.innoutwork.audit.AuditLog;
import org.springframework.beans.factory.annotation.Autowired;
import com.winllc.innoutwork.audit.JobAudit;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.LicensedJobs;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.ServiceOutage;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.service.ServiceOutages;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Component
public class MarkInactiveCron {

    private static final Logger log = LoggerFactory.getLogger(MarkInactiveCron.class);

    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final ApplicationProperties properties;
    /** Automatic check-out is part of attendance tracking, and writes: it pauses without a full license. */
    private final LicensedJobs licensedJobs;

    private AuditLog audit = AuditLog.NOOP;
    /** Times the whole service was down; absent in tests that do not need them. */
    private ServiceOutages outages;

    /** Status changes for the webhooks; absent in tests that build this by hand. */
    private org.springframework.context.ApplicationEventPublisher events;

    @Autowired(required = false)
    public void setEventPublisher(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    @Autowired(required = false)
    public void setAuditLog(AuditLog audit) {
        this.audit = audit;
    }

    @Autowired(required = false)
    public void setServiceOutages(ServiceOutages outages) {
        this.outages = outages;
    }

    public MarkInactiveCron(CheckInOutRecordRepository checkInOutRecordRepository, ApplicationProperties properties,
                            LicensedJobs licensedJobs) {
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.properties = properties;
        this.licensedJobs = licensedJobs;
    }

    // Not @Async: with it the scheduler returned at once, so the fixed delay never waited for a run to
    // finish and a slow run could overlap the next. spring.task.scheduling.pool.size gives each job a thread.
    // One replica at a time (ShedLock, see config.SchedulerLockConfig): two would each write the same forced
    // check-out.
    @Scheduled(fixedDelayString = "#{@inactiveCronProperties.fixedRate}",
            initialDelayString = "#{@inactiveCronProperties.initialDelay}")
    @SchedulerLock(name = "MarkInactiveCron",
            lockAtLeastFor = "#{@jobLocks.atLeastFor(@inactiveCronProperties.fixedRate)}",
            lockAtMostFor = "#{@jobLocks.atMostFor(@inactiveCronProperties.fixedRate)}")
    public void run(){
        if (!licensedJobs.mayRun(LicensedFeature.ATTENDANCE, "MarkInactiveCron")) {
            return;
        }

        long start = System.currentTimeMillis();
        try {
            int checkedOut = checkOutInactive(start);
            // Runs every minute: only runs that checked someone out are audited (job.completed), so the trail
            // is not one no-op event a minute. Failures always are.
            if (checkedOut > 0) {
                JobAudit.completed(audit, "MarkInactiveCron", start, java.util.Map.of("checkedOut", checkedOut));
            }
        } catch (RuntimeException e) {
            JobAudit.failed(audit, "MarkInactiveCron", start, e);
            throw e;
        }
    }

    private int checkOutInactive(long start) {

        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime startOfDay = ZonedDateTime.now().toLocalDate().atStartOfDay(ZoneId.systemDefault());
        ZonedDateTime endOfDay = startOfDay.plusDays(1).minusNanos(1);

        var lockedRecords = checkInOutRecordRepository.findLatestRecordsByDn(CheckInOutEnum.LOCK, startOfDay, endOfDay);

        log.debug("MarkInactiveCron: {} user(s) last seen locked today; checking out after {} minutes",
                lockedRecords.size(), properties.getCheckOutAfterMinutes());

        // An unlock sent while the whole service was down was lost: count such a session as idle from the outage's
        // end, not from its lock.
        List<ServiceOutage> outagesToday = outagesSince(startOfDay);

        int checkedOut = 0;
        for (CheckInOutRecord record : lockedRecords) {
            ZonedDateTime idleSince = record.getZonedDateTimestamp();
            if (!outagesToday.isEmpty()) {
                idleSince = ServiceOutages.idleSince(idleSince.toInstant(), outagesToday).atZone(idleSince.getZone());
            }
            ZonedDateTime timestampPlus = idleSince.plusMinutes(properties.getCheckOutAfterMinutes());

            if(timestampPlus.isBefore(now)){
                CheckInOutRecord checkInOutRecord = new CheckInOutRecord();
                checkInOutRecord.setDn(record.getDn());
                checkInOutRecord.setTimestamp(timestampPlus);
                checkInOutRecord.setAction(CheckInOutEnum.CHECK_OUT);
                checkInOutRecord.setLocation(record.getLocation());
                checkInOutRecord.setBranch(record.getBranch());
                checkInOutRecord.setEmployeeType(record.getEmployeeType());
                checkInOutRecord.setOrganization(record.getOrganization());
                checkInOutRecord.setSessionId(record.getSessionId());
                checkInOutRecord.setForced(true);
                checkInOutRecordRepository.save(checkInOutRecord);
                com.winllc.innoutwork.webhook.WebhookEvents.presence(events, checkInOutRecord);
                checkedOut++;

                // A forced check-out is a record the user did not create, so name who and when.
                log.debug("Forced check-out for {} at {} (locked since {})",
                        record.getDn(), timestampPlus, record.getZonedDateTimestamp());
            }
        }

        // Runs unattended: one line per run so it is visible that it ran and what it did.
        if (checkedOut > 0) {
            log.info("MarkInactiveCron checked out {} inactive user(s) of {} locked, in {}ms",
                    checkedOut, lockedRecords.size(), System.currentTimeMillis() - start);
        } else {
            log.debug("MarkInactiveCron found no inactive users to check out ({}ms)",
                    System.currentTimeMillis() - start);
        }
        return checkedOut;
    }

    private List<ServiceOutage> outagesSince(ZonedDateTime startOfDay) {
        if (outages == null) {
            return List.of();
        }
        try {
            outages.recordBeat();
            return outages.endedAfter(startOfDay.toInstant());
        } catch (RuntimeException e) {
            log.warn("Could not read the service's outages; checking out as if there were none: {}", e.toString());
            return List.of();
        }
    }
}
