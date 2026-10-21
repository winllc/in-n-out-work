package com.winllc.innoutwork.cron;

import com.winllc.innoutwork.export.ReportScheduleService;
import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.LicensedJobs;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the scheduled reports that are due (ReportScheduleService), every few minutes, on one replica at a time
 * (ShedLock), so none is sent twice. Runs only while the license includes reports.
 */
@Component
public class ScheduledReportsJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledReportsJob.class);

    private final ReportScheduleService schedules;
    private final LicensedJobs licensedJobs;

    public ScheduledReportsJob(ReportScheduleService schedules, LicensedJobs licensedJobs) {
        this.schedules = schedules;
        this.licensedJobs = licensedJobs;
    }

    @Scheduled(fixedDelayString = "${application.reports.check-millis:300000}",
            initialDelayString = "${application.reports.check-initial-delay-millis:120000}")
    @SchedulerLock(name = "ScheduledReportsJob", lockAtMostFor = "PT30M")
    public void run() {
        if (!licensedJobs.mayRun(LicensedFeature.METRICS, "ScheduledReportsJob")) {
            return;
        }
        try {
            int ran = schedules.runDue();
            if (ran > 0) {
                log.info("Sent {} scheduled report(s)", ran);
            }
        } catch (RuntimeException e) {
            log.error("Scheduled reports failed", e);
        }
    }
}
