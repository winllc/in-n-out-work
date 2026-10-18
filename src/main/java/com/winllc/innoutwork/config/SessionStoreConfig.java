package com.winllc.innoutwork.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * Browser sessions live in the database (Spring Session JDBC, tables from V6), so any replica serves any request
 * and a restart or rolling update keeps people signed in. The tables' clean-up is {@code security.SessionExpiry},
 * which also audits idle expiry (Spring's own clean-up is off: {@code spring.session.jdbc.cleanup-cron: "-"}).
 * <p>
 * Session attributes are Java-serialized. A value a replica cannot read back (a class that changed in a newer
 * release, during a rolling update in either direction) is treated as absent instead of failing the request: at
 * worst that person signs in again.
 */
@Configuration
public class SessionStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionStoreConfig.class);

    @Bean
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> tolerantSessionAttributes() {
        return repository -> repository.setConversionService(tolerantConversionService(
                SessionStoreConfig.class.getClassLoader()));
    }

    static GenericConversionService tolerantConversionService(ClassLoader classLoader) {
        GenericConversionService conversions = new GenericConversionService();
        conversions.addConverter(Object.class, byte[].class, new SerializingConverter());
        DeserializingConverter deserializer = new DeserializingConverter(classLoader);
        conversions.addConverter(byte[].class, Object.class, bytes -> {
            try {
                return deserializer.convert(bytes);
            } catch (RuntimeException e) {
                log.debug("A session attribute could not be read back and is ignored: {}", e.toString());
                return null;
            }
        });
        return conversions;
    }
}
