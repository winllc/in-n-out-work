package com.winllc.innoutwork.cron;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.LicensedJobs;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.ServiceOutage;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.service.ServiceOutages;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Automatic check-out of sessions left locked, and what an outage of the whole service does to it. */
class MarkInactiveCronTest {

    private final CheckInOutRecordRepository records = mock(CheckInOutRecordRepository.class);
    private final ApplicationProperties properties = new ApplicationProperties(); // 240 minutes

    private MarkInactiveCron cron(ServiceOutage... outages) {
        LicensedJobs jobs = mock(LicensedJobs.class);
        when(jobs.mayRun(eq(LicensedFeature.ATTENDANCE), anyString())).thenReturn(true);
        MarkInactiveCron cron = new MarkInactiveCron(records, properties, jobs);
        ServiceOutages service = mock(ServiceOutages.class);
        when(service.endedAfter(any())).thenReturn(List.of(outages));
        cron.setServiceOutages(service);
        return cron;
    }

    private void lockedAt(ZonedDateTime when) {
        CheckInOutRecord lock = new CheckInOutRecord();
        lock.setDn("cn=bob");
        lock.setAction(CheckInOutEnum.LOCK);
        lock.setTimestamp(when);
        when(records.findLatestRecordsByDn(eq(CheckInOutEnum.LOCK), any(), any())).thenReturn(List.of(lock));
    }

    @Test
    void aSessionLockedLongerThanTheLimitIsCheckedOut() {
        ZonedDateTime locked = ZonedDateTime.now().minusMinutes(250).truncatedTo(ChronoUnit.SECONDS);
        lockedAt(locked);

        cron().run();

        ArgumentCaptor<CheckInOutRecord> saved = ArgumentCaptor.forClass(CheckInOutRecord.class);
        verify(records).save(saved.capture());
        assertEquals(CheckInOutEnum.CHECK_OUT, saved.getValue().getAction());
        assertTrue(saved.getValue().getForced());
        assertEquals(locked.plusMinutes(240).toInstant(), saved.getValue().getTimestamp().toInstant());
    }

    /** The unlock may have been sent while nothing was running: the session counts as idle from the outage's end. */
    @Test
    void aSessionLockedBeforeAnOutageIsCountedFromItsEnd() {
        lockedAt(ZonedDateTime.now().minusMinutes(250));
        Instant outageEnd = Instant.now().minusSeconds(60 * 60);

        cron(new ServiceOutage(outageEnd.minusSeconds(120), outageEnd)).run();

        verify(records, never()).save(any());
    }

    @Test
    void anOutageBeforeTheLockChangesNothing() {
        lockedAt(ZonedDateTime.now().minusMinutes(250));
        Instant outageEnd = Instant.now().minusSeconds(300 * 60);

        cron(new ServiceOutage(outageEnd.minusSeconds(120), outageEnd)).run();

        verify(records).save(any());
    }
}
