package com.winllc.innoutwork.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The browser security headers, on every response, static files and error pages included.
 * <p>
 * The Content-Security-Policy allows scripts from ReadyRoom itself and inline scripts carrying this request's
 * nonce only, so an injected {@code <script>} or {@code onclick=} does not run. Templates give their inline
 * scripts the nonce with {@code th:attr="nonce=${cspNonce}"}; inline event handlers are not allowed at all and
 * are bound from script instead. Strict-Transport-Security goes only on secure requests: TLS to ReadyRoom
 * itself, or behind a proxy that terminates it when {@code server.forward-headers-strategy} lets ReadyRoom see
 * that.
 * <p>
 * Ahead of Spring Security, whose own header writers then leave these alone (they add a header only when it is
 * not already there) and still add Cache-Control to signed-in pages.
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /** The request attribute holding this request's nonce; templates read it as {@code ${cspNonce}}. */
    public static final String NONCE_ATTRIBUTE = "cspNonce";

    static final String POLICY = "default-src 'self'; script-src 'self' 'nonce-%s'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; "
            + "base-uri 'self'; form-action 'self'; object-src 'none'";

    static final String HSTS = "max-age=31536000; includeSubDomains";
    static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=()";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final boolean reportOnly;

    public SecurityHeadersFilter(boolean reportOnly) {
        this.reportOnly = reportOnly;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String nonce = nonce(request);
        response.setHeader(reportOnly ? "Content-Security-Policy-Report-Only" : "Content-Security-Policy",
                POLICY.formatted(nonce));
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", HSTS);
        }
        chain.doFilter(request, response);
    }

    /** One nonce per request, kept for the error page an error dispatch renders into the same response. */
    private static String nonce(HttpServletRequest request) {
        if (request.getAttribute(NONCE_ATTRIBUTE) instanceof String existing) {
            return existing;
        }
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        String nonce = Base64.getEncoder().encodeToString(bytes);
        request.setAttribute(NONCE_ATTRIBUTE, nonce);
        return nonce;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
