package com.winllc.innoutwork.security.x509;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CRL;
import java.security.cert.CRLException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Certificate revocation lists for a certificate's issuer: from ENS_X509_CRL_DIR when set (the air-gapped way;
 * the files are re-read when the directory changes, and nothing is fetched), otherwise from the certificate's
 * own http(s) distribution points, each kept until the list's next update.
 */
class CrlStore {

    private static final Logger log = LoggerFactory.getLogger(CrlStore.class);

    static final long MAX_CRL_BYTES = 20L * 1024 * 1024;
    /** A fetched list without a next update, or one far off, is fetched again after this. */
    static final Duration MAX_KEEP = Duration.ofHours(24);

    private final Path dir;
    private final BoundedHttp http;
    private final Clock clock;

    private volatile String dirSignature = "";
    private volatile List<X509CRL> dirCrls = List.of();
    private final Map<String, Fetched> fetched = new ConcurrentHashMap<>();

    private record Fetched(X509CRL crl, Instant until) {
    }

    CrlStore(String dir, BoundedHttp http, Clock clock) {
        this.dir = dir == null || dir.isBlank() ? null : Path.of(dir);
        this.http = http;
        this.clock = clock;
    }

    boolean fromDirectory() {
        return dir != null;
    }

    /** The lists that may cover {@code leaf}, issued by {@code issuer}'s subject. */
    List<CRL> crlsFor(X509Certificate leaf, X509Certificate issuer) {
        List<CRL> crls = new ArrayList<>();
        if (dir != null) {
            for (X509CRL crl : directory()) {
                if (crl.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
                    crls.add(crl);
                }
            }
            return crls;
        }
        for (String url : CertificateExtensions.crlDistributionPoints(leaf)) {
            X509CRL crl = fetch(url);
            if (crl != null) {
                crls.add(crl);
            }
        }
        return crls;
    }

    private X509CRL fetch(String url) {
        Instant now = clock.instant();
        Fetched held = fetched.get(url);
        if (held != null && now.isBefore(held.until())) {
            return held.crl();
        }
        try {
            byte[] bytes = http.get(URI.create(url), MAX_CRL_BYTES);
            X509CRL crl = (X509CRL) CertificateFactory.getInstance("X.509").generateCRL(new java.io.ByteArrayInputStream(bytes));
            Instant until = now.plus(MAX_KEEP);
            if (crl.getNextUpdate() != null && crl.getNextUpdate().toInstant().isBefore(until)) {
                until = crl.getNextUpdate().toInstant();
            }
            fetched.put(url, new Fetched(crl, until));
            return crl;
        } catch (IOException | CertificateException | CRLException | IllegalArgumentException | ClassCastException e) {
            log.warn("Could not fetch the CRL at {}: {}", url, e.getMessage());
            return null;
        }
    }

    /** The directory's lists, re-read when a file in it is added, removed or changed. */
    private List<X509CRL> directory() {
        String signature = signature();
        if (!signature.equals(dirSignature)) {
            synchronized (this) {
                if (!signature.equals(dirSignature)) {
                    dirCrls = read();
                    dirSignature = signature;
                    log.info("Read {} CRL(s) from {}", dirCrls.size(), dir);
                }
            }
        }
        return dirCrls;
    }

    private String signature() {
        try (Stream<Path> files = Files.list(dir)) {
            StringBuilder sb = new StringBuilder();
            files.filter(Files::isRegularFile).sorted().forEach(f -> {
                try {
                    sb.append(f.getFileName()).append(':').append(Files.size(f)).append(':')
                            .append(Files.getLastModifiedTime(f).toMillis()).append(';');
                } catch (IOException e) {
                    sb.append(f.getFileName()).append(":?;");
                }
            });
            return sb.toString();
        } catch (IOException e) {
            log.warn("Cannot list ENS_X509_CRL_DIR {}: {}", dir, e.getMessage());
            return "unreadable";
        }
    }

    private List<X509CRL> read() {
        List<X509CRL> crls = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                if (Files.size(file) > MAX_CRL_BYTES) {
                    log.warn("Skipping {}: larger than {} bytes", file, MAX_CRL_BYTES);
                    continue;
                }
                try (InputStream in = Files.newInputStream(file)) {
                    Collection<? extends CRL> parsed = CertificateFactory.getInstance("X.509").generateCRLs(in);
                    parsed.stream().filter(X509CRL.class::isInstance).map(X509CRL.class::cast).forEach(crls::add);
                } catch (CertificateException | CRLException | IOException e) {
                    log.warn("Skipping {}: not a CRL ({})", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Cannot read ENS_X509_CRL_DIR {}: {}", dir, e.getMessage());
        }
        return List.copyOf(crls);
    }
}
