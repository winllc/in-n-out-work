package com.winllc.innoutwork.security.sso;

import com.winllc.innoutwork.data.AppUserDetails;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Someone signed in by SAML single sign-on: their directory user and roles, as any other sign-in gives them. The
 * name is the directory DN, as everywhere else.
 */
public class SsoAuthenticationToken extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final AppUserDetails user;
    private final String method;
    private final String registrationId;

    public SsoAuthenticationToken(AppUserDetails user, String method, String registrationId) {
        super(user.getAuthorities());
        this.user = user;
        this.method = method;
        this.registrationId = registrationId;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public AppUserDetails getPrincipal() {
        return user;
    }

    @Override
    public String getName() {
        return user.getUsername();
    }

    /** {@code saml2} or {@code oidc}, for the audit trail. */
    public String method() {
        return method;
    }

    public String registrationId() {
        return registrationId;
    }
}
