package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.SecurityConfig;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.controller.advice.GlobalModelAttributes;
import com.winllc.innoutwork.security.AppUserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;

import static com.winllc.innoutwork.controller.ProfileControllerTest.mockCert;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Logout from the user menu, run through the application's own {@link SecurityConfig} rather than
 * a test copy, so the logout URL, redirect and session handling checked here are the real ones.
 * <p>
 * The menu entry appears only for username/password sessions. A certificate session has nothing to
 * log out of: the browser presents the certificate again and is signed straight back in.
 */
@WebMvcTest
@Import({SecurityConfig.class, GlobalModelAttributes.class, LoginController.class,
        LogoutTest.PageController.class, LogoutTest.Beans.class})
class LogoutTest {

    private static final String USER_DN = "CN=alice,OU=Users,DC=winllc,DC=com";
    private static final String LOGOUT_FORM = "id=\"logout-form\"";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    AppUserDetailsService appUserDetailsService;
    @MockitoBean
    BaseLdapPathContextSource contextSource;

    /** A page built on layout.html, which carries the user menu. */
    @Controller
    static class PageController {
        @GetMapping("/render/page")
        String page() {
            return "usersearch";
        }
    }

    /** SecurityConfig leaves enabling web security to Boot, which this slice does not do. */
    @Configuration
    @EnableWebSecurity
    static class Beans {
        /** Read while SecurityConfig builds its LDAP provider, before a mock could be stubbed. */
        @Bean
        ApplicationProperties applicationProperties() {
            ApplicationProperties properties = new ApplicationProperties();
            properties.setUserBaseDn("dc=winllc,dc=com");
            return properties;
        }
    }

    @BeforeEach
    void setUp() {
        when(appUserDetailsService.loadUserByUsername(anyString())).thenAnswer(inv ->
                User.withUsername(inv.getArgument(0)).password("").authorities(UserRoleEnum.USER.name()).build());
    }

    @Test
    void aPasswordSessionHasALogoutInTheUserMenu() throws Exception {
        mockMvc.perform(get("/render/page").with(user(USER_DN).authorities(() -> UserRoleEnum.USER.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(LOGOUT_FORM)))
                .andExpect(content().string(containsString("action=\"/logout\"")))
                .andExpect(content().string(containsString("method=\"post\"")));
    }

    @Test
    void aCertificateSessionHasNoLogout() throws Exception {
        mockMvc.perform(get("/render/page").with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("My Profile")))
                .andExpect(content().string(not(containsString(LOGOUT_FORM))));
    }

    @Test
    void loggingOutEndsTheSessionAndReturnsToTheSignInPage() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/logout").session(session).with(user(USER_DN)))
                .andExpect(redirectedUrl("/login?logout"));

        assertTrue(session.isInvalid(), "the HTTP session should be invalidated");
    }

    @Test
    void theSignInPageConfirmsTheLogout() throws Exception {
        mockMvc.perform(get("/login").param("logout", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("You have been signed out.")));
    }

    /** Once logged out, the next page asks for credentials again instead of rendering. */
    @Test
    void withoutASessionPagesRedirectToSignIn() throws Exception {
        mockMvc.perform(get("/render/page"))
                .andExpect(redirectedUrl("/login"));
    }
}
