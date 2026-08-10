package com.winllc.innoutwork.config;

import com.winllc.innoutwork.security.AppUserDetailsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.ldap.userdetails.UserDetailsContextMapper;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.util.Collection;


@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);


    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AppUserDetailsService appUserDetailsService,
                                                   LdapAuthenticationProvider ldapAuthenticationProvider) throws Exception {
        http
                // Preferred: HTTPS with a client certificate (X.509).
                .x509(x509 -> x509
                        .subjectPrincipalRegex("(.*)") // full subject DN is used as the username
                        .userDetailsService(appUserDetailsService)
                )
                // Fallback when no client certificate is presented: username/password
                // validated against LDAP (see ldapAuthenticationProvider below).
                .authenticationProvider(ldapAuthenticationProvider)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/check/out").permitAll()
                        .requestMatchers("/login", "/error").permitAll()
                        .anyRequest().authenticated()
                )
                // Branded login page (GET /login served by LoginController); the POST is
                // processed here by the LDAP provider.
                .formLogin(form -> form
                        .loginPage("/login")
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll())
                .csrf(csrf -> csrf.disable());

        return http.build();
    }

    /**
     * Username/password authentication backed by LDAP. Users are located by {@code uid},
     * {@code cn}, or {@code mail} under the configured user base DN, then a bind is
     * attempted with their password.
     * Roles/authorities are resolved through the same {@link AppUserDetailsService} used
     * by X.509 so both mechanisms grant identical authorities.
     */
    @Bean
    public LdapAuthenticationProvider ldapAuthenticationProvider(BaseLdapPathContextSource contextSource,
                                                                 AppUserDetailsService appUserDetailsService,
                                                                 ApplicationProperties properties) {
        // Accept login by uid, common name, or email address.
        FilterBasedLdapUserSearch userSearch =
                new FilterBasedLdapUserSearch(properties.getUserBaseDn(),
                        "(|(uid={0})(cn={0})(mail={0}))", contextSource);
        userSearch.setSearchSubtree(true);

        BindAuthenticator authenticator = new BindAuthenticator(contextSource);
        authenticator.setUserSearch(userSearch);

        LdapAuthenticationProvider provider = new LdapAuthenticationProvider(authenticator);
        // Reuse the app's role-mapping logic instead of deriving roles from LDAP groups.
        provider.setUserDetailsContextMapper(new AppUserDetailsContextMapper(appUserDetailsService));
        return provider;
    }

    /**
     * Bridges a successful LDAP bind to the application's UserDetails (and thus its roles)
     * by looking the authenticated entry up via its full DN.
     */
    private static final class AppUserDetailsContextMapper implements UserDetailsContextMapper {

        private final AppUserDetailsService appUserDetailsService;

        private AppUserDetailsContextMapper(AppUserDetailsService appUserDetailsService) {
            this.appUserDetailsService = appUserDetailsService;
        }

        @Override
        public UserDetails mapUserFromContext(DirContextOperations ctx, String username,
                                              Collection<? extends GrantedAuthority> authorities) {
            // The authenticated entry's full DN is what AppUserDetailsService keys on.
            return appUserDetailsService.loadUserByUsername(ctx.getNameInNamespace());
        }

        @Override
        public void mapUserToContext(UserDetails user, DirContextAdapter ctx) {
            throw new UnsupportedOperationException("AppUserDetailsContextMapper is read-only");
        }
    }

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> web.ignoring().requestMatchers(
                PathPatternRequestMatcher.pathPattern("/libs/**"),
                PathPatternRequestMatcher.pathPattern("/css/**"),
                PathPatternRequestMatcher.pathPattern("/js/**"),
                PathPatternRequestMatcher.pathPattern("/images/**")
        );
    }

}
