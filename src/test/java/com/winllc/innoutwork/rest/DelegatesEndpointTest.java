package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.controller.DelegatesController;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.DelegateService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.winllc.innoutwork.support.TestCerts.cert;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Delegates are set by administrators only: people can no longer pick their own. */
@WebMvcTest
@Import({UserRestService.class, DelegatesController.class, DelegatesEndpointTest.TestSecurityConfig.class})
class DelegatesEndpointTest {

    private static final String ADMIN = "CN=admin,OU=Users,DC=winllc,DC=com";
    private static final String ALICE = "CN=alice,OU=Users,DC=winllc,DC=com";
    private static final String BOB = "CN=bob,OU=Users,DC=winllc,DC=com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean LdapService ldapService;
    @MockitoBean UserRecordRepository userRecordRepository;
    @MockitoBean CheckInOutRecordRepository checkInOutRecordRepository;
    @MockitoBean UserService userService;
    @MockitoBean(name = "accessPolicy") AccessPolicy accessPolicy;
    @MockitoBean DelegateService delegateService;

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .x509(x509 -> x509.subjectPrincipalRegex("(.*)").userDetailsService(userDetailsService()));
            return http.build();
        }

        @Bean
        UserDetailsService userDetailsService() {
            return username -> User.withUsername(username).password("")
                    .authorities(username.startsWith("CN=admin")
                            ? new String[]{UserRoleEnum.USER.name(), UserRoleEnum.ADMIN.name()}
                            : new String[]{UserRoleEnum.USER.name(), UserRoleEnum.MANAGER.name()})
                    .build();
        }

        @Bean
        ApplicationProperties applicationProperties() {
            return new ApplicationProperties();
        }
    }

    @Test
    void aPersonCannotSetTheirOwnDelegatesAnyMore() throws Exception {
        mockMvc.perform(post("/api/users/managers/update").with(x509(cert(ALICE)))
                        .contentType(MediaType.APPLICATION_JSON).content("[\"" + BOB + "\"]"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/users/managers/update/{dn}", ALICE).with(x509(cert(ALICE)))
                        .contentType(MediaType.APPLICATION_JSON).content("[\"" + BOB + "\"]"))
                .andExpect(status().isForbidden());

        verify(delegateService, never()).setDelegates(anyString(), anyList());
    }

    @Test
    void anAdministratorSetsSomeonesDelegates() throws Exception {
        when(delegateService.setDelegates(eq(ALICE), eq(List.of(BOB))))
                .thenReturn(UserRecord.builder().dn(ALICE).alternateManagers(BOB).build());

        mockMvc.perform(post("/api/users/managers/update/{dn}", ALICE).with(x509(cert(ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content("[\"" + BOB + "\"]"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(BOB)));
    }

    @Test
    void theReviewPageIsForAdministratorsOnly() throws Exception {
        when(delegateService.withDelegates(anyBoolean())).thenReturn(List.of(
                UserRecord.builder().dn(ALICE).alternateManagers(BOB).alternateManagersReviewPending(true).build()));
        when(delegateService.pendingReviewCount()).thenReturn(1L);

        mockMvc.perform(get("/app/admin/delegates").with(x509(cert(ALICE)))).andExpect(status().isForbidden());
        mockMvc.perform(get("/app/admin/delegates").with(x509(cert(ADMIN))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"delegates-to-review\"")))
                .andExpect(content().string(containsString("The person, to review")));

        mockMvc.perform(post("/app/admin/delegates/keep").param("dn", ALICE).with(x509(cert(ALICE))))
                .andExpect(status().isForbidden());
        verify(delegateService, never()).keep(any());
        mockMvc.perform(post("/app/admin/delegates/keep").param("dn", ALICE).with(x509(cert(ADMIN))))
                .andExpect(status().is3xxRedirection());
        verify(delegateService).keep(ALICE);
    }
}
