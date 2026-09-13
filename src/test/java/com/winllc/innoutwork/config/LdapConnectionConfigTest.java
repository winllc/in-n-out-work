package com.winllc.innoutwork.config;

import com.winllc.innoutwork.support.InMemoryDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.mock.env.MockEnvironment;

import javax.naming.directory.SearchControls;

import static org.junit.jupiter.api.Assertions.*;

/** LDAP connections are pooled, and the pool reuses them. */
class LdapConnectionConfigTest {

    private static int searchRepeatedly(LdapTemplate template, int times) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[0]);
        int found = 0;
        for (int i = 0; i < times; i++) {
            found = template.search(InMemoryDirectory.BASE_DN, "(objectClass=inetOrgPerson)", controls,
                    (org.springframework.ldap.core.ContextMapper<Object>) ctx -> ctx).size();
        }
        return found;
    }

    /** Before: a new connection, and bind, for every single search. */
    @Test
    void withoutPoolingEverySearchOpensAConnection() {
        try (InMemoryDirectory directory = InMemoryDirectory.start()) {
            assertEquals(5, searchRepeatedly(new LdapTemplate(directory.contextSource(false)), 20));

            assertEquals(20, directory.connectionsOpened());
        }
    }

    @Test
    void withPoolingSearchesReuseConnections() {
        LdapConnectionConfig.applyPoolDefaults();
        try (InMemoryDirectory directory = InMemoryDirectory.start()) {
            assertEquals(5, searchRepeatedly(new LdapTemplate(directory.contextSource(true)), 20));

            assertTrue(directory.connectionsOpened() <= 2, "opened " + directory.connectionsOpened());
        }
    }

    @Test
    void theBootConfiguredContextSourceIsPooledUnlessTurnedOff() {
        BeanPostProcessor processor = LdapConnectionConfig.ldapContextSourcePooling(new MockEnvironment());
        LdapContextSource source = new LdapContextSource();
        processor.postProcessBeforeInitialization(source, "ldapContextSource");
        assertTrue(source.isPooled());

        BeanPostProcessor off = LdapConnectionConfig.ldapContextSourcePooling(
                new MockEnvironment().withProperty("application.ldap.pooled", "false"));
        LdapContextSource unpooled = new LdapContextSource();
        off.postProcessBeforeInitialization(unpooled, "ldapContextSource");
        assertFalse(unpooled.isPooled());
    }

    @Test
    void otherBeansAreLeftAlone() {
        Object other = new Object();
        assertSame(other, LdapConnectionConfig.ldapContextSourcePooling(new MockEnvironment())
                .postProcessBeforeInitialization(other, "other"));
    }

    /** Settings given on the command line win over the defaults. */
    @Test
    void poolDefaultsDoNotOverrideSystemPropertiesAlreadySet() {
        String key = "com.sun.jndi.ldap.connect.pool.maxsize";
        String before = System.getProperty(key);
        try {
            System.setProperty(key, "7");
            LdapConnectionConfig.applyPoolDefaults();
            assertEquals("7", System.getProperty(key));
            assertEquals("plain ssl", System.getProperty("com.sun.jndi.ldap.connect.pool.protocol"));
        } finally {
            if (before == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, before);
            }
        }
    }
}
