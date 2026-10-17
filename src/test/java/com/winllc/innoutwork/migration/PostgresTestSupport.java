package com.winllc.innoutwork.migration;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Base for tests that need real PostgreSQL: one container shared by every such test class, with the schema
 * built as in production - Flyway runs src/main/resources/db/migration and Hibernate only validates it.
 * Skipped when Docker is not available.
 * <p>
 * Tests run without a test transaction, so their writes commit and other sessions (psql, a second
 * thread) can see them.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Named explicitly: Spring only discovers a nested @Configuration on the test class itself, not a base class.
@ContextConfiguration(classes = PostgresTestSupport.JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public abstract class PostgresTestSupport {

    /** The psql script that indexed databases before Flyway; kept for the record and for the upgrade tests. */
    public static final Path LEGACY_001 = Path.of("db/legacy/001_indexes_and_unique_user_dn.sql");
    public static final Path LEGACY_002 = Path.of("db/legacy/002_audit_log.sql");

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    /** The application class lives in a sibling package, so point entity and repository scanning at the root. */
    @Configuration
    @AutoConfigurationPackage(basePackages = "com.winllc.innoutwork")
    public static class JpaConfig {
    }

    /** JDBC URL of another database in the shared container. */
    protected static String jdbcUrl(String database) {
        return "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432), database);
    }

    /** Runs a SQL file with psql inside the container, against {@code database}, as an operator would. */
    protected static ExecResult psqlFile(String database, Path script) throws Exception {
        return psqlFile(database, script, false);
    }

    /** As above; {@code oneTransaction} runs the whole file in one transaction, as Flyway runs a migration. */
    protected static ExecResult psqlFile(String database, Path script, boolean oneTransaction) throws Exception {
        String inContainer = "/tmp/" + script.getFileName();
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(script), inContainer);
        return POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d", database,
                "-v", "ON_ERROR_STOP=1", oneTransaction ? "--single-transaction" : "--echo-errors", "-f", inContainer);
    }

    /** Runs SQL with psql against {@code database} and fails the test if psql does. */
    protected static ExecResult psql(String database, String sql) throws Exception {
        ExecResult result = POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d", database,
                "-v", "ON_ERROR_STOP=1", "-c", sql);
        assertEquals(0, result.getExitCode(), () -> "psql failed:\n" + result.getStdout() + result.getStderr());
        return result;
    }

    /** Runs the legacy index script, as releases before Flyway asked operators to, against {@code database}. */
    protected static ExecResult runLegacyMigration(String database) throws Exception {
        return psqlFile(database, LEGACY_001);
    }
}
