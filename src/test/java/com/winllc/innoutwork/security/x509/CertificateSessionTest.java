package com.winllc.innoutwork.security.x509;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.SecurityConfig;
import com.winllc.innoutwork.config.SessionStoreConfig;
import com.winllc.innoutwork.controller.LoginController;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.support.TestPki;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A browser's certificate sign-in is kept in its session, without the certificate, so the session's later requests
 * use it: no second sign-in (directory and revocation lookups, an auth.login event) on every page.
 */
@WebMvcTest
@Import({SecurityConfig.class, LoginController.class, CertificateSessionTest.Beans.class})
class CertificateSessionTest {

    static final String DN = "CN=Alice,OU=Users,DC=example,DC=com";
    static final List<AuthenticationSuccessEvent> SIGN_INS = new CopyOnWriteArrayList<>();

    @Autowired
    MockMvc mvc;
    @MockitoBean
    AppUserDetailsService appUsers;
    @MockitoBean
    BaseLdapPathContextSource contextSource;

    @Configuration
    @EnableWebSecurity
    static class Beans {
        @Bean
        ApplicationProperties applicationProperties() {
            ApplicationProperties properties = new ApplicationProperties();
            properties.setUserBaseDn("dc=example,dc=com");
            return properties;
        }

        @Bean
        ApplicationListener<AuthenticationSuccessEvent> signIns() {
            return SIGN_INS::add;
        }
    }

    @BeforeEach
    void setUp() {
        SIGN_INS.clear();
        when(appUsers.loadUserByUsername(anyString())).thenAnswer(inv -> {
            AppUserDetails user = new AppUserDetails();
            user.setDn(inv.getArgument(0));
            user.setGrantedAuthorities(new ArrayList<>());
            user.addAuthority("USER");
            return user;
        });
    }

    @Test
    void theSecondRequestOfASessionDoesNotSignInAgain() throws Exception {
        X509Certificate certificate;
        try (TestPki pki = new TestPki("CN=Test CA,O=Example")) {
            certificate = pki.leaf(DN).issue();
        }
        MockHttpSession session = new MockHttpSession();

        mvc.perform(get("/login").session(session).with(x509(certificate))).andExpect(status().isOk());
        assertEquals(1, SIGN_INS.size());

        Object stored = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertNotNull(stored, "the sign-in is kept in the session");
        SecurityContext context = (SecurityContext) stored;
        assertEquals(DN.replace(",", ", "), context.getAuthentication().getName()); // the plain mapping's subject DN
        assertInstanceOf(SignedInCertificate.class, context.getAuthentication().getCredentials(),
                "the certificate itself is not kept");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(stored);
        }
        assertNotNull(SessionStoreConfig.deserialize(bytes.toByteArray(), getClass().getClassLoader()),
                "and the database session store reads it back");

        mvc.perform(get("/login").session(session).with(x509(certificate))).andExpect(status().isOk());
        assertEquals(1, SIGN_INS.size(), "no second sign-in, so no second auth.login");
    }

    /** The Windows client's calls still get no session: each one signs in, as before. */
    @Test
    void theClientApiStillGetsNoSession() throws Exception {
        X509Certificate certificate;
        try (TestPki pki = new TestPki("CN=Test CA,O=Example")) {
            certificate = pki.leaf(DN).issue();
        }
        org.springframework.test.web.servlet.MvcResult result = mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/check/out")
                                .with(x509(certificate)).contentType("application/json").content("{}"))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertNull(result.getRequest().getSession(false));
    }
}
