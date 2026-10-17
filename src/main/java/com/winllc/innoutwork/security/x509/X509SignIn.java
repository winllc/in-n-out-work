package com.winllc.innoutwork.security.x509;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.authentication.preauth.x509.X509AuthenticationFilter;

/**
 * Client-certificate sign-in as a chain applies it: in header mode the proxy's certificate is taken first
 * ({@link ProxyCertificateFilter}), then Spring Security's X.509 filter reads the subject and
 * {@link CertificateSignIn} decides who, if anyone, it signs in.
 */
public class X509SignIn {

    private final CertificateSignIn signIn;
    private final ProxyCertificateFilter proxyFilter;

    public X509SignIn(CertificateSignIn signIn, ProxyCertificateFilter proxyFilter) {
        this.signIn = signIn;
        this.proxyFilter = proxyFilter;
    }

    public void configure(HttpSecurity http) throws Exception {
        if (proxyFilter != null) {
            http.addFilterBefore(proxyFilter, X509AuthenticationFilter.class);
        }
        http.x509(x509 -> x509
                .subjectPrincipalRegex("(.*)") // full subject DN; the default mapping uses it as the directory DN
                .authenticationUserDetailsService(signIn));
    }
}
