package com.winllc.innoutwork.audit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * The pull API's own security chain, ahead of every other: bearer tokens only, no session (none is read or
 * created), no CSRF, no redirect to the login page. In demo mode too, since it only reads.
 */
@Configuration
public class AuditApiSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain auditApiFilterChain(HttpSecurity http, AuditTokenService tokens, SecurityAudit securityAudit)
            throws Exception {
        http.securityMatcher(PathPatternRequestMatcher.pathPattern(AuditApiController.PATH))
                .addFilterBefore(new AuditTokenFilter(tokens, securityAudit), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority(AuditRequestInfo.AUDIT_READ))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .securityContext(context -> context.requireExplicitSave(true))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
