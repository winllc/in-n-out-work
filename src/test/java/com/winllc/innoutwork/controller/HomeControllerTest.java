package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.TopLevelGroupProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.service.CacheService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.PermissionService;
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

import java.util.List;
import java.util.Optional;

import static com.winllc.innoutwork.controller.ProfileControllerTest.mockCert;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * See {@link ProfileControllerTest} for why the controller has to be imported explicitly.
 */
@WebMvcTest(HomeController.class)
@Import({HomeController.class, HomeControllerTest.TestSecurityConfig.class})
class HomeControllerTest {

    private static final String USER_DN = "CN=alice,OU=Test";
    private static final String GROUPS_BASE_DN = "ou=Groups,dc=winllc,dc=com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppUserDetailsService appUserDetailsService;
    @MockitoBean
    private CacheService cacheService;
    @MockitoBean
    private ApplicationProperties properties;
    @MockitoBean
    private UserRecordRepository userRecordRepository;
    @MockitoBean
    private PermissionService permissionService;
    @MockitoBean
    private LdapService ldapService;
    /** Referenced by name from the @PreAuthorize expression on /app/users/{group}. */
    @MockitoBean(name = "permissionEvaluator")
    private com.winllc.innoutwork.security.PermissionEvaluator permissionEvaluator;

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    // Mirror SecurityConfig: the full subject DN is the username.
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
    void rootRedirectsToGroups() throws Exception {
        mockMvc.perform(get("/").with(x509(mockCert(USER_DN))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/groups"));
    }

    @Test
    void appRedirectsToGroups() throws Exception {
        mockMvc.perform(get("/app").with(x509(mockCert(USER_DN))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/groups"));
    }

    /**
     * A plain USER only sees the parts of the tree their group permissions whitelist.
     */
    @Test
    void groupsRendersTheFilteredTreeForANonAdmin() throws Exception {
        TopLevelGroupProperties topLevel = new TopLevelGroupProperties();
        topLevel.setGroupsBaseDn(GROUPS_BASE_DN);

        when(properties.getGroups()).thenReturn(List.of(topLevel));
        when(properties.isGroupsInitiallyExpanded()).thenReturn(true);
        when(cacheService.getGroup(GROUPS_BASE_DN))
                .thenReturn(new LdapGroup(GROUPS_BASE_DN, "Groups"));
        when(permissionService.getUserGroupPermissions(any())).thenReturn(List.of());
        when(userRecordRepository.findByDnIgnoreCase(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/app/groups").with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk())
                .andExpect(view().name("groups"))
                .andExpect(model().attributeExists("groups"))
                .andExpect(model().attribute("initiallyExpanded", true));
    }

    @Test
    void groupsExposesTheUsersFavouritesWhenARecordExists() throws Exception {
        TopLevelGroupProperties topLevel = new TopLevelGroupProperties();
        topLevel.setGroupsBaseDn(GROUPS_BASE_DN);

        UserRecord record = UserRecord.builder().dn(USER_DN).build();
        record.addGroup(GROUPS_BASE_DN);

        when(properties.getGroups()).thenReturn(List.of(topLevel));
        when(cacheService.getGroup(GROUPS_BASE_DN))
                .thenReturn(new LdapGroup(GROUPS_BASE_DN, "Groups"));
        when(permissionService.getUserGroupPermissions(any())).thenReturn(List.of());
        when(userRecordRepository.findByDnIgnoreCase(anyString())).thenReturn(Optional.of(record));

        mockMvc.perform(get("/app/groups").with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("favoriteMap"));
    }

    /**
     * /app/users/{group} is gated by @PreAuthorize; a plain USER without a matching group
     * permission must not get through.
     */
    @Test
    void usersIsForbiddenForANonAdminWithoutGroupPermission() throws Exception {
        when(permissionEvaluator.groupCheck(anyString(), any())).thenReturn(false);

        mockMvc.perform(get("/app/users/some-group").with(x509(mockCert(USER_DN))))
                .andExpect(status().isForbidden());
    }

    @Test
    void usersRendersWhenTheGroupCheckPasses() throws Exception {
        when(permissionEvaluator.groupCheck(anyString(), any())).thenReturn(true);
        when(cacheService.getGroup("some-group"))
                .thenReturn(new LdapGroup(GROUPS_BASE_DN, "Groups"));
        when(userRecordRepository.findByDnIgnoreCase(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/app/users/some-group").with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk())
                .andExpect(view().name("users"))
                .andExpect(model().attribute("group", "Groups"))
                .andExpect(model().attribute("groupDn", GROUPS_BASE_DN))
                .andExpect(model().attribute("isFavorite", false));
    }

    @Test
    void groupsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/app/groups"))
                .andExpect(status().is4xxClientError());
    }
}
