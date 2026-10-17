package com.winllc.innoutwork.security.x509;

import com.winllc.innoutwork.audit.SecurityAudit;
import com.winllc.innoutwork.config.EnsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

/**
 * Header mode (ENS_X509_SOURCE=header): a proxy in front of ReadyRoom terminates TLS, checks the client
 * certificate and forwards it in ENS_X509_HEADER (default X-SSL-Client-Cert, URL-encoded PEM as nginx's
 * {@code $ssl_client_escaped_cert}). The certificate is taken only when the request comes straight from one of
 * ENS_X509_TRUSTED_PROXIES and carries the shared secret in X-ENS-Proxy-Secret (compared in constant time), and
 * only once it chains to a known CA ({@link TrustMaterial}); it is then where certificate sign-in reads it, as if
 * ReadyRoom had terminated TLS itself. Anything else is audited and ignored, so the request goes on without a
 * certificate. A certificate the connection itself carries is never used in this mode.
 */
public class ProxyCertificateFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ProxyCertificateFilter.class);

    private final String header;
    private final List<IpAddressMatcher> trustedProxies;
    private final byte[] secret;
    private final TrustMaterial trust;
    private final ObjectProvider<SecurityAudit> audit;

    public ProxyCertificateFilter(EnsProperties.X509 settings, String secret, TrustMaterial trust,
                                  ObjectProvider<SecurityAudit> audit) {
        this.header = settings.getHeader() == null || settings.getHeader().isBlank()
                ? "X-SSL-Client-Cert" : settings.getHeader().strip();
        this.trustedProxies = trustedProxies(settings.getTrustedProxies());
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.trust = trust;
        this.audit = audit;
    }

    static List<IpAddressMatcher> trustedProxies(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(configured.split("[;,\\s]+")).filter(s -> !s.isBlank())
                    .map(IpAddressMatcher::new).toList();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ENS_X509_TRUSTED_PROXIES holds a value that is not an address or CIDR: "
                    + e.getMessage(), e);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Never a certificate from the connection itself in this mode: only the proxy's word counts.
        String value = request.getHeader(header);
        X509Certificate cert = value == null || value.isBlank() ? null : accept(request, value);
        X509Certificate[] forwarded = cert == null ? null : new X509Certificate[]{cert};
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public Object getAttribute(String name) {
                return CertificateSignIn.CERTIFICATE_ATTRIBUTE.equals(name) ? forwarded : super.getAttribute(name);
            }
        }, response);
    }

    private X509Certificate accept(HttpServletRequest request, String value) {
        String peer = peerAddress(request);
        if (trustedProxies.stream().noneMatch(m -> m.matches(peer))) {
            return refused(request, null, "certificate header from an untrusted address " + peer);
        }
        String presented = request.getHeader(EnsProperties.X509.PROXY_SECRET_HEADER);
        if (presented == null || !MessageDigest.isEqual(secret, presented.getBytes(StandardCharsets.UTF_8))) {
            return refused(request, null, "proxy secret missing or wrong");
        }
        X509Certificate cert;
        try {
            cert = parse(value);
        } catch (CertificateException | IllegalArgumentException e) {
            return refused(request, null, "certificate header not a certificate");
        }
        try {
            trust.validateChain(cert);
        } catch (CertificateRefusedException e) {
            return refused(request, cert, e.getMessage());
        }
        return cert;
    }

    private X509Certificate refused(HttpServletRequest request, X509Certificate cert, String reason) {
        log.warn("Ignoring the client certificate in {} from {}: {}", header, peerAddress(request), reason);
        SecurityAudit trail = audit.getIfAvailable();
        if (trail != null) {
            CertificateIdentity identity = cert == null ? null : CertificateIdentity.of(cert);
            trail.certificateRefused(request, identity == null ? null : identity.subject(),
                    identity == null ? null : identity.issuer(), identity == null ? null : identity.serial(), reason);
        }
        return null;
    }

    /**
     * The directly connected peer. With SERVER_FORWARD_HEADERS_STRATEGY=framework the request is wrapped to report
     * X-Forwarded-For as the client, which anyone can send; the proxy check needs the real connection.
     */
    static String peerAddress(HttpServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        return current.getRemoteAddr();
    }

    /** URL-encoded PEM ($ssl_client_escaped_cert, ingress-nginx's ssl-client-cert), plain PEM, or base64 DER. */
    static X509Certificate parse(String value) throws CertificateException {
        String text = value.strip();
        if (text.contains("%")) {
            text = percentDecode(text);
        }
        byte[] bytes;
        if (text.contains("-----BEGIN")) {
            // Some proxies fold the PEM's new lines into spaces; put them back around the base64.
            String body = text.replaceAll("-----(BEGIN|END)[ +]CERTIFICATE-----", "").replaceAll("\\s+", "");
            bytes = java.util.Base64.getMimeDecoder().decode(body);
        } else {
            bytes = java.util.Base64.getMimeDecoder().decode(text.replaceAll("\\s+", ""));
        }
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(bytes));
    }

    /** RFC 3986 percent-decoding; unlike URLDecoder, keeps '+' (a base64 character). */
    static String percentDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            out.write(c);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
