package com.winllc.innoutwork.security.sso;

import com.winllc.innoutwork.config.EnsProperties;
import com.winllc.innoutwork.data.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenID Connect sign-in (ENS_SSO_OIDC_*): the authorization code flow with PKCE, always, also for a confidential
 * client. Spring Security checks the state, the nonce, the ID token's signature against the issuer's keys, its
 * issuer, audience and times (within ENS_SSO_CLOCK_SKEW). The user is then mapped to a directory user
 * ({@link SsoUserMapper}); the signed-in principal's name is that user's DN.
 * <p>
 * The issuer's discovery document is read on first use, not at startup, so an identity provider that is down does
 * not stop ReadyRoom (and the Windows client's check-ins) from starting. Access and refresh tokens are not kept:
 * ReadyRoom calls no API with them.
 */
public class OidcSignIn {

    private static final Logger log = LoggerFactory.getLogger(OidcSignIn.class);

    public static final String REGISTRATION_ID = "oidc";
    /** The claim the signed-in principal is named by: the directory DN it was mapped to. */
    static final String DN_CLAIM = "ens_dn";
    static final Duration RETRY_DISCOVERY_AFTER = Duration.ofSeconds(30);

    private final EnsProperties.Sso.Oidc settings;
    private final Duration clockSkew;
    private final SsoUserMapper mapper;
    private final LazyClientRegistrationRepository registrations;

    public OidcSignIn(EnsProperties.Sso settings, SsoUserMapper mapper) {
        this.settings = settings.getOidc();
        this.clockSkew = settings.getClockSkew();
        this.mapper = mapper;
        if (this.settings.getIssuerUri() == null || this.settings.getIssuerUri().isBlank()) {
            throw new IllegalStateException("ENS_SSO_OIDC_ENABLED is true but ENS_SSO_OIDC_ISSUER_URI is not set");
        }
        if (this.settings.getClientId() == null || this.settings.getClientId().isBlank()) {
            throw new IllegalStateException("ENS_SSO_OIDC_ENABLED is true but ENS_SSO_OIDC_CLIENT_ID is not set");
        }
        SsoUserMapper.requireTemplate("ENS_SSO_OIDC_USER_FILTER", this.settings.getUserFilter());
        this.registrations = new LazyClientRegistrationRepository(this::discover);
    }

    public ClientRegistrationRepository registrations() {
        return registrations;
    }

    /** The registration, from the issuer's discovery document. */
    ClientRegistration discover() {
        String secret = clientSecret();
        Set<String> scopes = new LinkedHashSet<>();
        scopes.add("openid");
        Arrays.stream(settings.getScopes().split("[,\\s]+")).map(String::strip).filter(s -> !s.isEmpty())
                .forEach(scopes::add);
        return ClientRegistrations.fromIssuerLocation(settings.getIssuerUri())
                .registrationId(REGISTRATION_ID)
                .clientId(settings.getClientId())
                .clientSecret(secret)
                .clientAuthenticationMethod(secret.isEmpty() ? ClientAuthenticationMethod.NONE
                        : ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .scope(scopes)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .userNameAttributeName("sub")
                .clientName(settings.getDisplayName())
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .build();
    }

    private String clientSecret() {
        String file = settings.getClientSecretFile();
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new IllegalStateException("ENS_SSO_OIDC_CLIENT_SECRET_FILE " + file + " cannot be read: "
                        + e.getMessage(), e);
            }
        }
        return settings.getClientSecret() == null ? "" : settings.getClientSecret().strip();
    }

    /** ID tokens checked with ENS_SSO_CLOCK_SKEW for their times. */
    public JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
        OidcIdTokenDecoderFactory factory = new OidcIdTokenDecoderFactory();
        factory.setJwtValidatorFactory(registration -> {
            OidcIdTokenValidator oidc = new OidcIdTokenValidator(registration);
            oidc.setClockSkew(clockSkew);
            return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(clockSkew), oidc);
        });
        return factory;
    }

    /** Spring's user service (claims, user info), then the mapping to a directory user. */
    public OAuth2UserService<OidcUserRequest, OidcUser> userService() {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser idpUser = delegate.loadUser(request);
            Map<String, List<String>> values = new HashMap<>();
            idpUser.getClaims().forEach((name, value) -> values.put(name, strings(value)));
            AppUserDetails user;
            try {
                user = mapper.map(settings.getUserFilter(), values, values.getOrDefault(settings.getGroupsClaim(),
                        List.of()));
            } catch (SsoRefusedException e) {
                String subject = idpUser.getSubject();
                throw new OAuth2AuthenticationException(new OAuth2Error("sso_refused",
                        e.getMessage() + " (subject " + subject + ")", null), e.getMessage());
            }
            Map<String, Object> claims = new HashMap<>(idpUser.getUserInfo() == null ? Map.of()
                    : idpUser.getUserInfo().getClaims());
            claims.put("sub", idpUser.getSubject());
            claims.put(DN_CLAIM, user.getUsername());
            return new DefaultOidcUser(user.getAuthorities(), idpUser.getIdToken(), new OidcUserInfo(claims), DN_CLAIM);
        };
    }

    /** Claims as strings: a list's elements, or the value itself. */
    static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof Collection<?> many) {
            many.forEach(v -> {
                if (v != null) {
                    result.add(String.valueOf(v));
                }
            });
        } else if (value != null) {
            result.add(String.valueOf(value));
        }
        return result;
    }

    public String loginUrl() {
        return "/oauth2/authorization/" + REGISTRATION_ID;
    }

    public String displayName() {
        return settings.getDisplayName();
    }

    /** Access and refresh tokens are not kept anywhere: nothing here uses them. */
    static final class NoAuthorizedClients implements OAuth2AuthorizedClientRepository {

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
                                                                         Authentication principal,
                                                                         HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
                                         HttpServletRequest request, HttpServletResponse response) {
        }

        @Override
        public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
                                           HttpServletRequest request, HttpServletResponse response) {
        }
    }

    /** The one registration, discovered on first use and again after a failure, at most every 30 seconds. */
    static final class LazyClientRegistrationRepository implements ClientRegistrationRepository {

        private final java.util.function.Supplier<ClientRegistration> discovery;
        private volatile ClientRegistration registration;
        private volatile Instant failedAt;

        LazyClientRegistrationRepository(java.util.function.Supplier<ClientRegistration> discovery) {
            this.discovery = discovery;
        }

        @Override
        public ClientRegistration findByRegistrationId(String registrationId) {
            if (!REGISTRATION_ID.equals(registrationId)) {
                return null;
            }
            ClientRegistration known = registration;
            if (known != null) {
                return known;
            }
            synchronized (this) {
                if (registration != null) {
                    return registration;
                }
                if (failedAt != null && Instant.now().isBefore(failedAt.plus(RETRY_DISCOVERY_AFTER))) {
                    return null;
                }
                try {
                    registration = discovery.get();
                    log.info("OpenID Connect: issuer {} discovered", registration.getProviderDetails().getIssuerUri());
                    return registration;
                } catch (RuntimeException e) {
                    failedAt = Instant.now();
                    log.error("OpenID Connect: the issuer's discovery document could not be read; single sign-on"
                            + " is unavailable until it can: {}", e.toString());
                    return null;
                }
            }
        }
    }
}
