package com.winllc.innoutwork.security.x509;

import com.winllc.innoutwork.config.EnsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CRL;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a client certificate has been revoked (ENS_X509_REVOCATION): by OCSP, by CRL, or OCSP and then the CRL
 * when OCSP cannot say.
 * <p>
 * The network is reached only through {@link BoundedHttp}. The answers are judged by the JDK's own PKIX
 * validator with a {@link PKIXRevocationChecker} configured for this one check: an OCSP response fetched here is
 * handed to it as if stapled, and CRLs as a certificate store, so it never fetches anything itself and no global
 * JDK security property is touched. Good and revoked answers are kept for ENS_X509_REVOCATION_CACHE per issuer
 * and serial; an unknown one is asked again next time.
 */
public class RevocationChecker {

    private static final Logger log = LoggerFactory.getLogger(RevocationChecker.class);

    static final long MAX_OCSP_BYTES = 1024 * 1024;
    static final String SHA1 = "1.3.14.3.2.26";

    public enum Status { GOOD, REVOKED, UNKNOWN }

    public record Result(Status status, String detail) {
    }

    private record Cached(Result result, Instant until) {
    }

    private final EnsProperties.X509 settings;
    private final BoundedHttp http;
    private final CrlStore crls;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public RevocationChecker(EnsProperties.X509 settings, Clock clock) {
        this(settings, new BoundedHttp(), clock);
    }

    RevocationChecker(EnsProperties.X509 settings, BoundedHttp http, Clock clock) {
        this.settings = settings;
        this.http = http;
        this.crls = new CrlStore(settings.getCrlDir(), http, clock);
        this.clock = clock;
    }

    public boolean enabled() {
        return settings.getRevocation() != EnsProperties.X509.Revocation.OFF;
    }

    /** The status of {@code leaf}, issued by {@code issuer}. */
    public Result check(X509Certificate leaf, X509Certificate issuer) {
        if (!enabled()) {
            return new Result(Status.GOOD, "revocation checking is off");
        }
        String key = issuer.getSubjectX500Principal().getName() + "#" + leaf.getSerialNumber().toString(16);
        Instant now = clock.instant();
        Cached cached = cache.get(key);
        if (cached != null && now.isBefore(cached.until())) {
            return cached.result();
        }
        Result result = switch (settings.getRevocation()) {
            case OCSP -> ocsp(leaf, issuer);
            case CRL -> crl(leaf, issuer);
            case OCSP_THEN_CRL -> {
                Result ocsp = ocsp(leaf, issuer);
                yield ocsp.status() == Status.UNKNOWN ? crl(leaf, issuer) : ocsp;
            }
            case OFF -> new Result(Status.GOOD, "revocation checking is off");
        };
        if (result.status() != Status.UNKNOWN) {
            cache.put(key, new Cached(result, now.plus(settings.getRevocationCache())));
        }
        return result;
    }

    // --- OCSP -------------------------------------------------------------------------------------------------

    private Result ocsp(X509Certificate leaf, X509Certificate issuer) {
        String responder = settings.getOcspResponderUrl() != null && !settings.getOcspResponderUrl().isBlank()
                ? settings.getOcspResponderUrl().strip() : CertificateExtensions.ocspResponder(leaf);
        if (responder == null) {
            return new Result(Status.UNKNOWN, "the certificate names no OCSP responder");
        }
        byte[] response;
        try {
            response = http.post(URI.create(responder), "application/ocsp-request", "application/ocsp-response",
                    ocspRequest(leaf, issuer), MAX_OCSP_BYTES);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            log.warn("OCSP responder {} could not be asked about serial {}: {}", responder,
                    leaf.getSerialNumber().toString(16), e.getMessage());
            return new Result(Status.UNKNOWN, "OCSP responder " + responder + " unreachable");
        }
        return validate(leaf, issuer, checker -> {
            checker.setOcspResponses(Map.of(leaf, response));
            checker.setOptions(EnumSet.of(PKIXRevocationChecker.Option.ONLY_END_ENTITY,
                    PKIXRevocationChecker.Option.NO_FALLBACK));
        }, List.of(), "OCSP " + responder);
    }

    /** An OCSPRequest (RFC 6960) for one certificate, named by SHA-1 CertID, without a nonce. */
    static byte[] ocspRequest(X509Certificate leaf, X509Certificate issuer) throws GeneralSecurityException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] nameHash = sha1.digest(issuer.getSubjectX500Principal().getEncoded());
        byte[] keyHash = sha1.digest(subjectPublicKeyBits(issuer.getPublicKey().getEncoded()));
        byte[] certId = Der.sequence(
                Der.sequence(Der.oid(SHA1), Der.nullValue()),
                Der.octets(nameHash),
                Der.octets(keyHash),
                Der.integer(leaf.getSerialNumber()));
        byte[] request = Der.sequence(certId);
        byte[] requestList = Der.sequence(request);
        byte[] tbsRequest = Der.sequence(requestList);
        return Der.sequence(tbsRequest);
    }

    /** The subjectPublicKey BIT STRING's bits from a SubjectPublicKeyInfo: what OCSP hashes an issuer's key by. */
    static byte[] subjectPublicKeyBits(byte[] subjectPublicKeyInfo) throws GeneralSecurityException {
        Der.Tlv info = Der.single(subjectPublicKeyInfo);
        if (info != null) {
            List<Der.Tlv> parts = info.children();
            if (parts.size() == 2 && parts.get(1).tag() == Der.BIT_STRING && parts.get(1).value().length > 0) {
                byte[] bits = parts.get(1).value();
                byte[] key = new byte[bits.length - 1];
                System.arraycopy(bits, 1, key, 0, key.length);
                return key;
            }
        }
        throw new GeneralSecurityException("Malformed issuer public key");
    }

    // --- CRL --------------------------------------------------------------------------------------------------

    private Result crl(X509Certificate leaf, X509Certificate issuer) {
        List<CRL> lists = crls.crlsFor(leaf, issuer);
        if (lists.isEmpty()) {
            return new Result(Status.UNKNOWN, crls.fromDirectory()
                    ? "no CRL from " + issuer.getSubjectX500Principal().getName() + " in ENS_X509_CRL_DIR"
                    : "no CRL could be fetched from the certificate's distribution points");
        }
        return validate(leaf, issuer, checker -> checker.setOptions(EnumSet.of(
                PKIXRevocationChecker.Option.ONLY_END_ENTITY, PKIXRevocationChecker.Option.PREFER_CRLS,
                PKIXRevocationChecker.Option.NO_FALLBACK)), lists, "CRL");
    }

    // --- judging the answer -----------------------------------------------------------------------------------

    private Result validate(X509Certificate leaf, X509Certificate issuer,
                            java.util.function.Consumer<PKIXRevocationChecker> configure, List<CRL> lists,
                            String source) {
        try {
            CertPathValidator validator = CertPathValidator.getInstance("PKIX");
            PKIXRevocationChecker checker = (PKIXRevocationChecker) validator.getRevocationChecker();
            configure.accept(checker);
            PKIXParameters params = new PKIXParameters(Set.of(new TrustAnchor(issuer, null)));
            params.setDate(Date.from(clock.instant()));
            // The checker below is used whatever this says; false keeps the JDK's default one out of it.
            params.setRevocationEnabled(false);
            params.addCertPathChecker(checker);
            if (!lists.isEmpty()) {
                params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(lists)));
            }
            validator.validate(CertificateFactory.getInstance("X.509").generateCertPath(List.of(leaf)), params);
            return new Result(Status.GOOD, source + ": good");
        } catch (CertPathValidatorException e) {
            if (e.getReason() == CertPathValidatorException.BasicReason.REVOKED) {
                return new Result(Status.REVOKED, source + ": " + e.getMessage());
            }
            log.warn("Revocation status of serial {} from {} could not be determined: {}",
                    leaf.getSerialNumber().toString(16), source, e.getMessage());
            return new Result(Status.UNKNOWN, source + ": " + e.getMessage());
        } catch (GeneralSecurityException e) {
            return new Result(Status.UNKNOWN, source + ": " + e.getMessage());
        }
    }
}
