package com.winllc.innoutwork.cron;

import com.winllc.innoutwork.config.RefreshUserRecordsCronProperties;
import com.winllc.innoutwork.data.DirectorySyncResult;
import com.winllc.innoutwork.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
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

    public RefreshUserRecordsCron(UserService userService, RefreshUserRecordsCronProperties properties) {
        this.userService = userService;
        this.properties = properties;
    }

    @Async
    @Scheduled(fixedDelayString = "#{@refreshUserRecordsCronProperties.fixedRate}",
            initialDelayString = "#{@refreshUserRecordsCronProperties.initialDelay}")
    public void run() {
        if (!properties.isEnabled()) {
            log.debug("RefreshUserRecordsCron is disabled; skipping run");
            return;
        }

        try {
            DirectorySyncResult result = userService.syncUserRecordsFromDirectory();

            // The service logs the detail; this line is about the job having run at all.
            log.debug("RefreshUserRecordsCron finished: {} scanned, {} written",
                    result.scanned(), result.written());
        } catch (Exception e) {
            // Unattended and periodic: swallow so one bad run does not kill the schedule.
            log.error("RefreshUserRecordsCron failed; the next run will retry", e);
        }
    }
}
