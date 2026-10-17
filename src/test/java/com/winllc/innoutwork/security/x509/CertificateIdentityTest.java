package com.winllc.innoutwork.security.x509;

import com.winllc.innoutwork.support.TestPki;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The placeholder values a CAC/PIV certificate gives ENS_X509_USER_FILTER. */
class CertificateIdentityTest {

    private static final TestPki PKI = new TestPki("CN=DoD ID CA-59,OU=PKI,OU=DoD,O=U.S. Government,C=US");

    @AfterAll
    static void close() {
        PKI.close();
    }

    @Test
    void aCacCertificateGivesItsUpnEdipiEmailAndUuid() {
        X509Certificate cert = PKI.leaf("CN=DOE.JOHN.Q.1234567890,OU=USA,OU=PKI,OU=DoD,O=U.S. Government,C=US")
                .upn("1234567890@mil").email("john.q.doe@mail.mil")
                .uuid("a1b2c3d4-e5f6-4789-abcd-ef0123456789").issue();

        CertificateIdentity identity = CertificateIdentity.of(cert);

        assertEquals("1234567890@mil", identity.upn());
        assertEquals("1234567890", identity.edipi());
        assertEquals("john.q.doe@mail.mil", identity.email());
        assertEquals("a1b2c3d4-e5f6-4789-abcd-ef0123456789", identity.uuid());
        assertEquals("DOE.JOHN.Q.1234567890", identity.cn());
        assertEquals("CN=DOE.JOHN.Q.1234567890,OU=USA,OU=PKI,OU=DoD,O=U.S. Government,C=US", identity.subject());
        assertEquals(Map.of("subject", identity.subject(), "cn", "DOE.JOHN.Q.1234567890", "upn", "1234567890@mil",
                "edipi", "1234567890", "email", "john.q.doe@mail.mil", "uuid", "a1b2c3d4-e5f6-4789-abcd-ef0123456789"),
                identity.placeholders());
    }

    @Test
    void theEdipiFallsBackToTheCommonName() {
        X509Certificate cert = PKI.leaf("CN=DOE.JANE.1098765432,OU=USN,O=U.S. Government,C=US")
                .upn("jane.doe@navy.mil").issue();

        assertEquals("1098765432", CertificateIdentity.of(cert).edipi());
    }

    @Test
    void theEmailFallsBackToTheSubject() {
        X509Certificate cert = PKI.leaf("EMAILADDRESS=alice@example.com,CN=alice,OU=Users,DC=winllc,DC=com").issue();

        CertificateIdentity identity = CertificateIdentity.of(cert);

        assertEquals("alice@example.com", identity.email());
        assertNull(identity.upn());
        assertNull(identity.edipi());
        assertNull(identity.uuid());
    }
}
