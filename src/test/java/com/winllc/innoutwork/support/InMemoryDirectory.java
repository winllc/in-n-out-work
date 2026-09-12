package com.winllc.innoutwork.support;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldif.LDIFReader;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.io.InputStream;

/**
 * A real LDAP server on an ephemeral port, loaded from {@code ldap/test-directory.ldif}.
 * <p>
 * Mocking {@link LdapTemplate} only checks that we call it the way we believe it works. How DNs
 * come back from a search, which filters the server accepts and how escaping behaves are all
 * decided by Spring LDAP and JNDI, so those paths are exercised against this instead.
 */
public final class InMemoryDirectory implements AutoCloseable {

    public static final String BASE_DN = "dc=winllc,dc=com";

    private final InMemoryDirectoryServer server;

    private InMemoryDirectory(InMemoryDirectoryServer server) {
        this.server = server;
    }

    public static InMemoryDirectory start() {
        try {
            InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
            config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("test", 0));
            // The fixture leans on extensibleObject for the app's custom attributes; skip schema checks.
            config.setSchema(null);

            InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
            try (InputStream ldif = InMemoryDirectory.class.getResourceAsStream("/ldap/test-directory.ldif")) {
                server.importFromLDIF(true, new LDIFReader(ldif));
            }
            server.startListening();
            return new InMemoryDirectory(server);
        } catch (Exception e) {
            throw new IllegalStateException("Could not start the in-memory directory", e);
        }
    }

    /**
     * A template configured the way application.yml configures the app: no {@code spring.ldap.base},
     * so every DN the app handles is absolute.
     */
    public LdapTemplate ldapTemplate() {
        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl("ldap://localhost:" + server.getListenPort());
        contextSource.afterPropertiesSet();
        return new LdapTemplate(contextSource);
    }

    @Override
    public void close() {
        server.shutDown(true);
    }
}
