package com.winllc.innoutwork.config;

import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.security.DemoAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Demo mode: the application with no sign-in, shown as an administrator, and read-only.
 *
 * <p>Active only with {@code application.demo.enabled: true}, and mutually exclusive with the normal
 * chain in {@link SecurityConfig} - that chain is conditional on this being off, so exactly one of
 * the two exists. Nothing switches this on at runtime: there is no header, parameter or path that
 * reaches it, and with the property absent or false none of these beans are created at all.
 *
 * <p>Read-only is enforced here rather than in the templates. Every request method that could change
 * something is refused for every path, so an endpoint nobody remembered to hide in the UI is still
 * refused, and so is anything reached directly with curl. The disabled controls and the banner are
 * there so visitors are not surprised by a refusal; they are not the boundary.
 *
 * <p>Actuator is refused apart from health. The demo is unauthenticated and usually public, and the
 * management configuration exposes {@code env}, which would hand out the application's configuration
 * to anyone who asked.
 *
 * <p><b>Point this at demo data only.</b> Turning it on removes authentication from the entire
 * application. Everything the configured directory and database hold becomes readable by anyone who
 * can reach the URL.
 */
@Configuration
@ConditionalOnProperty(name = "application.demo.enabled", havingValue = "true")
public class DemoSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(DemoSecurityConfig.class);

    /**
     * Deliberately not a {@code @Bean}. Boot registers every {@link jakarta.servlet.Filter} bean with
     * the servlet container as well, so the same instance would run once outside this chain and once
     * inside it - and because it is a OncePerRequestFilter, the outer run marks the request handled
     * and the inner one skips. The context the outer run set is then replaced by
     * SecurityContextHolderFilter, and the request reaches the controller with no authentication at
     * all. Building it here keeps it to the one place it belongs.
     */
    DemoAuthenticationFilter demoAuthenticationFilter(AppUserDetailsService appUserDetailsService,
                                                      ApplicationProperties properties) {
        String demoUserDn = properties.getDemo().getUserDn();

        if (demoUserDn == null || demoUserDn.isBlank()) {
            // Refusing to start beats starting a sign-in-free application whose every page then
            // fails on an identity that was never configured.
            throw new IllegalStateException(
                    "application.demo.enabled is true but application.demo.user-dn is not set. "
                            + "Demo mode needs a directory entry to present the application as.");
        }

        log.warn("DEMO MODE IS ON: every request is served as {} with administrator rights and no "
                + "sign-in. Requests that would change anything are refused. Only ever run this "
                + "against data that is safe for anyone who can reach this URL to read.", demoUserDn);

        return new DemoAuthenticationFilter(appUserDetailsService, demoUserDn);
    }

    @Bean
    public SecurityFilterChain demoFilterChain(HttpSecurity http,
                                               AppUserDetailsService appUserDetailsService,
                                               ApplicationProperties properties) throws Exception {
        DemoAuthenticationFilter demoAuthenticationFilter =
                demoAuthenticationFilter(appUserDetailsService, properties);

        http
                // Before the anonymous filter, so the demo identity is in place by the time the
                // authorization rules below are evaluated.
                .addFilterBefore(demoAuthenticationFilter, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // The read-only boundary. Listed by method rather than by path so an endpoint
                        // added later is covered without anyone remembering to come back here.
                        .requestMatchers(HttpMethod.POST, "/**").denyAll()
                        .requestMatchers(HttpMethod.PUT, "/**").denyAll()
                        .requestMatchers(HttpMethod.PATCH, "/**").denyAll()
                        .requestMatchers(HttpMethod.DELETE, "/**").denyAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .anyRequest().permitAll()
                )
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler((request, response, denied) -> {
                    log.debug("Refused {} {} in demo mode", request.getMethod(), request.getRequestURI());
                    response.sendError(HttpServletResponse.SC_FORBIDDEN,
                            "This is a read-only demo; nothing here can be changed.");
                }))
                // No sign-in to offer and no session to protect: there is nothing to log in or out of.
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .csrf(csrf -> csrf.disable());

        return http.build();
    }
}
