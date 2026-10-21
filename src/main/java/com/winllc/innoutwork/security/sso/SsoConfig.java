package com.winllc.innoutwork.security.sso;

import com.winllc.innoutwork.audit.SecurityAudit;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.EnsProperties;
import com.winllc.innoutwork.repository.SsoAssertionSeenRepository;
import com.winllc.innoutwork.repository.SsoSamlRequestRepository;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.service.LdapService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;

import java.time.Clock;

/**
 * Single sign-on (ENS_SSO_*). Each protocol's beans exist only when it is enabled; {@link SsoSignIn} always does,
 * empty when neither is, so SecurityConfig needs no condition of its own. A setting that is missing or impossible
 * stops startup with its name.
 */
@Configuration
public class SsoConfig {

    @Bean
    SsoUserMapper ssoUserMapper(EnsProperties properties, AppUserDetailsService users, LdapService ldap) {
        return new SsoUserMapper(users, ldap, RoleMapping.parse(properties.getSso().getRoleMapping()));
    }

    /** The packaged defaults of APPLICATION_BASE_URL, which say nothing about where users reach ReadyRoom. */
    static final java.util.Set<String> DEFAULT_BASE_URLS = java.util.Set.of("http://localhost:8181", "https://localhost");

    /**
     * The public base URL every single sign-on address is built from, never the request's Host or forwarded
     * headers, which a client controls. It must be set explicitly (APPLICATION_BASE_URL) and absolute.
     */
    static String publicBaseUrl(ApplicationProperties application, Environment environment) {
        String base = application.getApplicationBaseUrl() == null ? "" : application.getApplicationBaseUrl().strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(base);
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
            throw new IllegalStateException("Single sign-on needs APPLICATION_BASE_URL, the absolute URL users reach"
                    + " ReadyRoom at (https://readyroom.example.com): its addresses are built from it");
        }
        if (DEFAULT_BASE_URLS.contains(base) && environment.getProperty("APPLICATION_BASE_URL") == null) {
            throw new IllegalStateException("Single sign-on needs APPLICATION_BASE_URL set to the URL users reach"
                    + " ReadyRoom at; it is still the packaged default " + base);
        }
        return base;
    }

    @Bean
    @ConditionalOnProperty(name = "ens.sso.oidc.enabled", havingValue = "true")
    OidcSignIn oidcSignIn(EnsProperties properties, SsoUserMapper mapper, ApplicationProperties application,
                          Environment environment) {
        return new OidcSignIn(properties.getSso(), mapper, publicBaseUrl(application, environment));
    }

    /** Spring Security's OAuth2 login looks for this bean: ID token times within ENS_SSO_CLOCK_SKEW. */
    @Bean
    @ConditionalOnProperty(name = "ens.sso.oidc.enabled", havingValue = "true")
    JwtDecoderFactory<ClientRegistration> oidcIdTokenDecoderFactory(OidcSignIn oidc) {
        return oidc.idTokenDecoderFactory();
    }

    @Bean
    @ConditionalOnProperty(name = "ens.sso.saml.enabled", havingValue = "true")
    SamlSignIn samlSignIn(EnsProperties properties, SsoUserMapper mapper, SsoAssertionSeenRepository seen,
                          ApplicationProperties application, Environment environment) {
        return new SamlSignIn(properties.getSso(), mapper, seen, Clock.systemUTC(),
                publicBaseUrl(application, environment));
    }

    /** Spring Security's SAML login looks for this bean: requests in the database, see SamlRequestStore. */
    @Bean
    @ConditionalOnProperty(name = "ens.sso.saml.enabled", havingValue = "true")
    SamlRequestStore samlRequestStore(SsoSamlRequestRepository requests) {
        return new SamlRequestStore(requests, Clock.systemUTC());
    }

    @Bean
    SsoSignIn ssoSignIn(ObjectProvider<OidcSignIn> oidc, ObjectProvider<SamlSignIn> saml,
                        ObjectProvider<SecurityAudit> audit, ApplicationEventPublisher events) {
        return new SsoSignIn(oidc.getIfAvailable(), saml.getIfAvailable(), audit, events);
    }
}
