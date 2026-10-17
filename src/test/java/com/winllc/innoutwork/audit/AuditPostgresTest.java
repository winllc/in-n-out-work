package com.winllc.innoutwork.audit;

import com.winllc.innoutwork.migration.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit store on real PostgreSQL (skipped without Docker): the chain head's row lock keeps seq gapless under
 * concurrent writers, and db/migrations/002_audit_log.sql makes stored events impossible to update while
 * retention can still delete them.
 */
@Import(AuditStoreSupport.Store.class)
class AuditPostgresTest extends PostgresTestSupport {

    static final Path MIGRATION_002 = Path.of("db/migrations/002_audit_log.sql");

    @Autowired AuditService audit;
    @Autowired AuditQueryService query;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void empty() {
        jdbc.execute("DROP TRIGGER IF EXISTS audit_events_no_update ON audit_events");
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM audit_chain_head");
    }

    private static ExecResult migrate() throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(MIGRATION_002), "/tmp/002.sql");
        ExecResult result = POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d",
                POSTGRES.getDatabaseName(), "-v", "ON_ERROR_STOP=1", "-f", "/tmp/002.sql");
        assertEquals(0, result.getExitCode(), () -> "psql failed:\n" + result.getStdout() + result.getStderr());
        return result;
    }

    @Test
    void concurrentWritersOnPostgresGetGaplessSeqAndAnIntactChain() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int n = i;
                futures.add(pool.submit(() -> audit.record(AuditEntry.of(AuditEntry.Category.SYSTEM, "job.completed")
                        .actor(AuditEntry.Actor.system()).detail("n", n))));
            }
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(List.of(1L, 100L, 100L),
                jdbc.queryForList("SELECT min(seq), max(seq), count(*) FROM audit_events").getFirst().values()
                        .stream().map(v -> ((Number) v).longValue()).toList());
        assertTrue(query.verify().intact());
    }

    @Test
    void theMigrationMakesEventsAppendOnlyAndIsRepeatable() throws Exception {
        audit.record(AuditEntry.of(AuditEntry.Category.SYSTEM, "system.started").actor(AuditEntry.Actor.system()));
        audit.record(AuditEntry.of(AuditEntry.Category.SYSTEM, "job.completed").actor(AuditEntry.Actor.system()));

        assertTrue(migrate().getStdout().contains("Migration 002 complete."));
        migrate();

        assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE audit_events SET action = 'x' WHERE seq = 1"));
        audit.record(AuditEntry.of(AuditEntry.Category.SYSTEM, "job.completed").actor(AuditEntry.Actor.system()));
        assertEquals(1, jdbc.update("DELETE FROM audit_events WHERE seq = 1"), "retention can still prune");
        assertTrue(query.verify().intact());
        assertEquals(10, jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename IN ('audit_events', 'audit_api_tokens')"
                        + " AND (indexname LIKE 'ix_audit_%' OR indexname LIKE 'ux_audit_%')", Integer.class));
    }
}
