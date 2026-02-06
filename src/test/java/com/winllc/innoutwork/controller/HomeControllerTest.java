package com.winllc.innoutwork.controller;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.SecurityConfig;
import com.winllc.innoutwork.config.TopLevelGroupProperties;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.winllc.innoutwork.controller.ProfileControllerTest.mockCert;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(HomeController.class)
@Import(HomeControllerTest.TestSecurityConfig.class)
class HomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppUserDetailsService appUserDetailsService;
    @MockitoBean
    private CacheService cacheService;
    @MockitoBean
    private LoadingCache<String, List<LdapGroup>> groupCache;

    @MockitoBean
    private ApplicationProperties properties;
    @MockitoBean
    private UserRecordRepository userRecordRepository;
    @MockitoBean
    private PermissionService permissionService;
    @MockitoBean
    private LdapService ldapService;



    @Test
        // Remove @WithMockUser to test the actual X509 flow
    void homePageLoadsWithX509() throws Exception {
        X509Certificate cert = mockCert("CN=alice");

        mockMvc.perform(get("/")
                        .with(x509(cert))) // This triggers the X509AuthenticationFilter
                .andExpect(status().isOk());
    }

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http

                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .x509(x509 -> x509.subjectPrincipalRegex("CN=(.*?)(?:,|$)")
                            .userDetailsService(userDetailsService()));
            return http.build();
        }

        @Bean
        public UserDetailsService userDetailsService() {
            // Must return a user that matches the CN extracted (e.g., "alice")
            return username -> User.withUsername(username)
                    .password("")
                    .roles("USER")
                    .build();
        }
    }


}
