package com.winllc.innoutwork.license;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * What the gating tests share: stand-ins on the real URLs (the gate is by path, so what answers behind
 * it does not matter), and a security chain that only asks for a signed-in user.
 */
final class LicenseGatingSupport {

    static final RequestPostProcessor ADMIN = user("CN=admin,OU=Users,DC=winllc,DC=com")
            .authorities(new SimpleGrantedAuthority(UserRoleEnum.ADMIN.name()));
    static final RequestPostProcessor USER = user("CN=bob,OU=Users,DC=winllc,DC=com")
            .authorities(new SimpleGrantedAuthority(UserRoleEnum.USER.name()));

    private LicenseGatingSupport() {
    }

    @Controller
    static class Pages {
        /** A core page built on layout.html, so the sidebar is rendered. */
        @GetMapping("/app/user/search")
        String core() {
            return "usersearch";
        }

        @GetMapping("/app/metrics")
        String metrics() {
            return "usersearch";
        }

        @PostMapping("/app/group/report")
        String groupReport() {
            return "usersearch";
        }

        @GetMapping("/app/orgchart")
        String orgChart() {
            return "usersearch";
        }

        @GetMapping("/api/users/reports")
        @ResponseBody
        Map<String, Object> reports() {
            return Map.of("data", java.util.List.of());
        }

        @GetMapping("/api/orgnodes/hierarchy")
        @ResponseBody
        Map<String, Object> orgNodes() {
            return Map.of();
        }

        @GetMapping("/api/check/records")
        @ResponseBody
        Map<String, Object> checkInRecords() {
            return Map.of();
        }
    }

    @Configuration
    @EnableWebSecurity
    static class Beans {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
            return http.build();
        }

        @Bean
        ApplicationProperties applicationProperties() {
            return new ApplicationProperties();
        }
    }
}
