package com.winllc.innoutwork.cron;

import com.winllc.innoutwork.audit.AuditLog;
import org.springframework.beans.factory.annotation.Autowired;
import com.winllc.innoutwork.audit.JobAudit;
import com.winllc.innoutwork.config.RefreshUserRecordsCronProperties;
import com.winllc.innoutwork.data.DirectorySyncResult;
import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.LicensedJobs;
import com.winllc.innoutwork.service.UserService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the metadata columns on user records in step with the directory.
 *
 * <p>The work itself lives in {@link UserService#syncUserRecordsFromDirectory()}; this
 * class only decides when it runs and reports the outcome.
 */
@Component
public class RefreshUserRecordsCron {

    private static final Logger log = LoggerFactory.getLogger(RefreshUserRecordsCron.class);

    private final UserService userService;
    private final RefreshUserRecordsCronProperties properties;
    /**
     * The sync builds the list of tracked people, so it is part of attendance tracking and pauses
     * without a full license. While it runs, people beyond the licensed size are not added (see
     * UserService and TrackedPeopleLimit); nobody is ever removed.
     */
    private final LicensedJobs licensedJobs;

    private AuditLog audit = AuditLog.NOOP;

    @Autowired(required = false)
    public void setAuditLog(AuditLog audit) {
        this.audit = audit;
    }

    public RefreshUserRecordsCron(UserService userService, RefreshUserRecordsCronProperties properties,
                                  LicensedJobs licensedJobs) {
        this.userService = userService;
        this.properties = properties;
        this.licensedJobs = licensedJobs;
    }

    // Not @Async: with it the scheduler returned at once, so the fixed delay never waited for a run to
    // finish and a slow run could overlap the next. spring.task.scheduling.pool.size gives each job a thread.
    // One replica at a time (ShedLock, see config.SchedulerLockConfig): the sync is the heaviest directory reader,
    // and once an interval is enough however many replicas there are.
    @Scheduled(fixedDelayString = "#{@refreshUserRecordsCronProperties.fixedRate}",
            initialDelayString = "#{@refreshUserRecordsCronProperties.initialDelay}")
    @SchedulerLock(name = "RefreshUserRecordsCron",
            lockAtLeastFor = "#{@jobLocks.atLeastFor(@refreshUserRecordsCronProperties.fixedRate)}",
            lockAtMostFor = "#{@jobLocks.atMostFor(@refreshUserRecordsCronProperties.fixedRate)}")
    public void run() {
        if (!properties.isEnabled()) {
            log.debug("RefreshUserRecordsCron is disabled; skipping run");
            return;
        }
        if (!licensedJobs.mayRun(LicensedFeature.ATTENDANCE, "RefreshUserRecordsCron")) {
            return;
        }

        long start = System.currentTimeMillis();
        try {
            DirectorySyncResult result = userService.syncUserRecordsFromDirectory();
            // One event per run, with the counts; never one per person.
            JobAudit.completed(audit, "RefreshUserRecordsCron", start, java.util.Map.of(
                    "scanned", result.scanned(), "created", result.created(), "updated", result.updated(),
                    "unchanged", result.unchanged(), "skipped", result.skipped(), "notAdded", result.notAdded()));

            // The service logs the detail; this line is about the job having run at all.
            log.debug("RefreshUserRecordsCron finished: {} scanned, {} written, {} not added (license size)",
                    result.scanned(), result.written(), result.notAdded());
        } catch (Exception e) {
            // Unattended and periodic: swallow so one bad run does not kill the schedule.
            log.error("RefreshUserRecordsCron failed; the next run will retry", e);
            JobAudit.failed(audit, "RefreshUserRecordsCron", start, e);
        }
    }
}
