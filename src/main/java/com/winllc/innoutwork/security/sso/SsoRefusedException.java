package com.winllc.innoutwork.security.sso;

/** Why someone the identity provider signed in is not signed in here; the reason is audited. */
public class SsoRefusedException extends RuntimeException {

    public SsoRefusedException(String reason) {
        super(reason);
    }
}
