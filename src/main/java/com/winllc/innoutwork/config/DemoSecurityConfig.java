package com.winllc.innoutwork.config;

import com.winllc.innoutwork.audit.SecurityAudit;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.security.ConsentBanner;
import com.winllc.innoutwork.security.ConsentFilter;
import com.winllc.innoutwork.security.SessionControls;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Demo mode: the application signed into normally, but read-only, with the credentials published.
 *
 * <p>Active only with {@code application.demo.enabled: true}, and mutually exclusive with the chain
 * in {@link SecurityConfig} - that one is conditional on this being off, so exactly one of the two
 * exists and no ordering decides which wins.
 *
 * <p>Visitors sign in. The login page, the directory and the roles work exactly as they normally do;
 * this chain is the normal one plus a refusal. What demo mode adds is that the credentials for a few
 * seeded accounts are listed on the login page, so someone with no account can pick a role and look
 * around as it, and that nothing they do can change anything.
 *
 * <p>Read-only is enforced here rather than in the templates. Every request method that could change
 * something is refused for every path, so an endpoint nobody remembered to hide in the UI is still
 * refused, and so is anything reached directly with curl. The two exceptions are the sign-in and
 * sign-out posts, which write nothing of the application's own and without which there would be no
 * way in. The disabled controls and the banner are there so visitors are not surprised by a refusal;
 * they are not the boundary.
 *
 * <p>Actuator is refused apart from health, because a demo is usually reachable by people who should
 * not be reading the application's configuration, should an operator expose more than health.
 *
 * <p><b>Point this at demo data only.</b> The listed credentials are readable by anyone who can reach
 * the login page, so everything those accounts can see is effectively public.
 */
@Configuration
@ConditionalOnProperty(name = "application.demo.enabled", havingValue = "true")
public class DemoSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(DemoSecurityConfig.class);

    /** Spring Security's own endpoints, which have to keep accepting a post for sign-in to work. */
    static final String LOGIN_URL = "/login";
    static final String LOGOUT_URL = "/logout";

    @Bean
    public SecurityFilterChain demoFilterChain(HttpSecurity http,
                                               AppUserDetailsService appUserDetailsService,
                                               LdapAuthenticationProvider ldapAuthenticationProvider,
                                               ApplicationProperties properties,
                                               ObjectProvider<SecurityAudit> securityAudit,
                                               ObjectProvider<ConsentBanner> consentBanner,
                                               ObjectProvider<SessionControls> sessionControls,
                                               Environment environment) throws Exception {
        log.warn("DEMO MODE IS ON: the application is read-only, and the credentials for {} account(s) "
                        + "are published on the login page. Everything those accounts can see is readable "
                        + "by anyone who can reach this URL.",
                properties.getDemo().getAccounts().size());

        // Signing in works exactly as it does normally: a client certificate if one is presented,
        // otherwise the directory-backed login form.
        SecurityConfig.configureX509(http, appUserDetailsService);
        BrowserSessionConfig.apply(http, consentBanner, sessionControls);

        http
                .authenticationProvider(ldapAuthenticationProvider)
                .authorizeHttpRequests(auth -> auth
                        // Sign-in, sign-out and the consent banner's "I agree" first: they are posts, and the
                        // read-only rule below would otherwise refuse them and leave no way into the demo at all.
                        .requestMatchers(HttpMethod.POST, LOGIN_URL, LOGOUT_URL, ConsentFilter.CONSENT_URL).permitAll()
                        // The read-only boundary. By method rather than by path, so an endpoint added
                        // later is covered without anyone remembering to come back here.
                        .requestMatchers(HttpMethod.POST, "/**").denyAll()
                        .requestMatchers(HttpMethod.PUT, "/**").denyAll()
                        .requestMatchers(HttpMethod.PATCH, "/**").denyAll()
                        .requestMatchers(HttpMethod.DELETE, "/**").denyAll()
                        .requestMatchers(LOGIN_URL, "/error", ConsentFilter.CONSENT_URL).permitAll()
                        .requestMatchers(SecurityConfig.openActuatorPaths(environment)).permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        // Everything else still needs a signed-in visitor: demo mode publishes the
                        // credentials, it does not remove the sign-in.
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(SecurityConfig.auditing(securityAudit,
                        "demo_read_only", (request, response, denied) -> {
                    log.debug("Refused {} {} in demo mode", request.getMethod(), request.getRequestURI());
                    response.sendError(HttpServletResponse.SC_FORBIDDEN,
                            "This is a read-only demo; nothing here can be changed.");
                })))
                .formLogin(form -> form
                        .loginPage(LOGIN_URL)
                        .failureUrl(LOGIN_URL + "?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutSuccessUrl(LOGIN_URL + "?logout")
                        .permitAll());
        // CSRF stays on as in SecurityConfig: the sign-in and sign-out posts are the two writes demo mode allows.

        return http.build();
    }
}
