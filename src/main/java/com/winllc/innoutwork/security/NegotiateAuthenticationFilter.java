package com.winllc.innoutwork.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.kerberos.authentication.KerberosServiceRequestToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Base64;

/**
 * Signs a request in from a Windows ticket in {@code Authorization: Negotiate <token>}.
 * <p>
 * Does what Spring Security's SpnegoAuthenticationProcessingFilter does, minus two things that matter here:
 * that filter writes the whole ticket into the log (at WARN when it is rejected, at DEBUG on every request), and
 * a malformed header makes it throw. Here the log gets the reason only, and a bad header is a plain 401.
 * <p>
 * A request already signed in (by the certificate filter, which runs first) is passed straight through. The
 * sign-in lasts for the request only; nothing is stored in the session.
 */
public class NegotiateAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(NegotiateAuthenticationFilter.class);

    /** "NTLMSSP" in Base64: Windows sends NTLM under the Negotiate scheme when it has no Kerberos ticket. */
    public static final String NTLM_PREFIX = "TlRMTVNTUA";

    private final AuthenticationManager authenticationManager;
    private final AuthenticationEntryPoint entryPoint;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final WebAuthenticationDetailsSource detailsSource = new WebAuthenticationDetailsSource();

    public NegotiateAuthenticationFilter(AuthenticationManager authenticationManager, AuthenticationEntryPoint entryPoint) {
        this.authenticationManager = authenticationManager;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication existing = contextHolder.getContext().getAuthentication();
        if (existing != null && existing.isAuthenticated() && !(existing instanceof AnonymousAuthenticationToken)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !(header.startsWith("Negotiate ") || header.startsWith("Kerberos "))) {
            chain.doFilter(request, response);
            return;
        }
        String encoded = header.substring(header.indexOf(' ') + 1).trim();
        if (encoded.startsWith(NTLM_PREFIX)) {
            // Not signed in; the entry point answers 401 and logs why NTLM happens.
            chain.doFilter(request, response);
            return;
        }

        byte[] ticket;
        try {
            ticket = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            log.warn("Malformed Negotiate header on {} {}", request.getMethod(), request.getRequestURI());
            refuse(request, response, null);
            return;
        }

        KerberosServiceRequestToken attempt = new KerberosServiceRequestToken(ticket);
        attempt.setDetails(detailsSource.buildDetails(request));
        Authentication authenticated;
        try {
            authenticated = authenticationManager.authenticate(attempt);
        } catch (AuthenticationException e) {
            log.warn("Windows sign-in failed on {} {} from {}: {}", request.getMethod(), request.getRequestURI(),
                    request.getRemoteAddr(), reason(e));
            refuse(request, response, e);
            return;
        }

        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        contextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException, ServletException {
        contextHolder.clearContext();
        entryPoint.commence(request, response, e);
    }

    /** The innermost message: "Checksum failed", "Clock skew too great" and the like say what went wrong. */
    private static String reason(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == e ? e.getMessage() : e.getMessage() + " (" + root.getMessage() + ")";
    }
}
