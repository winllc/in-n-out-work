package com.winllc.innoutwork.security.sso;

import com.winllc.innoutwork.config.EnsProperties;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.service.LdapService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Mapping on the e-mail claim counts only when the identity provider says the address is verified. */
class OidcEmailClaimTest {

    static final String DN = "CN=Alice,OU=Users,DC=example,DC=com";

    private OidcUser load(Object emailVerified) {
        LdapService ldap = mock(LdapService.class);
        AppUserDetailsService users = mock(AppUserDetailsService.class);
        when(ldap.lookupUniqueUserByFilter("(mail=alice@example.com)"))
                .thenReturn(Optional.of(LdapUser.builder().dn(DN).build()));
        when(users.loadUserByUsername(DN)).thenAnswer(inv -> {
            AppUserDetails details = new AppUserDetails();
            details.setDn(DN);
            details.setGrantedAuthorities(new ArrayList<>());
            details.addAuthority("USER");
            return details;
        });
        EnsProperties.Sso sso = new EnsProperties.Sso();
        sso.getOidc().setIssuerUri("https://idp.example.com");
        sso.getOidc().setClientId("readyroom");
        sso.getOidc().setUserFilter("(mail={email})");
        OidcSignIn signIn = new OidcSignIn(sso, new SsoUserMapper(users, ldap, RoleMapping.parse("")),
                "https://readyroom.example.com");

        ClientRegistration registration = ClientRegistration.withRegistrationId("oidc").clientId("readyroom")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(signIn.redirectUri()).authorizationUri("https://idp.example.com/authorize")
                .tokenUri("https://idp.example.com/token").jwkSetUri("https://idp.example.com/jwks")
                .userNameAttributeName("sub").scope("openid").build();
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https://idp.example.com");
        claims.put("sub", "alice");
        claims.put("email", "alice@example.com");
        if (emailVerified != null) {
            claims.put("email_verified", emailVerified);
        }
        Instant now = Instant.now();
        OidcIdToken idToken = new OidcIdToken("token", now, now.plusSeconds(300), claims);
        OAuth2AccessToken access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "at", now,
                now.plusSeconds(300));
        return signIn.userService().loadUser(new OidcUserRequest(registration, access, idToken));
    }

    @Test
    void aVerifiedAddressMaps() {
        assertEquals(DN, load(true).getName());
    }

    @Test
    void anUnverifiedOrUnstatedAddressIsRefused() {
        assertTrue(assertThrows(OAuth2AuthenticationException.class, () -> load(false)).getError().getDescription()
                .contains("email_verified"));
        assertThrows(OAuth2AuthenticationException.class, () -> load(null));
    }
}
