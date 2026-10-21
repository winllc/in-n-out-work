package com.winllc.innoutwork.security.x509;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.cert.X509Certificate;

/**
 * Right after a client certificate signs someone in, replaces the certificate in the authentication with a
 * {@link SignedInCertificate} (subject, issuer, serial), and, for a browser, keeps that sign-in in its session (in
 * the database, under a new session id) like the login form's, so the session's later requests use it instead of
 * signing in again on every page (a directory and revocation lookup and an auth.login event each time). The
 * certificate itself is never stored: the session reader allows no JDK certificate classes. The Windows client's
 * API is left as it was: no session, a sign-in per call.
 */
public class CertificateCredentialsFilter extends OncePerRequestFilter {

    /** The Windows client's API: a script with no session, signing in on every call as before. */
    static final String CLIENT_API = "/api/check/";

    private final HttpSessionSecurityContextRepository sessions = new HttpSessionSecurityContextRepository();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication auth = context.getAuthentication();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (auth instanceof PreAuthenticatedAuthenticationToken token
                && token.getCredentials() instanceof X509Certificate certificate && !path.startsWith(CLIENT_API)) {
            // A browser: keep the sign-in, without the certificate, in its session under a new id (no fixation).
            context.setAuthentication(withoutCertificate(token, certificate));
            if (request.getSession(false) != null) {
                request.changeSessionId();
            }
            sessions.saveContext(context, request, response);
        }
        chain.doFilter(request, response);
    }

    /** The same sign-in, holding the certificate's subject, issuer and serial instead of the certificate. */
    public static PreAuthenticatedAuthenticationToken withoutCertificate(PreAuthenticatedAuthenticationToken token,
                                                                         X509Certificate certificate) {
        SignedInCertificate kept;
        try {
            CertificateIdentity identity = CertificateIdentity.of(certificate);
            kept = new SignedInCertificate(identity.subject(), identity.issuer(), identity.serial());
        } catch (RuntimeException e) {
            kept = new SignedInCertificate(token.getName(), null, null); // a certificate missing its parts
        }
        PreAuthenticatedAuthenticationToken stored = new PreAuthenticatedAuthenticationToken(token.getPrincipal(),
                kept, token.getAuthorities());
        stored.setDetails(token.getDetails());
        return stored;
    }
}
