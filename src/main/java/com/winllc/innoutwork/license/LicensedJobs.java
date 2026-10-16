package com.winllc.innoutwork.license;

import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The guard at the top of every scheduled job: a job runs only while the license is in full effect and
 * unlocks its feature. Read-only and unlicensed installations pause them all (auto check-out, the
 * directory sync, absence e-mails).
 *
 * <pre>
 * &#64;Scheduled(...)
 * void run() {
 *     if (!jobs.mayRun(LicensedFeature.ABSENCE_ALERTS, "SendAbsentNotificationsCron")) return;
 *     ...
 * }
 * </pre>
 */
@Component
public class LicensedJobs {

    private static final Logger log = LoggerFactory.getLogger(LicensedJobs.class);

    private final LicenseManager licenses;
    /** Why each job last skipped, so a paused job says so once rather than every few minutes. */
    private final Map<String, String> reported = new ConcurrentHashMap<>();

    public LicensedJobs(LicenseManager licenses) {
        this.licenses = licenses;
    }

    public boolean mayRun(LicensedFeature feature, String job) {
        LicenseCheck check = licenses.current();
        if (check.isFeatureEnabled(feature.id())) {
            if (reported.remove(job) != null) {
                log.info("{}: running again; the license allows it", job);
            }
            return true;
        }
        String why = check.isFullyLicensed()
                ? feature.label() + " is not included in the license"
                : "paused while the license is " + check.state() + ": " + check.message();
        if (!why.equals(reported.put(job, why))) {
            log.info("{}: skipping, {}", job, why);
        }
        return false;
    }
}
