package com.winllc.innoutwork.config;

import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.controller.UserController;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.rest.CheckInOutRestService;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.CheckInOutService;
import com.winllc.innoutwork.service.DirectReportsService;
import com.winllc.innoutwork.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-site request forgery, through the application's own {@link SecurityConfig}: a post another site's page
 * makes in a signed-in administrator's browser carries their session but not the token, and is refused. The
 * Windows client's API is the one exemption.
 */
@WebMvcTest
@Import({SecurityConfig.class, UserController.class, CheckInOutRestService.class, CsrfProtectionTest.Beans.class})
class CsrfProtectionTest {

    private static final String ADMIN_DN = "CN=admin,OU=Users,DC=winllc,DC=com";
    private static final String TARGET_DN = "CN=user1,OU=Users,DC=winllc,DC=com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean AppUserDetailsService appUsers;
    @MockitoBean BaseLdapPathContextSource contextSource;
    @MockitoBean UserService userService;
    @MockitoBean AccessPolicy accessPolicy;
    @MockitoBean DirectReportsService directReportsService;
    @MockitoBean com.winllc.innoutwork.service.Approvers approvers;
    @MockitoBean CheckInOutService checkIns;

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
        when(appUsers.loadUserByUsername(anyString())).thenAnswer(inv -> {
            String dn = inv.getArgument(0);
            return User.withUsername(dn).password("")
                    .authorities(dn.equals(ADMIN_DN) ? new String[]{"USER", "ADMIN"} : new String[]{"USER"})
                    .build();
        });
        when(userService.updateRole(any(), any())).thenReturn(new UserRecord());
        when(checkIns.saveCheckInOutRecord(any())).thenAnswer(inv -> inv.<CheckInOutRecord>getArgument(0));
        when(checkIns.saveSessionCheckOut(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new CheckInOutService.SessionCheckOut.UnknownSession());
    }

    /** The role change a forged form used to make: role=ADMIN for whoever the attacker names. */
    @Test
    void aRoleChangeWithoutTheTokenIsRefused() throws Exception {
        mockMvc.perform(post("/app/user/details").with(x509(cert(ADMIN_DN)))
                        .param("dn", TARGET_DN)
                        .param("role", UserRoleEnum.ADMIN.name()))
                .andExpect(status().isForbidden());

        verify(userService, never()).updateRole(any(), any());
    }

    @Test
    void aRoleChangeWithAWrongTokenIsRefused() throws Exception {
        mockMvc.perform(post("/app/user/details").with(x509(cert(ADMIN_DN))).with(csrf().useInvalidToken())
                        .param("dn", TARGET_DN)
                        .param("role", UserRoleEnum.ADMIN.name()))
                .andExpect(status().isForbidden());

        verify(userService, never()).updateRole(any(), any());
    }

    @Test
    void aRoleChangeWithTheTokenWorks() throws Exception {
        mockMvc.perform(post("/app/user/details").with(x509(cert(ADMIN_DN))).with(csrf())
                        .param("dn", TARGET_DN)
                        .param("role", UserRoleEnum.MANAGER.name()))
                .andExpect(redirectedUrl("/app/user/details/" + TARGET_DN));

        verify(userService).updateRole(any(), any());
    }

    /** The token is the session's: sent in the header, as layout.html's script sends it, it works too. */
    @Test
    void theTokenIsAcceptedInTheHeader() throws Exception {
        mockMvc.perform(post("/app/user/details").with(x509(cert(ADMIN_DN))).with(csrf().asHeader())
                        .param("dn", TARGET_DN)
                        .param("role", UserRoleEnum.MANAGER.name()))
                .andExpect(redirectedUrl("/app/user/details/" + TARGET_DN));
    }

    /** The Windows client has no browser session to take a token from. */
    @Test
    void theWindowsClientsCallsNeedNoToken() throws Exception {
        mockMvc.perform(post("/api/check/in").with(x509(cert(TARGET_DN)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"windowsUserId\":\"user1\"}"))
                .andExpect(status().isOk());

        // The logoff is refused for its unknown session id, not for want of a token.
        mockMvc.perform(post("/api/check/out")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"unknown\"}"))
                .andExpect(status().isUnauthorized());
    }

    static X509Certificate cert(String dn) {
        X509Certificate c = mock(X509Certificate.class);
        X500Principal p = new X500Principal(dn);
        when(c.getSubjectDN()).thenReturn(p);
        when(c.getSubjectX500Principal()).thenReturn(p);
        return c;
    }
}
