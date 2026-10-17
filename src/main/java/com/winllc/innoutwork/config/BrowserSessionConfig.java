package com.winllc.innoutwork.config;

import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.security.ConsentBanner;
import com.winllc.innoutwork.security.ConsentFilter;
import com.winllc.innoutwork.security.SessionControlFilter;
import com.winllc.innoutwork.security.SessionControls;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.preauth.x509.X509AuthenticationFilter;

import java.time.Clock;

/**
 * The browser-session rules of the platform baseline: the notice-and-consent banner and session lifetimes. Both
 * apply to the browser chains (SecurityConfig, DemoSecurityConfig) only, never to the Windows client's API.
 */
@Configuration
public class BrowserSessionConfig {

    @Bean
    ConsentBanner consentBanner(EnsProperties properties) {
        return new ConsentBanner(properties.getConsentBanner());
    }

    /** Also the session listener that notices idle expiry; Boot registers it with the container. */
    @Bean
    SessionControls sessionControls(EnsProperties properties, ObjectProvider<AuditLog> audit) {
        return new SessionControls(properties.getSession(), audit, Clock.systemUTC());
    }

    /**
     * Adds both to a browser chain: the banner ahead of every sign-in filter, the session rules once the request's
     * sign-in is known. Absent beans (test slices) leave the chain as it was.
     */
    public static void apply(HttpSecurity http, ObjectProvider<ConsentBanner> banner,
                             ObjectProvider<SessionControls> sessions) {
        ConsentBanner consent = banner.getIfAvailable();
        if (consent != null && consent.isEnabled()) {
            http.addFilterBefore(new ConsentFilter(consent), X509AuthenticationFilter.class);
        }
        SessionControls controls = sessions.getIfAvailable();
        if (controls != null) {
            http.addFilterAfter(new SessionControlFilter(controls), AnonymousAuthenticationFilter.class);
        }
    }
}
