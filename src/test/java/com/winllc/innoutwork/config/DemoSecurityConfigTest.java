package com.winllc.innoutwork.config;

import com.winllc.innoutwork.controller.LoginController;
import com.winllc.innoutwork.controller.advice.GlobalModelAttributes;
import com.winllc.innoutwork.security.AppUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Demo mode's security boundary.
 *
 * <p>Three things matter and none is visible in a template: the login page still stands in front of
 * the application, a request that could change something is refused, and the sign-in post is not
 * caught by that refusal - without which the published credentials would be unusable and the demo
 * would have no way in at all.
 */
@WebMvcTest(controllers = DemoSecurityConfigTest.DemoProbeController.class)
// The controller is imported as well: @SpringBootApplication lives in this package, which is not a
// parent of the tests, so a @WebMvcTest slice registers no controllers by itself (see README).
@Import({DemoSecurityConfig.class, DemoSecurityConfigTest.DemoTestConfig.class,
        DemoSecurityConfigTest.DemoProbeController.class, LoginController.class,
        GlobalModelAttributes.class})
// Set as properties rather than built in DemoTestConfig: ApplicationProperties is
// @ConfigurationProperties, so Boot binds the environment onto whatever instance the bean method
// returns and an "accounts: []" in application.yml would overwrite a list assembled in Java. This
// also exercises the binding the real configuration goes through.
@TestPropertySource(properties = {
        "application.demo.enabled=true",
        "application.demo.accounts[0].role=Administrator",
        "application.demo.accounts[0].username=demo",
        "application.demo.accounts[0].password=password",
        "application.demo.accounts[0].description=Everything"
})
class DemoSecurityConfigTest {

    /** A signed-in demo visitor: read-only is the chain's job, not a matter of what they hold. */
    private static final org.springframework.test.web.servlet.request.RequestPostProcessor SIGNED_IN =
            user("demo").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ADMIN"));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppUserDetailsService appUserDetailsService;

    @MockitoBean
    private LdapAuthenticationProvider ldapAuthenticationProvider;

    @Test
    void theLoginPageIsServedWithoutCredentials() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isOk());
    }

    /** Demo mode publishes the credentials; it does not remove the sign-in. */
    @Test
    void anApplicationPageStillRequiresSigningIn() throws Exception {
        mockMvc.perform(get("/demo-probe")).andExpect(status().is3xxRedirection());
    }

    @Test
    void aSignedInVisitorCanReadPages() throws Exception {
        mockMvc.perform(get("/demo-probe").with(SIGNED_IN)).andExpect(status().isOk());
    }

    /**
     * The read-only rule refuses posts by method for every path, so it would refuse the sign-in too
     * unless it is let through first - and then there would be no way into the demo.
     */
    /** The point of the tile: a visitor with no account can see what to sign in with. */
    @Test
    void theLoginPageListsTheConfiguredAccounts() throws Exception {
        String page = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(page.contains("Administrator"), "the role is not on the login page");
        assertTrue(page.contains("demo"), "the username is not on the login page");
        assertTrue(page.contains("password"), "the password is not on the login page");
    }

    @Test
    void theSignInPostIsNotCaughtByTheReadOnlyRule() throws Exception {
        mockMvc.perform(post("/login").param("username", "demo").param("password", "password"))
                .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus(),
                        "the login post must not be refused, or the published credentials are unusable"));
    }

    @Test
    void theSignOutPostIsNotCaughtEither() throws Exception {
        mockMvc.perform(post("/logout"))
                .andExpect(result -> assertNotEquals(403, result.getResponse().getStatus()));
    }

    @Test
    void postIsRefusedEvenForASignedInVisitor() throws Exception {
        mockMvc.perform(post("/demo-probe").with(SIGNED_IN)).andExpect(status().isForbidden());
    }

    @Test
    void putIsRefused() throws Exception {
        mockMvc.perform(put("/demo-probe").with(SIGNED_IN)).andExpect(status().isForbidden());
    }

    @Test
    void deleteIsRefused() throws Exception {
        mockMvc.perform(delete("/demo-probe").with(SIGNED_IN)).andExpect(status().isForbidden());
    }

    /** A path nobody thought to hide is refused too, because the rule is by method, not by path. */
    @Test
    void anUnknownWritePathIsRefusedRatherThanReaching404() throws Exception {
        mockMvc.perform(post("/some/endpoint/added/later").with(SIGNED_IN)).andExpect(status().isForbidden());
    }

    /** A demo is usually reachable by people who should not read the configuration. */
    @Test
    void actuatorIsRefusedApartFromHealth() throws Exception {
        mockMvc.perform(get("/actuator/env").with(SIGNED_IN)).andExpect(status().isForbidden());
    }

    @Configuration
    @EnableWebSecurity
    static class DemoTestConfig {

        @Bean
        ApplicationProperties applicationProperties() {
            // Bound from the properties above by Boot's @ConfigurationProperties processing.
            return new ApplicationProperties();
        }
    }

    @RestController
    static class DemoProbeController {

        @GetMapping("/demo-probe")
        String read() {
            return "read";
        }

        @PostMapping("/demo-probe")
        String create() {
            return "written";
        }

        @PutMapping("/demo-probe")
        String replace() {
            return "written";
        }

        @DeleteMapping("/demo-probe")
        String remove() {
            return "written";
        }
    }
}
