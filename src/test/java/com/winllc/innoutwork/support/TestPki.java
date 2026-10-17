package com.winllc.innoutwork.support;

import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CRLHolder;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.Req;
import org.bouncycastle.cert.ocsp.RespID;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.UnknownStatus;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A certificate authority made in the test: user certificates with the extensions CAC/PIV cards carry, CRLs, and
 * an OCSP responder and CRL distribution point on a local HTTP server. Nothing is read from or written to disk
 * unless a test asks for a file.
 */
public final class TestPki implements AutoCloseable {

    private static final AtomicLong SERIALS = new AtomicLong(1000);

    private final KeyPair caKeys;
    private final X509Certificate ca;
    private final Set<BigInteger> revoked = ConcurrentHashMap.newKeySet();
    private HttpServer server;
    /** OCSP requests answered, for tests of caching. */
    public final AtomicInteger ocspRequests = new AtomicInteger();

    public TestPki(String caDn) {
        try {
            caKeys = keys();
            Instant now = Instant.now();
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name(caDn),
                    BigInteger.valueOf(SERIALS.incrementAndGet()), Date.from(now.minus(Duration.ofDays(1))),
                    Date.from(now.plus(Duration.ofDays(365))), name(caDn), caKeys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            ca = convert(builder.build(signer(caKeys)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** An RFC 2253 DN as the JDK encodes it, so the certificate's DN reads back exactly as written. */
    private static X500Name name(String dn) {
        return X500Name.getInstance(new javax.security.auth.x500.X500Principal(dn).getEncoded());
    }

    private static KeyPair keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        return generator.generateKeyPair();
    }

    private static ContentSigner signer(KeyPair keys) throws Exception {
        return new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate());
    }

    private static X509Certificate convert(X509CertificateHolder holder) throws Exception {
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    public X509Certificate ca() {
        return ca;
    }

    /** The local server's base URL, started on first use: /ocsp answers OCSP, /crl serves the current CRL. */
    public synchronized String baseUrl() {
        if (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/ocsp", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                byte[] answer = ocspResponse(body);
                ocspRequests.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "application/ocsp-response");
                exchange.sendResponseHeaders(200, answer.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(answer);
                }
            });
            server.createContext("/crl", exchange -> {
                byte[] answer;
                try {
                    answer = crl().getEncoded();
                } catch (Exception e) {
                    throw new IOException(e);
                }
                exchange.sendResponseHeaders(200, answer.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(answer);
                }
            });
            server.start();
        }
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A certificate request for a user; {@link Leaf#issue()} makes it. */
    public Leaf leaf(String subjectDn) {
        return new Leaf(subjectDn);
    }

    public final class Leaf {
        private final String subject;
        private String upn;
        private String email;
        private String uuid;
        private String ocspUrl;
        private String crlUrl;
        private Instant notAfter = Instant.now().plus(Duration.ofDays(30));

        private Leaf(String subject) {
            this.subject = subject;
        }

        public Leaf upn(String value) {
            this.upn = value;
            return this;
        }

        public Leaf email(String value) {
            this.email = value;
            return this;
        }

        public Leaf uuid(String value) {
            this.uuid = value;
            return this;
        }

        /** AIA OCSP pointing at this PKI's responder. */
        public Leaf withOcsp() {
            this.ocspUrl = baseUrl() + "/ocsp";
            return this;
        }

        public Leaf ocspUrl(String url) {
            this.ocspUrl = url;
            return this;
        }

        /** A CRL distribution point at this PKI's server. */
        public Leaf withCrl() {
            this.crlUrl = baseUrl() + "/crl";
            return this;
        }

        public Leaf expired() {
            this.notAfter = Instant.now().minus(Duration.ofDays(1));
            return this;
        }

        public X509Certificate issue() {
            try {
                Instant now = Instant.now();
                X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(ca,
                        BigInteger.valueOf(SERIALS.incrementAndGet()), Date.from(now.minus(Duration.ofDays(2))),
                        Date.from(notAfter), name(subject), keys().getPublic());
                List<GeneralName> names = new ArrayList<>();
                if (upn != null) {
                    ASN1EncodableVector otherName = new ASN1EncodableVector();
                    otherName.add(new ASN1ObjectIdentifier("1.3.6.1.4.1.311.20.2.3"));
                    otherName.add(new DERTaggedObject(true, 0, new DERUTF8String(upn)));
                    names.add(new GeneralName(GeneralName.otherName, new DERSequence(otherName)));
                }
                if (email != null) {
                    names.add(new GeneralName(GeneralName.rfc822Name, email));
                }
                if (uuid != null) {
                    names.add(new GeneralName(GeneralName.uniformResourceIdentifier, "urn:uuid:" + uuid));
                }
                if (!names.isEmpty()) {
                    builder.addExtension(Extension.subjectAlternativeName, false,
                            new GeneralNames(names.toArray(GeneralName[]::new)));
                }
                if (ocspUrl != null) {
                    builder.addExtension(Extension.authorityInfoAccess, false, new AuthorityInformationAccess(
                            AccessDescription.id_ad_ocsp, new GeneralName(GeneralName.uniformResourceIdentifier, ocspUrl)));
                }
                if (crlUrl != null) {
                    builder.addExtension(Extension.cRLDistributionPoints, false, new CRLDistPoint(new DistributionPoint[]{
                            new DistributionPoint(new DistributionPointName(new GeneralNames(
                                    new GeneralName(GeneralName.uniformResourceIdentifier, crlUrl))), null, null)}));
                }
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
                return convert(builder.build(signer(caKeys)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public void revoke(X509Certificate cert) {
        revoked.add(cert.getSerialNumber());
    }

    /** The current CRL, valid for a day; {@code stale} makes one whose next update has passed. */
    public X509CRL crl(boolean stale) {
        try {
            Instant now = Instant.now();
            X509v2CRLBuilder builder = new X509v2CRLBuilder(new JcaX509CertificateHolder(ca).getSubject(),
                    Date.from(stale ? now.minus(Duration.ofDays(3)) : now.minus(Duration.ofMinutes(5))));
            builder.setNextUpdate(Date.from(stale ? now.minus(Duration.ofDays(1)) : now.plus(Duration.ofDays(1))));
            for (BigInteger serial : revoked) {
                builder.addCRLEntry(serial, Date.from(now.minus(Duration.ofHours(1))), CRLReason.keyCompromise);
            }
            X509CRLHolder holder = builder.build(signer(caKeys));
            return new JcaX509CRLConverter().getCRL(holder);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public X509CRL crl() {
        return crl(false);
    }

    /** An OCSP response for the request, signed by the CA itself. Serials the CA never issued are "unknown". */
    private byte[] ocspResponse(byte[] request) throws IOException {
        try {
            OCSPReq req = new OCSPReq(request);
            BasicOCSPRespBuilder builder = new BasicOCSPRespBuilder(new RespID(new JcaX509CertificateHolder(ca).getSubject()));
            Date now = new Date();
            Date next = Date.from(Instant.now().plus(Duration.ofHours(1)));
            for (Req one : req.getRequestList()) {
                CertificateID id = one.getCertID();
                CertificateStatus status = revoked.contains(id.getSerialNumber())
                        ? new RevokedStatus(new Date(now.getTime() - 3_600_000L), CRLReason.keyCompromise)
                        : id.getSerialNumber().longValue() > SERIALS.get() ? new UnknownStatus() : CertificateStatus.GOOD;
                builder.addResponse(id, status, now, next);
            }
            return new OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL,
                    builder.build(signer(caKeys), new X509CertificateHolder[]{new JcaX509CertificateHolder(ca)}, now))
                    .getEncoded();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** {@code cert} as PEM. */
    public static String pem(X509Certificate cert) {
        try {
            StringWriter out = new StringWriter();
            out.write("-----BEGIN CERTIFICATE-----\n");
            out.write(Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded()));
            out.write("\n-----END CERTIFICATE-----\n");
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** As nginx's $ssl_client_escaped_cert sends it: URL-encoded PEM. */
    public static String escapedPem(X509Certificate cert) {
        return java.net.URLEncoder.encode(pem(cert), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }
}
