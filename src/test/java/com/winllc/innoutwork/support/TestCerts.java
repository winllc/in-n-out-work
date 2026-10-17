package com.winllc.innoutwork.support;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A client certificate whose subject is {@code dn}, for signing in to a MockMvc slice as that directory user. */
public final class TestCerts {

    private TestCerts() {
    }

    public static X509Certificate cert(String dn) {
        X509Certificate c = mock(X509Certificate.class);
        X500Principal p = new X500Principal(dn);
        when(c.getSubjectDN()).thenReturn(p);
        when(c.getSubjectX500Principal()).thenReturn(p);
        return c;
    }
}
