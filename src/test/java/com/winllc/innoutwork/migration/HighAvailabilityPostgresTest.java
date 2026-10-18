package com.winllc.innoutwork.migration;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.InactiveCronProperties;
import com.winllc.innoutwork.config.SchedulerLockConfig;
import com.winllc.innoutwork.cron.MarkInactiveCron;
import com.winllc.innoutwork.license.LicensedJobs;
import com.winllc.innoutwork.model.ServiceOutage;
import com.winllc.innoutwork.repository.ServiceHeartbeatRepository;
import com.winllc.innoutwork.repository.ServiceOutageRepository;
import com.winllc.innoutwork.security.SessionControls;
import com.winllc.innoutwork.security.SessionExpiryAccess;
import com.winllc.innoutwork.service.ServiceOutages;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Several replicas on one PostgreSQL database built by Flyway: the job locks, the shared browser sessions and the
 * service heartbeat. Each "replica" here is its own lock provider or repository over the same database, as two
 * pods would be.
 */
@Import({SchedulerLockConfig.class, MarkInactiveCron.class, HighAvailabilityPostgresTest.Beans.class})
class HighAvailabilityPostgresTest extends PostgresTestSupport {

    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ServiceHeartbeatRepository heartbeats;
    @Autowired private ServiceOutageRepository outages;
    @Autowired private MarkInactiveCron markInactiveCron;
    @MockitoBean private LicensedJobs licensedJobs;
    @MockitoBean private ApplicationProperties applicationProperties;

    private JdbcTemplate jdbc;

    /** Under the name the application's component scan gives it, which the job's lock expression refers to. */
    @TestConfiguration
    static class Beans {
        @Bean("inactiveCronProperties")
        InactiveCronProperties inactiveCronProperties() {
            return new InactiveCronProperties();
        }
    }

    @BeforeEach
    void clean() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM shedlock");
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM service_outage");
    }

    private LockProvider replica() {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().build());
    }

    private static LockConfiguration job(Duration atMost, Duration atLeast) {
        return new LockConfiguration(Instant.now(), "SendAbsentNotificationsCron", atMost, atLeast);
    }

    @Test
    void oneReplicaAtATimeRunsAJob() {
        LockProvider a = replica();
        LockProvider b = replica();

        Optional<SimpleLock> held = a.lock(job(Duration.ofMinutes(5), Duration.ZERO));
        assertTrue(held.isPresent());
        assertTrue(b.lock(job(Duration.ofMinutes(5), Duration.ZERO)).isEmpty(), "the other replica skips this run");

        held.get().unlock();
        Optional<SimpleLock> next = b.lock(job(Duration.ofMinutes(5), Duration.ZERO));
        assertTrue(next.isPresent(), "and runs the next one");
        next.get().unlock();
    }

    /** lockAtLeastFor: a quick run still keeps the other replica from running the job again within the interval. */
    @Test
    void aQuickRunKeepsTheLockForMostOfTheInterval() {
        LockProvider a = replica();
        a.lock(job(Duration.ofMinutes(5), Duration.ofMinutes(4))).orElseThrow().unlock();

        assertTrue(replica().lock(job(Duration.ofMinutes(5), Duration.ofMinutes(4))).isEmpty());
    }

    /** A replica killed mid-run (an upgrade's SIGKILL, a lost node) never unlocks; its lock lapses. */
    @Test
    void aDeadReplicasLockLapses() throws InterruptedException {
        assertTrue(replica().lock(job(Duration.ofSeconds(1), Duration.ZERO)).isPresent()); // never unlocked
        assertTrue(replica().lock(job(Duration.ofSeconds(1), Duration.ZERO)).isEmpty());

        Thread.sleep(1500);

        assertTrue(replica().lock(job(Duration.ofSeconds(1), Duration.ZERO)).isPresent());
    }

    /** The annotation as the jobs use it, its durations from the job's interval through the jobLocks bean. */
    @Test
    void aScheduledJobRunsOnceWhileItsLockIsHeld() {
        when(licensedJobs.mayRun(any(), anyString())).thenReturn(false);

        markInactiveCron.run();
        markInactiveCron.run(); // within 90% of the interval: skipped

        verify(licensedJobs, times(1)).mayRun(any(), anyString());
        // 90% of application.yml's one-minute interval. ShedLock writes UTC database time without a zone.
        assertEquals(54_000, jdbc.queryForObject("SELECT extract(epoch FROM lock_until - locked_at) * 1000"
                + " FROM shedlock WHERE name = 'MarkInactiveCron'", Double.class), 1.0);
    }

    @Test
    void aGapInTheHeartbeatIsRecordedAsAnOutage() {
        when(applicationProperties.getHa()).thenReturn(new ApplicationProperties.Ha());
        ServiceOutages service = new ServiceOutages(heartbeats, outages, transactions, applicationProperties);
        jdbc.update("UPDATE service_heartbeat SET beat_at = now() - interval '10 minutes' WHERE id = 1");

        service.recordBeat();
        service.recordBeat(); // a second replica beating right after: no second outage

        List<ServiceOutage> recorded = service.endedAfter(Instant.now().minus(Duration.ofHours(1)));
        assertEquals(1, recorded.size());
        Duration down = Duration.between(recorded.get(0).getDownFrom(), recorded.get(0).getDownUntil());
        assertTrue(down.compareTo(Duration.ofMinutes(9)) > 0, down.toString());
    }

    @Test
    void aSteadyHeartbeatIsNoOutage() {
        when(applicationProperties.getHa()).thenReturn(new ApplicationProperties.Ha());
        ServiceOutages service = new ServiceOutages(heartbeats, outages, transactions, applicationProperties);
        jdbc.update("UPDATE service_heartbeat SET beat_at = now() WHERE id = 1"); // another replica just beat

        service.recordBeat();
        service.recordBeat();

        assertTrue(service.endedAfter(Instant.now().minus(Duration.ofHours(1))).isEmpty());
    }

    // --- shared browser sessions ------------------------------------------------------------------------

    /** A DN longer than Spring Session's default 100-character principal_name. */
    private static final String LONG_DN = "CN=Bartholomew Montgomery-Fitzwilliam,OU=Facilities and Logistics Directorate,"
            + "OU=Headquarters Staff,OU=People,DC=headquarters,DC=example,DC=com";

    @SuppressWarnings({"unchecked", "rawtypes"})
    private FindByIndexNameSessionRepository<Session> sessionStore() {
        JdbcIndexedSessionRepository store = new JdbcIndexedSessionRepository(new JdbcTemplate(dataSource),
                new TransactionTemplate(transactions));
        store.afterPropertiesSet();
        return (FindByIndexNameSessionRepository) store;
    }

    @Test
    void aSessionSavedByOneReplicaIsFoundByAnother() {
        FindByIndexNameSessionRepository<Session> a = sessionStore();
        Session session = a.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(LONG_DN, "", AuthorityUtils.createAuthorityList("USER"))));
        a.save(session);

        FindByIndexNameSessionRepository<Session> b = sessionStore();
        assertTrue(LONG_DN.length() > 100);
        assertEquals(1, b.findByPrincipalName(LONG_DN).size());
        assertTrue(b.findById(session.getId()).getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                instanceof SecurityContextImpl);
    }

    /** Idle sessions are deleted and each signed-in one audited once, however many replicas run the clean-up. */
    @Test
    void expiredSessionsAreDeletedAndTheSignedInOnesAuditedOnce() {
        FindByIndexNameSessionRepository<Session> store = sessionStore();
        Session signedIn = store.createSession();
        signedIn.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(LONG_DN, "", AuthorityUtils.createAuthorityList("USER"))));
        store.save(signedIn);
        store.save(store.createSession()); // anonymous (the login page)
        Session live = store.createSession();
        store.save(live);
        jdbc.update("UPDATE spring_session SET expiry_time = ? WHERE session_id <> ?",
                System.currentTimeMillis() - 1000, live.getId());

        SessionControls controls = mock(SessionControls.class);
        assertEquals(1, SessionExpiryAccess.purge(dataSource, controls));
        assertEquals(0, SessionExpiryAccess.purge(dataSource, controls));

        verify(controls, times(1)).idleExpired(LONG_DN);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class));
        assertFalse(store.findByPrincipalName(LONG_DN).containsKey(signedIn.getId()));
    }
}
