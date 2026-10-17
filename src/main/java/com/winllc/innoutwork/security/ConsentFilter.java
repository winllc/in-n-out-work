package com.winllc.innoutwork.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Enforces the notice-and-consent banner on the server: while it is enabled, nothing signs in - the login form,
 * a client certificate, anything - until this browser session has acknowledged it on {@code /consent}.
 * <p>
 * In the browser chains, ahead of every sign-in filter. Before acknowledgement a request still goes on (the
 * banner itself, the error page) but with its client certificate hidden, so certificate sign-in cannot happen;
 * anything else is sent to the banner, or answered 401 when it is an API call. Machine endpoints are not
 * browser sessions and are exempt: the Windows client's API ({@code /api/check/**}), the audit pull API (its
 * own chain, bearer tokens) and health.
 */
public class ConsentFilter extends OncePerRequestFilter {

    public static final String CONSENT_URL = "/consent";

    /** Where to go once acknowledged: the page asked for, when it was a plain GET. */
    static final String TARGET = ConsentFilter.class.getName() + ".target";

    /** Reachable before acknowledgement, without signing in. */
    private static final List<String> OPEN = List.of(CONSENT_URL, "/error", "/favicon.ico");
    /** Not browser sessions: exempt altogether. */
    private static final List<String> MACHINE = List.of("/api/check/", "/api/audit/", "/actuator/health",
            "/actuator/prometheus");

    static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private final ConsentBanner banner;

    public ConsentFilter(ConsentBanner banner) {
        this.banner = banner;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!banner.isEnabled()) {
            return true;
        }
        String path = path(request);
        return MACHINE.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (acknowledged(request)) {
            chain.doFilter(request, response);
            return;
        }
        String path = path(request);
        if (OPEN.contains(path)) {
            chain.doFilter(withoutCertificate(request), response);
            return;
        }
        if (path.startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"consent_required\",\"message\":"
                    + "\"Acknowledge the notice and consent banner at " + CONSENT_URL + " first.\"}");
            return;
        }
        if ("GET".equals(request.getMethod()) && !"/login".equals(path)) {
            String query = request.getQueryString();
            request.getSession().setAttribute(TARGET, path + (query == null ? "" : "?" + query));
        }
        response.sendRedirect(request.getContextPath() + CONSENT_URL);
    }

    static boolean acknowledged(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(ConsentBanner.ACKNOWLEDGED) != null;
    }

    /** Where the browser asked to go before the banner, or the home page. Only ever a path on this server. */
    public static String target(HttpSession session) {
        Object target = session.getAttribute(TARGET);
        session.removeAttribute(TARGET);
        return target instanceof String path && path.startsWith("/") && !path.startsWith("//") ? path : "/";
    }

    /** The request as the sign-in filters will see it: with no client certificate. */
    static HttpServletRequest withoutCertificate(HttpServletRequest request) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public Object getAttribute(String name) {
                return CERTIFICATE_ATTRIBUTE.equals(name) ? null : super.getAttribute(name);
            }
        };
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
