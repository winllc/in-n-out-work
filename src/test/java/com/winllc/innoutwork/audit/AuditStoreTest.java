package com.winllc.innoutwork.audit;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store: gapless seq and an intact chain under concurrent writers, tamper detection at the right seq,
 * verification after pruning, transactions, and that the lines go to the ens.audit logger only after commit.
 * Runs without a test transaction, so writes commit as they do in the application.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auditstore;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;LOCK_TIMEOUT=20000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditStoreTest {

    @Configuration
    @Import(AuditStoreSupport.Store.class)
    static class Config {
    }

    @Autowired AuditService audit;
    @Autowired AuditQueryService query;
    @Autowired AuditEventRepository events;
    @Autowired AuditProperties properties;
    @Autowired AuditOutputs outputs;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private ListAppender<ILoggingEvent> lines;

    @BeforeEach
    void empty() {
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM audit_chain_head");
        lines = AuditStoreSupport.captureAuditLines();
    }

    @AfterEach
    void release() {
        AuditStoreSupport.release(lines);
        properties.setRetentionDays(0);
    }

    private static AuditEntry event(String action) {
        return AuditEntry.of(AuditEntry.Category.SYSTEM, action).actor(AuditEntry.Actor.system());
    }

    @Test
    void eventsAreChainedFromSeqOneAndEmittedAsTheStoredLine() {
        audit.record(event("system.started"));
        audit.record(AuditEntry.of(AuditEntry.Category.CONFIGURATION, "settings.changed").change("x", 1, 2));

        List<AuditEventRecord> stored = events.findAll(org.springframework.data.domain.Sort.by("seq"));
        assertEquals(2, stored.size());
        AuditEventRecord first = stored.get(0);
        AuditEventRecord second = stored.get(1);
        assertEquals(1, first.getSeq());
        assertEquals(2, second.getSeq());
        assertTrue(first.getLine().startsWith("{\"schema\":\"ens-audit/1\",\"id\":\""));
        assertTrue(first.getLine().contains("\"product\":\"readyroom\""));
        assertEquals(AuditJson.ZERO_HASH, AuditJson.prevHashOf(AuditJson.bodyOf(first.getLine())));
        assertEquals(first.getHash(), AuditJson.prevHashOf(AuditJson.bodyOf(second.getLine())));
        assertEquals(AuditJson.hash(first.getHash(), AuditJson.bodyOf(second.getLine())), second.getHash());
        assertEquals("notice", second.getSeverity(), "configuration changes default to notice");
        assertTrue(second.getLine().contains("\"details\":{\"x.old\":1,\"x.new\":2}"));

        assertEquals(List.of(first.getLine(), second.getLine()), AuditStoreSupport.lines(lines),
                "stdout gets exactly the stored lines, in order");
        assertTrue(query.verify().intact());
    }

    @Test
    void concurrentWritersGetGaplessSeqAndAnIntactChain() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 80; i++) {
                int n = i;
                futures.add(pool.submit(() -> audit.record(event("job.completed").detail("n", n))));
            }
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        List<Long> seqs = events.findAll(org.springframework.data.domain.Sort.by("seq")).stream()
                .map(AuditEventRecord::getSeq).toList();
        assertEquals(80, seqs.size());
        for (int i = 0; i < seqs.size(); i++) {
            assertEquals(i + 1, seqs.get(i));
        }
        AuditQueryService.Verification v = query.verify();
        assertTrue(v.intact(), v.summary());
        assertEquals(80, v.checked());
    }

    @Test
    void tamperingWithAStoredEventIsDetectedAtItsSeq() {
        for (int i = 0; i < 5; i++) {
            audit.record(event("job.completed").detail("n", i));
        }
        String line = events.findById(3L).orElseThrow().getLine();
        jdbc.update("UPDATE audit_events SET line = ? WHERE seq = 3", line.replace("\"n\":2", "\"n\":42"));

        AuditQueryService.Verification v = query.verify();

        assertFalse(v.intact());
        assertEquals(3L, v.brokenSeq());
        assertTrue(v.summary().startsWith("Broken at seq 3"), v.summary());
    }

    @Test
    void aDeletedEventInTheMiddleIsDetected() {
        for (int i = 0; i < 4; i++) {
            audit.record(event("job.completed"));
        }
        jdbc.update("DELETE FROM audit_events WHERE seq = 2");

        AuditQueryService.Verification v = query.verify();

        assertFalse(v.intact());
        assertEquals(2L, v.brokenSeq());
    }

    @Test
    void verificationAfterPruningReportsTheAnchor() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofDays(40)), ZoneOffset.UTC);
        for (int i = 0; i < 3; i++) {
            audit.record(event("job.completed").time(past.instant().plusSeconds(i)));
        }
        for (int i = 0; i < 2; i++) {
            audit.record(event("job.completed"));
        }
        properties.setRetentionDays(30);
        AuditRetention retention = new AuditRetention(properties, audit, events, outputs, transactions);

        assertEquals(3, retention.prune());

        AuditQueryService.Verification v = query.verify();
        assertTrue(v.intact(), v.summary());
        assertEquals(4L, v.anchorSeq());
        assertFalse(v.anchorIsGenesis());
        assertTrue(v.summary().contains("from seq 4"), v.summary());
        AuditEventRecord pruned = events.findFirstByOrderBySeqDesc().orElseThrow();
        assertEquals("audit.pruned", pruned.getAction());
        assertTrue(pruned.getLine().contains("\"count\":3,\"fromSeq\":1,\"toSeq\":3"), pruned.getLine());
    }

    @Test
    void pruningNeverRemovesTheNewestEvent() {
        Instant old = Instant.now().minus(Duration.ofDays(400));
        audit.record(event("job.completed").time(old));
        audit.record(event("job.completed").time(old.plusSeconds(1)));
        properties.setRetentionDays(1);

        new AuditRetention(properties, audit, events, outputs, transactions).prune();

        assertTrue(events.findById(2L).isPresent(), "the chain head survives");
        assertTrue(query.verify().intact());
    }

    @Test
    void anEventRecordedInATransactionThatRollsBackLeavesNoTraceAndNoGap() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            audit.record(event("settings.changed"));
            throw new IllegalStateException("the change failed");
        }));
        assertEquals(0, events.count());
        assertTrue(AuditStoreSupport.lines(lines).isEmpty(), "nothing is emitted for a rolled-back event");

        audit.record(event("settings.changed"));
        assertEquals(1L, events.findFirstByOrderBySeqDesc().orElseThrow().getSeq());
    }

    @Test
    void aQuietEventInAFailingTransactionIsStillRecordedAfterwards() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            audit.recordQuietly(event("license.limit-exceeded"));
            throw new IllegalStateException("refused");
        }));
        assertEquals(1, events.count());
    }

    @Test
    void requireAvailableFailsWhenTheStoreCannotBeWritten() {
        jdbc.execute("ALTER TABLE audit_chain_head RENAME TO audit_chain_head_gone");
        try {
            assertThrows(AuditUnavailableException.class, () -> audit.requireAvailable());
            assertThrows(AuditUnavailableException.class, () -> audit.record(event("settings.changed")));
            audit.recordQuietly(event("job.completed")); // logged, not thrown
        } finally {
            jdbc.execute("ALTER TABLE audit_chain_head_gone RENAME TO audit_chain_head");
        }
    }

    @Test
    void filtersSearchTheColumnsAndTheJson() {
        audit.record(AuditEntry.of(AuditEntry.Category.AUTHENTICATION, "auth.login").failure("bad credentials")
                .actor(AuditEntry.Actor.anonymous("jdoe")).detail("method", "ldap"));
        audit.record(AuditEntry.of(AuditEntry.Category.ACCOUNT, "account.role-changed")
                .target("user", "cn=Bob,ou=Users", "Bob").change("role", "USER", "ADMIN"));
        audit.record(event("job.completed"));

        assertEquals(1, query.search(new AuditQueryService.Filter(null, null, "authentication", null, null, null,
                null, null, null, null), 0, 10).getTotalElements());
        assertEquals(1, query.search(new AuditQueryService.Filter(null, null, null, null, "failure", "warning",
                "JDO", null, null, null), 0, 10).getTotalElements());
        assertEquals(1, query.search(new AuditQueryService.Filter(null, null, null, null, null, null,
                null, "bob", null, null), 0, 10).getTotalElements());
        assertEquals(1, query.search(new AuditQueryService.Filter(null, null, null, null, null, null,
                null, null, null, "\"role.new\":\"admin"), 0, 10).getTotalElements());
        assertEquals(0, query.search(new AuditQueryService.Filter(Instant.now().plusSeconds(60), null, null, null,
                null, null, null, null, null, null), 0, 10).getTotalElements());
        assertNull(query.search(AuditQueryService.Filter.none(), 0, 10).getContent().isEmpty() ? "empty" : null);
        assertEquals(3L, query.search(AuditQueryService.Filter.none(), 0, 10).getContent().getFirst().getSeq(),
                "newest first");
    }
}
