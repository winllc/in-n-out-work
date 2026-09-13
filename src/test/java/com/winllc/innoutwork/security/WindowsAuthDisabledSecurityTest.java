package com.winllc.innoutwork.security;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.SecurityConfig;
import com.winllc.innoutwork.config.WindowsAuthSecurityConfig;
import com.winllc.innoutwork.rest.CheckInOutRestService;
import com.winllc.innoutwork.service.CheckInOutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.kerberos.authentication.KerberosTicketValidator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;

import static com.winllc.innoutwork.security.WindowsAuthTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With Windows sign-in off (the default) the check-in calls behave exactly as before it existed. */
@WebMvcTest
@Import({SecurityConfig.class, WindowsAuthSecurityConfig.class, CheckInOutRestService.class,
        WindowsAuthDisabledSecurityTest.Beans.class})
class WindowsAuthDisabledSecurityTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationContext context;

    @MockitoBean
    AppUserDetailsService appUsers;
    @MockitoBean
    BaseLdapPathContextSource contextSource;
    @MockitoBean
    CheckInOutService checkIns;

    @Configuration
    @EnableWebSecurity
    static class Beans {
        @Bean
        ApplicationProperties applicationProperties() {
            ApplicationProperties properties = new ApplicationProperties();
            properties.setUserBaseDn("dc=winllc,dc=com");
            return properties;
        }
    }

    @BeforeEach
    void setUp() {
        appUsersResolveByDn(appUsers);
        recordsAreSaved(checkIns);
    }

    @Test
    void nothingForWindowsSignInIsCreated() {
        assertEquals(0, context.getBeansOfType(KerberosTicketValidator.class).size());
        assertEquals(1, context.getBeansOfType(SecurityFilterChain.class).size());
    }

    @Test
    void aCertificateSignsIn() throws Exception {
        mockMvc.perform(post("/api/check/in").with(x509(cert(CERT_DN)))
                        .contentType(MediaType.APPLICATION_JSON).content(body("alice")))
                .andExpect(status().isOk());

        assertEquals(CERT_DN, savedRecord(checkIns).getDn());
    }

    @Test
    void withoutACertificateTheRequestIsSentToSignInAsBefore() throws Exception {
        mockMvc.perform(post("/api/check/in").contentType(MediaType.APPLICATION_JSON).content(body("bob")))
                .andExpect(redirectedUrl("/login"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void aTicketIsIgnored() throws Exception {
        mockMvc.perform(post("/api/check/in")
                        .header("Authorization", "Negotiate " + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}))
                        .contentType(MediaType.APPLICATION_JSON).content(body("bob")))
                .andExpect(redirectedUrl("/login"));
    }
}
