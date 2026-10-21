package com.winllc.innoutwork.config;

import com.winllc.innoutwork.audit.SecurityAudit;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.security.ConsentBanner;
import com.winllc.innoutwork.security.ConsentFilter;
import com.winllc.innoutwork.security.SessionControls;
import com.winllc.innoutwork.security.sso.SsoSignIn;
import com.winllc.innoutwork.security.x509.X509SignIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
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
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.util.Collection;


@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * The Windows client's API, exempt from CSRF tokens: the client is a script with no browser session to take a
     * token from. It signs in with a certificate or a Windows ticket, or not at all (the logoff, identified by its
     * session id), and accepts only JSON, which another site's page cannot post without a CORS preflight that is
     * never granted. The audit pull API has a chain of its own (bearer tokens, no session, no CSRF).
     */
    public static final String CLIENT_API = "/api/check/**";

    /** Actuator's health endpoint and its groups (/liveness, /readiness): the only actuator paths open to anyone. */
    public static final String[] ACTUATOR_HEALTH = {"/actuator/health", "/actuator/health/**"};

    /**
     * Prometheus metrics: exposed only on a separate management port (ManagementPortExposure), where they are read
     * without signing in. The application's own port has no such endpoint.
     */
    public static final String PROMETHEUS = "/actuator/prometheus";

    /**
     * Prometheus on the management port, which actuator reports as {@code local.management.port} once it listens.
     * Nowhere else: on the application's own port the path stays behind the ADMIN rule like the rest of
     * /actuator.
     */
    static RequestMatcher prometheusOnManagementPort(Environment environment) {
        RequestMatcher path = PathPatternRequestMatcher.withDefaults().matcher(PROMETHEUS);
        return request -> path.matches(request)
                && String.valueOf(request.getLocalPort()).equals(environment.getProperty("local.management.port"));
    }


    /**
     * The normal chain. Absent while demo mode is on, where {@link DemoSecurityConfig} defines the
     * only chain instead - the two are mutually exclusive by construction rather than by ordering.
     */
    @Bean
    @ConditionalOnProperty(name = "application.demo.enabled", havingValue = "false", matchIfMissing = true)
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AppUserDetailsService appUserDetailsService,
                                                   LdapAuthenticationProvider ldapAuthenticationProvider,
                                                   ObjectProvider<SecurityAudit> securityAudit,
                                                   ObjectProvider<ConsentBanner> consentBanner,
                                                   ObjectProvider<SessionControls> sessionControls,
                                                   ObjectProvider<X509SignIn> x509SignIn,
                                                   ObjectProvider<SsoSignIn> ssoSignIn,
                                                   Environment environment) throws Exception {
        // Preferred: HTTPS with a client certificate (X.509).
        configureX509(http, appUserDetailsService, x509SignIn);
        // Single sign-on (OpenID Connect, SAML), each only when configured (ENS_SSO_*).
        SsoSignIn sso = ssoSignIn.getIfAvailable();
        if (sso != null) {
            sso.configure(http);
        }
        // The notice-and-consent banner before any sign-in, and the session lifetimes after it.
        BrowserSessionConfig.apply(http, consentBanner, sessionControls);
        http
                // Fallback when no client certificate is presented: username/password
                // validated against LDAP (see ldapAuthenticationProvider below).
                .authenticationProvider(ldapAuthenticationProvider)
                .authorizeHttpRequests(auth -> auth
                        // Health, for the container healthcheck and the Kubernetes probes, is all application.yml
                        // exposes. Anything an operator exposes besides (metrics, env) needs ADMIN.
                        .requestMatchers(ACTUATOR_HEALTH).permitAll()
                        .requestMatchers(prometheusOnManagementPort(environment)).permitAll()
                        .requestMatchers("/actuator/**").hasAuthority(UserRoleEnum.ADMIN.name())
                        .requestMatchers("/api/check/out").permitAll()
                        .requestMatchers("/login", "/error", ConsentFilter.CONSENT_URL).permitAll()
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
                // The default 403, recorded in the audit trail as access.denied. An API call that is not signed in
                // gets a 401 rather than the login page: a script (the Windows client, a page's fetch) following
                // the redirect would take the page's 200 for success, as the client did when a certificate's
                // subject was not in the directory.
                // Everything else still goes to the login page: registered second, for any request, so that it is
                // what a request matching neither gets (the first registration would otherwise be that default).
                .exceptionHandling(e -> e
                        .accessDeniedHandler(auditing(securityAudit, "permission_denied", new AccessDeniedHandlerImpl()))
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                PathPatternRequestMatcher.withDefaults().matcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"),
                                AnyRequestMatcher.INSTANCE))
                // Every post from a browser carries the token: forms built with th:action get it from Thymeleaf,
                // scripts from the meta tags in layout.html. A missing or wrong one is a 403 (access.denied).
                .csrf(csrf -> csrf.ignoringRequestMatchers(CLIENT_API));

        return http.build();
    }

    /** {@code handler}, recording each refusal in the audit trail first when there is one. */
    public static AccessDeniedHandler auditing(ObjectProvider<SecurityAudit> securityAudit, String reason,
                                               AccessDeniedHandler handler) {
        SecurityAudit audit = securityAudit.getIfAvailable();
        return audit == null ? handler : audit.accessDeniedHandler(reason, handler);
    }

    /**
     * Client certificate sign-in. Shared with WindowsAuthSecurityConfig and DemoSecurityConfig, so certificates are
     * accepted the same way everywhere: the issuer allowlist, revocation, the mapping to a directory user and header
     * mode all come from {@link X509SignIn} (ENS_X509_*). Without it (test slices) the subject DN is the user's DN.
     */
    public static void configureX509(HttpSecurity http, AppUserDetailsService appUserDetailsService,
                                     ObjectProvider<X509SignIn> x509SignIn) throws Exception {
        X509SignIn signIn = x509SignIn.getIfAvailable();
        if (signIn != null) {
            signIn.configure(http);
            return;
        }
        configureX509(http, appUserDetailsService);
    }

    /** Certificate sign-in by subject DN alone: the subject DN is the directory DN. */
    public static void configureX509(HttpSecurity http, AppUserDetailsService appUserDetailsService) throws Exception {
        http.x509(x509 -> x509
                .subjectPrincipalRegex("(.*)") // full subject DN is used as the username
                .userDetailsService(appUserDetailsService)
        );
        X509SignIn.keepNoCertificate(http);
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
