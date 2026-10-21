package com.winllc.innoutwork.security;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deletes expired browser sessions from the shared session store, auditing each signed-in one as
 * {@code auth.session-expired} with reason {@code idle}, as the container did for its own sessions. Spring
 * Session's own clean-up is turned off for this ({@code spring.session.jdbc.cleanup-cron: "-"}). Sessions ended
 * on purpose (sign-out, the absolute limit, the per-user limit) were deleted and audited when they ended, so every
 * signed-in session still here past its expiry went idle.
 * <p>
 * One replica at a time (ShedLock): each expiry is audited once. Does nothing where sessions are not in the
 * database.
 */
@Component
public class SessionExpiry {

    private static final Logger log = LoggerFactory.getLogger(SessionExpiry.class);

    static final int BATCH = 500;
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");

    private final ObjectProvider<JdbcIndexedSessionRepository> sessions;
    private final JdbcTemplate jdbc;
    private final SessionControls controls;
    private final String table;
    private final Clock clock;

    @Autowired
    public SessionExpiry(ObjectProvider<JdbcIndexedSessionRepository> sessions, DataSource dataSource,
                         SessionControls controls,
                         @Value("${spring.session.jdbc.table-name:SPRING_SESSION}") String table) {
        this(sessions, dataSource, controls, table, Clock.systemUTC());
    }

    SessionExpiry(ObjectProvider<JdbcIndexedSessionRepository> sessions, DataSource dataSource,
                  SessionControls controls, String table, Clock clock) {
        if (!TABLE_NAME.matcher(table).matches()) {
            throw new IllegalStateException("spring.session.jdbc.table-name is not a table name: " + table);
        }
        this.sessions = sessions;
        this.jdbc = new JdbcTemplate(dataSource);
        this.controls = controls;
        this.table = table;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @SchedulerLock(name = "SessionExpiry", lockAtLeastFor = "50s", lockAtMostFor = "5m")
    public void run() {
        if (sessions.getIfAvailable() == null) {
            return;
        }
        try {
            int expired = purge();
            if (expired > 0) {
                log.debug("Deleted {} expired signed-in session(s)", expired);
            }
        } catch (RuntimeException e) {
            log.warn("Could not clean up expired sessions; the next run retries: {}", e.toString());
        }
    }

    /** Deletes every session expired by now; returns how many signed-in ones were audited. */
    int purge() {
        long now = clock.millis();
        int audited = 0;
        List<String[]> rows;
        do {
            rows = jdbc.query("SELECT PRIMARY_ID, PRINCIPAL_NAME FROM " + table
                            + " WHERE EXPIRY_TIME < ? AND PRINCIPAL_NAME IS NOT NULL ORDER BY EXPIRY_TIME LIMIT " + BATCH,
                    (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, now);
            for (String[] row : rows) {
                // Deleted first, so a second run (the lock's time ran out) cannot audit it again.
                if (jdbc.update("DELETE FROM " + table + " WHERE PRIMARY_ID = ? AND EXPIRY_TIME < ?", row[0], now) > 0) {
                    controls.idleExpired(row[1]);
                    audited++;
                }
            }
        } while (rows.size() == BATCH);
        jdbc.update("DELETE FROM " + table + " WHERE EXPIRY_TIME < ? AND PRINCIPAL_NAME IS NULL", now);
        return audited;
    }
}
