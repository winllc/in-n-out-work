package com.winllc.innoutwork.api;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditJson;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.audit.AuditRequestInfo;
import com.winllc.innoutwork.audit.SecurityAudit;
import com.winllc.innoutwork.model.ApiToken;
import com.winllc.innoutwork.security.ClientRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import com.winllc.innoutwork.security.DirectoryAccounts;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * {@code Authorization: Bearer ensapi_...} on /api/v1. A usable token signs the request in, for this request only,
 * as an {@link ApiTokenAuthentication}: the owner's roles as they are now, plus the token's scopes. Refused, each a
 * failed {@code auth.login} (method {@code public-api-token}) in the audit trail, collapsed per source and reason:
 * <ul>
 *   <li>a malformed, unknown, revoked or expired token: 401;</li>
 *   <li>a token whose owner is no longer in the directory, is disabled, locked out or expired there, or is no
 *       longer an administrator, read live (DirectoryAccounts; a directory that cannot be read refuses): 401.</li>
 * </ul>
 * Past the token's rate limit: 429 with {@code Retry-After}, the first of each minute audited. A request with no
 * Authorization header goes on unauthenticated, so the OpenAPI document stays readable and everything else gets
 * the chain's 401. The token is never logged, stored in a session or echoed back.
 */
public class ApiTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenFilter.class);
    static final String METHOD = "public-api-token";

    private final ApiTokenService tokens;
    private final DirectoryAccounts accounts;
    private final SecurityAudit securityAudit;
    private final AuditLog audit;
    private final ClientRateLimiter limiter;

    public ApiTokenFilter(ApiTokenService tokens, DirectoryAccounts accounts, SecurityAudit securityAudit,
                          AuditLog audit, ClientRateLimiter limiter) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.securityAudit = securityAudit;
        this.audit = audit == null ? AuditLog.NOOP : audit;
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            refuse(request, response, "not a bearer token",
                    "Send the API token as Authorization: Bearer <token>.");
            return;
        }
        ApiTokenService.Presented presented = tokens.authenticate(header.substring(7).trim());
        if (!presented.accepted()) {
            refuse(request, response, presented.refusal().reason(),
                    "The API token is not valid, has expired or has been revoked.");
            return;
        }
        ApiToken token = presented.token();

        // The owner as the directory has them now (not from the user cache): still there, not disabled, locked or
        // expired, and still an administrator. A directory that cannot be read refuses.
        DirectoryAccounts.Account owner = accounts.check(token.getOwnerDn());
        if (!owner.active()) {
            refuse(request, response, "token owner " + owner.status().reason(),
                    "The administrator this token acts for can no longer use it.");
            return;
        }
        if (!owner.isAdmin()) {
            refuse(request, response, "token owner no longer an administrator",
                    "The administrator this token acts for can no longer use it.");
            return;
        }

        ClientRateLimiter.Outcome outcome = limiter.count("api-token:" + token.getId(), token.getRateLimitPerMinute());
        if (outcome != ClientRateLimiter.Outcome.WITHIN) {
            if (outcome == ClientRateLimiter.Outcome.FIRST_OVER) {
                audit.recordQuietly(AuditEntry.of(AuditEntry.Category.AUTHORIZATION, "access.denied")
                        .denied("rate_limited")
                        .actor(AuditEntry.Actor.service("api-token:" + token.getId(), token.getName()))
                        .source(AuditRequestInfo.source(request))
                        .detail("path", request.getRequestURI())
                        .detail("rateLimitPerMinute", token.getRateLimitPerMinute()));
                log.warn("API token {} ({}) is over its limit of {} requests a minute", token.getId(),
                        token.getTokenPrefix(), token.getRateLimitPerMinute());
            }
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(ClientRateLimiter.WINDOW.toSeconds()));
            write(response, "rate_limited", "Over this token's limit of " + token.getRateLimitPerMinute()
                    + " requests a minute.");
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ApiTokenAuthentication(token.getId(), token.getName(), token.getOwnerDn(),
                token.getScopeSet(), owner.details().getAuthorities()));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, String reason, String message)
            throws IOException {
        if (securityAudit != null) {
            securityAudit.apiTokenRefused(request, METHOD, reason);
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
        write(response, "unauthorized", message);
    }

    static void write(HttpServletResponse response, String error, String message) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":" + AuditJson.quote(error) + ",\"message\":" + AuditJson.quote(message) + "}");
    }
}
