package com.winllc.innoutwork.audit;

import com.winllc.innoutwork.data.LdapDn;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Who and where an event comes from, read from the calling thread: the signed-in user (Spring Security) and the
 * current request. {@code source.ip} is the request's remote address, which honours the server's own proxy
 * handling ({@code server.forward-headers-strategy}) and nothing else: a client-supplied X-Forwarded-For is
 * never read here.
 */
public final class AuditRequestInfo {

    static final String REQUEST_ID = AuditRequestInfo.class.getName() + ".requestId";
    /** The authority a pull-API token signs in with; its holder is a service, not a user. */
    public static final String AUDIT_READ = "AUDIT_READ";

    private AuditRequestInfo() {
    }

    record Info(AuditEntry.Actor actor, AuditEntry.Source source, String requestId, String session) {
    }

    static Info current() {
        HttpServletRequest request = request();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuditEntry.Actor actor = actor(auth);
        if (actor == null) {
            actor = request != null ? new AuditEntry.Actor("anonymous", null, null, List.of())
                    : AuditEntry.Actor.system();
        }
        if (request == null) {
            return new Info(actor, null, null, null);
        }
        return new Info(actor, source(request), requestId(request), session(request));
    }

    /** The signed-in user as an actor, or null for nobody. */
    public static AuditEntry.Actor actor(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        if (auth instanceof ServiceAuthentication service) {
            return service.auditActor();
        }
        List<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                // Spring Security's own markers of how the user signed in (FACTOR_PASSWORD...), not roles.
                .filter(a -> !a.startsWith("FACTOR_"))
                .sorted()
                .distinct()
                .toList();
        if (roles.contains(AUDIT_READ)) {
            return AuditEntry.Actor.service(auth.getName(), auth.getName());
        }
        return new AuditEntry.Actor("user", auth.getName(), LdapDn.cnOf(auth.getName()), roles);
    }

    static HttpServletRequest request() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    public static AuditEntry.Source source(HttpServletRequest request) {
        return new AuditEntry.Source(request.getRemoteAddr(), request.getHeader("User-Agent"));
    }

    static String requestId(HttpServletRequest request) {
        try {
            Object id = request.getAttribute(REQUEST_ID);
            if (id == null) {
                id = UUID.randomUUID().toString();
                request.setAttribute(REQUEST_ID, id);
            }
            return id.toString();
        } catch (IllegalStateException e) {
            // The request has already been recycled (an event recorded after the response).
            return null;
        }
    }

    /** The first 16 hex characters of sha256(session id): correlates a session's events, never reveals it. */
    static String session(HttpServletRequest request) {
        try {
            HttpSession session = request.getSession(false);
            return session == null ? null : AuditJson.sha256Hex(session.getId()).substring(0, 16);
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
