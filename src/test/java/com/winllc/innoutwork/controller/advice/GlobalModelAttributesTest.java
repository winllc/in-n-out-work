package com.winllc.innoutwork.controller.advice;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only a completed username/password login offers a logout. */
class GlobalModelAttributesTest {

    private static final String DN = "CN=alice,OU=Users,DC=winllc,DC=com";

    @Test
    void aPasswordLoginCanLogOut() {
        assertTrue(GlobalModelAttributes.isPasswordLogin(
                UsernamePasswordAuthenticationToken.authenticated(DN, null, AuthorityUtils.createAuthorityList("USER"))));
    }

    /** What the X.509 filter produces. */
    @Test
    void aCertificateLoginCannot() {
        assertFalse(GlobalModelAttributes.isPasswordLogin(
                new PreAuthenticatedAuthenticationToken(DN, null, AuthorityUtils.createAuthorityList("USER"))));
    }

    /** The form's credentials before the LDAP bind has accepted them. */
    @Test
    void anUnverifiedPasswordAttemptCannot() {
        assertFalse(GlobalModelAttributes.isPasswordLogin(UsernamePasswordAuthenticationToken.unauthenticated(DN, "pw")));
    }

    @Test
    void nobodySignedInCannot() {
        assertFalse(GlobalModelAttributes.isPasswordLogin(null));
        assertFalse(GlobalModelAttributes.isPasswordLogin(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))));
    }
}
