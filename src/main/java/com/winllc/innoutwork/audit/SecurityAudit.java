package com.winllc.innoutwork.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.kerberos.authentication.KerberosServiceRequestToken;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Sign-in, sign-out and the security chains' own refusals.
 *
 * <ul>
 *   <li>{@code auth.login}: every interactive sign-in, success or failure: the login form (method {@code ldap})
 *       and a browser's client certificate ({@code x509}). The Windows client's check-in, lock and unlock calls
 *       sign in on every call; those are attendance data, so their successes are not audited, only their
 *       failures (a refused Windows ticket, or no certificate at all), collapsed per source and reason.</li>
 *   <li>{@code auth.logout}.</li>
 *   <li>{@code access.denied} for a 403 from the filter chain (see {@link AccessDenials}).</li>
 * </ul>
 * A successful sign-in that cannot be audited fails: the listener throws an {@link AuthenticationException},
 * which the sign-in filters treat as a failed sign-in.
 */
@Component
public class SecurityAudit {

    private static final Logger log = LoggerFactory.getLogger(SecurityAudit.class);

    /** The Windows client's API: one sign-in per logon, lock, unlock or logoff. */
    static final String CLIENT_API = "/api/check/";

    private final AuditLog audit;
    private final AccessDenials denials;

    public SecurityAudit(AuditLog audit, AccessDenials denials) {
        this.audit = audit;
        this.denials = denials;
    }

    @EventListener
    public void signedIn(AuthenticationSuccessEvent event) {
        HttpServletRequest request = AuditRequestInfo.request();
        if (request == null || isClientApi(request) || isPullApi(request)) {
            return;
        }
        Authentication auth = event.getAuthentication();
        try {
            audit.record(AuditEntry.of(AuditEntry.Category.AUTHENTICATION, "auth.login")
                    .actor(AuditRequestInfo.actor(auth))
                    .target("user", auth.getName(), com.winllc.innoutwork.data.LdapDn.cnOf(auth.getName()))
                    .detail("method", method(auth)));
        } catch (AuditUnavailableException e) {
            throw new AuthenticationServiceException("Sign-in is unavailable: the audit log cannot be written.", e);
        }
    }

    @EventListener
    public void signInFailed(AbstractAuthenticationFailureEvent event) {
        HttpServletRequest request = AuditRequestInfo.request();
        Authentication attempt = event.getAuthentication();
        String username = attempt != null ? attempt.getName() : null;
        if (attempt instanceof KerberosServiceRequestToken) {
            username = null; // the ticket, not a name; never recorded
        }
        AuditEntry entry = AuditEntry.of(AuditEntry.Category.AUTHENTICATION, "auth.login")
                .failure(reason(event.getException()))
                .actor(AuditEntry.Actor.anonymous(username))
                .detail("method", method(attempt));
        if (request != null) {
            entry.source(AuditRequestInfo.source(request));
            entry.detail("path", request.getRequestURI());
        }
        if (request != null && isClientApi(request)) {
            // Every workstation retries on its next event; one event per source and reason a minute is enough.
            denials.collapse("auth.login|" + request.getRemoteAddr() + "|" + username + "|" + entry.reason(), entry);
        } else {
            audit.recordQuietly(entry);
        }
    }

    @EventListener
    public void signedOut(LogoutSuccessEvent event) {
        Authentication auth = event.getAuthentication();
        audit.recordQuietly(AuditEntry.of(AuditEntry.Category.AUTHENTICATION, "auth.logout")
                .actor(AuditRequestInfo.actor(auth))
                .target("user", auth.getName(), com.winllc.innoutwork.data.LdapDn.cnOf(auth.getName())));
    }

    /** The chain's 403s: records access.denied, then answers as {@code delegate} does. */
    public AccessDeniedHandler accessDeniedHandler(String reason, AccessDeniedHandler delegate) {
        return (request, response, denied) -> {
            denials.denied(request, reason, denied.getMessage(), null);
            delegate.handle(request, response, denied);
        };
    }

    /** The Windows client's API refused for want of any sign-in (no certificate): a failure, collapsed. */
    void clientApiWithoutCredentials(HttpServletRequest request) {
        AuditEntry entry = AuditEntry.of(AuditEntry.Category.AUTHENTICATION, "auth.login")
                .failure("no credentials")
                .actor(AuditEntry.Actor.anonymous(null))
                .source(AuditRequestInfo.source(request))
                .detail("method", "x509")
                .detail("path", request.getRequestURI());
        denials.collapse("auth.login|" + request.getRemoteAddr() + "|no credentials", entry);
    }

    /** A pull-API request with a wrong or revoked token: a failed sign-in, collapsed per source. */
    void apiTokenRefused(HttpServletRequest request) {
        AuditEntry entry = AuditEntry.of(AuditEntry.Category.AUTHENTICATION, "auth.login")
                .failure("invalid or revoked token")
                .actor(AuditEntry.Actor.anonymous(null))
                .source(AuditRequestInfo.source(request))
                .detail("method", "api-token")
                .detail("path", request.getRequestURI());
        denials.collapse("auth.login|" + request.getRemoteAddr() + "|api-token", entry);
    }

    /** The logoff call identifies the user by the session id cached at logon; an unknown one is a failure. */
    public void unknownClientSession(HttpServletRequest request) {
        AuditEntry entry = AuditEntry.of(AuditEntry.Category.AUTHENTICATION, "auth.login")
                .failure("unknown session")
                .actor(AuditEntry.Actor.anonymous(null))
                .source(AuditRequestInfo.source(request))
                .detail("method", "session-id")
                .detail("path", request.getRequestURI());
        denials.collapse("auth.login|" + request.getRemoteAddr() + "|unknown session", entry);
    }

    /**
     * A source past the limit on refused logoff session ids. Recorded once per source and minute, when it goes over;
     * its refusals after that are not recorded one by one.
     */
    public void refusedClientSessionsLimited(HttpServletRequest request, int perMinute) {
        AuditEntry entry = AuditEntry.of(AuditEntry.Category.AUTHENTICATION, "auth.login")
                .failure("too many unknown sessions")
                .actor(AuditEntry.Actor.anonymous(null))
                .source(AuditRequestInfo.source(request))
                .detail("method", "session-id")
                .detail("path", request.getRequestURI())
                .detail("perMinute", perMinute);
        denials.collapse("auth.login|" + request.getRemoteAddr() + "|too many unknown sessions", entry);
    }

    static String method(Authentication auth) {
        if (auth instanceof UsernamePasswordAuthenticationToken) {
            return "ldap";
        }
        if (auth instanceof PreAuthenticatedAuthenticationToken) {
            return "x509";
        }
        if (auth instanceof KerberosServiceRequestToken) {
            return "sso";
        }
        return auth == null ? null : auth.getClass().getSimpleName();
    }

    static String reason(AuthenticationException e) {
        if (e instanceof BadCredentialsException) {
            return "bad credentials";
        }
        if (e instanceof UsernameNotFoundException) {
            return "unknown user";
        }
        if (e instanceof LockedException) {
            return "account locked";
        }
        if (e instanceof DisabledException) {
            return "account disabled";
        }
        log.debug("Sign-in failed: {}", e.toString());
        return e.getClass().getSimpleName().replaceAll("Exception$", "") + ": " + e.getMessage();
    }

    private static boolean isClientApi(HttpServletRequest request) {
        return path(request).startsWith(CLIENT_API);
    }

    private static boolean isPullApi(HttpServletRequest request) {
        return path(request).startsWith("/api/audit/");
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
