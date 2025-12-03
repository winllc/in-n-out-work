package com.winllc.innoutwork.config;

import com.winllc.innoutwork.security.AppUserDetailsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);


    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AppUserDetailsService appUserDetailsService) throws Exception {
        http
                // Require HTTPS with client certificate
                .x509(x509 -> x509
                        .subjectPrincipalRegex("(.*)") // Extract CN as username
                        .userDetailsService(appUserDetailsService)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/check/out").permitAll()
                        .anyRequest().authenticated()
                )
                .csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> web.ignoring().requestMatchers(
                AntPathRequestMatcher.antMatcher("/libs/**"),
                AntPathRequestMatcher.antMatcher("/css/**"),
                AntPathRequestMatcher.antMatcher("/js/**"),
                AntPathRequestMatcher.antMatcher("/images/**")
        );
    }

}
