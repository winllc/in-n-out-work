package com.winllc.innoutwork.security.sso;

import com.winllc.innoutwork.audit.SecurityAudit;
import com.winllc.innoutwork.security.ConsentBanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The single sign-on methods that are configured, as a browser chain applies them (SecurityConfig), and the
 * buttons the sign-in page shows for them. With neither configured it adds nothing, so the chain is exactly what
 * it was before single sign-on existed. Only browser chains get it: the Windows client's API keeps signing in
 * with certificates and Windows tickets only.
 */
public class SsoSignIn {

    private static final Logger log = LoggerFactory.getLogger(SsoSignIn.class);

    /** The sign-in page's buttons. */
    public record Option(String url, String label) {
    }

    private final OidcSignIn oidc;
    private final SamlSignIn saml;
    private final ObjectProvider<SecurityAudit> audit;
    private final ApplicationEventPublisher events;

    public SsoSignIn(OidcSignIn oidc, SamlSignIn saml, ObjectProvider<SecurityAudit> audit,
                     ApplicationEventPublisher events) {
        this.oidc = oidc;
        this.saml = saml;
        this.audit = audit;
        this.events = events;
        if (oidc != null) {
            log.info("Single sign-on: OpenID Connect enabled ({})", oidc.loginUrl());
        }
        if (saml != null) {
            log.info("Single sign-on: SAML 2.0 enabled ({})", saml.loginUrl());
        }
    }

    public boolean isEnabled() {
        return oidc != null || saml != null;
    }

    public List<Option> options() {
        List<Option> options = new ArrayList<>();
        if (oidc != null) {
            options.add(new Option(oidc.loginUrl(), oidc.displayName()));
        }
        if (saml != null) {
            options.add(new Option(saml.loginUrl(), saml.displayName()));
        }
        return options;
    }

    public void configure(HttpSecurity http) throws Exception {
        if (oidc != null) {
            http.oauth2Login(login -> login
                    .loginPage("/login")
                    .clientRegistrationRepository(oidc.registrations())
                    .authorizedClientRepository(new OidcSignIn.NoAuthorizedClients())
                    .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidc.userService()))
                    .failureHandler(failureHandler("oidc")));
        }
        if (saml != null) {
            ProviderManager manager = new ProviderManager(saml.authenticationProvider());
            // Sign-in events like every other provider, so a SAML sign-in reaches the audit trail as auth.login.
            manager.setAuthenticationEventPublisher(new DefaultAuthenticationEventPublisher(events));
            http.saml2Login(login -> login
                    .loginPage("/login")
                    .relyingPartyRegistrationRepository(saml.registrations())
                    .authenticationManager(manager)
                    .successHandler(samlSuccessHandler())
                    .failureHandler(failureHandler("saml2")));
            http.saml2Metadata(Customizer.withDefaults());
        }
    }

    /**
     * The answer arrives without the browser's session (a cross-site POST), so a new one starts here. It carries
     * over the consent banner's acknowledgement when the session that started the sign-in had given it.
     */
    private AuthenticationSuccessHandler samlSuccessHandler() {
        SavedRequestAwareAuthenticationSuccessHandler target = new SavedRequestAwareAuthenticationSuccessHandler();
        return (request, response, authentication) -> {
            if (Boolean.TRUE.equals(request.getAttribute(SamlRequestStore.CONSENTED))) {
                request.getSession().setAttribute(ConsentBanner.ACKNOWLEDGED, Instant.now());
            }
            target.onAuthenticationSuccess(request, response, authentication);
        };
    }

    /** Audits the refusal (once: a provider's failure event may have done it) and returns to the sign-in page. */
    private AuthenticationFailureHandler failureHandler(String method) {
        return (request, response, exception) -> {
            SecurityAudit trail = audit.getIfAvailable();
            if (trail != null && request.getAttribute(SecurityAudit.FAILURE_RECORDED) == null) {
                trail.ssoRefused(request, method, reason(exception));
            }
            log.info("Single sign-on ({}) refused: {}", method, reason(exception));
            response.sendRedirect(request.getContextPath() + "/login?sso_error");
        };
    }

    static String reason(AuthenticationException e) {
        return SecurityAudit.reason(e);
    }

    /** For tests and diagnostics. */
    boolean hasOidc() {
        return oidc != null;
    }

    boolean hasSaml() {
        return saml != null;
    }

}
