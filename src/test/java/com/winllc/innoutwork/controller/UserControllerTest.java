package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.security.PermissionEvaluator;
import com.winllc.innoutwork.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
 * The controller is imported explicitly for the reason documented on {@link ProfileControllerTest}:
 * the {@code @SpringBootApplication} is not in a parent package of the tests, so a {@code @WebMvcTest}
 * slice registers no controllers on its own.
 */
@WebMvcTest(UserController.class)
@Import({UserController.class, UserControllerTest.TestSecurityConfig.class})
class UserControllerTest {

    private static final String ADMIN_DN  = "CN=admin,OU=Users,DC=winllc,DC=com";
    private static final String TARGET_DN = "CN=user1,OU=Users,DC=winllc,DC=com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    UserService userRecordService;
    @MockitoBean
    PermissionEvaluator permissionEvaluator;

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity(prePostEnabled = true)
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // Mirror SecurityConfig: the full subject DN is the username.
                .x509(x509 -> x509.subjectPrincipalRegex("(.*)")
                        .userDetailsService(userDetailsService()));
            return http.build();
        }

        /** Grants whatever role the certificate CN asks for, so a test can pick its authority. */
        @Bean
        public UserDetailsService userDetailsService() {
            return username -> User.withUsername(username).password("")
                    .authorities(username.startsWith("CN=admin") ? UserRoleEnum.ADMIN.name()
                                                                 : UserRoleEnum.USER.name())
                    .build();
        }
    }

    /**
     * Regression test. The role form used to bind the whole {@code UserStatus} read model, which
     * {@code @Builder} had left without a no-args constructor; Spring bound it through the all-args
     * constructor instead and its primitive {@code id} field, absent from the form, could not
     * convert from null, so every submission failed with 400 before reaching the service.
     */
    @Test
    void updatingARoleSavesItAndRedirectsBackToTheUser() throws Exception {
        when(userRecordService.updateRole(any(), any())).thenReturn(new UserRecord());

        mockMvc.perform(post("/app/user/details")
                        .with(x509(cert(ADMIN_DN)))
                        .param("dn", TARGET_DN)
                        .param("role", UserRoleEnum.MANAGER.name()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/user/details/" + TARGET_DN));

        ArgumentCaptor<LdapDn> dn = ArgumentCaptor.forClass(LdapDn.class);
        ArgumentCaptor<UserRoleEnum> role = ArgumentCaptor.forClass(UserRoleEnum.class);
        verify(userRecordService).updateRole(dn.capture(), role.capture());

        assertEquals(TARGET_DN, dn.getValue().dn());
        assertEquals(UserRoleEnum.MANAGER, role.getValue());
    }

    /** A value outside the enum is ignored rather than blowing up on valueOf(). */
    @Test
    void anUnknownRoleIsIgnored() throws Exception {
        mockMvc.perform(post("/app/user/details")
                        .with(x509(cert(ADMIN_DN)))
                        .param("dn", TARGET_DN)
                        .param("role", "NOT_A_ROLE"))
                .andExpect(status().is3xxRedirection());

        verify(userRecordService, never()).updateRole(any(), any());
    }

    /** The update is admin-only; a plain USER must not be able to change roles. */
    @Test
    void aNonAdminCannotUpdateARole() throws Exception {
        mockMvc.perform(post("/app/user/details")
                        .with(x509(cert(TARGET_DN)))
                        .param("dn", TARGET_DN)
                        .param("role", UserRoleEnum.ADMIN.name()))
                .andExpect(status().isForbidden());

        verify(userRecordService, never()).updateRole(any(), any());
    }

    /** The page the update redirects to must actually resolve for a DN-shaped path variable. */
    @Test
    void theUserDetailsPageRendersForADnPath() throws Exception {
        when(userRecordService.getUserDetails(any(), any()))
                .thenReturn(UserStatus.builder().dn(TARGET_DN).status("IN").build());

        mockMvc.perform(get("/app/user/details/{dn}", TARGET_DN).with(x509(cert(ADMIN_DN))))
                .andExpect(status().isOk())
                .andExpect(view().name("userdetails"))
                .andExpect(model().attributeExists("user", "roles", "isGroupManager", "roleForm"));
    }

    /**
     * The form binds a narrow command object, so fields belonging to the rendered read model are
     * ignored rather than silently accepted from the request.
     */
    @Test
    void fieldsOutsideTheRoleFormAreNotBound() throws Exception {
        when(userRecordService.updateRole(any(), any())).thenReturn(new UserRecord());

        mockMvc.perform(post("/app/user/details")
                        .with(x509(cert(ADMIN_DN)))
                        .param("dn", TARGET_DN)
                        .param("role", UserRoleEnum.MANAGER.name())
                        // none of these exist on RoleUpdateForm; binding must ignore them
                        .param("notes", "injected")
                        .param("organization", "injected")
                        .param("status", "injected"))
                .andExpect(status().is3xxRedirection());

        verify(userRecordService).updateRole(any(), any());
    }

    /** The reports page is open to any authenticated user, since reporting comes from the
     *  directory rather than the application role. */
    @Test
    void theReportsPageRendersForAPlainUser() throws Exception {
        when(userRecordService.getDirectReports(any(), any())).thenReturn(List.of(
                UserStatus.builder().dn("cn=bob,ou=Users,dc=winllc,dc=com").status("IN").build()));

        mockMvc.perform(get("/app/user/reports").with(x509(cert(TARGET_DN))))
                .andExpect(status().isOk())
                .andExpect(view().name("myreports"))
                .andExpect(model().attribute("reportCount", 1))
                .andExpect(model().attribute("managerCn", "user1"));
    }

    /** A user with nobody reporting to them still gets the page, with the empty state. */
    @Test
    void theReportsPageRendersWhenThereAreNoReports() throws Exception {
        when(userRecordService.getDirectReports(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/app/user/reports").with(x509(cert(ADMIN_DN))))
                .andExpect(status().isOk())
                .andExpect(view().name("myreports"))
                .andExpect(model().attribute("reportCount", 0));
    }

    static X509Certificate cert(String dn) {
        X509Certificate c = mock(X509Certificate.class);
        X500Principal p = new X500Principal(dn);
        // Spring Security reads both depending on the code path.
        when(c.getSubjectDN()).thenReturn(p);
        when(c.getSubjectX500Principal()).thenReturn(p);
        return c;
    }
}
