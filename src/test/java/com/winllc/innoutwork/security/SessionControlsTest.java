package com.winllc.innoutwork.security;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.config.EnsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Idle, administrators' idle, absolute and concurrent session limits, each audited as auth.session-expired. */
class SessionControlsTest {

    private static final String ALICE = "CN=alice,OU=Users,DC=winllc,DC=com";

    private final AuditLog audit = mock(AuditLog.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-17T08:00:00Z"));
    private final EnsProperties.Session settings = new EnsProperties.Session();

    @SuppressWarnings("unchecked")
    private SessionControls controls() {
        ObjectProvider<AuditLog> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(audit);
        return new SessionControls(settings, provider, clock);
    }

    @BeforeEach
    void clean() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void cleanAfter() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication user(String dn, String... roles) {
        return UsernamePasswordAuthenticationToken.authenticated(dn, "", AuthorityUtils.createAuthorityList(roles));
    }

    private static MockHttpSession signedInSession(Authentication auth) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(auth));
        return session;
    }

    private static MockHttpServletRequest request(String path, MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setSession(session);
        return request;
    }

    private List<AuditEntry> audited() {
        ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit, org.mockito.Mockito.atLeast(0)).recordQuietly(entries.capture());
        return entries.getAllValues();
    }

    @Test
    void theFirstSignedInRequestSetsTheIdleTimeoutForTheUsersRole() {
        settings.setIdleTimeout(Duration.ofMinutes(30));
        settings.setAdminIdleTimeout(Duration.ofMinutes(15));
        SessionControls controls = controls();
        MockHttpSession user = signedInSession(user(ALICE, "USER"));
        MockHttpSession admin = signedInSession(user("CN=root,OU=Users,DC=winllc,DC=com", "USER", "ADMIN"));

        assertTrue(controls.admit(request("/app/home", user), user, user(ALICE, "USER")));
        assertTrue(controls.admit(request("/app/home", admin), admin, user("CN=root,OU=Users,DC=winllc,DC=com", "USER", "ADMIN")));

        assertEquals(30 * 60, user.getMaxInactiveInterval());
        assertEquals(15 * 60, admin.getMaxInactiveInterval());
    }

    @Test
    void theAdministratorsTimeoutDefaultsToEveryones() {
        settings.setIdleTimeout(Duration.ofMinutes(20));
        MockHttpSession admin = signedInSession(user(ALICE, "ADMIN"));

        controls().admit(request("/", admin), admin, user(ALICE, "ADMIN"));

        assertEquals(20 * 60, admin.getMaxInactiveInterval());
    }

    @Test
    void aSessionEndsAtItsAbsoluteLimitHoweverBusy() throws Exception {
        settings.setAbsoluteTimeout(Duration.ofHours(12));
        SessionControls controls = controls();
        Authentication alice = user(ALICE, "USER");
        MockHttpSession session = signedInSession(alice);
        controls.admit(request("/", session), session, alice);

        clock.advance(Duration.ofHours(11).plusMinutes(59));
        assertTrue(controls.admit(request("/", session), session, alice));
        clock.advance(Duration.ofMinutes(1));

        // Through the filter: a page goes to sign-in, the session is gone and the end is audited.
        SecurityContextHolder.getContext().setAuthentication(alice);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        new SessionControlFilter(controls).doFilter(request("/app/home", session), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals("/login?expired", response.getRedirectedUrl());
        assertTrue(session.isInvalid());
        AuditEntry entry = audited().getFirst();
        assertEquals("auth.session-expired", entry.action());
        assertEquals("absolute", entry.reason());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void anApiCallPastTheLimitGets401() throws Exception {
        settings.setAbsoluteTimeout(Duration.ofMinutes(5));
        SessionControls controls = controls();
        Authentication alice = user(ALICE, "USER");
        MockHttpSession session = signedInSession(alice);
        controls.admit(request("/", session), session, alice);
        clock.advance(Duration.ofMinutes(6));
        SecurityContextHolder.getContext().setAuthentication(alice);

        MockHttpServletResponse response = new MockHttpServletResponse();
        new SessionControlFilter(controls).doFilter(request("/api/license", session), response, mock(FilterChain.class));

        assertEquals(401, response.getStatus());
    }

    @Test
    void theWindowsClientsApiIsNotABrowserSession() throws Exception {
        SessionControls controls = controls();
        Authentication alice = user(ALICE, "USER");
        MockHttpSession session = signedInSession(alice);
        SecurityContextHolder.getContext().setAuthentication(alice);
        FilterChain chain = mock(FilterChain.class);

        new SessionControlFilter(controls).doFilter(request("/api/check/in", session), new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        assertNull(session.getAttribute(SessionControls.SIGNED_IN_AT));
        assertEquals(0, controls.sessionsOf(ALICE));
    }

    @Test
    void pastTheConcurrentLimitTheOldestSessionEnds() {
        settings.setMaxPerUser(2);
        SessionControls controls = controls();
        Authentication alice = user(ALICE, "USER");
        MockHttpSession first = signedInSession(alice);
        MockHttpSession second = signedInSession(alice);
        MockHttpSession third = signedInSession(alice);

        controls.admit(request("/", first), first, alice);
        clock.advance(Duration.ofMinutes(1));
        controls.admit(request("/", second), second, alice);
        clock.advance(Duration.ofMinutes(1));
        controls.admit(request("/", third), third, alice);

        assertTrue(first.isInvalid(), "the oldest is ended");
        assertFalse(second.isInvalid());
        assertFalse(third.isInvalid());
        assertEquals(2, controls.sessionsOf(ALICE));
        assertEquals("concurrent", audited().getFirst().reason());
    }

    @Test
    void noLimitByDefault() {
        SessionControls controls = controls();
        Authentication alice = user(ALICE, "USER");
        for (int i = 0; i < 5; i++) {
            MockHttpSession session = signedInSession(alice);
            controls.admit(request("/", session), session, alice);
        }
        assertEquals(5, controls.sessionsOf(ALICE));
        verify(audit, never()).recordQuietly(any());
    }

    @Test
    void theContainersIdleExpiryIsAuditedButASignOutIsNot() {
        settings.setIdleTimeout(Duration.ofMinutes(30));
        SessionControls controls = controls();
        Authentication alice = user(ALICE, "USER");

        MockHttpSession idle = signedInSession(alice);
        // MockHttpSession records accesses with the system clock; start this clock there.
        clock.set(Instant.ofEpochMilli(idle.getLastAccessedTime()));
        controls.admit(request("/", idle), idle, alice);
        clock.advance(Duration.ofMinutes(31));
        controls.sessionDestroyed(new HttpSessionEvent(idle));

        MockHttpSession signedOut = signedInSession(alice);
        controls.admit(request("/", signedOut), signedOut, alice);
        signedOut.access();
        // Signed out straight after a request.
        clock.set(Instant.ofEpochMilli(signedOut.getLastAccessedTime()));
        controls.sessionDestroyed(new HttpSessionEvent(signedOut));

        List<AuditEntry> entries = audited();
        assertEquals(1, entries.size());
        assertEquals("idle", entries.getFirst().reason());
        assertEquals(0, controls.sessionsOf(ALICE));
    }

    /** A clock the test moves. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        void set(Instant at) {
            now = at;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
