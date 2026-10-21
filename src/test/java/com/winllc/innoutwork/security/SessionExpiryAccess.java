package com.winllc.innoutwork.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import javax.sql.DataSource;
import java.time.Clock;

import static org.mockito.Mockito.mock;

/** SessionExpiry's clean-up, for tests in other packages (the PostgreSQL tests). */
public final class SessionExpiryAccess {

    private SessionExpiryAccess() {
    }

    @SuppressWarnings("unchecked")
    public static int purge(DataSource dataSource, SessionControls controls) {
        return new SessionExpiry(mock(ObjectProvider.class), dataSource, controls, "SPRING_SESSION", Clock.systemUTC())
                .purge();
    }
}
