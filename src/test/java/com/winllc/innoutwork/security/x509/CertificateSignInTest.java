package com.winllc.innoutwork.security.x509;

import com.winllc.innoutwork.audit.SecurityAudit;
import com.winllc.innoutwork.config.EnsProperties;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.support.TestPki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Who a certificate signs in as, and the refusals: issuer, revocation, mapping. Each refusal is audited. */
class CertificateSignInTest {

    private static final String ALICE_DN = "CN=alice,OU=Users,DC=winllc,DC=com";

    private final TestPki pki = new TestPki("CN=Test CA,O=Example");
    private final AppUserDetailsService users = mock(AppUserDetailsService.class);
    private final LdapService ldap = mock(LdapService.class);
    private final SecurityAudit audit = mock(SecurityAudit.class);
    private final EnsProperties.X509 settings = new EnsProperties.X509();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/home");

    @BeforeEach
    void setUp() {
        when(users.loadUserByUsername(anyString())).thenAnswer(inv ->
                User.withUsername(inv.<String>getArgument(0)).password("").authorities("USER").build());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        pki.close();
    }

    @SuppressWarnings("unchecked")
    private CertificateSignIn signIn() {
        ObjectProvider<SecurityAudit> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(audit);
        TrustMaterial trust = new TrustMaterial("", List.of(pki.ca()), Clock.systemUTC());
        return new CertificateSignIn(users, ldap, new RevocationChecker(settings, Clock.systemUTC()), trust, settings, provider);
    }

    private static PreAuthenticatedAuthenticationToken token(X509Certificate cert) {
        return new PreAuthenticatedAuthenticationToken(cert.getSubjectX500Principal().getName(), cert);
    }

    @Test
    void byDefaultTheSubjectDnIsTheDirectoryDn() {
        X509Certificate cert = pki.leaf(ALICE_DN).issue();

        UserDetails user = signIn().loadUserDetails(token(cert));

        assertEquals(ALICE_DN, user.getUsername());
        verify(ldap, never()).lookupUniqueUserByFilter(anyString());
    }

    @Test
    void aFilterFindsTheUserByTheCertificatesValuesEscaped() {
        settings.setUserFilter("(|(userPrincipalName={upn})(employeeNumber={edipi}))");
        X509Certificate cert = pki.leaf("CN=DOE.JOHN.Q.1234567890,OU=USA,O=U.S. Government,C=US")
                .upn("1234567890@mil").issue();
        when(ldap.lookupUniqueUserByFilter("(|(userPrincipalName=1234567890@mil)(employeeNumber=1234567890))"))
                .thenReturn(Optional.of(LdapUser.builder().dn(ALICE_DN).build()));

        assertEquals(ALICE_DN, signIn().loadUserDetails(token(cert)).getUsername());

        X509Certificate tricky = pki.leaf("CN=x,O=Example").upn("*)(uid=*").issue();
        settings.setUserFilter("(userPrincipalName={upn})");
        when(ldap.lookupUniqueUserByFilter(any())).thenReturn(Optional.empty());
        assertThrows(CertificateRefusedException.class, () -> signIn().loadUserDetails(token(tricky)));
        verify(ldap).lookupUniqueUserByFilter("(userPrincipalName=\\2a\\29\\28uid=\\2a)");
    }

    @Test
    void noSingleMatchSignsNobodyIn() {
        settings.setUserFilter("(mail={email})");
        X509Certificate cert = pki.leaf("CN=bob,O=Example").email("bob@example.com").issue();
        when(ldap.lookupUniqueUserByFilter("(mail=bob@example.com)")).thenReturn(Optional.empty());

        CertificateRefusedException e = assertThrows(CertificateRefusedException.class,
                () -> signIn().loadUserDetails(token(cert)));

        assertEquals("no single directory user matches", e.getMessage());
        verify(audit).certificateRefused(eq(request), eq("CN=bob,O=Example"), eq("CN=Test CA,O=Example"), anyString(),
                eq("no single directory user matches"));
    }

    @Test
    void aCertificateWithoutTheValueTheFilterNeedsIsRefused() {
        settings.setUserFilter("(employeeNumber={edipi})");
        X509Certificate cert = pki.leaf("CN=bob,O=Example").issue();

        assertEquals("certificate has no edipi",
                assertThrows(CertificateRefusedException.class, () -> signIn().loadUserDetails(token(cert))).getMessage());
    }

    @Test
    void anUnknownPlaceholderStopsStartup() {
        settings.setUserFilter("(uid={username})");
        assertThrows(IllegalStateException.class, this::signIn);
    }

    @Test
    void anIssuerNotOnTheListIsRefusedEvenWhenTrusted() {
        settings.setAllowedIssuers("CN=Other CA,O=Example; CN=Third CA,O=Example");
        X509Certificate cert = pki.leaf(ALICE_DN).issue();

        assertEquals("issuer not allowed",
                assertThrows(CertificateRefusedException.class, () -> signIn().loadUserDetails(token(cert))).getMessage());
        verify(users, never()).loadUserByUsername(anyString());

        settings.setAllowedIssuers("CN=Other CA,O=Example;cn=test ca, o=example");
        assertEquals(ALICE_DN, signIn().loadUserDetails(token(cert)).getUsername(), "DNs compare as DNs");
    }

    @Test
    void aRevokedCertificateIsRefusedAndAudited() {
        settings.setRevocation(EnsProperties.X509.Revocation.OCSP);
        X509Certificate cert = pki.leaf(ALICE_DN).withOcsp().issue();
        pki.revoke(cert);

        assertEquals("certificate revoked",
                assertThrows(CertificateRefusedException.class, () -> signIn().loadUserDetails(token(cert))).getMessage());
        verify(audit).certificateRefused(eq(request), eq(ALICE_DN), anyString(), anyString(), eq("certificate revoked"));
    }

    @Test
    void anUnknownStatusIsRefusedUnlessFailingOpen() {
        settings.setRevocation(EnsProperties.X509.Revocation.OCSP);
        X509Certificate cert = pki.leaf(ALICE_DN).ocspUrl("http://127.0.0.1:1/ocsp").issue();

        assertEquals("revocation unknown",
                assertThrows(CertificateRefusedException.class, () -> signIn().loadUserDetails(token(cert))).getMessage());

        settings.setRevocationFail(EnsProperties.X509.RevocationFail.OPEN);
        assertEquals(ALICE_DN, signIn().loadUserDetails(token(cert)).getUsername());
    }

    @Test
    void anUnknownIssuerMeansAnUnknownStatus() {
        settings.setRevocation(EnsProperties.X509.Revocation.OCSP);
        TestPki other = new TestPki("CN=Unknown CA,O=Elsewhere");
        try {
            X509Certificate cert = other.leaf(ALICE_DN).withOcsp().issue();
            assertEquals("revocation unknown",
                    assertThrows(CertificateRefusedException.class, () -> signIn().loadUserDetails(token(cert))).getMessage());
        } finally {
            other.close();
        }
    }

    @Test
    void aSubjectThatIsNoDirectoryUserIsRefusedAsBefore() {
        when(users.loadUserByUsername(ALICE_DN)).thenThrow(new UsernameNotFoundException("No directory user"));
        X509Certificate cert = pki.leaf(ALICE_DN).issue();

        assertThrows(UsernameNotFoundException.class, () -> signIn().loadUserDetails(token(cert)));
    }
}
