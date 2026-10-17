package com.winllc.innoutwork.security.x509;

import com.winllc.innoutwork.audit.SecurityAudit;
import com.winllc.innoutwork.config.EnsProperties;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.service.LdapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/**
 * Client-certificate sign-in (ENS_X509_*). Refuses to start in header mode without a proxy secret of at least 32
 * characters or without a CA to check forwarded certificates against, and warns when certificate sign-in is on
 * but revocation is not checked, which STIG requires.
 */
@Configuration
public class X509Config {

    private static final Logger log = LoggerFactory.getLogger(X509Config.class);

    static final int MIN_SECRET_LENGTH = 32;

    @Bean
    TrustMaterial x509TrustMaterial(EnsProperties properties, Environment environment) {
        return new TrustMaterial(properties.getX509().getTrustedCaFile(), TrustMaterial.serverTrustStore(environment),
                Clock.systemUTC());
    }

    @Bean
    RevocationChecker x509RevocationChecker(EnsProperties properties) {
        return new RevocationChecker(properties.getX509(), Clock.systemUTC());
    }

    @Bean
    X509SignIn x509SignIn(EnsProperties properties, Environment environment, AppUserDetailsService users,
                          LdapService ldap, RevocationChecker revocation, TrustMaterial trust,
                          ObjectProvider<SecurityAudit> audit) {
        EnsProperties.X509 settings = properties.getX509();
        CertificateSignIn signIn = new CertificateSignIn(users, ldap, revocation, trust, settings, audit);
        ProxyCertificateFilter proxy = null;
        boolean header = settings.getSource() == EnsProperties.X509.Source.HEADER;
        if (header) {
            String secret = proxySecret(settings);
            if (trust.isEmpty()) {
                throw new IllegalStateException("ENS_X509_SOURCE is header but there is no CA to check forwarded"
                        + " certificates against: set ENS_X509_TRUSTED_CA_FILE to a PEM file of the CAs that issue"
                        + " your users' certificates (or configure server.ssl.trust-store)");
            }
            proxy = new ProxyCertificateFilter(settings, secret, trust, audit);
            log.info("Client certificates are read from the {} header sent by {} with the proxy secret",
                    settings.getHeader(), settings.getTrustedProxies());
        }
        boolean servlet = !header && environment.getProperty("server.ssl.enabled", Boolean.class, false)
                && !"none".equalsIgnoreCase(environment.getProperty("server.ssl.client-auth", "none"));
        if (header || servlet) {
            log.info("Client-certificate sign-in: {}", String.join(", ", signIn.describe()));
            if (!revocation.enabled()) {
                log.warn("Client-certificate sign-in is on but ENS_X509_REVOCATION is off: revoked certificates still"
                        + " sign in. STIG requires revocation checking; set ENS_X509_REVOCATION to ocsp, crl or"
                        + " ocsp-then-crl.");
            }
        }
        return new X509SignIn(signIn, proxy);
    }

    /** ENS_X509_PROXY_SECRET_FILE, or else ENS_X509_PROXY_SECRET; at least 32 characters. */
    static String proxySecret(EnsProperties.X509 settings) {
        String secret = settings.getProxySecret();
        String file = settings.getProxySecretFile();
        if (file != null && !file.isBlank()) {
            try {
                secret = Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new IllegalStateException("ENS_X509_PROXY_SECRET_FILE " + file + " cannot be read: " + e.getMessage(), e);
            }
        }
        if (secret == null || secret.strip().length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("ENS_X509_SOURCE is header but the proxy secret is missing or shorter than "
                    + MIN_SECRET_LENGTH + " characters: set ENS_X509_PROXY_SECRET_FILE (or ENS_X509_PROXY_SECRET) to"
                    + " the value your proxy sends in " + EnsProperties.X509.PROXY_SECRET_HEADER);
        }
        return secret.strip();
    }
}
