package com.winllc.innoutwork.security;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.audit.AuditRequestInfo;
import com.winllc.innoutwork.config.EnsProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.LdapDn;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How long a signed-in browser session lasts (ENS_SESSION_*): an idle timeout, a shorter one for administrators
 * if configured, an absolute limit from sign-in, and an optional limit on concurrent sessions per user, past
 * which the oldest is ended. Each end is audited as {@code auth.session-expired} with reason {@code idle},
 * {@code absolute} or {@code concurrent}.
 * <p>
 * Applied by {@link SessionControlFilter} on the first request a session makes signed in, whatever the sign-in
 * was (form, certificate). Idle expiry is the servlet container's own, set per session here; it is noticed
 * when the container destroys the session (this is its listener). Sessions live in this one instance's memory,
 * which is all there is with one replica.
 */
public class SessionControls implements HttpSessionListener {

    private static final Logger log = LoggerFactory.getLogger(SessionControls.class);

    /** When this session signed in; set on its first signed-in request. */
    static final String SIGNED_IN_AT = SessionControls.class.getName() + ".signedInAt";
    /** Why this application ended the session itself, so its destruction is not taken for idle expiry. */
    static final String ENDED = SessionControls.class.getName() + ".ended";

    private final EnsProperties.Session settings;
    private final ObjectProvider<AuditLog> audit;
    private final Clock clock;
    /** A user's signed-in sessions, by lower-cased name. */
    private final Map<String, List<HttpSession>> byUser = new ConcurrentHashMap<>();

    public SessionControls(EnsProperties.Session settings, ObjectProvider<AuditLog> audit, Clock clock) {
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
        log.info("Sessions end after {} idle ({} for administrators), {} after sign-in; {} per user",
                settings.getIdleTimeout(), settings.effectiveAdminIdleTimeout(), settings.getAbsoluteTimeout(),
                settings.getMaxPerUser() == 0 ? "no limit" : settings.getMaxPerUser());
    }

    public EnsProperties.Session settings() {
        return settings;
    }

    /**
     * Called for each signed-in request with a session. Starts the session's clock on its first, and says whether
     * it is past its absolute limit (then it has been ended and audited, and the caller answers as for no session).
     */
    boolean admit(HttpServletRequest request, HttpSession session, Authentication auth) {
        Instant now = clock.instant();
        Object signedInAt = session.getAttribute(SIGNED_IN_AT);
        if (signedInAt == null) {
            session.setAttribute(SIGNED_IN_AT, now);
            boolean admin = auth.getAuthorities().stream()
                    .anyMatch(a -> UserRoleEnum.ADMIN.name().equals(a.getAuthority()));
            Duration idle = admin ? settings.effectiveAdminIdleTimeout() : settings.getIdleTimeout();
            session.setMaxInactiveInterval((int) Math.min(Integer.MAX_VALUE, idle.toSeconds()));
            register(auth, session);
            return true;
        }
        if (signedInAt instanceof Instant start && !now.isBefore(start.plus(settings.getAbsoluteTimeout()))) {
            end(session, auth, "absolute", request);
            return false;
        }
        return true;
    }

    /** Remembers the session under its user, ending that user's oldest sessions past the limit. */
    private void register(Authentication auth, HttpSession session) {
        String user = auth.getName().toLowerCase(Locale.ROOT);
        List<HttpSession> over = new ArrayList<>();
        byUser.compute(user, (key, sessions) -> {
            List<HttpSession> list = sessions == null ? new ArrayList<>() : new ArrayList<>(sessions);
            list.removeIf(s -> s.getId().equals(session.getId()));
            list.add(session);
            int max = settings.getMaxPerUser();
            if (max > 0 && list.size() > max) {
                list.sort(Comparator.comparing(SessionControls::signedInAt));
                over.addAll(list.subList(0, list.size() - max));
                list.removeAll(over);
            }
            return list;
        });
        for (HttpSession oldest : over) {
            end(oldest, auth, "concurrent", null);
        }
    }

    private static Instant signedInAt(HttpSession session) {
        try {
            return session.getAttribute(SIGNED_IN_AT) instanceof Instant at ? at : Instant.EPOCH;
        } catch (IllegalStateException alreadyInvalid) {
            return Instant.EPOCH;
        }
    }

    /** Ends a session this application decided to end, and audits it. */
    private void end(HttpSession session, Authentication auth, String reason, HttpServletRequest request) {
        try {
            session.setAttribute(ENDED, reason);
            session.invalidate();
        } catch (IllegalStateException alreadyInvalid) {
            return;
        }
        log.info("Ended a session of {}: {}", auth.getName(), reason);
        expired(auth, reason, request != null);
    }

    private void expired(Authentication auth, String reason, boolean inRequest) {
        AuditLog trail = audit.getIfAvailable();
        if (trail == null) {
            return;
        }
        AuditEntry entry = AuditEntry.of(AuditEntry.Category.AUTHENTICATION, "auth.session-expired")
                .actor(AuditRequestInfo.actor(auth))
                .target("user", auth.getName(), LdapDn.cnOf(auth.getName()))
                .reason(reason)
                .detail("reason", reason);
        if (!inRequest) {
            entry.source(null);
        }
        trail.recordQuietly(entry);
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        HttpSession session = event.getSession();
        Authentication auth;
        try {
            auth = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                    instanceof SecurityContext context ? context.getAuthentication() : null;
            if (auth != null) {
                byUser.computeIfPresent(auth.getName().toLowerCase(Locale.ROOT), (key, sessions) -> {
                    List<HttpSession> rest = new ArrayList<>(sessions);
                    rest.removeIf(s -> s.getId().equals(session.getId()));
                    return rest.isEmpty() ? null : rest;
                });
            }
            if (auth == null || session.getAttribute(SIGNED_IN_AT) == null || session.getAttribute(ENDED) != null) {
                return; // never signed in, or ended deliberately (and audited then)
            }
            // A sign-out invalidates the session just after a request; the container's expiry, only once it has
            // been idle for its whole interval.
            long idleMillis = clock.millis() - session.getLastAccessedTime();
            if (session.getMaxInactiveInterval() > 0 && idleMillis >= session.getMaxInactiveInterval() * 1000L - 1000) {
                expired(auth, "idle", false);
            }
        } catch (IllegalStateException alreadyInvalid) {
            log.debug("Session {} was already invalid when destroyed", session.getId());
        }
    }

    /** Signed-in sessions this instance knows of for {@code user}; for tests. */
    int sessionsOf(String user) {
        List<HttpSession> sessions = byUser.get(user.toLowerCase(Locale.ROOT));
        return sessions == null ? 0 : sessions.size();
    }
}
