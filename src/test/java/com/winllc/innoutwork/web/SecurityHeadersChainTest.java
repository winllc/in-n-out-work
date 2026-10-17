package com.winllc.innoutwork.web;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.EnsProperties;
import com.winllc.innoutwork.config.SecurityConfig;
import com.winllc.innoutwork.config.SecurityHeadersConfig;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.controller.LoginController;
import com.winllc.innoutwork.controller.advice.GlobalModelAttributes;
import com.winllc.innoutwork.license.LicensingConfiguration;
import com.winllc.innoutwork.license.TestLicensing;
import com.winllc.innoutwork.security.AppUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The security headers through the application's own {@link SecurityConfig}: Spring Security's header writers
 * leave the filter's values alone (one value each, not two), and a rendered page's inline scripts carry exactly
 * the nonce its policy allows.
 */
@WebMvcTest
@Import({SecurityConfig.class, SecurityHeadersConfig.class, GlobalModelAttributes.class, LicensingConfiguration.class,
        TestLicensing.class, LoginController.class, SecurityHeadersChainTest.PageController.class,
        SecurityHeadersChainTest.Beans.class})
class SecurityHeadersChainTest {

    private static final String USER_DN = "CN=alice,OU=Users,DC=winllc,DC=com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    AppUserDetailsService appUserDetailsService;
    @MockitoBean
    BaseLdapPathContextSource contextSource;

    @Controller
    static class PageController {
        @GetMapping("/render/page")
        String page() {
            return "usersearch";
        }
    }

    @Configuration
    @EnableWebSecurity
    static class Beans {
        @Bean
        ApplicationProperties applicationProperties() {
            ApplicationProperties properties = new ApplicationProperties();
            properties.setUserBaseDn("dc=winllc,dc=com");
            return properties;
        }

        @Bean
        EnsProperties ensProperties() {
            return new EnsProperties();
        }
    }

    @Test
    void eachHeaderIsSentOnceWithTheBaselineValue() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/login").secure(true)).andReturn().getResponse();

        assertEquals(List.of("DENY"), response.getHeaders("X-Frame-Options"));
        assertEquals(List.of("nosniff"), response.getHeaders("X-Content-Type-Options"));
        assertEquals(List.of("max-age=31536000; includeSubDomains"), response.getHeaders("Strict-Transport-Security"));
        assertEquals(List.of("strict-origin-when-cross-origin"), response.getHeaders("Referrer-Policy"));
        assertEquals(List.of("camera=(), microphone=(), geolocation=(), payment=()"),
                response.getHeaders("Permissions-Policy"));
        assertEquals(1, response.getHeaders("Content-Security-Policy").size());
    }

    @Test
    void plainHttpGetsNoStrictTransportSecurity() throws Exception {
        assertNull(mockMvc.perform(get("/login")).andReturn().getResponse().getHeader("Strict-Transport-Security"));
    }

    @Test
    void theRenderedPagesInlineScriptsCarryThePoliciesNonce() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/render/page")
                        .with(user(USER_DN).authorities(() -> UserRoleEnum.USER.name())))
                .andReturn().getResponse();

        Matcher policy = Pattern.compile("'nonce-([^']+)'").matcher(response.getHeader("Content-Security-Policy"));
        assertTrue(policy.find());
        String nonce = policy.group(1);
        Matcher scripts = Pattern.compile("<script\\b([^>]*)>").matcher(response.getContentAsString());
        int inline = 0;
        while (scripts.find()) {
            String attributes = scripts.group(1);
            if (!attributes.contains("src=")) {
                inline++;
                assertTrue(attributes.contains("nonce=\"" + nonce + "\""), attributes);
            }
        }
        assertTrue(inline > 0, "layout.html has inline scripts");
        assertFalse(response.getContentAsString().contains("onclick="));
    }
}
