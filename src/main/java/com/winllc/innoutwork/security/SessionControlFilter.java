package com.winllc.innoutwork.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies {@link SessionControls} to signed-in browser requests: starts a session's clock on its first, and ends
 * one past its absolute limit, sending the browser to sign in again ({@code /login?expired}) or answering an API
 * call 401. The Windows client's API ({@code /api/check/**}) is exempt: it is not a browser session.
 */
public class SessionControlFilter extends OncePerRequestFilter {

    private final SessionControls controls;

    public SessionControlFilter(SessionControls controls) {
        this.controls = controls;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/check/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (session == null || auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken
                || controls.admit(request, session, auth)) {
            chain.doFilter(request, response);
            return;
        }
        SecurityContextHolder.clearContext();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        } else {
            response.sendRedirect(request.getContextPath() + "/login?expired");
        }
    }
}
