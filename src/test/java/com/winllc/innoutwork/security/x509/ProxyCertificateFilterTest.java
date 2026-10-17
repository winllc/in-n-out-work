package com.winllc.innoutwork.security.x509;

import com.winllc.innoutwork.audit.SecurityAudit;
import com.winllc.innoutwork.config.EnsProperties;
import com.winllc.innoutwork.support.TestPki;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Header mode: a forwarded certificate counts only from a trusted proxy, with the secret, chaining to a known CA. */
class ProxyCertificateFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-proxy";

    private final TestPki pki = new TestPki("CN=Test CA,O=Example");
    private final SecurityAudit audit = mock(SecurityAudit.class);
    private final EnsProperties.X509 settings = new EnsProperties.X509();

    @AfterEach
    void close() {
        pki.close();
    }

    @SuppressWarnings("unchecked")
    private ProxyCertificateFilter filter() {
        ObjectProvider<SecurityAudit> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(audit);
        settings.setSource(EnsProperties.X509.Source.HEADER);
        settings.setTrustedProxies("10.0.0.0/8; 127.0.0.1/32");
        return new ProxyCertificateFilter(settings, SECRET,
                new TrustMaterial("", List.of(pki.ca()), Clock.systemUTC()), provider);
    }

    /** What the filter hands on as the request's client certificate. */
    private X509Certificate[] forwarded(MockHttpServletRequest request) throws Exception {
        AtomicReference<Object> seen = new AtomicReference<>();
        filter().doFilter(request, new MockHttpServletResponse(),
                (ServletRequest req, jakarta.servlet.ServletResponse res) -> seen.set(req.getAttribute(
                        CertificateSignIn.CERTIFICATE_ATTRIBUTE)));
        return (X509Certificate[]) seen.get();
    }

    private MockHttpServletRequest request(String peer, X509Certificate cert, String secret) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/home");
        request.setRemoteAddr(peer);
        if (cert != null) {
            request.addHeader("X-SSL-Client-Cert", TestPki.escapedPem(cert));
        }
        if (secret != null) {
            request.addHeader("X-ENS-Proxy-Secret", secret);
        }
        return request;
    }

    @Test
    void aTrustedProxyWithTheSecretForwardsTheCertificate() throws Exception {
        X509Certificate cert = pki.leaf("CN=alice,OU=Users,DC=winllc,DC=com").issue();

        assertArrayEquals(new X509Certificate[]{cert}, forwarded(request("10.1.2.3", cert, SECRET)));
    }

    @Test
    void anUntrustedAddressIsIgnoredAndAudited() throws Exception {
        X509Certificate cert = pki.leaf("CN=alice,OU=Users,DC=winllc,DC=com").issue();

        assertNull(forwarded(request("192.168.1.5", cert, SECRET)));
        verify(audit).certificateRefused(any(), eq(null), eq(null), eq(null),
                eq("certificate header from an untrusted address 192.168.1.5"));
    }

    @Test
    void aWrongOrMissingSecretIsIgnored() throws Exception {
        X509Certificate cert = pki.leaf("CN=alice,OU=Users,DC=winllc,DC=com").issue();

        assertNull(forwarded(request("10.1.2.3", cert, null)));
        assertNull(forwarded(request("10.1.2.3", cert, SECRET + "x")));
        assertNull(forwarded(request("10.1.2.3", cert, SECRET.substring(1))));
    }

    @Test
    void aCertificateFromAnUnknownCaIsIgnored() throws Exception {
        TestPki other = new TestPki("CN=Rogue CA,O=Elsewhere");
        try {
            X509Certificate cert = other.leaf("CN=alice,OU=Users,DC=winllc,DC=com").issue();
            assertNull(forwarded(request("10.1.2.3", cert, SECRET)));
            verify(audit).certificateRefused(any(), eq("CN=alice,OU=Users,DC=winllc,DC=com"), any(), any(),
                    eq("certificate not issued by a trusted CA"));
        } finally {
            other.close();
        }
    }

    @Test
    void anExpiredCertificateIsIgnored() throws Exception {
        X509Certificate cert = pki.leaf("CN=alice,OU=Users,DC=winllc,DC=com").expired().issue();
        assertNull(forwarded(request("10.1.2.3", cert, SECRET)));
    }

    /** X-Forwarded-For is anyone's to send; the proxy check looks at the connection itself. */
    @Test
    void aForwardedForHeaderCannotPoseAsATrustedProxy() throws Exception {
        X509Certificate cert = pki.leaf("CN=alice,OU=Users,DC=winllc,DC=com").issue();
        MockHttpServletRequest request = request("192.168.1.5", cert, SECRET);
        request.addHeader("X-Forwarded-For", "10.1.2.3");
        AtomicReference<Object> seen = new AtomicReference<>();

        new ForwardedHeaderFilter().doFilter(request, new MockHttpServletResponse(),
                (req, res) -> filter().doFilter(req, res, (inner, out) -> seen.set(
                        inner.getAttribute(CertificateSignIn.CERTIFICATE_ATTRIBUTE))));

        assertNull(seen.get());
    }

    /** A certificate on the connection itself is not used in header mode. */
    @Test
    void theConnectionsOwnCertificateIsIgnored() throws Exception {
        X509Certificate cert = pki.leaf("CN=alice,OU=Users,DC=winllc,DC=com").issue();
        MockHttpServletRequest request = request("10.1.2.3", null, SECRET);
        request.setAttribute(CertificateSignIn.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{cert});

        assertNull(forwarded(request));
    }

    @Test
    void plainAndFoldedPemParseToo() throws Exception {
        X509Certificate cert = pki.leaf("CN=alice,O=Example").issue();
        String pem = TestPki.pem(cert);

        assertEquals(cert, ProxyCertificateFilter.parse(pem));
        assertEquals(cert, ProxyCertificateFilter.parse(pem.replace("\n", " ")));
        assertEquals(cert, ProxyCertificateFilter.parse(TestPki.escapedPem(cert)));
    }

    // --- startup -------------------------------------------------------------------------------------------

    @Test
    void headerModeNeedsALongEnoughSecret(@TempDir Path dir) throws Exception {
        EnsProperties.X509 x509 = new EnsProperties.X509();
        x509.setSource(EnsProperties.X509.Source.HEADER);
        assertThrows(IllegalStateException.class, () -> X509Config.proxySecret(x509));
        x509.setProxySecret("too-short");
        assertThrows(IllegalStateException.class, () -> X509Config.proxySecret(x509));
        x509.setProxySecret(SECRET);
        assertEquals(SECRET, X509Config.proxySecret(x509));

        Path file = dir.resolve("secret");
        Files.writeString(file, "from-the-file-0123456789abcdef-0123\n");
        x509.setProxySecretFile(file.toString());
        assertEquals("from-the-file-0123456789abcdef-0123", X509Config.proxySecret(x509), "the file wins, trimmed");
        x509.setProxySecretFile(dir.resolve("missing").toString());
        assertThrows(IllegalStateException.class, () -> X509Config.proxySecret(x509));
    }

    @Test
    void headerModeNeedsACaToCheckAgainst() {
        assertTrue(new TrustMaterial("", List.of(), Clock.systemUTC()).isEmpty());
    }

    @Test
    void anUnparseableProxyListStopsStartup() {
        assertThrows(IllegalStateException.class, () -> ProxyCertificateFilter.trustedProxies("10.0.0.0/8;not-an-ip/99"));
    }
}
