package com.winllc.innoutwork.security.x509;

import com.winllc.innoutwork.config.EnsProperties;
import com.winllc.innoutwork.support.TestPki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OCSP and CRL revocation checks against a real (test) CA, responder and distribution point. */
class RevocationCheckerTest {

    private final TestPki pki = new TestPki("CN=Test CA,O=Example");
    private final EnsProperties.X509 settings = new EnsProperties.X509();

    @AfterEach
    void close() {
        pki.close();
    }

    private RevocationChecker checker(EnsProperties.X509.Revocation mode) {
        settings.setRevocation(mode);
        return new RevocationChecker(settings, Clock.systemUTC());
    }

    @Test
    void ocspSaysGoodOrRevoked() {
        X509Certificate good = pki.leaf("CN=good,O=Example").withOcsp().issue();
        X509Certificate bad = pki.leaf("CN=bad,O=Example").withOcsp().issue();
        pki.revoke(bad);
        RevocationChecker checker = checker(EnsProperties.X509.Revocation.OCSP);

        assertEquals(RevocationChecker.Status.GOOD, checker.check(good, pki.ca()).status());
        assertEquals(RevocationChecker.Status.REVOKED, checker.check(bad, pki.ca()).status());
    }

    @Test
    void anUnreachableResponderIsUnknownNotGood() {
        X509Certificate cert = pki.leaf("CN=a,O=Example").ocspUrl("http://127.0.0.1:1/ocsp").issue();

        assertEquals(RevocationChecker.Status.UNKNOWN,
                checker(EnsProperties.X509.Revocation.OCSP).check(cert, pki.ca()).status());
    }

    @Test
    void noResponderAtAllIsUnknown() {
        X509Certificate cert = pki.leaf("CN=a,O=Example").issue();

        assertEquals(RevocationChecker.Status.UNKNOWN,
                checker(EnsProperties.X509.Revocation.OCSP).check(cert, pki.ca()).status());
    }

    @Test
    void theConfiguredResponderOverridesTheCertificates() {
        X509Certificate cert = pki.leaf("CN=a,O=Example").ocspUrl("http://127.0.0.1:1/ocsp").issue();
        pki.revoke(cert);
        settings.setOcspResponderUrl(pki.baseUrl() + "/ocsp");

        assertEquals(RevocationChecker.Status.REVOKED,
                checker(EnsProperties.X509.Revocation.OCSP).check(cert, pki.ca()).status());
    }

    @Test
    void answersAreCachedPerSerial() {
        X509Certificate cert = pki.leaf("CN=a,O=Example").withOcsp().issue();
        RevocationChecker checker = checker(EnsProperties.X509.Revocation.OCSP);

        checker.check(cert, pki.ca());
        checker.check(cert, pki.ca());

        assertEquals(1, pki.ocspRequests.get());

        settings.setRevocationCache(Duration.ZERO);
        RevocationChecker uncached = new RevocationChecker(settings, Clock.systemUTC());
        uncached.check(cert, pki.ca());
        uncached.check(cert, pki.ca());
        assertEquals(3, pki.ocspRequests.get());
    }

    @Test
    void aCrlFromTheDistributionPoint() {
        X509Certificate good = pki.leaf("CN=good,O=Example").withCrl().issue();
        X509Certificate bad = pki.leaf("CN=bad,O=Example").withCrl().issue();
        pki.revoke(bad);
        RevocationChecker checker = checker(EnsProperties.X509.Revocation.CRL);

        assertEquals(RevocationChecker.Status.GOOD, checker.check(good, pki.ca()).status());
        assertEquals(RevocationChecker.Status.REVOKED, checker.check(bad, pki.ca()).status());
    }

    /** Air-gapped: CRL files in a directory, re-read when they change; distribution points are not fetched. */
    @Test
    void crlsFromADirectoryAreReReadWhenTheyChange(@TempDir Path dir) throws Exception {
        X509Certificate cert = pki.leaf("CN=a,O=Example").ocspUrl("http://127.0.0.1:1/never").issue();
        Files.write(dir.resolve("ca.crl"), pki.crl().getEncoded());
        settings.setCrlDir(dir.toString());
        settings.setRevocationCache(Duration.ZERO);
        RevocationChecker checker = checker(EnsProperties.X509.Revocation.CRL);
        assertEquals(RevocationChecker.Status.GOOD, checker.check(cert, pki.ca()).status());

        pki.revoke(cert);
        // PEM this time, and a new modification time.
        String pem = "-----BEGIN X509 CRL-----\n" + java.util.Base64.getMimeEncoder().encodeToString(pki.crl().getEncoded())
                + "\n-----END X509 CRL-----\n";
        Files.writeString(dir.resolve("ca.crl"), pem);
        Files.setLastModifiedTime(dir.resolve("ca.crl"), java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() + 5000));

        assertEquals(RevocationChecker.Status.REVOKED, checker.check(cert, pki.ca()).status());
    }

    @Test
    void aStaleCrlIsUnknown(@TempDir Path dir) throws Exception {
        X509Certificate cert = pki.leaf("CN=a,O=Example").issue();
        Files.write(dir.resolve("ca.crl"), pki.crl(true).getEncoded());
        settings.setCrlDir(dir.toString());

        assertEquals(RevocationChecker.Status.UNKNOWN,
                checker(EnsProperties.X509.Revocation.CRL).check(cert, pki.ca()).status());
    }

    @Test
    void ocspThenCrlFallsBackWhenTheResponderCannotSay() {
        X509Certificate cert = pki.leaf("CN=a,O=Example").ocspUrl("http://127.0.0.1:1/ocsp").withCrl().issue();
        pki.revoke(cert);

        assertEquals(RevocationChecker.Status.REVOKED,
                checker(EnsProperties.X509.Revocation.OCSP_THEN_CRL).check(cert, pki.ca()).status());
    }

    @Test
    void offAnswersGoodWithoutAsking() {
        X509Certificate cert = pki.leaf("CN=a,O=Example").withOcsp().issue();
        pki.revoke(cert);

        assertEquals(RevocationChecker.Status.GOOD, checker(EnsProperties.X509.Revocation.OFF).check(cert, pki.ca()).status());
        assertEquals(0, pki.ocspRequests.get());
    }

    @Test
    void theNetworkIsReachedOnlyOverHttpAndNeverAMetadataAddress() throws Exception {
        assertThrows(IOException.class, () -> BoundedHttp.checked(URI.create("http://169.254.169.254/latest/meta-data")));
        assertThrows(IOException.class, () -> BoundedHttp.checked(URI.create("ldap://ldap.example.com/cn=crl")));
        assertThrows(IOException.class, () -> BoundedHttp.checked(URI.create("file:///etc/passwd")));
        assertThrows(IOException.class, () -> BoundedHttp.checked(URI.create("http://[fe80::1]/crl")));
        assertTrue(BoundedHttp.blocked(InetAddress.getByName("100.100.100.200")));
        assertEquals(URI.create("http://127.0.0.1/ocsp"), BoundedHttp.checked(URI.create("http://127.0.0.1/ocsp")),
                "a local OCSP relay is fine");
    }

    /** The request's CertID is the RFC 6960 one: what BouncyCastle (and so real responders) read. */
    @Test
    void theOcspRequestNamesTheCertificateAsResponderExpect() throws Exception {
        X509Certificate cert = pki.leaf("CN=a,O=Example").issue();

        org.bouncycastle.cert.ocsp.OCSPReq request = new org.bouncycastle.cert.ocsp.OCSPReq(
                RevocationChecker.ocspRequest(cert, pki.ca()));

        org.bouncycastle.cert.ocsp.CertificateID id = request.getRequestList()[0].getCertID();
        assertEquals(cert.getSerialNumber(), id.getSerialNumber());
        assertTrue(id.matchesIssuer(new org.bouncycastle.cert.jcajce.JcaX509CertificateHolder(pki.ca()),
                new org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder().build()));
    }
}
