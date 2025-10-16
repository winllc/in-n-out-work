package com.winllc.innoutwork.config;

import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.service.LdapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   UserDetailsService ldapUserDetailsService) throws Exception {
        http
                // Require HTTPS with client certificate
                .x509(x509 -> x509
                        .subjectPrincipalRegex("(.*)") // Extract CN as username
                        .userDetailsService(ldapUserDetailsService)
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
    public UserDetailsService ldapUserDetailsService(LdapTemplate ldapTemplate) {
        return username -> {
            log.info("Looking up user: {}", username);

            LdapUser user = null;
            try {
                user = ldapTemplate.lookup(username, new ContextMapper<LdapUser>() {
                    @Override
                    public LdapUser mapFromContext(Object ctx) {
                        DirContextAdapter context = (DirContextAdapter) ctx;
                        return LdapUser.builder()
                                .dn(context.getDn().toString())
                                .build();
                    }
                });
            }catch (Exception e) {log.error("Not found: %s".formatted(username), e);}

            List<String> roles = new ArrayList<>();

            if(user != null){
                return User.withUsername(username)
                        .password("") // not used with X.509
                        .roles(roles.toArray(new String[0]))
                        .build();
            }else{
                return User.withUsername("NOTFOUND")
                        .password("") // not used with X.509
                        .roles(roles.toArray(new String[0]))
                        .build();
            }

        };
    }
}
