package com.winllc.innoutwork.security.sso;

import com.winllc.innoutwork.config.EnsProperties;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.repository.SsoAssertionSeenRepository;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.service.LdapService;
import net.shibboleth.shared.xml.SerializeSupport;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.core.xml.schema.impl.XSStringBuilder;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.AuthnContext;
import org.opensaml.saml.saml2.core.AuthnContextClassRef;
import org.opensaml.saml.saml2.core.AuthnStatement;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.Signer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationToken;
import org.springframework.security.saml2.provider.service.authentication.Saml2PostAuthenticationRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;

import javax.xml.namespace.QName;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SAML responses signed by an identity provider made here, checked by the provider ReadyRoom configures: what
 * signs in, and each check that refuses (signature, audience, times, InResponseTo, replay, unknown user).
 */
class SamlSignInTest {

    static final String IDP = "https://idp.example.com/saml";
    static final String SP = "https://readyroom.example.com/saml2/service-provider-metadata/saml";
    static final String ACS = "https://readyroom.example.com/login/saml2/sso/saml";
    static final String UPN_ATTRIBUTE = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn";
    static final String GROUPS = "http://schemas.microsoft.com/ws/2008/06/identity/claims/groups";
    static final String ALICE_DN = "CN=Alice,OU=Users,DC=example,DC=com";

    @TempDir
    Path dir;

    private final LdapService ldap = mock(LdapService.class);
    private final AppUserDetailsService users = mock(AppUserDetailsService.class);
    private final SsoAssertionSeenRepository seen = mock(SsoAssertionSeenRepository.class);
    private final Set<String> used = new HashSet<>();

    private BasicX509Credential idpCredential;
    private SamlSignIn signIn;
    private RelyingPartyRegistration registration;
    private OpenSaml5AuthenticationProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        idpCredential = credential("CN=idp.example.com");
        provider = null;
        Files.writeString(dir.resolve("idp.xml"), metadata(idpCredential.getEntityCertificate()));

        EnsProperties.Sso sso = new EnsProperties.Sso();
        sso.getSaml().setEnabled(true);
        sso.getSaml().setIdpMetadata("file:" + dir.resolve("idp.xml"));
        sso.getSaml().setUserFilter("(userPrincipalName={" + UPN_ATTRIBUTE + "})");
        sso.setRoleMapping("ReadyRoom Managers=MANAGER");

        when(ldap.lookupUniqueUserByFilter(anyString())).thenReturn(Optional.empty());
        when(ldap.lookupUniqueUserByFilter("(userPrincipalName=alice@example.com)"))
                .thenReturn(Optional.of(LdapUser.builder().dn(ALICE_DN).build()));
        when(users.loadUserByUsername(ALICE_DN)).thenAnswer(inv -> {
            AppUserDetails details = new AppUserDetails();
            details.setDn(ALICE_DN);
            details.setGrantedAuthorities(new ArrayList<>());
            details.addAuthority("USER");
            return details;
        });
        when(seen.insertFirstUse(anyString(), any())).thenAnswer(inv -> {
            if (!used.add(inv.getArgument(0))) {
                throw new DataIntegrityViolationException("duplicate key");
            }
            return 1;
        });

        signIn = new SamlSignIn(sso, new SsoUserMapper(users, ldap, RoleMapping.parse(sso.getRoleMapping())), seen,
                Clock.systemUTC(), "https://readyroom.example.com");
        registration = signIn.registrations().findByRegistrationId("saml");
        provider = signIn.authenticationProvider();
    }

    @Test
    void theRedirectBindingIsPreferredOverThePostThatTheMetadataListsFirst() {
        RelyingPartyRegistration loaded = signIn.registrations().findByRegistrationId("saml");
        assertEquals(Saml2MessageBinding.REDIRECT, loaded.getAssertingPartyMetadata().getSingleSignOnServiceBinding());
        assertEquals(IDP + "/redirect", loaded.getAssertingPartyMetadata().getSingleSignOnServiceLocation());
    }

    /**
     * The audience and destination come from APPLICATION_BASE_URL, never from a request's Host or forwarded
     * headers: the registration holds no {baseUrl} template for a request to fill in.
     */
    @Test
    void theEntityIdAndConsumerServiceArePinnedToThePublicUrl() {
        assertEquals(SP, registration.getEntityId());
        assertEquals(ACS, registration.getAssertionConsumerServiceLocation());
        org.springframework.mock.web.MockHttpServletRequest spoofed =
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/login/saml2/sso/saml");
        spoofed.setServerName("other-sp.example.com");
        spoofed.addHeader("Forwarded", "host=other-sp.example.com;proto=https");
        spoofed.addHeader("X-Forwarded-Host", "other-sp.example.com");
        RelyingPartyRegistration resolved = new org.springframework.security.saml2.provider.service.web
                .DefaultRelyingPartyRegistrationResolver(signIn.registrations()).resolve(spoofed, "saml");
        assertEquals(SP, resolved.getEntityId());
        assertEquals(ACS, resolved.getAssertionConsumerServiceLocation());
    }

    @Test
    void aRelativeEntityIdStopsStartup() {
        EnsProperties.Sso sso = new EnsProperties.Sso();
        sso.getSaml().setIdpMetadata("file:/nowhere");
        sso.getSaml().setUserFilter("(uid={nameid})");
        sso.getSaml().setEntityId("readyroom");
        assertThrows(IllegalStateException.class,
                () -> new SamlSignIn(sso, null, seen, Clock.systemUTC(), "https://readyroom.example.com"));
    }

    @Test
    void aSignedAnswerToOurRequestSignsInTheDirectoryUser() throws Exception {
        Authentication auth = provider.authenticate(token(response(b -> {
        }), "request-1"));

        assertEquals(ALICE_DN, auth.getName());
        assertTrue(auth instanceof SsoAuthenticationToken t && "saml2".equals(t.method()));
        assertEquals(List.of("MANAGER", "USER"), auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .sorted().toList());
    }

    @Test
    void anAssertionIsUsedOnce() throws Exception {
        String response = response(b -> {
        });
        provider.authenticate(token(response, "request-1"));

        AuthenticationException e = assertThrows(AuthenticationException.class,
                () -> provider.authenticate(token(response, "request-1")));
        assertTrue(e.getMessage().contains("already used"), e.getMessage());
    }

    @Test
    void anIdpInitiatedResponseIsRefused() throws Exception {
        String response = response(b -> b.inResponseTo = null);
        assertRefused(() -> provider.authenticate(token(response, null)), "IdP-initiated sign-in is not accepted");
    }

    @Test
    void anAnswerToAnotherRequestIsRefused() throws Exception {
        String response = response(b -> {
        });
        assertRefused(() -> provider.authenticate(token(response, "request-2")), "does not match");
    }

    @Test
    void anUnsignedOrTamperedResponseIsRefused() throws Exception {
        String response = response(b -> {
        }).replace("alice@example.com", "mallory@example.com");
        assertRefused(() -> provider.authenticate(token(response, "request-1")), "signature");
    }

    @Test
    void aResponseSignedByAnotherKeyIsRefused() throws Exception {
        idpCredential = credential("CN=attacker");
        String response = response(b -> {
        });
        assertRefused(() -> provider.authenticate(token(response, "request-1")), "signature");
    }

    @Test
    void anAssertionForAnotherServiceIsRefused() throws Exception {
        String response = response(b -> b.audience = "https://other.example.com");
        assertRefused(() -> provider.authenticate(token(response, "request-1")), "audience");
    }

    @Test
    void anExpiredAssertionIsRefused() throws Exception {
        String response = response(b -> b.issued = Instant.now().minusSeconds(3600));
        assertRefused(() -> provider.authenticate(token(response, "request-1")), "");
    }

    @Test
    void someoneNotInTheDirectoryIsRefused() throws Exception {
        String response = response(b -> b.upn = "mallory@example.com");
        assertRefused(() -> provider.authenticate(token(response, "request-1")), "no single directory user");
    }

    @Test
    void aUserFilterWithoutPlaceholderStopsStartup() {
        EnsProperties.Sso sso = new EnsProperties.Sso();
        sso.getSaml().setIdpMetadata("file:/nowhere");
        sso.getSaml().setUserFilter("(uid=alice)");
        assertThrows(IllegalStateException.class,
                () -> new SamlSignIn(sso, null, seen, Clock.systemUTC(), "https://readyroom.example.com"));
    }

    // --- an identity provider ---------------------------------------------------------------------------------

    interface Change {
        void apply(Shape shape);
    }

    static final class Shape {
        String inResponseTo = "request-1";
        String audience = SP;
        String upn = "alice@example.com";
        Instant issued = Instant.now();
    }

    private void assertRefused(org.junit.jupiter.api.function.Executable call, String expected) {
        AuthenticationException e = assertThrows(AuthenticationException.class, call);
        assertTrue(e.getMessage().toLowerCase().contains(expected.toLowerCase()), e.getMessage());
    }

    private Saml2AuthenticationToken token(String responseXml, String requestId) {
        Saml2PostAuthenticationRequest request = requestId == null ? null
                : Saml2PostAuthenticationRequest.withRelyingPartyRegistration(registration)
                .samlRequest("unused").authenticationRequestUri(IDP + "/post").relayState("rs").id(requestId).build();
        return new Saml2AuthenticationToken(registration, responseXml, request);
    }

    @SuppressWarnings("unchecked")
    private static <T extends XMLObject> T build(QName name) {
        return (T) XMLObjectProviderRegistrySupport.getBuilderFactory().ensureBuilder(name).buildObject(name);
    }

    private String response(Change change) throws Exception {
        Shape shape = new Shape();
        change.apply(shape);
        Instant now = shape.issued;

        Response response = build(Response.DEFAULT_ELEMENT_NAME);
        response.setID("_r" + java.util.UUID.randomUUID());
        response.setIssueInstant(now);
        response.setVersion(SAMLVersion.VERSION_20);
        response.setDestination(ACS);
        response.setInResponseTo(shape.inResponseTo);
        response.setIssuer(issuer());
        Status status = build(Status.DEFAULT_ELEMENT_NAME);
        StatusCode code = build(StatusCode.DEFAULT_ELEMENT_NAME);
        code.setValue(StatusCode.SUCCESS);
        status.setStatusCode(code);
        response.setStatus(status);

        Assertion assertion = build(Assertion.DEFAULT_ELEMENT_NAME);
        assertion.setID("_a" + java.util.UUID.randomUUID());
        assertion.setIssueInstant(now);
        assertion.setVersion(SAMLVersion.VERSION_20);
        assertion.setIssuer(issuer());

        Subject subject = build(Subject.DEFAULT_ELEMENT_NAME);
        NameID nameId = build(NameID.DEFAULT_ELEMENT_NAME);
        nameId.setValue("alice-at-idp");
        subject.setNameID(nameId);
        SubjectConfirmation confirmation = build(SubjectConfirmation.DEFAULT_ELEMENT_NAME);
        confirmation.setMethod(SubjectConfirmation.METHOD_BEARER);
        SubjectConfirmationData data = build(SubjectConfirmationData.DEFAULT_ELEMENT_NAME);
        data.setRecipient(ACS);
        data.setNotOnOrAfter(now.plusSeconds(300));
        if (shape.inResponseTo != null) {
            data.setInResponseTo(shape.inResponseTo);
        }
        confirmation.setSubjectConfirmationData(data);
        subject.getSubjectConfirmations().add(confirmation);
        assertion.setSubject(subject);

        Conditions conditions = build(Conditions.DEFAULT_ELEMENT_NAME);
        conditions.setNotBefore(now.minusSeconds(5));
        conditions.setNotOnOrAfter(now.plusSeconds(300));
        AudienceRestriction restriction = build(AudienceRestriction.DEFAULT_ELEMENT_NAME);
        Audience audience = build(Audience.DEFAULT_ELEMENT_NAME);
        audience.setURI(shape.audience);
        restriction.getAudiences().add(audience);
        conditions.getAudienceRestrictions().add(restriction);
        assertion.setConditions(conditions);

        AuthnStatement authn = build(AuthnStatement.DEFAULT_ELEMENT_NAME);
        authn.setAuthnInstant(now);
        AuthnContext context = build(AuthnContext.DEFAULT_ELEMENT_NAME);
        AuthnContextClassRef classRef = build(AuthnContextClassRef.DEFAULT_ELEMENT_NAME);
        classRef.setURI(AuthnContext.PPT_AUTHN_CTX);
        context.setAuthnContextClassRef(classRef);
        authn.setAuthnContext(context);
        assertion.getAuthnStatements().add(authn);

        AttributeStatement attributes = build(AttributeStatement.DEFAULT_ELEMENT_NAME);
        attributes.getAttributes().add(attribute(UPN_ATTRIBUTE, shape.upn));
        attributes.getAttributes().add(attribute(GROUPS, "ReadyRoom Managers", "Everyone"));
        assertion.getAttributeStatements().add(attributes);

        Signature signature = build(Signature.DEFAULT_ELEMENT_NAME);
        signature.setSigningCredential(idpCredential);
        signature.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
        signature.setCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        assertion.setSignature(signature);
        response.getAssertions().add(assertion);

        org.w3c.dom.Element element = XMLObjectProviderRegistrySupport.getMarshallerFactory().getMarshaller(response)
                .marshall(response);
        Signer.signObject(signature);
        return SerializeSupport.nodeToString(element);
    }

    private static Attribute attribute(String name, String... values) {
        Attribute attribute = build(Attribute.DEFAULT_ELEMENT_NAME);
        attribute.setName(name);
        for (String value : values) {
            XSString text = new XSStringBuilder().buildObject(org.opensaml.saml.saml2.core.AttributeValue.DEFAULT_ELEMENT_NAME,
                    XSString.TYPE_NAME);
            text.setValue(value);
            attribute.getAttributeValues().add(text);
        }
        return attribute;
    }

    private static Issuer issuer() {
        Issuer issuer = build(Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue(IDP);
        return issuer;
    }

    static BasicX509Credential credential(String dn) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        X500Name name = new X500Name(dn);
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(name,
                BigInteger.valueOf(System.nanoTime()), Date.from(Instant.now().minusSeconds(60)),
                Date.from(Instant.now().plusSeconds(86400)), name, pair.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
        return new BasicX509Credential(cert, pair.getPrivate());
    }

    /** POST listed first, as some IdPs do; ReadyRoom should still use Redirect. */
    static String metadata(X509Certificate cert) throws Exception {
        String certificate = Base64.getEncoder().encodeToString(cert.getEncoded());
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata"
                        xmlns:ds="http://www.w3.org/2000/09/xmldsig#" entityID="%1$s">
                  <md:IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                    <md:KeyDescriptor use="signing">
                      <ds:KeyInfo><ds:X509Data><ds:X509Certificate>%2$s</ds:X509Certificate></ds:X509Data></ds:KeyInfo>
                    </md:KeyDescriptor>
                    <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST" Location="%1$s/post"/>
                    <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect" Location="%1$s/redirect"/>
                  </md:IDPSSODescriptor>
                </md:EntityDescriptor>
                """.formatted(IDP, certificate).strip();
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
