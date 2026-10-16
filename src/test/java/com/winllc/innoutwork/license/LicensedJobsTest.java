package com.winllc.innoutwork.license;

import com.enssolutions.license.LicenseManager;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.RefreshUserRecordsCronProperties;
import com.winllc.innoutwork.cron.MarkInactiveCron;
import com.winllc.innoutwork.cron.RefreshUserRecordsCron;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Scheduled jobs run only while the license is in full effect and includes their feature. */
class LicensedJobsTest {

    @TempDir
    Path dir;

    private LicensedJobs jobs(String license) {
        LicenseManager licenses = TestLicenses.manager(dir);
        if (license != null) {
            TestLicenses.installFile(licenses, license);
        }
        return new LicensedJobs(licenses);
    }

    @Test
    void aFullLicenseRunsTheJobsOfItsFeatures() {
        LicensedJobs jobs = jobs(TestLicenses.license().without(LicensedFeature.ABSENCE_ALERTS).sign());

        assertTrue(jobs.mayRun(LicensedFeature.ATTENDANCE, "MarkInactiveCron"));
        assertFalse(jobs.mayRun(LicensedFeature.ABSENCE_ALERTS, "SendAbsentNotificationsCron"));
    }

    @Test
    void everyJobPausesWhileReadOnly() {
        LicensedJobs jobs = jobs(TestLicenses.license().expired(1).sign());

        for (LicensedFeature feature : LicensedFeature.values()) {
            assertFalse(jobs.mayRun(feature, "job"), feature.id());
        }
    }

    @Test
    void everyJobPausesWithoutALicense() {
        LicensedJobs jobs = jobs(null);

        for (LicensedFeature feature : LicensedFeature.values()) {
            assertFalse(jobs.mayRun(feature, "job"), feature.id());
        }
    }

    @Test
    void autoCheckOutPausesWhenReadOnly() {
        CheckInOutRecordRepository checkIns = mock(CheckInOutRecordRepository.class);
        MarkInactiveCron cron = new MarkInactiveCron(checkIns, new ApplicationProperties(),
                jobs(TestLicenses.license().expired(1).sign()));

        cron.run();

        verifyNoInteractions(checkIns);
    }

    @Test
    void theDirectorySyncPausesWithoutALicenseAndRunsWithOne() {
        UserService users = mock(UserService.class);
        when(users.syncUserRecordsFromDirectory()).thenReturn(com.winllc.innoutwork.data.DirectorySyncResult.EMPTY);
        RefreshUserRecordsCronProperties enabled = new RefreshUserRecordsCronProperties();

        new RefreshUserRecordsCron(users, enabled, jobs(null)).run();
        verifyNoInteractions(users);

        new RefreshUserRecordsCron(users, enabled, jobs(TestLicenses.full())).run();
        verify(users).syncUserRecordsFromDirectory();
    }
}
