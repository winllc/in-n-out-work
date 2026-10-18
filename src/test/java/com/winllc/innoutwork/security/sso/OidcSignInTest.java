package com.winllc.innoutwork.security.sso;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.winllc.innoutwork.audit.SecurityAudit;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.EnsProperties;
import com.winllc.innoutwork.config.SecurityConfig;
import com.winllc.innoutwork.controller.LoginController;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.service.LdapService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenID Connect sign-in end to end against a small identity provider served from the test: discovery, the
 * authorization code flow with PKCE, a signed ID token, and the mapping to a directory user and roles.
 */
@WebMvcTest
@Import({SecurityConfig.class, SsoConfig.class, LoginController.class, OidcSignInTest.Beans.class})
class OidcSignInTest {

    static final String CLIENT_ID = "readyroom";
    static final String ALICE_DN = "CN=Alice,OU=Users,DC=example,DC=com";

    static final Idp IDP = new Idp();

    @Autowired
    MockMvc mvc;
    @MockitoBean
    AppUserDetailsService appUsers;
    @MockitoBean
    BaseLdapPathContextSource contextSource;
    @MockitoBean
    LdapService ldap;
    @MockitoBean
    SecurityAudit audit;

    @DynamicPropertySource
    static void sso(DynamicPropertyRegistry registry) {
        registry.add("ens.sso.oidc.enabled", () -> "true");
        registry.add("ens.sso.oidc.issuer-uri", IDP::issuer);
        registry.add("ens.sso.oidc.client-id", () -> CLIENT_ID);
        registry.add("ens.sso.oidc.client-secret", () -> "s3cret");
        registry.add("ens.sso.oidc.user-filter", () -> "(userPrincipalName={preferred_username})");
        registry.add("ens.sso.role-mapping", () -> "ReadyRoom Admins=ADMIN");
        registry.add("application.application-base-url", () -> "https://readyroom.example.com");
    }

    @Configuration
    @EnableWebSecurity
    @EnableConfigurationProperties(EnsProperties.class)
    static class Beans {
        @Bean
        ApplicationProperties applicationProperties() {
            ApplicationProperties properties = new ApplicationProperties();
            properties.setUserBaseDn("dc=example,dc=com");
            return properties;
        }
    }

    @AfterAll
    static void stop() {
        IDP.server.stop(0);
    }

    @BeforeEach
    void setUp() {
        IDP.reset();
        when(audit.accessDeniedHandler(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(ldap.lookupUniqueUserByFilter(anyString())).thenReturn(Optional.empty());
        when(ldap.lookupUniqueUserByFilter("(userPrincipalName=alice@example.com)"))
                .thenReturn(Optional.of(LdapUser.builder().dn(ALICE_DN).build()));
        when(appUsers.loadUserByUsername(ALICE_DN)).thenAnswer(inv -> {
            AppUserDetails details = new AppUserDetails();
            details.setDn(ALICE_DN);
            details.setGrantedAuthorities(new java.util.ArrayList<>());
            details.addAuthority("USER");
            return details;
        });
    }

    @Test
    void theSignInPageOffersSingleSignOn() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/oauth2/authorization/oidc")));
    }

    /** The Windows client's API is not sent to the identity provider: without a certificate it is still a 401. */
    @Test
    void theClientApiIsNotSentToTheIdentityProvider() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/check/lock")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/home")).andExpect(redirectedUrl("/login"));
    }

    /** The redirect URI is APPLICATION_BASE_URL's, whatever Host or forwarded headers the request carries. */
    @Test
    void theRedirectUriIsPinnedToThePublicUrl() throws Exception {
        MvcResult start = mvc.perform(get("/oauth2/authorization/oidc").session(new MockHttpSession())
                        .header("Host", "attacker.example.com")
                        .header("Forwarded", "host=attacker.example.com;proto=https")
                        .header("X-Forwarded-Host", "attacker.example.com"))
                .andExpect(status().is3xxRedirection()).andReturn();
        String redirectUri = UriComponentsBuilder.fromUriString(start.getResponse().getRedirectedUrl()).build()
                .getQueryParams().getFirst("redirect_uri");
        assertEquals("https://readyroom.example.com/login/oauth2/code/oidc",
                URLDecoder.decode(redirectUri, StandardCharsets.UTF_8));
    }

    @Test
    void aMappedUserSignsInWithTheirDirectoryDnAndRoles() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Map<String, String> authorize = startSignIn(session);

        assertEquals("S256", authorize.get("code_challenge_method"), "PKCE, also for a confidential client");
        assertNotNull(authorize.get("code_challenge"));
        assertNotNull(authorize.get("nonce"));

        IDP.idToken = idToken(IDP.issuer(), CLIENT_ID, authorize.get("nonce"), "alice@example.com",
                List.of("ReadyRoom Admins", "Everyone"), Instant.now());
        MvcResult result = mvc.perform(get("/login/oauth2/code/oidc").session(session)
                        .param("code", "the-code").param("state", authorize.get("state")))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertEquals("/", result.getResponse().getRedirectedUrl());
        assertTrue(IDP.tokenRequest.get().contains("code_verifier="), "the verifier goes with the code");
        Authentication auth = signedIn((MockHttpSession) result.getRequest().getSession());
        assertEquals(ALICE_DN, auth.getName());
        assertTrue(roles(auth).contains("USER"));
        assertTrue(roles(auth).contains("ADMIN"), "granted by the mapped group");
        verify(audit, never()).ssoRefused(any(), anyString(), anyString());
    }

    @Test
    void anUnmappedGroupGrantsNothing() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Map<String, String> authorize = startSignIn(session);
        IDP.idToken = idToken(IDP.issuer(), CLIENT_ID, authorize.get("nonce"), "alice@example.com",
                List.of("Domain Admins", "ADMIN"), Instant.now());

        MvcResult result = mvc.perform(get("/login/oauth2/code/oidc").session(session)
                .param("code", "the-code").param("state", authorize.get("state"))).andReturn();

        Authentication auth = signedIn((MockHttpSession) result.getRequest().getSession());
        assertEquals(List.of("USER"), roles(auth));
    }

    @Test
    void someoneNotInTheDirectoryIsRefusedAndAudited() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Map<String, String> authorize = startSignIn(session);
        IDP.idToken = idToken(IDP.issuer(), CLIENT_ID, authorize.get("nonce"), "mallory@example.com", List.of(),
                Instant.now());

        MvcResult result = mvc.perform(get("/login/oauth2/code/oidc").session(session)
                        .param("code", "the-code").param("state", authorize.get("state")))
                .andExpect(redirectedUrl("/login?sso_error")).andReturn();

        assertNull(signedIn((MockHttpSession) result.getRequest().getSession()));
        verify(audit).ssoRefused(any(), eq("oidc"), org.mockito.ArgumentMatchers.contains("no single directory user"));
    }

    @Test
    void anIdTokenForAnotherClientIsRefused() throws Exception {
        assertRefused(session -> idToken(IDP.issuer(), "another-app", session, "alice@example.com", List.of(),
                Instant.now()));
    }

    @Test
    void anIdTokenFromAnotherIssuerIsRefused() throws Exception {
        assertRefused(nonce -> idToken("https://evil.example.com", CLIENT_ID, nonce, "alice@example.com", List.of(),
                Instant.now()));
    }

    @Test
    void anExpiredIdTokenIsRefused() throws Exception {
        assertRefused(nonce -> idToken(IDP.issuer(), CLIENT_ID, nonce, "alice@example.com", List.of(),
                Instant.now().minusSeconds(3600)));
    }

    @Test
    void anIdTokenWithAnotherNonceIsRefused() throws Exception {
        assertRefused(nonce -> idToken(IDP.issuer(), CLIENT_ID, "replayed-nonce", "alice@example.com", List.of(),
                Instant.now()));
    }

    @Test
    void anIdTokenSignedByAnotherKeyIsRefused() throws Exception {
        assertRefused(nonce -> {
            String token = idToken(IDP.issuer(), CLIENT_ID, nonce, "alice@example.com", List.of(), Instant.now());
            return token.substring(0, token.lastIndexOf('.') + 1)
                    + idToken(IDP.issuer(), CLIENT_ID, nonce, "mallory@example.com", List.of(), Instant.now())
                    .substring(token.lastIndexOf('.') + 1);
        });
    }

    @Test
    void aCallbackWithoutTheStateItStartedWithIsRefused() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Map<String, String> authorize = startSignIn(session);
        IDP.idToken = idToken(IDP.issuer(), CLIENT_ID, authorize.get("nonce"), "alice@example.com", List.of(),
                Instant.now());

        mvc.perform(get("/login/oauth2/code/oidc").session(new MockHttpSession())
                        .param("code", "the-code").param("state", authorize.get("state")))
                .andExpect(redirectedUrl("/login?sso_error"));
        assertNull(IDP.tokenRequest.get(), "the code was never redeemed");
    }

    interface TokenMaker {
        String make(String nonce) throws Exception;
    }

    private void assertRefused(TokenMaker token) throws Exception {
        MockHttpSession session = new MockHttpSession();
        Map<String, String> authorize = startSignIn(session);
        IDP.idToken = token.make(authorize.get("nonce"));

        MvcResult result = mvc.perform(get("/login/oauth2/code/oidc").session(session)
                        .param("code", "the-code").param("state", authorize.get("state")))
                .andExpect(redirectedUrl("/login?sso_error")).andReturn();

        assertNull(signedIn((MockHttpSession) result.getRequest().getSession()));
        verify(audit).ssoRefused(any(), eq("oidc"), anyString());
    }

    private Map<String, String> startSignIn(MockHttpSession session) throws Exception {
        MvcResult start = mvc.perform(get("/oauth2/authorization/oidc").session(session))
                .andExpect(status().is3xxRedirection()).andReturn();
        String location = start.getResponse().getRedirectedUrl();
        assertTrue(location.startsWith(IDP.issuer() + "/authorize"), location);
        Map<String, String> params = new java.util.HashMap<>();
        UriComponentsBuilder.fromUriString(location).build().getQueryParams()
                .forEach((k, v) -> params.put(k, URLDecoder.decode(v.get(0), StandardCharsets.UTF_8)));
        return params;
    }

    private static Authentication signedIn(MockHttpSession session) {
        Object context = session == null ? null
                : session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return context instanceof SecurityContext c ? c.getAuthentication() : null;
    }

    private static List<String> roles(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> !a.startsWith("FACTOR_")).sorted().toList();
    }

    static String idToken(String issuer, String audience, String nonce, String username, List<String> groups,
                          Instant issuedAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject("subject-" + username)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plusSeconds(600)))
                .claim("nonce", nonce)
                .claim("preferred_username", username)
                .claim("groups", groups)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(IDP.key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(IDP.key));
        return jwt.serialize();
    }

    /** Discovery, keys and a token endpoint, enough for the authorization code flow. */
    static final class Idp {
        final HttpServer server;
        final RSAKey key;
        volatile String idToken;
        final AtomicReference<String> tokenRequest = new AtomicReference<>();

        Idp() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                key = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                        .keyID(UUID.randomUUID().toString()).build();
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/.well-known/openid-configuration", ex -> json(ex, """
                    {"issuer":"%1$s","authorization_endpoint":"%1$s/authorize","token_endpoint":"%1$s/token",
                     "jwks_uri":"%1$s/jwks","response_types_supported":["code"],"subject_types_supported":["public"],
                     "id_token_signing_alg_values_supported":["RS256"],
                     "token_endpoint_auth_methods_supported":["client_secret_basic"]}""".formatted(issuer())));
            server.createContext("/jwks", ex -> json(ex, new JWKSet(key.toPublicJWK()).toString()));
            server.createContext("/token", ex -> {
                tokenRequest.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                json(ex, "{\"access_token\":\"at\",\"token_type\":\"Bearer\",\"expires_in\":300,\"scope\":\"openid profile email\",\"id_token\":\""
                        + idToken + "\"}");
            });
            server.start();
        }

        String issuer() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset() {
            idToken = null;
            tokenRequest.set(null);
        }

        private static void json(HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
