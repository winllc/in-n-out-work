package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.ProfileForm;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The controller under test is imported explicitly: this project's {@code @SpringBootApplication}
 * lives in {@code com.winllc.innoutwork.config}, which is not a parent package of the tests, so the
 * nested {@code @Configuration} below becomes the slice's only configuration class and no component
 * scan of the app package happens. Without the explicit import no controller is registered and
 * every request 404s.
 */
@WebMvcTest(ProfileController.class)
@Import({ProfileController.class, ProfileControllerTest.TestSecurityConfig.class})
class ProfileControllerTest {

    private static final String USER_DN = "CN=alice,OU=Test";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    UserRecordRepository recordRepository;

    @MockitoBean
    UserService userRecordService;

    @MockitoBean
    LdapService ldapService;

    @MockitoBean
    ApplicationProperties properties;

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity(prePostEnabled = true)
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http
                    .csrf(csrf -> csrf.disable()) // Disable CSRF for tests
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    // Mirror SecurityConfig: the full subject DN is the username, which the
                    // controllers then parse as an LdapDn.
                    .x509(x509 -> x509.subjectPrincipalRegex("(.*)")
                            .userDetailsService(userDetailsService()));
            return http.build();
        }

        @Bean
        public UserDetailsService userDetailsService() {
            return username -> User.withUsername(username)
                    .password("")
                    .authorities(UserRoleEnum.USER.name())
                    .build();
        }
    }

    @Test
    void profileRendersForAUserWithNoStoredRecord() throws Exception {
        when(recordRepository.findByDnIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(userRecordService.getUserDetails(any(), any()))
                .thenReturn(UserStatus.builder().dn(USER_DN).status("NONE").build());

        mockMvc.perform(get("/app/profile").with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk())
                .andExpect(view().name("profile"))
                .andExpect(model().attributeExists("form", "statuses", "userDn", "userCn"))
                .andExpect(model().attribute("userDn", USER_DN))
                .andExpect(model().attribute("userCn", "alice"));
    }

    /**
     * When a record exists the form is pre-populated from it.
     */
    @Test
    void profilePrefillsTheFormFromTheStoredRecord() throws Exception {
        UserRecord record = UserRecord.builder().dn("alice").notes("stored notes").build();

        when(recordRepository.findByDnIgnoreCase(anyString())).thenReturn(Optional.of(record));
        when(userRecordService.getUserDetails(any(), any()))
                .thenReturn(UserStatus.builder().dn("alice").status("IN").build());

        mockMvc.perform(get("/app/profile").with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk())
                .andExpect(view().name("profile"))
                .andExpect(result -> {
                    ProfileForm form = (ProfileForm) result.getModelAndView().getModel().get("form");
                    org.junit.jupiter.api.Assertions.assertEquals("stored notes", form.getNotes());
                });
    }

    @Test
    void profileSubmitUpdatesTheProfileAndRedirects() throws Exception {
        when(userRecordService.updateProfile(any(), any())).thenReturn(new UserRecord());

        mockMvc.perform(post("/app/profile")
                        .with(x509(mockCert(USER_DN)))
                        .param("notes", "updated notes")
                        .param("loginTime", "08:30:00"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/profile"));

        verify(userRecordService).updateProfile(any(), any());
    }

    @Test
    void profileRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/app/profile"))
                .andExpect(status().is4xxClientError());
    }

    static X509Certificate mockCert(String dn) {
        X509Certificate cert = mock(X509Certificate.class);
        X500Principal principal = new X500Principal(dn);

        // Some Spring Security paths use SubjectDN; others use SubjectX500Principal.
        when(cert.getSubjectDN()).thenReturn(principal);
        when(cert.getSubjectX500Principal()).thenReturn(principal);

        return cert;
    }
}
