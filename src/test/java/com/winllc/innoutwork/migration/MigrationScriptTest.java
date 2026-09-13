package com.winllc.innoutwork.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.Container.ExecResult;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@code db/migrations/001_indexes_and_unique_user_dn.sql} the way an operator would: through psql,
 * against real PostgreSQL, on a schema Hibernate created from the entities. Skipped without Docker.
 */
class MigrationScriptTest extends PostgresTestSupport {

    private static final Set<String> INDEXES = Set.of(
            "ux_user_records_dn_lower", "ix_check_in_out_dn_lower_ts", "ix_check_in_out_dn_ts", "ix_check_in_out_ts",
            "ix_check_in_out_session_id", "ix_user_events_dn_lower_date", "ix_user_events_date",
            "ix_notifications_for_dn_lower", "ix_notifications_about_dn_lower_date", "ix_notifications_uuid",
            "ix_permission_records_user_id", "ix_group_records_group_dn_lower", "ix_global_calendar_date");

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        INDEXES.forEach(index -> jdbc.execute("DROP INDEX IF EXISTS " + index));
        jdbc.execute("TRUNCATE user_records, permission_records, check_in_out_records, user_event_records,"
                + " notification_records, group_records, global_calendar_records RESTART IDENTITY CASCADE");
    }

    private long insertUser(String dn, String notes, String role, String favorites, String alternates) {
        return jdbc.queryForObject("INSERT INTO user_records (dn, notes, user_role, favorite_groups, alternate_managers)"
                + " VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class, dn, notes, role, favorites, alternates);
    }

    private void insertPermission(long userId, String groupDn) {
        jdbc.update("INSERT INTO permission_records (user_id, group_dn) VALUES (?, ?)", userId, groupDn);
    }

    @Test
    void mergesUsersWhoseDnsDifferOnlyByCase() throws Exception {
        String dn = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";
        long kept = insertUser(dn, null, "USER", "cn=Sales;cn=Ops", null);
        long upper = insertUser(dn.toUpperCase(), "In the lab", "ADMIN", "cn=Ops;cn=Eng", "cn=Alice");
        long mixed = insertUser("CN=Bob Barker,ou=Users,dc=winllc,dc=com", "Later note", null, null, "cn=Carol");
        long other = insertUser("cn=Carol Clark,ou=Users,dc=winllc,dc=com", "untouched", "USER", null, null);
        insertPermission(kept, "cn=Sales,ou=Groups");
        insertPermission(upper, "CN=SALES,OU=GROUPS");     // same grant as the kept row's
        insertPermission(upper, "cn=Eng,ou=Groups");
        insertPermission(mixed, "cn=Eng,ou=Groups");        // held by two duplicates

        ExecResult result = runMigration();

        assertTrue(result.getStderr().contains("Merging 2 duplicate user record(s) into 1 user(s)"), result.getStderr());
        List<Map<String, Object>> users = jdbc.queryForList("SELECT * FROM user_records ORDER BY id");
        assertEquals(2, users.size());
        Map<String, Object> bob = users.getFirst();
        assertEquals(kept, ((Number) bob.get("id")).longValue(), "the oldest row is kept");
        assertEquals(dn, bob.get("dn"));
        assertEquals("In the lab", bob.get("notes"), "an empty field is filled from the oldest duplicate that has it");
        assertEquals("ADMIN", bob.get("user_role"), "the most privileged role wins");
        assertEquals("cn=Eng;cn=Ops;cn=Sales", bob.get("favorite_groups"));
        assertEquals("cn=Alice;cn=Carol", bob.get("alternate_managers"));
        assertEquals(other, ((Number) users.get(1).get("id")).longValue());
        assertEquals("untouched", users.get(1).get("notes"));

        List<String> grants = jdbc.queryForList(
                "SELECT lower(group_dn) FROM permission_records WHERE user_id = ? ORDER BY 1", String.class, kept);
        assertEquals(List.of("cn=eng,ou=groups", "cn=sales,ou=groups"), grants);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM permission_records WHERE user_id <> ?", Long.class, kept));
    }

    @Test
    void createsEveryIndexAndEnforcesOneUserPerDn() throws Exception {
        insertUser("cn=Bob Barker,ou=Users,dc=winllc,dc=com", null, "USER", null, null);

        runMigration();

        Set<String> created = new TreeSet<>(jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class));
        assertTrue(created.containsAll(INDEXES), "missing: " + new TreeSet<>(INDEXES).stream().filter(i -> !created.contains(i)).toList());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE NOT i.indisvalid", Long.class));

        assertThrows(DataIntegrityViolationException.class,
                () -> insertUser("CN=BOB BARKER,OU=USERS,DC=WINLLC,DC=COM", null, "USER", null, null));
    }

    @Test
    void runningItAgainChangesNothing() throws Exception {
        insertUser("cn=Bob Barker,ou=Users,dc=winllc,dc=com", "note", "USER", null, null);
        runMigration();

        ExecResult again = runMigration();

        assertTrue(again.getStderr().contains("No duplicate user records"), again.getStderr());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM user_records", Long.class));
        assertEquals("note", jdbc.queryForObject("SELECT notes FROM user_records", String.class));
    }

    /** A failed CONCURRENTLY build leaves an invalid index that IF NOT EXISTS would otherwise skip. */
    @Test
    void rebuildsAnIndexLeftInvalidByAnEarlierFailedRun() throws Exception {
        jdbc.execute("CREATE INDEX ix_check_in_out_ts ON check_in_out_records (\"timestamp\")");
        jdbc.execute("UPDATE pg_index SET indisvalid = false WHERE indexrelid = 'ix_check_in_out_ts'::regclass");

        ExecResult result = runMigration();

        assertTrue(result.getStderr().contains("Dropping invalid index ix_check_in_out_ts"), result.getStderr());
        assertTrue(jdbc.queryForObject(
                "SELECT indisvalid FROM pg_index WHERE indexrelid = 'ix_check_in_out_ts'::regclass", Boolean.class));
    }

    @Test
    void theDnLookupTheApplicationMakesCanUseTheIndex() throws Exception {
        runMigration();

        // With a near-empty table the planner would pick a sequential scan anyway; rule that out to see
        // whether the index is usable for this query shape at all.
        String plan = jdbc.execute((ConnectionCallback<String>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                StringBuilder lines = new StringBuilder();
                try (ResultSet rows = statement.executeQuery("EXPLAIN SELECT * FROM check_in_out_records"
                        + " WHERE lower(dn) = lower('cn=Bob') AND \"timestamp\" >= now() - interval '1 day'")) {
                    while (rows.next()) {
                        lines.append(rows.getString(1)).append('\n');
                    }
                } finally {
                    statement.execute("RESET enable_seqscan");
                }
                return lines.toString();
            }
        });

        assertTrue(plan.contains("ix_check_in_out_dn_lower_ts"), plan);
    }

    @Test
    void refusesToRunBeforeTheApplicationHasCreatedTheTables() throws Exception {
        POSTGRES.execInContainer("createdb", "-U", POSTGRES.getUsername(), "empty_db");
        try {
            ExecResult result = runMigration("empty_db");

            assertNotEquals(0, result.getExitCode());
            assertTrue(result.getStderr().contains("Start the application once so Hibernate creates the schema"),
                    result.getStderr());
        } finally {
            POSTGRES.execInContainer("dropdb", "-U", POSTGRES.getUsername(), "--if-exists", "empty_db");
        }
    }
}
