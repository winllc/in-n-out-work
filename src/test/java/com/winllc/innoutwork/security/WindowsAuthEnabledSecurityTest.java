package com.winllc.innoutwork.security;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.SecurityConfig;
import com.winllc.innoutwork.config.WindowsAuthSecurityConfig;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.rest.CheckInOutRestService;
import com.winllc.innoutwork.service.CheckInOutService;
import com.winllc.innoutwork.service.LdapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.kerberos.authentication.KerberosTicketValidation;
import org.springframework.security.kerberos.authentication.KerberosTicketValidator;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static com.winllc.innoutwork.security.WindowsAuthTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The check-in calls with Windows sign-in turned on, through the application's own security configuration.
 * The ticket check itself is stubbed here; WindowsAuthKerberosEndToEndTest runs it with real tickets.
 */
@WebMvcTest(properties = "application.windows-auth.enabled=true")
@Import({SecurityConfig.class, WindowsAuthSecurityConfig.class, CheckInOutRestService.class,
        WindowsAuthEnabledSecurityTest.Beans.class})
class WindowsAuthEnabledSecurityTest {

    private static final String TICKET = "Negotiate " + Base64.getEncoder().encodeToString("ticket".getBytes(StandardCharsets.UTF_8));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    AppUserDetailsService appUsers;
    @MockitoBean
    BaseLdapPathContextSource contextSource;
    @MockitoBean
    CheckInOutService checkIns;
    @MockitoBean
    LdapService ldapService;
    @MockitoBean
    KerberosTicketValidator ticketValidator;

    @Configuration
    @EnableWebSecurity
    static class Beans {
        @Bean
        ApplicationProperties applicationProperties() {
            ApplicationProperties properties = new ApplicationProperties();
            properties.setUserBaseDn("dc=winllc,dc=com");
            properties.getWindowsAuth().setEnabled(true);
            properties.getWindowsAuth().setServicePrincipal("HTTP/inout.winllc.com@WINLLC.COM");
            return properties;
        }
    }

    @BeforeEach
    void setUp() {
        appUsersResolveByDn(appUsers);
        recordsAreSaved(checkIns);
        when(ticketValidator.validateTicket(any()))
                .thenReturn(new KerberosTicketValidation("bob@WINLLC.COM", "HTTP/inout.winllc.com@WINLLC.COM", new byte[0], null));
        when(ldapService.lookupUniqueUser("sAMAccountName", "bob"))
                .thenReturn(Optional.of(LdapUser.builder().dn(BOB_DN).build()));
    }

    // --- certificates: unchanged ------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"in", "lock", "unlock"})
    void aCertificateStillSignsInAndTheTicketCodeIsNeverReached(String action) throws Exception {
        mockMvc.perform(post("/api/check/" + action).with(x509(cert(CERT_DN)))
                        .contentType(MediaType.APPLICATION_JSON).content(body("alice")))
                .andExpect(status().isOk());

        CheckInOutRecord saved = savedRecord(checkIns);
        assertEquals(CERT_DN, saved.getDn());
        assertEquals("alice", saved.getWindowsUserId());
        verify(appUsers).loadUserByUsername(CERT_DN);
        verifyNoInteractions(ticketValidator, ldapService);
    }

    /** With both, the certificate decides who it is; the ticket is not even checked. */
    @Test
    void aCertificateWinsOverATicket() throws Exception {
        mockMvc.perform(post("/api/check/in").with(x509(cert(CERT_DN))).header("Authorization", TICKET)
                        .contentType(MediaType.APPLICATION_JSON).content(body("alice")))
                .andExpect(status().isOk());

        assertEquals(CERT_DN, savedRecord(checkIns).getDn());
        verifyNoInteractions(ticketValidator);
    }

    // --- Windows tickets ----------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"in", "lock", "unlock"})
    void aTicketSignsInAsTheMatchingDirectoryUser(String action) throws Exception {
        mockMvc.perform(post("/api/check/" + action).header("Authorization", TICKET)
                        .contentType(MediaType.APPLICATION_JSON).content(body("bob")))
                .andExpect(status().isOk());

        CheckInOutRecord saved = savedRecord(checkIns);
        assertEquals(BOB_DN, saved.getDn());
        verify(appUsers).loadUserByUsername(BOB_DN);
    }

    /** The account recorded is the one Kerberos verified, whatever the request body claims. */
    @Test
    void theRecordedWindowsAccountComesFromTheTicketNotTheBody() throws Exception {
        mockMvc.perform(post("/api/check/in").header("Authorization", TICKET)
                        .contentType(MediaType.APPLICATION_JSON).content(body("mallory")))
                .andExpect(status().isOk());

        assertEquals("bob@WINLLC.COM", savedRecord(checkIns).getWindowsUserId());
    }

    @Test
    void withNeitherTheClientIsAskedForATicket() throws Exception {
        mockMvc.perform(post("/api/check/in").contentType(MediaType.APPLICATION_JSON).content(body("bob")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Negotiate"));

        verify(checkIns, never()).saveCheckInOutRecord(any());
    }

    @Test
    void aTicketThatDoesNotValidateIsRefused() throws Exception {
        when(ticketValidator.validateTicket(any())).thenThrow(new BadCredentialsException("Kerberos validation not successful"));

        mockMvc.perform(post("/api/check/in").header("Authorization", TICKET)
                        .contentType(MediaType.APPLICATION_JSON).content(body("bob")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Negotiate"));

        verify(checkIns, never()).saveCheckInOutRecord(any());
    }

    @Test
    void aValidTicketForAnAccountWithNoDirectoryUserIsRefused() throws Exception {
        when(ldapService.lookupUniqueUser(anyString(), anyString())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/check/in").header("Authorization", TICKET)
                        .contentType(MediaType.APPLICATION_JSON).content(body("bob")))
                .andExpect(status().isUnauthorized());

        verify(checkIns, never()).saveCheckInOutRecord(any());
    }

    @Test
    void aTicketFromAnotherRealmIsRefused() throws Exception {
        when(ticketValidator.validateTicket(any()))
                .thenReturn(new KerberosTicketValidation("bob@PARTNER.COM", "HTTP/inout.winllc.com@WINLLC.COM", new byte[0], null));

        mockMvc.perform(post("/api/check/in").header("Authorization", TICKET)
                        .contentType(MediaType.APPLICATION_JSON).content(body("bob")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(ldapService);
    }

    /** What Windows sends when it has no Kerberos ticket for the URL, e.g. when the URL uses an IP address. */
    @Test
    void ntlmIsNotAccepted() throws Exception {
        mockMvc.perform(post("/api/check/in").header("Authorization", "Negotiate TlRMTVNTUAABAAAAl4II4gAAAAA=")
                        .contentType(MediaType.APPLICATION_JSON).content(body("bob")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Negotiate"));

        verifyNoInteractions(ticketValidator);
    }

    @Test
    void aMalformedNegotiateHeaderIsA401NotAServerError() throws Exception {
        mockMvc.perform(post("/api/check/in").header("Authorization", "Negotiate %%%not-base64%%%")
                        .contentType(MediaType.APPLICATION_JSON).content(body("bob")))
                .andExpect(status().isUnauthorized());
    }

    // --- everything else: unchanged -----------------------------------------------------------------------

    /** Only the three check-in calls take tickets; other URLs keep the sign-in page and ignore the header. */
    @Test
    void otherUrlsIgnoreTicketsAndStillRedirectToSignIn() throws Exception {
        mockMvc.perform(get("/api/check/records").header("Authorization", TICKET))
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/api/check/in").header("Authorization", TICKET))
                .andExpect(redirectedUrl("/login"));

        verifyNoInteractions(ticketValidator);
    }

    @Test
    void checkOutStaysOpenWithoutSignIn() throws Exception {
        mockMvc.perform(post("/api/check/out").contentType(MediaType.APPLICATION_JSON).content(body("bob")))
                .andExpect(status().isOk());
    }
}
