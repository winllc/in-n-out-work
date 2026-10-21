package com.winllc.innoutwork.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.ConfigurableObjectInputStream;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;

/**
 * Browser sessions live in the database (Spring Session JDBC, tables from V6), so any replica serves any request
 * and a restart or rolling update keeps people signed in. The tables' clean-up is {@code security.SessionExpiry},
 * which also audits idle expiry (Spring's own clean-up is off: {@code spring.session.jdbc.cleanup-cron: "-"}).
 * <p>
 * Session attributes are Java-serialized. Reading them back allows only the classes sessions hold
 * ({@link #ALLOWED_CLASSES}). A value a replica cannot read back (a class that changed in a newer release, during
 * a rolling update in either direction, or one the allow-list refuses) is treated as absent instead of failing
 * the request: at worst that person signs in again.
 */
@Configuration
public class SessionStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionStoreConfig.class);

    @Bean
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> tolerantSessionAttributes() {
        return repository -> repository.setConversionService(tolerantConversionService(
                SessionStoreConfig.class.getClassLoader()));
    }

    /**
     * What a session attribute may be made of when read back. Session rows are in the database: a row written by
     * anyone with database access must not be able to make ReadyRoom instantiate arbitrary classes (gadget chains).
     * The JDK's plain values and collections (not PriorityQueue, whose comparator runs on read), java.time, Spring
     * Security's own types (security context, tokens, saved request, CSRF token, OAuth2 authorization request,
     * OIDC user), Spring MVC's flash map, and ReadyRoom's own classes; nothing else, and never java.rmi or
     * java.net.URL (whose hashCode resolves the host). OIDC principals keep their claims as plain values for this
     * (security.sso.OidcSignIn).
     */
    static final String ALLOWED_CLASSES = "maxdepth=40;maxrefs=20000;maxbytes=2097152;maxarray=262144;"
            + "!java.util.PriorityQueue;!java.rmi.**;!java.net.**;!javax.management.**;"
            + "java.lang.*;java.util.*;java.time.*;java.security.cert.Certificate$CertificateRep;"
            + "org.springframework.security.**;org.springframework.web.servlet.FlashMap;"
            + "org.springframework.util.LinkedMultiValueMap;org.springframework.util.MultiValueMapAdapter;"
            + "com.winllc.innoutwork.**;!*";

    private static final ObjectInputFilter ALLOWED = ObjectInputFilter.Config.createFilter(ALLOWED_CLASSES);

    /** The allow-list, naming the class it refused. */
    private static final ObjectInputFilter FILTER = info -> {
        ObjectInputFilter.Status status = ALLOWED.checkInput(info);
        if (status == ObjectInputFilter.Status.REJECTED) {
            log.warn("A session attribute was refused: it holds {}, which sessions may not hold",
                    info.serialClass() != null ? info.serialClass().getName() : "more than the limits allow");
        }
        return status;
    };

    static GenericConversionService tolerantConversionService(ClassLoader classLoader) {
        GenericConversionService conversions = new GenericConversionService();
        conversions.addConverter(Object.class, byte[].class, new SerializingConverter());
        conversions.addConverter(byte[].class, Object.class, bytes -> deserialize(bytes, classLoader));
        return conversions;
    }

    /** The attribute, or null (absent) when it cannot be read back or holds a class it may not. */
    public static Object deserialize(byte[] bytes, ClassLoader classLoader) {
        try (ObjectInputStream in = new ConfigurableObjectInputStream(new ByteArrayInputStream(bytes), classLoader)) {
            in.setObjectInputFilter(FILTER);
            return in.readObject();
        } catch (InvalidClassException e) {
            log.debug("A refused session attribute was ignored: {}", e.getMessage());
            return null;
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            log.debug("A session attribute could not be read back and is ignored: {}", e.toString());
            return null;
        }
    }
}
