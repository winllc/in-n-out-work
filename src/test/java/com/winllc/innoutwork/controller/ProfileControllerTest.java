package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = ProfileController.class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {ProfileController.class})
class ProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    UserRecordRepository recordRepository;

    @MockBean
    UserService userRecordService;

    @MockBean
    LdapService ldapService;

    @MockBean
    ApplicationProperties properties;

    @Configuration
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .x509(x509 -> x509.subjectPrincipalRegex("CN=(.*?)(?:,|$)"));
            return http.build();
        }

        @Bean
        UserDetailsService userDetailsService() {
            // Accept the CN extracted by subjectPrincipalRegex and mark the user authenticated.
            return username -> User.withUsername(username)
                    .password("{noop}n/a")
                    .roles("USER")
                    .build();
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Test
    void printMappings() {
        RequestMappingHandlerMapping mapping =
                context.getBean(RequestMappingHandlerMapping.class);

        mapping.getHandlerMethods().forEach((k, v) ->
                System.out.println(k + " -> " + v)
        );
    }

    @Test
    void mvcExists() {
        System.out.println(Arrays.toString(
                context.getBeanNamesForType(RequestMappingHandlerMapping.class)
        ));
    }

    @Test
    @WithMockUser(username = "CN=alice, OU=Test")
    void profile() throws Exception {
        X509Certificate cert = mockCert("CN=alice, OU=Test");

        when(recordRepository.findByDnIgnoreCase(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/app/profile").with(x509(cert)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(view().name("profile"));
    }

    @Test
    @WithMockUser(username = "CN=alice, OU=Test", authorities = {"T(com.winllc.innoutwork.constant.UserRoleEnum).USER)"})
    void profileSubmit() throws Exception {
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=alice, OU=Test"));

        when(recordRepository.findByDnIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(userRecordService.updateProfile(any(), any())).thenReturn(new UserRecord());

        mockMvc.perform(post("/app/profile")
                        .param("notes", "updated notes")
                        .param("status", "ACTIVE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/profile"));
    }

    private static X509Certificate mockCert(String dn) {
        X509Certificate cert = mock(X509Certificate.class);
        X500Principal principal = new X500Principal(dn);

        // Some Spring Security paths use SubjectDN; others use SubjectX500Principal.
        when(cert.getSubjectDN()).thenReturn(principal);
        when(cert.getSubjectX500Principal()).thenReturn(principal);

        return cert;
    }
}