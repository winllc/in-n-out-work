package com.winllc.innoutwork.security.x509;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The CA certificates ReadyRoom knows: ENS_X509_TRUSTED_CA_FILE (PEM, re-read when it changes) and the TLS trust
 * store (server.ssl.trust-store). They find a certificate's issuer for revocation checking, and in header mode
 * they are what a forwarded certificate must chain to, since no TLS handshake with ReadyRoom checked it.
 */
public class TrustMaterial {

    private static final Logger log = LoggerFactory.getLogger(TrustMaterial.class);

    private final Path caFile;
    private final List<X509Certificate> trustStore;
    private final Clock clock;

    private volatile long caFileModified = -1;
    private volatile List<X509Certificate> caFileCerts = List.of();

    public TrustMaterial(String caFile, List<X509Certificate> trustStore, Clock clock) {
        this.caFile = caFile == null || caFile.isBlank() ? null : Path.of(caFile);
        this.trustStore = List.copyOf(trustStore);
        this.clock = clock;
        if (this.caFile != null && caFileCertificates().isEmpty()) {
            throw new IllegalStateException("ENS_X509_TRUSTED_CA_FILE " + caFile + " holds no CA certificate");
        }
    }

    /** The trust store application.yml configures for TLS (server.ssl.trust-store), when there is one. */
    public static List<X509Certificate> serverTrustStore(Environment environment) {
        String location = environment.getProperty("server.ssl.trust-store");
        if (!StringUtils.hasText(location)) {
            return List.of();
        }
        String type = environment.getProperty("server.ssl.trust-store-type", "PKCS12");
        String password = environment.getProperty("server.ssl.trust-store-password", "");
        try (InputStream in = ResourceUtils.getURL(location).openStream()) {
            KeyStore store = KeyStore.getInstance(type);
            store.load(in, password.toCharArray());
            List<X509Certificate> certs = new ArrayList<>();
            for (String alias : Collections.list(store.aliases())) {
                if (store.getCertificate(alias) instanceof X509Certificate cert) {
                    certs.add(cert);
                }
            }
            return certs;
        } catch (IOException | GeneralSecurityException e) {
            // SslStoresCheck and Tomcat report a bad trust store themselves; here it only means fewer issuers.
            log.warn("Could not read the trust store {} for client-certificate checks: {}", location, e.getMessage());
            return List.of();
        }
    }

    public boolean hasCaFile() {
        return caFile != null;
    }

    public boolean isEmpty() {
        return caFileCertificates().isEmpty() && trustStore.isEmpty();
    }

    /** Every known CA certificate. */
    List<X509Certificate> all() {
        Set<X509Certificate> all = new LinkedHashSet<>(caFileCertificates());
        all.addAll(trustStore);
        return List.copyOf(all);
    }

    /** {@code leaf}'s issuer: among the chain the client presented, then the known CAs; null when none signed it. */
    public X509Certificate issuerOf(X509Certificate leaf, X509Certificate[] presented) {
        List<X509Certificate> candidates = new ArrayList<>();
        if (presented != null) {
            for (int i = 1; i < presented.length; i++) {
                candidates.add(presented[i]);
            }
        }
        candidates.addAll(all());
        for (X509Certificate candidate : candidates) {
            if (candidate.getSubjectX500Principal().equals(leaf.getIssuerX500Principal())) {
                try {
                    leaf.verify(candidate.getPublicKey());
                    return candidate;
                } catch (GeneralSecurityException e) {
                    // Same name, different key (a renewed CA); keep looking.
                }
            }
        }
        return null;
    }

    /**
     * Checks a forwarded certificate chains to a known CA and is within its validity: what the TLS handshake would
     * have checked had ReadyRoom terminated TLS. Every known CA certificate is an anchor, so a leaf whose direct
     * issuer is known validates. Revocation is checked separately.
     *
     * @throws CertificateRefusedException when it does not
     */
    void validateChain(X509Certificate leaf) {
        List<X509Certificate> anchors = all();
        if (anchors.isEmpty()) {
            throw new CertificateRefusedException("no trusted CA");
        }
        try {
            Set<TrustAnchor> trust = new LinkedHashSet<>();
            anchors.forEach(ca -> trust.add(new TrustAnchor(ca, null)));
            PKIXParameters params = new PKIXParameters(trust);
            params.setRevocationEnabled(false);
            params.setDate(Date.from(clock.instant()));
            CertPathValidator.getInstance("PKIX")
                    .validate(CertificateFactory.getInstance("X.509").generateCertPath(List.of(leaf)), params);
        } catch (CertPathValidatorException e) {
            throw new CertificateRefusedException(switch (e.getReason() instanceof CertPathValidatorException.BasicReason
                    reason ? reason : CertPathValidatorException.BasicReason.UNSPECIFIED) {
                case EXPIRED -> "certificate expired";
                case NOT_YET_VALID -> "certificate not yet valid";
                default -> "certificate not issued by a trusted CA";
            });
        } catch (GeneralSecurityException e) {
            throw new CertificateRefusedException("certificate not issued by a trusted CA");
        }
    }

    private List<X509Certificate> caFileCertificates() {
        if (caFile == null) {
            return List.of();
        }
        try {
            long modified = Files.getLastModifiedTime(caFile).toMillis();
            if (modified != caFileModified) {
                synchronized (this) {
                    if (modified != caFileModified) {
                        List<X509Certificate> certs = new ArrayList<>();
                        try (InputStream in = Files.newInputStream(caFile)) {
                            for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                                if (cert instanceof X509Certificate x509) {
                                    certs.add(x509);
                                }
                            }
                        }
                        caFileCerts = List.copyOf(certs);
                        caFileModified = modified;
                        log.info("Read {} CA certificate(s) from {}", certs.size(), caFile);
                    }
                }
            }
        } catch (IOException | GeneralSecurityException e) {
            log.warn("Cannot read ENS_X509_TRUSTED_CA_FILE {}: {}", caFile, e.getMessage());
        }
        return caFileCerts;
    }
}
