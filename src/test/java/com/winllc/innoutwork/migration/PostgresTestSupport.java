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
 * Base for tests that need real PostgreSQL: one container shared by every such test class, a schema
 * Hibernate creates from the entities, and the migration script run through psql as an operator would.
 * Skipped when Docker is not available.
 * <p>
 * Tests run without a test transaction, so their writes commit and other sessions (psql, a second
 * thread) can see them.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Named explicitly: Spring only discovers a nested @Configuration on the test class itself, not a base class.
@ContextConfiguration(classes = PostgresTestSupport.JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public abstract class PostgresTestSupport {

    public static final Path MIGRATION = Path.of("db/migrations/001_indexes_and_unique_user_dn.sql");

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

    /** Runs the migration script with psql inside the container, against {@code database}. */
    protected static ExecResult runMigration(String database) throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(MIGRATION), "/tmp/001.sql");
        return POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d", database,
                "-v", "ON_ERROR_STOP=1", "-f", "/tmp/001.sql");
    }

    /** Runs the migration script against the test database and fails the test if psql does. */
    protected static ExecResult runMigration() throws Exception {
        ExecResult result = runMigration(POSTGRES.getDatabaseName());
        assertEquals(0, result.getExitCode(), () -> "psql failed:\n" + result.getStdout() + result.getStderr());
        return result;
    }
}
