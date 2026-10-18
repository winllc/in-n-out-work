package com.winllc.innoutwork.security.sso;

import com.winllc.innoutwork.audit.SecurityAudit;
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

    @Bean
    @ConditionalOnProperty(name = "ens.sso.oidc.enabled", havingValue = "true")
    OidcSignIn oidcSignIn(EnsProperties properties, SsoUserMapper mapper) {
        return new OidcSignIn(properties.getSso(), mapper);
    }

    /** Spring Security's OAuth2 login looks for this bean: ID token times within ENS_SSO_CLOCK_SKEW. */
    @Bean
    @ConditionalOnProperty(name = "ens.sso.oidc.enabled", havingValue = "true")
    JwtDecoderFactory<ClientRegistration> oidcIdTokenDecoderFactory(OidcSignIn oidc) {
        return oidc.idTokenDecoderFactory();
    }

    @Bean
    @ConditionalOnProperty(name = "ens.sso.saml.enabled", havingValue = "true")
    SamlSignIn samlSignIn(EnsProperties properties, SsoUserMapper mapper, SsoAssertionSeenRepository seen) {
        return new SamlSignIn(properties.getSso(), mapper, seen, Clock.systemUTC());
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
