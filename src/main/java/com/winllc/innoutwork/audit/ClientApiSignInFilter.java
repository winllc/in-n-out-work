package com.winllc.innoutwork.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Notices the Windows client's check-in, lock and unlock calls that the security chain refused for want of any
 * sign-in: it sends them to the login page (302), which a script cannot use. Each is an {@code auth.login}
 * failure, collapsed per source. Runs around the security chain and changes nothing about the response.
 *
 * <p>A 401 is not counted: with Windows sign-in on it is the normal first step (the client then sends its
 * ticket), and a refused ticket is recorded from the authentication events instead.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class ClientApiSignInFilter extends OncePerRequestFilter {

    private final SecurityAudit securityAudit;

    public ClientApiSignInFilter(SecurityAudit securityAudit) {
        this.securityAudit = securityAudit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod()) || !path.startsWith(SecurityAudit.CLIENT_API)
                || path.equals(SecurityAudit.CLIENT_API + "out");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
        String location = response.getHeader("Location");
        if (response.getStatus() == HttpServletResponse.SC_FOUND && location != null && location.contains("/login")) {
            securityAudit.clientApiWithoutCredentials(request);
        }
    }
}
