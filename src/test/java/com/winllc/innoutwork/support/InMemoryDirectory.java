package com.winllc.innoutwork.support;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldif.LDIFReader;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

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
    private final AtomicInteger connections;
    private final List<String> searches;

    private InMemoryDirectory(InMemoryDirectoryServer server, AtomicInteger connections, List<String> searches) {
        this.server = server;
        this.connections = connections;
        this.searches = searches;
    }

    public static InMemoryDirectory start() {
        return start(0);
    }

    /**
     * @param maxSizeLimit the most entries the server returns for one search, as a real directory's size
     *                     limit does (Active Directory 1000, OpenLDAP 500); 0 for no limit
     */
    public static InMemoryDirectory start(int maxSizeLimit) {
        try {
            InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
            config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("test", 0));
            // The fixture leans on extensibleObject for the app's custom attributes; skip schema checks.
            config.setSchema(null);
            if (maxSizeLimit > 0) {
                config.setMaxSizeLimit(maxSizeLimit);
            }

            // The access log records each new client connection; counting them shows whether pooling reuses them.
            AtomicInteger connections = new AtomicInteger();
            List<String> searches = new CopyOnWriteArrayList<>();
            config.setAccessLogHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    // "DISCONNECT conn=..." contains "CONNECT " too, so match the word on its own.
                    if (record.getMessage() != null && record.getMessage().matches("(?s).*(^|\\s|\\])CONNECT conn=.*")) {
                        connections.incrementAndGet();
                    }
                    if (record.getMessage() != null && record.getMessage().contains("SEARCH REQUEST")) {
                        searches.add(record.getMessage());
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });

            InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
            try (InputStream ldif = InMemoryDirectory.class.getResourceAsStream("/ldap/test-directory.ldif")) {
                server.importFromLDIF(true, new LDIFReader(ldif));
            }
            server.startListening();
            return new InMemoryDirectory(server, connections, searches);
        } catch (Exception e) {
            throw new IllegalStateException("Could not start the in-memory directory", e);
        }
    }

    public String url() {
        return "ldap://localhost:" + server.getListenPort();
    }

    /**
     * A template configured the way application.yml configures the app: no {@code spring.ldap.base},
     * so every DN the app handles is absolute.
     */
    public LdapTemplate ldapTemplate() {
        return new LdapTemplate(contextSource(false));
    }

    /** A context source for this server, with or without JDK connection pooling. */
    public LdapContextSource contextSource(boolean pooled) {
        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(url());
        contextSource.setPooled(pooled);
        contextSource.afterPropertiesSet();
        return contextSource;
    }

    /** Client connections the server has accepted so far. */
    public int connectionsOpened() {
        return connections.get();
    }

    /**
     * The access log line of every search (a lookup is a base-scope search) received so far, including the
     * attributes requested.
     */
    public List<String> searchRequests() {
        return List.copyOf(searches);
    }

    public void clearSearchRequests() {
        searches.clear();
    }

    @Override
    public void close() {
        server.shutDown(true);
    }
}
