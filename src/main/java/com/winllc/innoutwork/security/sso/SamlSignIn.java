package com.winllc.innoutwork.security.sso;

import com.winllc.innoutwork.config.EnsProperties;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.repository.SsoAssertionSeenRepository;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.core.Saml2ErrorCodes;
import org.springframework.security.saml2.core.Saml2ResponseValidatorResult;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SAML 2.0 sign-in (ENS_SSO_SAML_*), SP-initiated only. OpenSAML, through Spring Security, checks the response's
 * and assertions' signatures against the IdP metadata's certificates, the issuer, the audience (this SP's entity
 * id), the destination and recipient, and the times (within ENS_SSO_CLOCK_SKEW). On top of that:
 * <ul>
 *   <li>the response must answer a request this service sent ({@code InResponseTo}): IdP-initiated sign-in, which
 *       anyone holding a fresh response could replay into another browser, is refused;</li>
 *   <li>each assertion is used once, across replicas ({@code sso_assertion_seen});</li>
 *   <li>the request was sent from this browser ({@link SamlRequestStore}).</li>
 * </ul>
 * The user is then mapped to a directory user ({@link SsoUserMapper}). The IdP metadata is read on first use and
 * again every 12 hours (certificate rollover); a failure keeps the last good copy.
 */
public class SamlSignIn {

    private static final Logger log = LoggerFactory.getLogger(SamlSignIn.class);

    public static final String REGISTRATION_ID = "saml";
    static final Duration METADATA_REFRESH = Duration.ofHours(12);
    static final Duration METADATA_RETRY = Duration.ofSeconds(30);
    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    /** How long an assertion with no NotOnOrAfter is remembered. */
    static final Duration DEFAULT_ASSERTION_LIFETIME = Duration.ofMinutes(10);

    private final EnsProperties.Sso.Saml settings;
    private final Duration clockSkew;
    private final SsoUserMapper mapper;
    private final SsoAssertionSeenRepository seen;
    private final Clock clock;
    private final LazyRegistrations registrations = new LazyRegistrations();

    public SamlSignIn(EnsProperties.Sso settings, SsoUserMapper mapper, SsoAssertionSeenRepository seen, Clock clock) {
        this.settings = settings.getSaml();
        this.clockSkew = settings.getClockSkew();
        this.mapper = mapper;
        this.seen = seen;
        this.clock = clock;
        if (this.settings.getIdpMetadata() == null || this.settings.getIdpMetadata().isBlank()) {
            throw new IllegalStateException("ENS_SSO_SAML_ENABLED is true but ENS_SSO_SAML_IDP_METADATA is not set");
        }
        SsoUserMapper.requireTemplate("ENS_SSO_SAML_USER_FILTER", this.settings.getUserFilter());
        boolean key = !this.settings.getSpKeyFile().isBlank();
        boolean cert = !this.settings.getSpCertFile().isBlank();
        if (key != cert) {
            throw new IllegalStateException("ENS_SSO_SAML_SP_KEY_FILE and ENS_SSO_SAML_SP_CERT_FILE go together");
        }
        if (key) {
            credentials(); // unreadable files stop startup, not the first sign-in
        }
    }

    public RelyingPartyRegistrationRepository registrations() {
        return registrations;
    }

    public String loginUrl() {
        return "/saml2/authenticate/" + REGISTRATION_ID;
    }

    public String displayName() {
        return settings.getDisplayName();
    }

    // --- the provider ----------------------------------------------------------------------------------------

    public OpenSaml5AuthenticationProvider authenticationProvider() {
        OpenSaml5AuthenticationProvider provider = new OpenSaml5AuthenticationProvider();
        provider.setAssertionValidator(OpenSaml5AuthenticationProvider.AssertionValidator.builder()
                .clockSkew(clockSkew).build());
        provider.setResponseValidator(OpenSaml5AuthenticationProvider.ResponseValidator
                .withDefaults(SamlSignIn::requireInResponseTo));
        OpenSaml5AuthenticationProvider.ResponseAuthenticationConverter base =
                new OpenSaml5AuthenticationProvider.ResponseAuthenticationConverter();
        provider.setResponseAuthenticationConverter(token -> signIn(token, base));
        return provider;
    }

    /** The response answers a request this service sent: Spring only checks that when the response says so. */
    static Saml2ResponseValidatorResult requireInResponseTo(OpenSaml5AuthenticationProvider.ResponseToken token) {
        String inResponseTo = token.getResponse().getInResponseTo();
        if (token.getToken().getAuthenticationRequest() == null || inResponseTo == null || inResponseTo.isBlank()) {
            return Saml2ResponseValidatorResult.failure(new Saml2Error(Saml2ErrorCodes.INVALID_IN_RESPONSE_TO,
                    "the response answers no sign-in request from this browser (IdP-initiated sign-in is not"
                            + " accepted; start from ReadyRoom's sign-in page)"));
        }
        return Saml2ResponseValidatorResult.success();
    }

    /** After every check passed: each assertion used once, then the directory user. */
    SsoAuthenticationToken signIn(OpenSaml5AuthenticationProvider.ResponseToken token,
                                  OpenSaml5AuthenticationProvider.ResponseAuthenticationConverter base) {
        Response response = token.getResponse();
        for (Assertion assertion : response.getAssertions()) {
            firstUse(assertion);
        }
        Saml2Authentication validated = base.convert(token);
        Saml2AuthenticatedPrincipal principal = (Saml2AuthenticatedPrincipal) validated.getPrincipal();
        Map<String, List<String>> values = new HashMap<>();
        principal.getAttributes().forEach((name, list) -> {
            List<String> strings = new ArrayList<>();
            list.forEach(v -> {
                if (v != null) {
                    strings.add(String.valueOf(v));
                }
            });
            values.put(name, strings);
        });
        values.put("nameid", List.of(principal.getName()));
        AppUserDetails user;
        try {
            user = mapper.map(settings.getUserFilter(), values, values.getOrDefault(settings.getGroupsAttribute(),
                    List.of()));
        } catch (SsoRefusedException e) {
            throw new Saml2AuthenticationException(new Saml2Error("sso_refused",
                    e.getMessage() + " (NameID " + principal.getName() + ")"));
        }
        return new SsoAuthenticationToken(user, "saml2", REGISTRATION_ID);
    }

    private void firstUse(Assertion assertion) {
        Instant now = clock.instant();
        Instant expires = assertion.getConditions() != null && assertion.getConditions().getNotOnOrAfter() != null
                ? assertion.getConditions().getNotOnOrAfter() : now.plus(DEFAULT_ASSERTION_LIFETIME);
        try {
            seen.deleteExpired(now.minus(clockSkew));
            seen.insertFirstUse(assertion.getID(), expires.plus(clockSkew));
        } catch (DataIntegrityViolationException e) {
            throw new Saml2AuthenticationException(new Saml2Error(Saml2ErrorCodes.INVALID_ASSERTION,
                    "assertion " + assertion.getID() + " was already used (replayed)"));
        }
    }

    // --- the registration --------------------------------------------------------------------------------------

    RelyingPartyRegistration load() {
        byte[] metadata = preferRedirectBinding(readMetadata(settings.getIdpMetadata()));
        RelyingPartyRegistration.Builder builder = RelyingPartyRegistrations
                .fromMetadata(new ByteArrayInputStream(metadata))
                .registrationId(REGISTRATION_ID)
                .entityId(settings.getEntityId().isBlank()
                        ? "{baseUrl}/saml2/service-provider-metadata/{registrationId}" : settings.getEntityId())
                .assertionConsumerServiceLocation("{baseUrl}/login/saml2/sso/{registrationId}")
                .assertionConsumerServiceBinding(Saml2MessageBinding.POST);
        if (!settings.getSpKeyFile().isBlank()) {
            Credentials c = credentials();
            builder.signingX509Credentials(list -> list.add(Saml2X509Credential.signing(c.key(), c.certificate())))
                    .decryptionX509Credentials(list -> list.add(Saml2X509Credential.decryption(c.key(), c.certificate())));
        }
        RelyingPartyRegistration registration = builder.build();
        log.info("SAML: identity provider {} (sign-in {} by {}), this service provider {}",
                registration.getAssertingPartyMetadata().getEntityId(),
                registration.getAssertingPartyMetadata().getSingleSignOnServiceLocation(),
                registration.getAssertingPartyMetadata().getSingleSignOnServiceBinding(), registration.getEntityId());
        return registration;
    }

    record Credentials(RSAPrivateKey key, X509Certificate certificate) {
    }

    Credentials credentials() {
        try (InputStream keyIn = Files.newInputStream(Path.of(settings.getSpKeyFile()));
             InputStream certIn = Files.newInputStream(Path.of(settings.getSpCertFile()))) {
            RSAPrivateKey key = RsaKeyConverters.pkcs8().convert(keyIn);
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(certIn);
            return new Credentials(key, cert);
        } catch (Exception e) {
            throw new IllegalStateException("ENS_SSO_SAML_SP_KEY_FILE / ENS_SSO_SAML_SP_CERT_FILE cannot be read (a PEM"
                    + " PKCS#8 RSA key and its certificate): " + e.getMessage(), e);
        }
    }

    static byte[] readMetadata(String location) {
        try {
            if (location.startsWith("https://") || location.startsWith("http://")) {
                HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL).build();
                HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(location))
                        .timeout(HTTP_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + " from " + location);
                }
                return response.body();
            }
            return Files.readAllBytes(Path.of(location.startsWith("file:") ? URI.create(location).getPath() : location));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted reading SAML metadata", e);
        } catch (Exception e) {
            throw new IllegalStateException("The SAML IdP metadata " + location + " cannot be read: " + e.getMessage(), e);
        }
    }

    /**
     * The metadata with the IdP's HTTP-Redirect sign-in endpoint listed first, which Spring then uses: the POST
     * binding needs a self-submitting form to another site, which ReadyRoom's Content-Security-Policy (form-action
     * 'self', no inline scripts) rightly blocks. Metadata without a Redirect endpoint is left as it is.
     */
    static byte[] preferRedirectBinding(byte[] metadata) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(metadata));
            NodeList services = document.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:metadata",
                    "SingleSignOnService");
            boolean moved = false;
            List<Element> redirects = new ArrayList<>();
            for (int i = 0; i < services.getLength(); i++) {
                Element service = (Element) services.item(i);
                if (Saml2MessageBinding.REDIRECT.getUrn().equals(service.getAttribute("Binding"))) {
                    redirects.add(service);
                }
            }
            for (Element redirect : redirects) {
                Node parent = redirect.getParentNode();
                Node first = firstSingleSignOnService(parent);
                if (first != null && first != redirect) {
                    parent.insertBefore(redirect, first);
                    moved = true;
                }
            }
            if (!moved) {
                return metadata;
            }
            TransformerFactory transformers = TransformerFactory.newInstance();
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = transformers.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception e) {
            log.debug("SAML metadata left as it is: {}", e.toString());
            return metadata;
        }
    }

    private static Node firstSingleSignOnService(Node parent) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element e && "SingleSignOnService".equals(e.getLocalName())) {
                return child;
            }
        }
        return null;
    }

    /** The one registration: loaded on first use, refreshed every 12 hours, the last good one kept on failure. */
    final class LazyRegistrations implements RelyingPartyRegistrationRepository {

        private volatile RelyingPartyRegistration registration;
        private volatile Instant loadedAt;
        private volatile Instant failedAt;

        @Override
        public RelyingPartyRegistration findByRegistrationId(String registrationId) {
            if (!REGISTRATION_ID.equals(registrationId)) {
                return null;
            }
            Instant now = clock.instant();
            RelyingPartyRegistration known = registration;
            if (known != null && now.isBefore(loadedAt.plus(METADATA_REFRESH))) {
                return known;
            }
            synchronized (this) {
                if (registration != null && now.isBefore(loadedAt.plus(METADATA_REFRESH))) {
                    return registration;
                }
                if (failedAt != null && now.isBefore(failedAt.plus(METADATA_RETRY))) {
                    return registration;
                }
                try {
                    registration = load();
                    loadedAt = now;
                    failedAt = null;
                } catch (RuntimeException e) {
                    failedAt = now;
                    log.error("SAML: the identity provider's metadata could not be read{}: {}",
                            registration == null ? "; SAML sign-in is unavailable until it can" : "; keeping the last copy",
                            e.toString());
                }
                return registration;
            }
        }

        @Override
        public RelyingPartyRegistration findUniqueByAssertingPartyEntityId(String entityId) {
            RelyingPartyRegistration known = findByRegistrationId(REGISTRATION_ID);
            return known != null && known.getAssertingPartyMetadata().getEntityId().equals(entityId) ? known : null;
        }
    }
}
