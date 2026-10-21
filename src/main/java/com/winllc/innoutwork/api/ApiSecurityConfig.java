package com.winllc.innoutwork.api;

import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.audit.SecurityAudit;
import com.winllc.innoutwork.config.SecurityConfig;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.security.ClientRateLimiter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * The public API's own security chain (/api/v1/**), ahead of the browser chain: bearer tokens only
 * ({@link ApiTokenFilter}), no session (none is read or created, so a browser's session cookie signs nothing in
 * here), no request cache, no redirect to the login page. CSRF tokens are not asked for on this chain alone: every
 * request on it is signed in by its Authorization header, which another site's page cannot make a browser send.
 * Only GETs are served. The OpenAPI document is open to anyone; everything else needs a token, and each endpoint
 * a scope. In demo mode too: tokens cannot be created there, so nothing is reachable.
 */
@Configuration
public class ApiSecurityConfig {

    public static final String PATH = "/api/v1/**";
    public static final String OPENAPI = "/api/v1/openapi";

    /** Per token, in memory, per replica: each replica allows a token its limit. */
    private final ClientRateLimiter tokenLimiter = new ClientRateLimiter(0);

    @Bean
    @Order(1)
    SecurityFilterChain publicApiFilterChain(HttpSecurity http, ApiTokenService tokens,
                                             AppUserDetailsService users,
                                             ObjectProvider<SecurityAudit> securityAudit,
                                             ObjectProvider<AuditLog> audit) throws Exception {
        http.securityMatcher(PathPatternRequestMatcher.pathPattern(PATH))
                .addFilterBefore(new ApiTokenFilter(tokens, users, securityAudit.getIfAvailable(),
                        audit.getIfAvailable(), tokenLimiter), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PathPatternRequestMatcher.pathPattern(HttpMethod.GET, OPENAPI),
                                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, OPENAPI + ".yaml")).permitAll()
                        .requestMatchers(PathPatternRequestMatcher.pathPattern(HttpMethod.GET, PATH))
                        .hasAuthority(ApiScope.API_TOKEN_AUTHORITY)
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .securityContext(context -> context.requireExplicitSave(true))
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setHeader("WWW-Authenticate", "Bearer");
                            ApiTokenFilter.write(response, "unauthorized",
                                    "Send an API token as Authorization: Bearer <token>.");
                        })
                        .accessDeniedHandler(SecurityConfig.auditing(securityAudit, "permission_denied",
                                (request, response, ex) -> {
                                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                    ApiTokenFilter.write(response, "forbidden",
                                            "This token may not do that: check its scopes.");
                                })));
        return http.build();
    }
}
