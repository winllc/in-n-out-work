package com.winllc.innoutwork.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The audit trail's outputs, all configured from {@code ENS_AUDIT_*} ({@link AuditProperties}):
 * <ul>
 *   <li><b>stdout</b> (on by default) and <b>file</b>: the {@code ens.audit} logger, with its own appenders and
 *       {@code additivity=false}, so each event is one raw JSON line and the application's log format never
 *       wraps it. Written synchronously after the store commits. The file rolls daily or at 100 MB.</li>
 *   <li><b>Splunk HEC</b> and <b>syslog</b>: a {@link AuditForwarder} each, delivering from the store.</li>
 *   <li>The <b>pull API</b>, {@code GET /api/audit/events}, always available to token holders.</li>
 * </ul>
 * Startup logs one line per output saying whether it is enabled; the Splunk token is never logged.
 */
@Component
public class AuditOutputs implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AuditOutputs.class);

    static final String PATTERN = "%msg%n";
    static final long MAX_FILE_BYTES = 100L * 1024 * 1024;

    private final AuditProperties properties;
    private final AuditService audit;
    private final AuditQueryService query;
    private final AuditOutputStateRepository states;
    private final List<AuditForwarder> forwarders = new ArrayList<>();

    public AuditOutputs(AuditProperties properties, AuditService audit, AuditQueryService query,
                        AuditOutputStateRepository states) {
        this(properties, audit, query, states, null);
    }

    @Autowired
    public AuditOutputs(AuditProperties properties, AuditService audit, AuditQueryService query,
                        AuditOutputStateRepository states, ObjectProvider<LockProvider> locks) {
        this.properties = properties;
        this.audit = audit;
        this.query = query;
        this.states = states;
        configureLogger(properties);
        Duration alertAfter = Duration.ofMinutes(properties.getFailureAlertMinutes());
        if (properties.getSplunk().isEnabled()) {
            try {
                forwarders.add(new AuditForwarder(new SplunkHecOutput(properties.getSplunk(), audit.product(),
                        audit.host()), audit, query, states, alertAfter, properties.getPollMillis(), Clock.systemUTC()));
            } catch (RuntimeException e) {
                log.error("Audit output splunk: NOT started: {}", e.getMessage());
            }
        }
        if (properties.getSyslog().isEnabled()) {
            try {
                forwarders.add(new AuditForwarder(new SyslogOutput(properties.getSyslog(), audit.product(),
                        audit.host()), audit, query, states, alertAfter, properties.getPollMillis(), Clock.systemUTC()));
            } catch (RuntimeException e) {
                log.error("Audit output syslog: NOT started: {}", e.getMessage());
            }
        }
        LockProvider lockProvider = locks == null ? null : locks.getIfAvailable();
        if (lockProvider != null) {
            // Replicas take turns delivering each batch (see AuditForwarder).
            DefaultLockingTaskExecutor executor = new DefaultLockingTaskExecutor(lockProvider);
            forwarders.forEach(f -> f.setClusterLock(executor));
        }
        forwarders.forEach(f -> audit.onAppend(f::wake));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        log.info("Audit output stdout: {}", properties.isStdout() ? "enabled (logger ens.audit)" : "disabled");
        log.info("Audit output file: {}", fileEnabled()
                ? "enabled, " + properties.getFile() + " (daily or 100 MB, keeping " + properties.getFileKeep() + ")"
                : "disabled");
        log.info("Audit output splunk: {}", outputLine("splunk"));
        if (properties.getSplunk().isEnabled() && properties.getSplunk().isInsecure()) {
            log.warn("ENS_AUDIT_SPLUNK_INSECURE is true: the Splunk HEC certificate is NOT checked. Test setups only.");
        }
        log.info("Audit output syslog: {}", outputLine("syslog"));
        log.info("Audit pull API: enabled at /api/audit/events (token holders only)");
        log.info("Audit retention: {}", properties.getRetentionDays() > 0
                ? properties.getRetentionDays() + " days" : "keep forever");
        forwarders.forEach(AuditForwarder::start);
    }

    private String outputLine(String name) {
        return forwarders.stream().filter(f -> f.output().name().equals(name)).findFirst()
                .map(f -> "enabled, " + f.output().describe())
                .orElse("disabled");
    }

    @Override
    public void destroy() {
        forwarders.forEach(AuditForwarder::stop);
    }

    public List<AuditForwarder> forwarders() {
        return List.copyOf(forwarders);
    }

    /** The lowest seq every enabled forwarding output has delivered: retention never prunes past it. */
    public long deliveredByAll(long headSeq) {
        long min = headSeq;
        for (AuditForwarder f : forwarders) {
            min = Math.min(min, f.state().getLastSeq());
        }
        return min;
    }

    private boolean fileEnabled() {
        return properties.getFile() != null && !properties.getFile().isBlank();
    }

    /** One row of the delivery status panel. */
    public record Status(String name, boolean enabled, String destination, Long lastSeq, Instant lastDeliveredAt,
                         Long backlog, String lastError, Instant lastErrorAt, Instant failingSince) {
    }

    public List<Status> status() {
        long head = audit.headSeq();
        List<Status> rows = new ArrayList<>();
        rows.add(new Status("stdout", properties.isStdout(), "logger ens.audit", properties.isStdout() ? head : null,
                null, properties.isStdout() ? 0L : null, null, null, null));
        rows.add(new Status("file", fileEnabled(), fileEnabled() ? properties.getFile() : null,
                fileEnabled() ? head : null, null, fileEnabled() ? 0L : null, null, null, null));
        for (String name : List.of("splunk", "syslog")) {
            AuditForwarder f = forwarders.stream().filter(x -> x.output().name().equals(name)).findFirst().orElse(null);
            if (f == null) {
                rows.add(new Status(name, false, null, null, null, null, null, null, null));
            } else {
                AuditOutputState s = f.state();
                rows.add(new Status(name, true, f.output().describe(), s.getLastSeq(), s.getLastDeliveredAt(),
                        Math.max(0, head - s.getLastSeq()), s.getLastError(), s.getLastErrorAt(), s.getFailingSince()));
            }
        }
        return rows;
    }

    /** Points the {@code ens.audit} logger at its own appenders, with no prefix and no additivity. */
    static void configureLogger(AuditProperties properties) {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            log.warn("Logging is not Logback; the ens.audit logger is left as configured");
            return;
        }
        ch.qos.logback.classic.Logger logger = context.getLogger("ens.audit");
        logger.detachAndStopAllAppenders();
        logger.setAdditive(false);
        boolean any = false;

        if (properties.isStdout()) {
            ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<>();
            console.setContext(context);
            console.setName("ens-audit-stdout");
            console.setEncoder(encoder(context));
            console.start();
            logger.addAppender(console);
            any = true;
        }

        String file = properties.getFile();
        if (file != null && !file.isBlank()) {
            File target = new File(file.trim());
            File dir = target.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                log.error("Cannot create the audit file's directory {}", dir);
            }
            RollingFileAppender<ILoggingEvent> rolling = new RollingFileAppender<>();
            rolling.setContext(context);
            rolling.setName("ens-audit-file");
            rolling.setFile(target.getPath());
            SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = new SizeAndTimeBasedRollingPolicy<>();
            policy.setContext(context);
            policy.setParent(rolling);
            policy.setFileNamePattern(target.getPath() + ".%d{yyyy-MM-dd}.%i");
            policy.setMaxFileSize(new FileSize(MAX_FILE_BYTES));
            int keep = Math.max(1, properties.getFileKeep());
            policy.setMaxHistory(keep);
            policy.setTotalSizeCap(new FileSize(MAX_FILE_BYTES * keep));
            policy.start();
            rolling.setRollingPolicy(policy);
            rolling.setEncoder(encoder(context));
            rolling.start();
            logger.addAppender(rolling);
            any = true;
        }
        logger.setLevel(any ? Level.INFO : Level.OFF);
    }

    private static PatternLayoutEncoder encoder(LoggerContext context) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(PATTERN);
        encoder.start();
        return encoder;
    }
}
