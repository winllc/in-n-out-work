package com.winllc.innoutwork.license;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.controller.LoginController;
import com.winllc.innoutwork.controller.advice.GlobalModelAttributes;
import com.winllc.innoutwork.rest.CheckInOutRestService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * What the license tests share: the licensing wiring with the test-key {@link TestLicensing} manager,
 * stand-ins on the real URLs carrying the same {@link RequiresFeature} as the real controllers
 * (FeatureMappingTest checks those), the real check-in API, and a security chain that lets anyone
 * signed in through (and the login page to anyone).
 */
final class LicenseGatingSupport {

    static final RequestPostProcessor ADMIN = user("CN=admin,OU=Users,DC=winllc,DC=com")
            .authorities(new SimpleGrantedAuthority(UserRoleEnum.ADMIN.name()),
                    new SimpleGrantedAuthority(UserRoleEnum.USER.name()));
    static final RequestPostProcessor USER = user("CN=bob,OU=Users,DC=winllc,DC=com")
            .authorities(new SimpleGrantedAuthority(UserRoleEnum.USER.name()));

    private LicenseGatingSupport() {
    }

    /** The slice: import this from a nested @Configuration of each test (see the README's @WebMvcTest note). */
    @Configuration
    @Import({LicensingConfiguration.class, TestLicensing.class, LicenseExceptionHandler.class, LicenseController.class,
            LicenseRestService.class, GlobalModelAttributes.class, LoginController.class, CheckInOutRestService.class,
            Pages.class, Beans.class})
    static class Slice {
    }

    @Controller
    static class Pages {
        /** A core page built on layout.html, so the sidebar and banners are rendered. */
        @GetMapping("/app/user/search")
        String core() {
            return "usersearch";
        }

        @PostMapping("/app/profile")
        String saveProfile() {
            return "redirect:/app/profile";
        }

        @GetMapping("/app/metrics")
        @RequiresFeature(LicensedFeature.METRICS)
        String metrics() {
            return "usersearch";
        }

        @PostMapping("/app/group/report")
        @RequiresFeature(LicensedFeature.METRICS)
        String groupReport() {
            return "usersearch";
        }

        @GetMapping("/app/orgchart")
        @RequiresFeature(LicensedFeature.ORG_CHART)
        String orgChart() {
            return "usersearch";
        }

        @GetMapping("/api/users/reports")
        @RequiresFeature(LicensedFeature.METRICS)
        @ResponseBody
        Map<String, Object> reports() {
            return Map.of("data", List.of());
        }

        @GetMapping("/api/orgnodes/hierarchy")
        @RequiresFeature(LicensedFeature.ORG_CHART)
        @ResponseBody
        Map<String, Object> orgNodes() {
            return Map.of();
        }

        @GetMapping("/api/event/all")
        @RequiresFeature(LicensedFeature.CALENDAR)
        @ResponseBody
        List<Object> events() {
            return List.of();
        }

        /** A person's export: ungated, like the audit log (LicensingConfiguration.PRIVACY_SCREEN). */
        @GetMapping("/app/privacy/export")
        @ResponseBody
        Map<String, Object> privacyExport() {
            return Map.of("format", "readyroom-personal-data/1");
        }

        /** An administrator's erasure: ungated, writes included. */
        @PostMapping("/app/admin/privacy/erase")
        String erase() {
            return "redirect:/app/admin/privacy";
        }
    }

    @Configuration
    @EnableWebSecurity
    static class Beans {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/login", "/error").permitAll()
                            .anyRequest().authenticated())
                    .formLogin(form -> form.loginPage("/login").permitAll());
            return http.build();
        }

        @Bean
        ApplicationProperties applicationProperties() {
            return new ApplicationProperties();
        }
    }
}
