package com.winllc.innoutwork.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.Ssl;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

/** TLS starts only with a key store, and while client certificates are asked for, a trust store. */
class SslStoresCheckTest {

    /** The embedded Tomcat and the check, under the packaged application.yml. */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(TomcatServletWebServerAutoConfiguration.class)
    @Import(SslStoresCheck.class)
    static class TomcatOnly {
    }

    private static ConfigurableApplicationContext start(String... args) {
        SpringApplication application = new SpringApplication(TomcatOnly.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        return application.run(args);
    }

    private static String startupFailure(String... args) {
        Throwable failure = assertThrows(Throwable.class, () -> start(args).close());
        StringBuilder messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private static Ssl ssl(String keyStore, String trustStore, Ssl.ClientAuth clientAuth) {
        Ssl ssl = new Ssl();
        ssl.setEnabled(true);
        ssl.setKeyStore(keyStore);
        ssl.setTrustStore(trustStore);
        ssl.setClientAuth(clientAuth);
        return ssl;
    }

    @Test
    void theDefaultsStillStartOnPlainHttp() {
        try (ConfigurableApplicationContext context = start("--server.port=0")) {
            assertTrue(context.isRunning());
        }
    }

    @Test
    void sslOnWithTheDefaultsFailsForWantOfAKeyStore() {
        String failure = startupFailure("--server.port=0", "--server.ssl.enabled=true");

        assertTrue(failure.contains("no key store is configured"), failure);
        assertTrue(failure.contains("SERVER_SSL_KEYSTORE"), failure);
        // The packaged client-auth is want, so the missing trust store is reported in the same breath.
        assertTrue(failure.contains("no trust store is configured"), failure);
    }

    @Test
    void sslOnWithAKeyStoreButNoTrustStoreFails() {
        String failure = startupFailure("--server.port=0", "--server.ssl.enabled=true",
                "--server.ssl.key-store=file:/etc/readyroom/keystore.p12");

        assertTrue(failure.contains("server.ssl.client-auth is want but no trust store is configured"), failure);
        assertTrue(failure.contains("SERVER_SSL_TRUSTSTORE"), failure);
        assertFalse(failure.contains("no key store"), failure);
    }

    @Test
    void bothStoresConfiguredPass() {
        assertDoesNotThrow(() -> SslStoresCheck.check(ssl("file:k.p12", "file:t.p12", Ssl.ClientAuth.WANT)));
        assertDoesNotThrow(() -> SslStoresCheck.check(ssl("file:k.p12", "file:t.p12", Ssl.ClientAuth.NEED)));
    }

    @Test
    void noTrustStoreIsNeededWithoutClientCertificates() {
        assertDoesNotThrow(() -> SslStoresCheck.check(ssl("file:k.p12", null, Ssl.ClientAuth.NONE)));
        assertDoesNotThrow(() -> SslStoresCheck.check(ssl("file:k.p12", null, null)));
    }

    @Test
    void needWithoutATrustStoreFails() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SslStoresCheck.check(ssl("file:k.p12", "", Ssl.ClientAuth.NEED)));
        assertTrue(e.getMessage().startsWith("server.ssl.client-auth is need"), e.getMessage());
    }

    @Test
    void pemCertificatesAndBundlesCountAsConfigured() {
        Ssl pem = ssl(null, null, Ssl.ClientAuth.WANT);
        pem.setCertificate("file:server.crt");
        pem.setCertificatePrivateKey("file:server.key");
        pem.setTrustCertificate("file:ca.crt");
        assertDoesNotThrow(() -> SslStoresCheck.check(pem));

        assertDoesNotThrow(() -> SslStoresCheck.check(Ssl.forBundle("readyroom")));
    }

    @Test
    void sslOffIsNotChecked() {
        Ssl off = ssl(null, null, Ssl.ClientAuth.WANT);
        off.setEnabled(false);
        assertDoesNotThrow(() -> SslStoresCheck.check(off));
        assertDoesNotThrow(() -> SslStoresCheck.check(null));
    }
}
