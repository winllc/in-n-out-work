package com.winllc.innoutwork.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * {@code Authorization: Bearer ensat_...} on the pull API. A valid token signs the request in, for this request
 * only, as a service holding {@code AUDIT_READ} and nothing else. Anything else gets 401; a wrong or revoked
 * token is recorded as a failed {@code auth.login} (method {@code api-token}), collapsed so a misconfigured SIEM
 * polling every few seconds does not flood the trail.
 */
public class AuditTokenFilter extends OncePerRequestFilter {

    private final AuditTokenService tokens;
    private final SecurityAudit securityAudit;

    public AuditTokenFilter(AuditTokenService tokens, SecurityAudit securityAudit) {
        this.tokens = tokens;
        this.securityAudit = securityAudit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            unauthorized(response, "Send the audit API token as Authorization: Bearer <token>.");
            return;
        }
        String presented = header.substring(7).trim();
        Optional<com.winllc.innoutwork.audit.AuditApiToken> token = tokens.authenticate(presented);
        if (token.isEmpty()) {
            if (securityAudit != null) {
                securityAudit.apiTokenRefused(request);
            }
            unauthorized(response, "The audit API token is not valid, or has been revoked.");
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "audit-token:" + token.get().getId() + ":" + token.get().getName(), null,
                List.of(new SimpleGrantedAuthority(AuditRequestInfo.AUDIT_READ))));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":" + AuditJson.quote(message) + "}");
    }
}
