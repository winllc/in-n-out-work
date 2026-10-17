package com.winllc.innoutwork.security.x509;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.EnsProperties;
import com.winllc.innoutwork.config.SecurityConfig;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.support.TestPki;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Header mode through the application's own SecurityConfig and X509Config: behind a TLS-terminating proxy that
 * forwards the certificate with the secret, the user signs in exactly as with TLS to ReadyRoom; without the secret,
 * or with a certificate on the connection instead, nobody does. The issuer allowlist applies the same way.
 */
@WebMvcTest(properties = {
        "ens.x509.source=header",
        "ens.x509.proxy-secret=" + X509ChainTest.SECRET,
        "ens.x509.allowed-issuers=CN=Test CA,O=Example"
})
@Import({SecurityConfig.class, X509Config.class, X509ChainTest.Pages.class, X509ChainTest.Beans.class})
class X509ChainTest {

    private static final String ALICE_DN = "CN=alice,OU=Users,DC=winllc,DC=com";
    static final String SECRET = "0123456789abcdef0123456789abcdef-proxy";
    static final TestPki PKI = new TestPki("CN=Test CA,O=Example");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean AppUserDetailsService appUsers;
    @MockitoBean BaseLdapPathContextSource contextSource;
    @MockitoBean LdapService ldapService;

    @AfterAll
    static void close() {
        PKI.close();
    }

    @RestController
    static class Pages {
        @GetMapping("/app/home")
        String home(java.security.Principal principal) {
            return principal.getName();
        }
    }

    @Configuration
    @EnableWebSecurity
    @EnableConfigurationProperties(EnsProperties.class)
    static class Beans {
        @Bean
        ApplicationProperties applicationProperties() {
            ApplicationProperties properties = new ApplicationProperties();
            properties.setUserBaseDn("dc=winllc,dc=com");
            return properties;
        }
    }

    /** The CA, as the PEM file ENS_X509_TRUSTED_CA_FILE names. */
    @DynamicPropertySource
    static void caFile(DynamicPropertyRegistry registry) throws IOException {
        Path ca = Files.createTempFile("readyroom-ca", ".pem");
        ca.toFile().deleteOnExit();
        Files.writeString(ca, TestPki.pem(PKI.ca()));
        registry.add("ens.x509.trusted-ca-file", ca::toString);
    }

    @BeforeEach
    void users() {
        when(appUsers.loadUserByUsername(anyString())).thenAnswer(inv ->
                User.withUsername(inv.<String>getArgument(0)).password("").authorities("USER").build());
    }

    @Test
    void theProxysCertificateSignsIn() throws Exception {
        X509Certificate cert = PKI.leaf(ALICE_DN).issue();

        mockMvc.perform(get("/app/home").header("X-SSL-Client-Cert", TestPki.escapedPem(cert))
                        .header("X-ENS-Proxy-Secret", SECRET))
                .andExpect(status().isOk())
                // The subject DN as Spring Security's X.509 filter reads it, which the default mapping looks up.
                .andExpect(content().string("CN=alice, OU=Users, DC=winllc, DC=com"));
    }

    @Test
    void withoutTheSecretNobodySignsIn() throws Exception {
        X509Certificate cert = PKI.leaf(ALICE_DN).issue();

        mockMvc.perform(get("/app/home").header("X-SSL-Client-Cert", TestPki.escapedPem(cert)))
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void aCertificateOnTheConnectionIsNotUsedInHeaderMode() throws Exception {
        mockMvc.perform(get("/app/home").with(x509(PKI.leaf(ALICE_DN).issue())))
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void anotherIssuerIsRefusedEvenWithTheSecret() throws Exception {
        TestPki other = new TestPki("CN=Other CA,O=Example");
        try {
            X509Certificate cert = other.leaf(ALICE_DN).issue();
            mockMvc.perform(get("/app/home").header("X-SSL-Client-Cert", TestPki.escapedPem(cert))
                            .header("X-ENS-Proxy-Secret", SECRET))
                    .andExpect(redirectedUrl("/login"));
        } finally {
            other.close();
        }
    }
}
