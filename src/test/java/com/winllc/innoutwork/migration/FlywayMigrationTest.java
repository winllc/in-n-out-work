package com.winllc.innoutwork.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Flyway migrations on real PostgreSQL (skipped without Docker), configured as application.yml configures
 * them: V1 on an empty database, on one an earlier ReadyRoom created (Hibernate plus the legacy psql scripts),
 * and on an older one that never ran those scripts, with the same schema at the end every time. The shared
 * test database itself is migrated by Flyway at startup and validated by Hibernate, so the context starting
 * at all proves that schema matches the entities.
 */
class FlywayMigrationTest extends PostgresTestSupport {

    /** What Hibernate created before Flyway; see the file's header. */
    static final Path LEGACY_SCHEMA = Path.of("src/test/resources/db/legacy-hibernate-schema.sql");
    static final Path V1 = Path.of("src/main/resources/db/migration/V1__baseline.sql");
    static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /** Every migration file's version, in order: V1 and those after it. */
    static List<Integer> versions() {
        try (var files = java.nio.file.Files.list(MIGRATIONS)) {
            return files.map(f -> f.getFileName().toString())
                    .filter(n -> n.matches("V\\d+__.*\\.sql"))
                    .map(n -> Integer.parseInt(n.substring(1, n.indexOf("__"))))
                    .sorted()
                    .toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static String latestVersion() {
        return String.valueOf(versions().getLast());
    }

    private static final Set<String> INDEXES = Set.of(
            "ux_user_records_dn_lower", "ix_check_in_out_dn_lower_ts", "ix_check_in_out_dn_ts", "ix_check_in_out_ts",
            "ix_check_in_out_session_id", "ix_user_events_dn_lower_date", "ix_user_events_date",
            "ix_notifications_for_dn_lower", "ix_notifications_about_dn_lower_date", "ix_notifications_uuid",
            "ix_permission_records_user_id", "ix_group_records_group_dn_lower", "ix_global_calendar_date",
            "ux_audit_events_event_id", "ix_audit_events_time", "ix_audit_events_category", "ix_audit_events_action",
            "ix_audit_events_outcome", "ix_audit_events_severity", "ix_audit_events_actor_id",
            "ix_audit_events_target", "ix_audit_events_source_ip", "ux_audit_api_tokens_hash",
            // V2: status requests
            "ix_status_requests_requester_lower", "ix_status_requests_state", "ix_status_requests_to_date",
            "ix_user_events_request_id");

    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> databases = new ArrayList<>();

    @AfterEach
    void dropDatabases() throws Exception {
        for (String db : databases) {
            POSTGRES.execInContainer("dropdb", "-U", POSTGRES.getUsername(), "--if-exists", db);
        }
        POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-c",
                "DROP ROLE IF EXISTS rr_app; DROP ROLE IF EXISTS rr_migrator;");
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private String createDatabase(String name) throws Exception {
        ExecResult created = POSTGRES.execInContainer("createdb", "-U", POSTGRES.getUsername(), name);
        assertEquals(0, created.getExitCode(), created.getStderr());
        databases.add(name);
        return name;
    }

    /** A database as the last release before Flyway left it: Hibernate's tables, optionally the legacy scripts. */
    private String legacyDatabase(String name) throws Exception {
        createDatabase(name);
        ExecResult schema = psqlFile(name, LEGACY_SCHEMA);
        assertEquals(0, schema.getExitCode(), schema.getStderr());
        return name;
    }

    /** Flyway as application.yml configures it (spring.flyway.*), against {@code database}. */
    static Flyway flyway(String database, String user, String password) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties app = yaml.getObject();
        assertNotNull(app);
        return Flyway.configure()
                .dataSource(jdbcUrl(database), user, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(Boolean.parseBoolean(app.getProperty("spring.flyway.baseline-on-migrate")))
                .baselineVersion(app.getProperty("spring.flyway.baseline-version"))
                .baselineDescription(app.getProperty("spring.flyway.baseline-description"))
                .load();
    }

    private static MigrateResult migrate(String database) {
        return flyway(database, POSTGRES.getUsername(), POSTGRES.getPassword()).migrate();
    }

    private static JdbcTemplate jdbc(String database, String user, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(database), user, password));
    }

    private static JdbcTemplate jdbc(String database) {
        return jdbc(database, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * The schema as pg_dump prints it, one normalized line each, sorted: column order is lost on purpose (a
     * column V1 adds to an old table goes at the end) and so is the data (Flyway's baseline row).
     */
    private static List<String> schema(String database) throws Exception {
        ExecResult dump = POSTGRES.execInContainer("pg_dump", "--schema-only", "--no-owner", "--no-privileges",
                "-U", POSTGRES.getUsername(), database);
        assertEquals(0, dump.getExitCode(), dump.getStderr());
        return dump.getStdout().lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("--") && !l.startsWith("SET ")
                        && !l.startsWith("SELECT pg_catalog.") && !l.startsWith("\\"))
                .map(l -> l.endsWith(",") ? l.substring(0, l.length() - 1) : l)
                .sorted()
                .toList();
    }

    private static Set<String> indexes(JdbcTemplate db) {
        return new TreeSet<>(db.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class));
    }

    private static long insertUser(JdbcTemplate db, String dn, String notes, String role, String favorites,
                                   String alternates) {
        return db.queryForObject("INSERT INTO user_records (dn, notes, user_role, favorite_groups, alternate_managers)"
                + " VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class, dn, notes, role, favorites, alternates);
    }

    private static void insertPermission(JdbcTemplate db, long userId, String groupDn) {
        db.update("INSERT INTO permission_records (user_id, group_dn) VALUES (?, ?)", userId, groupDn);
    }

    // --- an empty database ---------------------------------------------------------------------------------

    @Test
    void anEmptyDatabaseGetsTheWholeSchema() throws Exception {
        String db = createDatabase("fresh");

        MigrateResult result = migrate(db);

        assertTrue(result.success);
        assertEquals(versions().size(), result.migrationsExecuted, "V1 and every migration after it");
        assertNull(result.initialSchemaVersion, "an empty database is not baselined, V1 just runs");
        JdbcTemplate fresh = jdbc(db);
        assertTrue(indexes(fresh).containsAll(INDEXES), () -> "missing: " + INDEXES.stream()
                .filter(i -> !indexes(fresh).contains(i)).toList());
        assertEquals(1, fresh.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname = 'audit_events_no_update'",
                Integer.class));
        assertEquals(List.of("fk_permission_records_user", "fk_user_event_records_request"), fresh.queryForList(
                "SELECT conname FROM pg_constraint WHERE contype = 'f' ORDER BY conname", String.class));
        assertEquals(0, migrate(db).migrationsExecuted, "a second start has nothing to do");
    }

    /** The shared test database: Flyway ran at startup and Hibernate validated the result against the entities. */
    @Test
    void theApplicationDatabaseIsMigratedAndValidated() {
        assertEquals(latestVersion(), jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class));
        assertTrue(indexes(jdbc).containsAll(INDEXES));
    }

    // --- a database the old release created -----------------------------------------------------------------

    /** Hibernate's tables plus db/legacy/001 and 002: what a fully migrated install had before Flyway. */
    @Test
    void aDatabaseTheLastReleaseLeftGetsTheSameSchemaAndKeepsItsData() throws Exception {
        String db = legacyDatabase("legacy_scripted");
        JdbcTemplate legacy = jdbc(db);
        long bob = insertUser(legacy, "cn=Bob Barker,ou=Users,dc=winllc,dc=com", "note", "MANAGER", "cn=Ops", null);
        insertPermission(legacy, bob, "cn=Ops,ou=Groups");
        legacy.update("INSERT INTO check_in_out_records (dn, \"timestamp\", action, session_id) VALUES (?, now(), 'CHECK_IN', 's1')",
                "cn=Bob Barker,ou=Users,dc=winllc,dc=com");
        assertEquals(0, runLegacyMigration(db).getExitCode());
        assertEquals(0, psqlFile(db, LEGACY_002).getExitCode());

        MigrateResult result = migrate(db);

        assertTrue(result.success);
        assertEquals("0", result.initialSchemaVersion, "an existing schema is baselined at 0 ...");
        assertEquals(versions().size(), result.migrationsExecuted, "... and V1 still runs on it, then the rest");
        String fresh = createDatabase("fresh_for_scripted");
        migrate(fresh);
        assertEquals(schema(fresh), schema(db));
        assertEquals("note", legacy.queryForObject("SELECT notes FROM user_records WHERE id = ?", String.class, bob));
        assertEquals(1, legacy.queryForObject("SELECT count(*) FROM permission_records", Integer.class));
        assertEquals(1, legacy.queryForObject("SELECT count(*) FROM check_in_out_records", Integer.class));
    }

    /**
     * An install from further back that never ran the legacy scripts: columns added since are missing, an enum
     * check lists fewer statuses, users differ only by DN case, and an interrupted index build left an INVALID
     * index. V1 fixes all of it and ends with the same schema as a new install.
     */
    @Test
    void anOlderDatabaseThatNeverRanTheScriptsIsMergedIndexedAndCompleted() throws Exception {
        String db = legacyDatabase("legacy_unscripted");
        JdbcTemplate legacy = jdbc(db);
        legacy.execute("ALTER TABLE check_in_out_records DROP COLUMN session_id, DROP COLUMN forced,"
                + " DROP COLUMN duty_sub_organization");
        legacy.execute("ALTER TABLE user_records DROP COLUMN duty_sub_organization, DROP COLUMN chosen_login_time");
        legacy.execute("ALTER TABLE user_event_records DROP CONSTRAINT user_event_records_status_check");
        legacy.execute("ALTER TABLE user_event_records ADD CONSTRAINT user_event_records_status_check"
                + " CHECK (status IN ('STANDARD', 'ABSENT'))");
        legacy.execute("CREATE INDEX ix_check_in_out_ts ON check_in_out_records (\"timestamp\")");
        legacy.execute("UPDATE pg_index SET indisvalid = false WHERE indexrelid = 'ix_check_in_out_ts'::regclass");

        String dn = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";
        long kept = insertUser(legacy, dn, null, "USER", "cn=Sales;cn=Ops", null);
        long upper = insertUser(legacy, dn.toUpperCase(), "In the lab", "ADMIN", "cn=Ops;cn=Eng", "cn=Alice");
        long mixed = insertUser(legacy, "CN=Bob Barker,ou=Users,dc=winllc,dc=com", "Later note", null, null, "cn=Carol");
        long other = insertUser(legacy, "cn=Carol Clark,ou=Users,dc=winllc,dc=com", "untouched", "USER", null, null);
        insertPermission(legacy, kept, "cn=Sales,ou=Groups");
        insertPermission(legacy, upper, "CN=SALES,OU=GROUPS");     // same grant as the kept row's
        insertPermission(legacy, upper, "cn=Eng,ou=Groups");
        insertPermission(legacy, mixed, "cn=Eng,ou=Groups");        // held by two duplicates

        assertTrue(migrate(db).success);

        List<Map<String, Object>> users = legacy.queryForList("SELECT * FROM user_records ORDER BY id");
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
        assertEquals(List.of("cn=eng,ou=groups", "cn=sales,ou=groups"), legacy.queryForList(
                "SELECT lower(group_dn) FROM permission_records WHERE user_id = ? ORDER BY 1", String.class, kept));
        assertEquals(0, legacy.queryForObject("SELECT count(*) FROM permission_records WHERE user_id <> ?",
                Long.class, kept));

        assertTrue(legacy.queryForObject(
                "SELECT indisvalid FROM pg_index WHERE indexrelid = 'ix_check_in_out_ts'::regclass", Boolean.class),
                "the invalid index is rebuilt");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertUser(legacy, "CN=BOB BARKER,OU=USERS,DC=WINLLC,DC=COM", null, "USER", null, null),
                "one user record per DN from now on");
        legacy.update("INSERT INTO user_event_records (dn, date, status) VALUES (?, current_date, 'LATE_ARRIVAL')", dn);
        assertThrows(DataAccessException.class, () -> legacy.update(
                "INSERT INTO user_event_records (dn, date, status) VALUES (?, current_date, 'NOT_A_STATUS')", dn));

        String fresh = createDatabase("fresh_for_unscripted");
        migrate(fresh);
        assertEquals(schema(fresh), schema(db));
    }

    /** V1 is written to run over what it already did; run by hand a second time, it changes nothing. */
    @Test
    void runningTheBaselineAgainChangesNothing() throws Exception {
        String db = createDatabase("twice");
        migrate(db);
        JdbcTemplate twice = jdbc(db);
        insertUser(twice, "cn=Bob Barker,ou=Users,dc=winllc,dc=com", "note", "USER", null, null);
        List<String> before = schema(db);

        ExecResult again = psqlFile(db, V1, true);

        assertEquals(0, again.getExitCode(), again.getStderr());
        assertEquals(before, schema(db));
        assertEquals(1, twice.queryForObject("SELECT count(*) FROM user_records", Long.class));
        assertEquals("note", twice.queryForObject("SELECT notes FROM user_records", String.class));
    }

    // --- after the baseline ------------------------------------------------------------------------------------

    /** Flyway as configured, but stopping at {@code version}: a database as an earlier release left it. */
    private static void migrateTo(String database, String version) {
        Flyway configured = flyway(database, POSTGRES.getUsername(), POSTGRES.getPassword());
        assertTrue(Flyway.configure().configuration(configured.getConfiguration()).target(version).load().migrate().success);
    }

    /**
     * V3: people no longer choose their own delegates, but those they chose before are kept, marked for an
     * administrator to review, so an upgrade does not silently change who receives their alerts.
     */
    @Test
    void delegatesPeopleChoseThemselvesAreKeptAndMarkedForReview() throws Exception {
        String db = createDatabase("delegates");
        migrateTo(db, "2");
        JdbcTemplate before = jdbc(db);
        long chose = insertUser(before, "cn=Alice,ou=Users,dc=winllc,dc=com", null, "USER", null,
                "cn=Bob,ou=Users,dc=winllc,dc=com;cn=Carol,ou=Users,dc=winllc,dc=com");
        long none = insertUser(before, "cn=Dan,ou=Users,dc=winllc,dc=com", null, "USER", null, null);
        long emptied = insertUser(before, "cn=Erin,ou=Users,dc=winllc,dc=com", null, "USER", null, ";");

        assertTrue(migrate(db).success);

        JdbcTemplate after = jdbc(db);
        String pending = "SELECT alternate_managers_review_pending FROM user_records WHERE id = ?";
        assertTrue(after.queryForObject(pending, Boolean.class, chose));
        assertFalse(after.queryForObject(pending, Boolean.class, none));
        assertFalse(after.queryForObject(pending, Boolean.class, emptied));
        assertEquals("cn=Bob,ou=Users,dc=winllc,dc=com;cn=Carol,ou=Users,dc=winllc,dc=com", after.queryForObject(
                "SELECT alternate_managers FROM user_records WHERE id = ?", String.class, chose), "kept as they were");
        assertEquals(2000, after.queryForObject("SELECT character_maximum_length FROM information_schema.columns"
                + " WHERE table_name = 'user_records' AND column_name = 'alternate_managers'", Integer.class));
    }

    // --- a separate migration account -----------------------------------------------------------------------

    /**
     * SPRING_FLYWAY_USER owns the schema; the application's own account, with the grants db/README.md lists, can
     * read and write rows (retention deletes audit events) but not change the schema.
     */
    @Test
    void theApplicationAccountCanBeLimitedToRows() throws Exception {
        psql(POSTGRES.getDatabaseName(), "CREATE ROLE rr_migrator LOGIN PASSWORD 'migrator-pw';"
                + " CREATE ROLE rr_app LOGIN PASSWORD 'app-pw';");
        ExecResult created = POSTGRES.execInContainer("createdb", "-U", POSTGRES.getUsername(), "-O", "rr_migrator", "split");
        assertEquals(0, created.getExitCode(), created.getStderr());
        databases.add("split");
        // As in db/README.md, run by the migration account before the first start.
        JdbcTemplate owner = jdbc("split", "rr_migrator", "migrator-pw");
        owner.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        owner.execute("GRANT USAGE ON SCHEMA public TO rr_app");
        owner.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rr_app");
        owner.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO rr_app");

        assertTrue(flyway("split", "rr_migrator", "migrator-pw").migrate().success);

        JdbcTemplate app = jdbc("split", "rr_app", "app-pw");
        long id = insertUser(app, "cn=Bob Barker,ou=Users,dc=winllc,dc=com", null, "USER", null, null);
        app.update("UPDATE user_records SET notes = 'x' WHERE id = ?", id);
        app.update("INSERT INTO audit_events (seq, event_id, event_time, category, action, outcome, severity, line, hash)"
                + " VALUES (1, 'e1', now(), 'system', 'system.started', 'success', 'info', '{}', 'h')");
        assertThrows(DataAccessException.class, () -> app.update("UPDATE audit_events SET action = 'x'"),
                "the audit trail stays append-only");
        assertEquals(1, app.update("DELETE FROM audit_events WHERE seq = 1"), "retention can still prune");
        assertThrows(DataAccessException.class, () -> app.execute("CREATE TABLE sneaky (id int)"));
        assertThrows(DataAccessException.class, () -> app.execute("ALTER TABLE user_records ADD COLUMN sneaky int"));
    }

    // --- what the indexes are for ----------------------------------------------------------------------------

    @Test
    void theDnLookupTheApplicationMakesCanUseTheIndex() {
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
}
