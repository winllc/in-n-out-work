package com.winllc.innoutwork.config;

import org.springframework.boot.web.server.AbstractConfigurableWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Refuses to start with TLS on unless the key store, and while client certificates are asked for the trust
 * store, are configured explicitly. Nothing is packaged as a default: the defaults used to be a keystore and
 * a truststore in the jar, and that truststore trusted the development CA whose keys are in the repository, so
 * anyone could mint a certificate that signs in as anyone. Without a trust store Java would fall back to its
 * own default CAs, which is no better.
 *
 * <p>A web server customizer, so it runs after Boot has copied {@code server.ssl.*} onto the factory and
 * before the connector is built. A {@code server.ssl.bundle} counts as explicit configuration.
 */
@Component
public class SslStoresCheck implements WebServerFactoryCustomizer<AbstractConfigurableWebServerFactory>, Ordered {

    @Override
    public void customize(AbstractConfigurableWebServerFactory factory) {
        check(factory.getSsl());
    }

    /** After ServletWebServerFactoryCustomizer (order 0), which applies the server properties. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    static void check(Ssl ssl) {
        if (!Ssl.isEnabled(ssl) || StringUtils.hasText(ssl.getBundle())) {
            return;
        }
        List<String> problems = new ArrayList<>();
        if (!StringUtils.hasText(ssl.getKeyStore()) && !StringUtils.hasText(ssl.getCertificate())) {
            problems.add("server.ssl.enabled is true but no key store is configured. Set server.ssl.key-store"
                    + " (SERVER_SSL_KEYSTORE) and server.ssl.key-store-password (SERVER_SSL_KEYSTOREPASSWORD) to"
                    + " the server's PKCS#12 keystore; ReadyRoom ships none.");
        }
        Ssl.ClientAuth clientAuth = ssl.getClientAuth();
        boolean asksForCertificates = clientAuth == Ssl.ClientAuth.WANT || clientAuth == Ssl.ClientAuth.NEED;
        if (asksForCertificates && !StringUtils.hasText(ssl.getTrustStore())
                && !StringUtils.hasText(ssl.getTrustCertificate())) {
            problems.add("server.ssl.client-auth is " + clientAuth.name().toLowerCase(Locale.ROOT)
                    + " but no trust store is configured, so a client certificate from any CA Java trusts by"
                    + " default would sign in. Set server.ssl.trust-store (SERVER_SSL_TRUSTSTORE) and"
                    + " server.ssl.trust-store-password (SERVER_SSL_TRUSTSTOREPASSWORD) to a PKCS#12 truststore"
                    + " holding only the CAs that issue your users' certificates, or set"
                    + " server.ssl.client-auth=none to turn certificate sign-in off.");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join(" ", problems));
        }
    }
}
