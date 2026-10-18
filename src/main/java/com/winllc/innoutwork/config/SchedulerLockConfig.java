package com.winllc.innoutwork.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

/**
 * Scheduled jobs that must run on one replica at a time hold a lock in the database while they run (ShedLock, the
 * {@code shedlock} table from V6). A replica that finds a job's lock held skips that run; with one replica nothing
 * changes. Each lock is:
 * <ul>
 *   <li>kept while its run lasts, however long: it is extended every half {@code lockAtMostFor} while the job runs
 *       (KeepAliveLockProvider), so a slow directory sync or absence check is never run twice at once;</li>
 *   <li>freed when the run ends, or, when the replica holding it dies (killed during an upgrade, a node lost),
 *       {@code lockAtMostFor} after its last extension, so another replica takes over at its next run;</li>
 *   <li>kept for at least 90% of the job's interval ({@code lockAtLeastFor}) even after a quick run, so several
 *       replicas, each with its own timer, still run the job about once an interval between them.</li>
 * </ul>
 * Lock times are the database's clock, so the replicas' own clocks do not matter.
 * <p>
 * Jobs that keep each instance's own state run on every instance and are not locked: the access-denial summaries
 * (AccessDenials), the license mode check (LicenseModeAudit, whose change is recorded once under the audit chain's
 * row lock) and the service heartbeat (ServiceOutages).
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig implements DisposableBean {

    /** Extends held locks; not a bean, so it cannot be mistaken for the application's task scheduler. */
    private final ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor(daemon());

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        JdbcTemplateLockProvider jdbc = new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
        return new KeepAliveLockProvider(jdbc, keepAlive);
    }

    /** Lock durations derived from a job's interval, for the jobs' {@code @SchedulerLock} expressions. */
    @Bean
    JobLocks jobLocks() {
        return new JobLocks();
    }

    @Override
    public void destroy() {
        keepAlive.shutdownNow();
    }

    private static ThreadFactory daemon() {
        return runnable -> {
            Thread thread = new Thread(runnable, "shedlock-keepalive");
            thread.setDaemon(true);
            return thread;
        };
    }

    /** {@code #{@jobLocks.atLeastFor(@someCronProperties.fixedRate)}}: milliseconds, as ShedLock reads a number. */
    public static class JobLocks {

        /** The shortest lockAtMostFor: how soon a dead replica's lock is taken over, and ShedLock's keep-alive floor. */
        static final long MIN_AT_MOST_MILLIS = 120_000;

        /** 90% of the interval: replicas run the job about once an interval between them. */
        public String atLeastFor(long intervalMillis) {
            return String.valueOf(Math.max(0, intervalMillis / 10 * 9));
        }

        /** The interval, but at least two minutes: a dead replica's lock is free again by its next run. */
        public String atMostFor(long intervalMillis) {
            return String.valueOf(Math.max(MIN_AT_MOST_MILLIS, intervalMillis));
        }
    }
}
