package com.winllc.innoutwork.config;

import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.security.NegotiateAuthenticationFilter;
import com.winllc.innoutwork.security.WindowsAccountUserDetailsService;
import com.winllc.innoutwork.service.LdapService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.kerberos.authentication.KerberosServiceAuthenticationProvider;
import org.springframework.security.kerberos.authentication.KerberosTicketValidator;
import org.springframework.security.kerberos.authentication.sun.SunJaasKerberosTicketValidator;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.preauth.x509.X509AuthenticationFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Windows sign-in (Kerberos over SPNEGO) for the Windows client's check-in, lock and unlock calls.
 * <p>
 * Only active with {@code application.windows-auth.enabled: true}. It adds a filter chain for exactly
 * {@code POST /api/check/in}, {@code /lock} and {@code /unlock} that accepts a client certificate, configured
 * by the same code as the main chain, or a Windows ticket in an {@code Authorization: Negotiate} header. A
 * certificate is checked first, and a request it authenticates never reaches the Kerberos code. Every other
 * URL, including {@code /api/check/out} and {@code /api/check/records}, stays on {@link SecurityConfig}'s chain.
 * <p>
 * The one visible difference on those three calls: a request with neither a certificate nor a ticket gets
 * {@code 401} with {@code WWW-Authenticate: Negotiate}, which is what prompts Windows to send a ticket, instead
 * of a redirect to the login page, which a script could not use anyway.
 */
@Configuration
@ConditionalOnProperty(name = "application.windows-auth.enabled", havingValue = "true")
public class WindowsAuthSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(WindowsAuthSecurityConfig.class);

    static final String[] CHECK_IN_PATHS = {"/api/check/in", "/api/check/lock", "/api/check/unlock"};

    /** Checks tickets against the service principal's key in the keytab, loaded once at startup. */
    @Bean
    KerberosTicketValidator kerberosTicketValidator(ApplicationProperties properties) {
        ApplicationProperties.WindowsAuth settings = properties.getWindowsAuth();
        requireSettings(settings);

        if (!settings.getKrb5ConfigLocation().isBlank()) {
            System.setProperty("java.security.krb5.conf", settings.getKrb5ConfigLocation());
        }

        SunJaasKerberosTicketValidator validator = new SunJaasKerberosTicketValidator();
        validator.setServicePrincipal(settings.getServicePrincipal());
        validator.setKeyTabLocation(new FileSystemResource(settings.getKeytabLocation()));
        validator.setDebug(settings.isDebug());

        log.info("Windows sign-in enabled for {} as {} (keytab {}, users matched on {})",
                String.join(", ", CHECK_IN_PATHS), settings.getServicePrincipal(), settings.getKeytabLocation(),
                settings.getAccountAttribute());
        return validator;
    }

    /** Fails startup with a clear message rather than on the first check-in. */
    static void requireSettings(ApplicationProperties.WindowsAuth settings) {
        if (settings.getServicePrincipal() == null || settings.getServicePrincipal().isBlank()) {
            throw new IllegalStateException("application.windows-auth.enabled is true but "
                    + "application.windows-auth.service-principal is not set (e.g. HTTP/inout.winllc.com@WINLLC.COM)");
        }
        if (settings.getKeytabLocation() == null || settings.getKeytabLocation().isBlank()) {
            throw new IllegalStateException("application.windows-auth.enabled is true but "
                    + "application.windows-auth.keytab-location is not set");
        }
        Path keytab = Path.of(settings.getKeytabLocation());
        if (!Files.isReadable(keytab)) {
            throw new IllegalStateException("Windows sign-in keytab " + keytab + " does not exist or cannot be read");
        }
        if (settings.getAccountAttribute() == null || settings.getAccountAttribute().isBlank()) {
            throw new IllegalStateException("application.windows-auth.account-attribute is blank");
        }
    }

    @Bean
    @Order(1) // ahead of SecurityConfig's chain, which matches every request
    SecurityFilterChain windowsCheckInFilterChain(HttpSecurity http,
                                                  AppUserDetailsService appUserDetailsService,
                                                  LdapService ldapService,
                                                  ApplicationProperties properties,
                                                  KerberosTicketValidator ticketValidator) throws Exception {
        KerberosServiceAuthenticationProvider kerberos = new KerberosServiceAuthenticationProvider();
        kerberos.setTicketValidator(ticketValidator);
        kerberos.setUserDetailsService(
                new WindowsAccountUserDetailsService(ldapService, appUserDetailsService, properties.getWindowsAuth()));
        kerberos.afterPropertiesSet();

        AuthenticationEntryPoint negotiate = new NegotiateEntryPoint();

        // Created here, not as a bean: a Filter bean would also be registered for every request.
        NegotiateAuthenticationFilter windowsSignIn =
                new NegotiateAuthenticationFilter(new ProviderManager(kerberos), negotiate);

        http.securityMatchers(matchers -> matchers.requestMatchers(HttpMethod.POST, CHECK_IN_PATHS));
        SecurityConfig.configureX509(http, appUserDetailsService);
        http
                // Runs after the certificate filter and skips requests it already authenticated.
                .addFilterAfter(windowsSignIn, X509AuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(negotiate))
                .csrf(csrf -> csrf.disable());

        return http.build();
    }

    /**
     * 401 with {@code WWW-Authenticate: Negotiate}, which is what makes Windows send a ticket. When the client
     * offered NTLM instead, which Windows does when it cannot get a Kerberos ticket for the URL, the log says so,
     * since the fix is on the client side.
     */
    static final class NegotiateEntryPoint implements AuthenticationEntryPoint {

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
                throws IOException {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Negotiate " + NegotiateAuthenticationFilter.NTLM_PREFIX)) {
                log.warn("{} {} from {} offered NTLM, which is not supported. Windows falls back to NTLM when it has no "
                                + "Kerberos ticket for the URL: use the host name registered as the service principal "
                                + "(not an IP address) and check the client can reach a domain controller.",
                        request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            }
            response.setHeader("WWW-Authenticate", "Negotiate");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }
}
