package com.winllc.innoutwork.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.ldap.core.support.LdapContextSource;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * How the application connects to the directory.
 * <p>
 * Spring LDAP opens a new connection, and binds again, for every search and lookup unless pooling is on,
 * and Spring Boot does not turn it on. With it on, the JDK keeps authenticated connections for the
 * application's own bind DN and reuses them. Binds that check a user's password on the login form are
 * never pooled (Spring LDAP disables it for those), so a changed password takes effect at once.
 * <p>
 * Timeouts are ordinary JNDI environment properties set under {@code spring.ldap.base-environment} in
 * application.yml. Without them an unresponsive directory holds a request indefinitely.
 */
@Configuration
public class LdapConnectionConfig {

    private static final Logger log = LoggerFactory.getLogger(LdapConnectionConfig.class);

    /** JDK connection pool settings, read once when the first pooled connection is made. */
    static final Map<String, String> POOL_DEFAULTS = Map.of(
            // Pool ldaps:// connections as well as plain ones; the JDK default pools only plain.
            "com.sun.jndi.ldap.connect.pool.protocol", "plain ssl",
            // Enough for request threads plus the scheduled jobs.
            "com.sun.jndi.ldap.connect.pool.maxsize", "20",
            // Close connections idle for five minutes, before firewalls and servers drop them silently.
            "com.sun.jndi.ldap.connect.pool.timeout", "300000");

    /**
     * Sets the JDK pool defaults as system properties, leaving any already given with -D untouched. Must
     * run before the first LDAP connection, so it is called at the top of {@code main}.
     */
    public static void applyPoolDefaults() {
        POOL_DEFAULTS.forEach((key, value) -> {
            if (System.getProperty(key) == null) {
                System.setProperty(key, value);
            }
        });
    }

    private final Environment environment;

    public LdapConnectionConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * Warns, once the application is up, when the directory is reached over plain {@code ldap://}: the sign-in form
     * binds with the user's password, which then crosses the network in clear text. ReadyRoom does not negotiate
     * StartTLS, so the fix is an {@code ldaps://} URL (SPRING_LDAP_URLS).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warnAboutCleartextDirectory() {
        String[] urls = Binder.get(environment).bind("spring.ldap.urls", String[].class).orElse(new String[0]);
        cleartextWarning(urls).ifPresent(log::warn);
    }

    static Optional<String> cleartextWarning(String[] urls) {
        List<String> cleartext = Arrays.stream(urls).map(String::strip)
                .filter(url -> url.toLowerCase(Locale.ROOT).startsWith("ldap://")).toList();
        if (cleartext.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("Password sign-in sends users' passwords to the directory in clear text: " + cleartext
                + " is ldap:// without StartTLS. Use ldaps:// (SPRING_LDAP_URLS=ldaps://host:636; the Helm chart's"
                + " ldap.scheme) so passwords and directory data are encrypted.");
    }

    /** Turns pooling on for the Boot-configured context source before it initialises. */
    @Bean
    static BeanPostProcessor ldapContextSourcePooling(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof LdapContextSource source) {
                    boolean pooled = environment.getProperty("application.ldap.pooled", Boolean.class, true);
                    source.setPooled(pooled);
                    log.info("LDAP connection pooling {} for {}", pooled ? "enabled" : "disabled", beanName);
                }
                return bean;
            }
        };
    }
}
