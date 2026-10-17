package com.winllc.innoutwork.audit;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

/**
 * The audit store's beans over an in-memory H2 database (PostgreSQL mode), for tests: the store, its queries,
 * tokens, outputs, retention and the collapsing of denials.
 */
public final class AuditStoreSupport {

    public static final String[] H2 = {
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.flyway.enabled=false",
            "ens.audit.product=readyroom"
    };

    private AuditStoreSupport() {
    }

    /** Import from a nested @Configuration of the test. */
    @Configuration
    @AutoConfigurationPackage(basePackages = "com.winllc.innoutwork")
    @Import({AuditConfiguration.class, AuditService.class, AuditQueryService.class, AuditTokenService.class,
            AuditOutputs.class, AuditRetention.class, AccessDenials.class})
    public static class Store {
    }

    /** Captures what the ens.audit logger writes (stdout and the file). */
    public static ListAppender<ILoggingEvent> captureAuditLines() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        context.getLogger("ens.audit").addAppender(appender);
        return appender;
    }

    public static void release(ListAppender<ILoggingEvent> appender) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger("ens.audit").detachAppender(appender);
    }

    public static List<String> lines(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
