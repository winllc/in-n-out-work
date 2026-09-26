package com.winllc.innoutwork.config;

import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.security.AppUserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Demo mode's security boundary.
 *
 * <p>Two properties matter and neither is visible in a template: a request with no credentials is
 * served, and a request that could change something is not. The second is what makes "read only"
 * true - the disabled buttons in the layout are a courtesy, and anyone with curl ignores them.
 */
@WebMvcTest(controllers = DemoSecurityConfigTest.DemoProbeController.class)
// The controller is imported as well: @SpringBootApplication lives in this package, which is not a
// parent of the tests, so a @WebMvcTest slice registers no controllers by itself (see README).
@Import({DemoSecurityConfig.class, DemoSecurityConfigTest.DemoTestConfig.class,
        DemoSecurityConfigTest.DemoProbeController.class})
@TestPropertySource(properties = {
        "application.demo.enabled=true",
        "application.demo.user-dn=cn=Demo User,ou=Users,dc=winllc,dc=com"
})
class DemoSecurityConfigTest {

    private static final String DEMO_DN = "cn=Demo User,ou=Users,dc=winllc,dc=com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppUserDetailsService appUserDetailsService;

    @BeforeEach
    void setUp() {
        UserRecord record = new UserRecord();
        record.setDn(DEMO_DN);
        AppUserDetails details = new AppUserDetails(record);
        details.addAuthority(UserRoleEnum.USER.name());

        when(appUserDetailsService.loadUserByUsername(anyString())).thenReturn(details);
    }

    @Test
    void aPageIsServedWithNoCredentialsAtAll() throws Exception {
        mockMvc.perform(get("/demo-probe"))
                .andExpect(status().isOk());
    }

    @Test
    void theRequestArrivesAsTheDemoUserWithAdministratorRights() throws Exception {
        mockMvc.perform(get("/demo-probe"))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals(DEMO_DN + ":true",
                        result.getResponse().getContentAsString()));
    }

    @Test
    void postIsRefused() throws Exception {
        mockMvc.perform(post("/demo-probe")).andExpect(status().isForbidden());
    }

    @Test
    void putIsRefused() throws Exception {
        mockMvc.perform(put("/demo-probe")).andExpect(status().isForbidden());
    }

    @Test
    void deleteIsRefused() throws Exception {
        mockMvc.perform(delete("/demo-probe")).andExpect(status().isForbidden());
    }

    /**
     * A path nobody thought to hide is still refused, because the rule is by method rather than by
     * path. This one has no controller behind it at all.
     */
    @Test
    void anUnknownWritePathIsRefusedRatherThanReaching404() throws Exception {
        mockMvc.perform(post("/some/endpoint/added/later")).andExpect(status().isForbidden());
    }

    /** The demo is unauthenticated and usually public; actuator exposes env in this application. */
    @Test
    void actuatorIsRefusedApartFromHealth() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
    }

    @Test
    void enablingDemoModeWithoutAUserDnRefusesToStart() {
        ApplicationProperties properties = new ApplicationProperties();
        properties.getDemo().setEnabled(true);
        properties.getDemo().setUserDn("   ");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new DemoSecurityConfig()
                        .demoAuthenticationFilter(mock(AppUserDetailsService.class), properties));

        assertEquals(true, thrown.getMessage().contains("application.demo.user-dn"));
    }

    @Test
    void demoModeIsOffUnlessAskedFor() {
        assertEquals(false, new ApplicationProperties().getDemo().isEnabled());
    }

    @Configuration
    @EnableWebSecurity
    static class DemoTestConfig {

        @Bean
        ApplicationProperties applicationProperties() {
            ApplicationProperties properties = new ApplicationProperties();
            properties.getDemo().setEnabled(true);
            properties.getDemo().setUserDn(DEMO_DN);
            return properties;
        }
    }

    /** Stands in for the real pages: echoes who the request arrived as, and offers write methods. */
    @RestController
    static class DemoProbeController {

        @GetMapping("/demo-probe")
        String read(org.springframework.security.core.Authentication authentication) {
            boolean admin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals(UserRoleEnum.ADMIN.name()));
            return authentication.getName() + ":" + admin;
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
