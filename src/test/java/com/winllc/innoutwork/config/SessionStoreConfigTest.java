package com.winllc.innoutwork.config;

import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.OrgParseRuleRecord;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.security.ConsentBanner;
import com.winllc.innoutwork.security.sso.OidcSignIn;
import com.winllc.innoutwork.security.sso.RoleMapping;
import com.winllc.innoutwork.security.sso.SsoAuthenticationToken;
import com.winllc.innoutwork.security.sso.SsoUserMapper;
import com.winllc.innoutwork.service.LdapService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.SessionFlashMapManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.rmi.Remote;
import java.rmi.server.Operation;
import java.rmi.server.RemoteCall;
import java.rmi.server.RemoteObject;
import java.rmi.server.RemoteObjectInvocationHandler;
import java.rmi.server.RemoteRef;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every kind of value ReadyRoom puts in a browser session, saved and read back through the real JDBC session
 * repository (a MockHttpSession would never serialize them), and the allow-list refusing what sessions never hold.
 */
class SessionStoreConfigTest {

    static final String DN = "CN=Alice,OU=Users,DC=example,DC=com";

    private final EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true).addScript("org/springframework/session/jdbc/schema-h2.sql").build();

    @AfterEach
    void shutdown() {
        database.shutdown();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private FindByIndexNameSessionRepository<Session> replica() {
        JdbcIndexedSessionRepository repository = new JdbcIndexedSessionRepository(new JdbcTemplate(database),
                new TransactionTemplate(new DataSourceTransactionManager(database)));
        repository.setConversionService(SessionStoreConfig.tolerantConversionService(getClass().getClassLoader()));
        repository.afterPropertiesSet();
        return (FindByIndexNameSessionRepository) repository;
    }

    private static AppUserDetails alice() {
        AppUserDetails user = new AppUserDetails();
        user.setDn(DN);
        user.setGrantedAuthorities(new ArrayList<>());
        user.addAuthority("USER");
        return user;
    }

    /** An OIDC principal as OidcSignIn makes it, from an ID token whose issuer the decoder made a java.net.URL. */
    private static OidcUser oidcUser() throws Exception {
        LdapService ldap = mock(LdapService.class);
        AppUserDetailsService users = mock(AppUserDetailsService.class);
        when(ldap.lookupUniqueUserByFilter("(userPrincipalName=alice@example.com)"))
                .thenReturn(Optional.of(LdapUser.builder().dn(DN).build()));
        when(users.loadUserByUsername(DN)).thenAnswer(inv -> alice());
        EnsProperties.Sso sso = new EnsProperties.Sso();
        sso.getOidc().setIssuerUri("https://idp.example.com");
        sso.getOidc().setClientId("readyroom");
        sso.getOidc().setUserFilter("(userPrincipalName={upn})");
        sso.setRoleMapping("Admins=ADMIN");
        OidcSignIn signIn = new OidcSignIn(sso, new SsoUserMapper(users, ldap, RoleMapping.parse(sso.getRoleMapping())),
                "https://readyroom.example.com");
        ClientRegistration registration = ClientRegistration.withRegistrationId("oidc").clientId("readyroom")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri(signIn.redirectUri())
                .authorizationUri("https://idp.example.com/authorize").tokenUri("https://idp.example.com/token")
                .jwkSetUri("https://idp.example.com/jwks").userNameAttributeName("sub").scope("openid").build();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", URI.create("https://idp.example.com").toURL());
        claims.put("sub", "alice");
        claims.put("aud", List.of("readyroom"));
        claims.put("upn", "alice@example.com");
        claims.put("groups", List.of("Admins"));
        claims.put("weight", new java.math.BigDecimal("1.5"));
        Instant now = Instant.now();
        OidcIdToken idToken = new OidcIdToken("token", now, now.plusSeconds(300), claims);
        return signIn.userService().loadUser(new OidcUserRequest(registration,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "at", now, now.plusSeconds(300)), idToken));
    }

    private static Object pendingErasure() throws Exception {
        Class<?> type = Class.forName("com.winllc.innoutwork.controller.AdminPrivacyController$PendingErasure");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class, Instant.class);
        constructor.setAccessible(true);
        return constructor.newInstance(DN, "token", Instant.now());
    }

    @Test
    void everyAttributeTheApplicationStoresSurvivesTheDatabase() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/home");
        request.setRemoteAddr("192.0.2.1");

        UsernamePasswordAuthenticationToken form = UsernamePasswordAuthenticationToken.authenticated(alice(), null,
                alice().getAuthorities());
        form.setDetails(new WebAuthenticationDetails(request));
        OidcUser oidc = oidcUser();
        OAuth2AuthenticationToken oidcToken = new OAuth2AuthenticationToken(oidc, oidc.getAuthorities(), "oidc");
        SsoAuthenticationToken saml = new SsoAuthenticationToken(alice(), "saml2", "saml", List.of("MANAGER"));

        FlashMap flash = new FlashMap();
        flash.put("orgParseRuleForm", OrgParseRuleRecord.builder().orgName("HQ").orgParseRegex(".*").build());
        flash.put("error", "Organization name and parse regex are both required.");
        flash.setTargetRequestPath("/app/settings");
        flash.startExpirationPeriod(180);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("form", new SecurityContextImpl(form));
        attributes.put("oidc", new SecurityContextImpl(oidcToken));
        attributes.put("saml", new SecurityContextImpl(saml));
        attributes.put("pendingErasure", pendingErasure());
        attributes.put("systemTime", ZonedDateTime.now());
        attributes.put(ConsentBanner.ACKNOWLEDGED, Instant.now());
        attributes.put("consentTarget", "/app/home?x=1");
        attributes.put("signedInAt", Instant.now());
        attributes.put("ended", "absolute");
        attributes.put(SessionFlashMapManager.class.getName() + ".FLASH_MAPS", new ArrayList<>(List.of(flash)));
        attributes.put("csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "value"));
        attributes.put("savedRequest", new DefaultSavedRequest(request));
        attributes.put("authorizationRequest", OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://idp.example.com/authorize").clientId("readyroom")
                .redirectUri("https://readyroom.example.com/login/oauth2/code/oidc").state("state")
                .scopes(java.util.Set.of("openid")).attributes(Map.of("registration_id", "oidc", "nonce", "n"))
                .build());

        FindByIndexNameSessionRepository<Session> writer = replica();
        Session session = writer.createSession();
        attributes.forEach(session::setAttribute);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, attributes.get("oidc"));
        writer.save(session);

        Session read = replica().findById(session.getId());
        for (String name : attributes.keySet()) {
            assertNotNull(read.getAttribute(name), name + " did not survive the database");
        }
        SecurityContextImpl oidcRead = read.getAttribute("oidc");
        assertEquals(DN, oidcRead.getAuthentication().getName());
        OidcUser principal = (OidcUser) oidcRead.getAuthentication().getPrincipal();
        assertEquals("https://idp.example.com", principal.getIdToken().getClaims().get("iss"),
                "the issuer is kept as a string, not a java.net.URL");
        assertEquals(List.of("ADMIN"), principal.getClaims().get(OidcSignIn.IDP_ROLES_CLAIM));
        assertEquals("saml2", ((SsoAuthenticationToken) ((SecurityContextImpl) read.getAttribute("saml"))
                .getAuthentication()).method());
        List<FlashMap> flashes = read.getAttribute(SessionFlashMapManager.class.getName() + ".FLASH_MAPS");
        assertInstanceOf(OrgParseRuleRecord.class, flashes.get(0).get("orgParseRuleForm"));
        assertEquals(1, replica().findByPrincipalName(DN).size());
    }

    private static byte[] serialize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static Object read(Object value) throws IOException {
        return SessionStoreConfig.deserialize(serialize(value), SessionStoreConfigTest.class.getClassLoader());
    }

    @Test
    void aUrlKeyedMapIsRefused() throws Exception {
        Map<URL, String> map = new HashMap<>();
        map.put(URI.create("http://127.0.0.1/").toURL(), "x"); // the DNS lookup gadget: URL.hashCode on read
        assertNull(read(map));
    }

    @Test
    void anRmiInvocationHandlerIsRefused() throws Exception {
        Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Remote.class},
                new RemoteObjectInvocationHandler(new NoRef()));
        assertNull(read(proxy));
        assertNull(read(new java.rmi.MarshalledObject<>("x")));
    }

    @Test
    void aPriorityQueueIsRefused() throws Exception {
        PriorityQueue<String> queue = new PriorityQueue<>();
        queue.add("a");
        assertNull(read(queue), "its comparator runs while it is read");
    }

    @Test
    void plainValuesAreRead() throws Exception {
        assertEquals("x", read("x"));
        assertEquals(List.of(1L, 2L), read(new ArrayList<>(List.of(1L, 2L))));
        byte[] bytes = {1, 2, 3};
        assertTrue(java.util.Arrays.equals(bytes, (byte[]) read(bytes)));
    }

    /** A RemoteRef that serializes by itself, enough to put RemoteObjectInvocationHandler on the wire. */
    public static final class NoRef implements RemoteRef {
        public NoRef() {
        }

        @Override
        public Object invoke(Remote obj, java.lang.reflect.Method method, Object[] params, long opnum) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public RemoteCall newCall(RemoteObject obj, Operation[] op, int opnum, long hash) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public void invoke(RemoteCall call) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public void done(RemoteCall call) {
        }

        @Override
        public String getRefClass(ObjectOutput out) {
            return null;
        }

        @Override
        public int remoteHashCode() {
            return 0;
        }

        @Override
        public boolean remoteEquals(RemoteRef obj) {
            return false;
        }

        @Override
        public String remoteToString() {
            return "NoRef";
        }

        @Override
        public void writeExternal(ObjectOutput out) {
        }

        @Override
        public void readExternal(ObjectInput in) {
        }
    }
}
