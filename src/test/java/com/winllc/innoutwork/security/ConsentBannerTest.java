package com.winllc.innoutwork.security;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.EnsProperties;
import com.winllc.innoutwork.config.SecurityConfig;
import com.winllc.innoutwork.controller.ConsentController;
import com.winllc.innoutwork.controller.LoginController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.security.auth.x500.X500Principal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The notice-and-consent banner, enforced by the application's own {@link SecurityConfig}: while it is enabled no
 * sign-in of any kind happens until the browser session has pressed "I agree", which is audited; the Windows
 * client's API is exempt.
 */
@WebMvcTest
@Import({SecurityConfig.class, ConsentController.class, LoginController.class, ConsentBannerTest.Pages.class,
        ConsentBannerTest.Beans.class})
class ConsentBannerTest {

    private static final String USER_DN = "CN=alice,OU=Users,DC=winllc,DC=com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean AppUserDetailsService appUsers;
    @MockitoBean BaseLdapPathContextSource contextSource;
    @MockitoBean AuditLog audit;

    @RestController
    static class Pages {
        @GetMapping("/app/home")
        String home() {
            return "home";
        }

        @GetMapping("/api/check/records")
        String records() {
            return "[]";
        }

        @GetMapping("/api/license")
        String license() {
            return "{}";
        }
    }

    @Configuration
    @EnableWebSecurity
    static class Beans {
        @Bean
        ApplicationProperties applicationProperties() {
            ApplicationProperties properties = new ApplicationProperties();
            properties.setUserBaseDn("dc=winllc,dc=com");
            return properties;
        }

        @Bean
        ConsentBanner consentBanner() {
            EnsProperties.ConsentBanner settings = new EnsProperties.ConsentBanner();
            settings.setEnabled(true);
            return new ConsentBanner(settings);
        }
    }

    @BeforeEach
    void users() {
        when(appUsers.loadUserByUsername(anyString())).thenAnswer(inv ->
                User.withUsername(inv.<String>getArgument(0)).password("").authorities("USER", "ADMIN").build());
    }

    static X509Certificate mockCert(String dn) {
        X509Certificate cert = mock(X509Certificate.class);
        X500Principal principal = new X500Principal(dn);
        // Some Spring Security paths use SubjectDN; others use SubjectX500Principal.
        when(cert.getSubjectDN()).thenReturn(principal);
        when(cert.getSubjectX500Principal()).thenReturn(principal);
        return cert;
    }

    private static MockHttpSession acknowledged() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ConsentBanner.ACKNOWLEDGED, java.time.Instant.now());
        return session;
    }

    @Test
    void aPageBeforeAcknowledgementGoesToTheBanner() throws Exception {
        mockMvc.perform(get("/app/home")).andExpect(redirectedUrl("/consent"));
        mockMvc.perform(get("/login")).andExpect(redirectedUrl("/consent"));
    }

    @Test
    void theBannerShowsTheDodTextAndAnAgreeButton() throws Exception {
        mockMvc.perform(get("/consent"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(EnsProperties.ConsentBanner.DEFAULT_TITLE)))
                .andExpect(content().string(containsString("You are accessing a U.S. Government (USG) Information"
                        + " System (IS) that is provided for USG-authorized use only.")))
                .andExpect(content().string(containsString("See User Agreement for details.")))
                .andExpect(content().string(containsString("I agree")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    /** A client certificate does not sign in before the banner: not on a page, not on the banner itself. */
    @Test
    void aCertificateDoesNotSignInBeforeAcknowledgement() throws Exception {
        mockMvc.perform(get("/app/home").with(x509(mockCert(USER_DN)))).andExpect(redirectedUrl("/consent"));
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/consent").session(session).with(x509(mockCert(USER_DN)))).andExpect(status().isOk());

        assertNull(session.getAttribute("SPRING_SECURITY_CONTEXT"));
        verify(appUsers, never()).loadUserByUsername(anyString());
    }

    @Test
    void thePasswordFormDoesNotSignInBeforeAcknowledgement() throws Exception {
        mockMvc.perform(post("/login").with(csrf()).param("username", "alice").param("password", "secret"))
                .andExpect(redirectedUrl("/consent"));

        verifyNoInteractions(contextSource);
    }

    @Test
    void agreeingIsAuditedAndRecordedThenSignInWorks() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/app/home").session(session)).andExpect(redirectedUrl("/consent"));

        mockMvc.perform(post("/consent").session(session).with(csrf())).andExpect(redirectedUrl("/app/home"));

        assertNotNull(session.getAttribute(ConsentBanner.ACKNOWLEDGED));
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertEquals("auth.consent-acknowledged", entry.getValue().action());
        assertEquals(AuditEntry.Category.AUTHENTICATION, entry.getValue().category());
        assertEquals(AuditEntry.Outcome.SUCCESS, entry.getValue().outcome());

        mockMvc.perform(get("/app/home").session(session).with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk()).andExpect(content().string("home"));
    }

    @Test
    void agreeingNeedsTheCsrfToken() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/consent").session(session)).andExpect(status().isForbidden());
        assertNull(session.getAttribute(ConsentBanner.ACKNOWLEDGED));
    }

    @Test
    void browserApiCallsBeforeAcknowledgementGet401() throws Exception {
        mockMvc.perform(get("/api/license").with(x509(mockCert(USER_DN))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("consent_required")));
        mockMvc.perform(get("/api/license").session(acknowledged()).with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk());
    }

    /** The Windows client is not a browser session: its API never sees the banner. */
    @Test
    void theWindowsClientsApiIsExempt() throws Exception {
        mockMvc.perform(get("/api/check/records").with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk());
    }

    @Test
    void theTextComesFromTheFileWhenOneIsSet(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("banner.txt");
        Files.writeString(file, "First paragraph\nstill first.\n\n  Second.  \n");
        EnsProperties.ConsentBanner settings = new EnsProperties.ConsentBanner();
        settings.setText("ignored");
        settings.setFile(file.toString());

        assertEquals(List.of("First paragraph still first.", "Second."), new ConsentBanner(settings).getParagraphs());

        settings.setFile(dir.resolve("missing.txt").toString());
        assertThrows(IllegalStateException.class, () -> new ConsentBanner(settings));
    }

    @Test
    void blankTitleAndTextMeanTheDefaults() {
        EnsProperties.ConsentBanner settings = new EnsProperties.ConsentBanner();
        settings.setTitle("");
        settings.setText("  ");

        ConsentBanner banner = new ConsentBanner(settings);

        assertEquals(EnsProperties.ConsentBanner.DEFAULT_TITLE, banner.getTitle());
        assertEquals(6, banner.getParagraphs().size(), "the introduction and the five conditions");
    }
}
