package com.winllc.innoutwork.migration;

import com.enssolutions.license.InstallationId;
import com.enssolutions.license.JdbcLicenseStore;
import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseVerifier;
import com.winllc.innoutwork.license.LicensedFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The license state in the database (Flyway V8), as several replicas share it, and the import of what an earlier
 * release kept in files, so an upgraded installation keeps its id.
 */
class LicenseStatePostgresTest extends PostgresTestSupport {

    // Any key: these tests are about where the state is kept, not about verifying licenses.
    private static final LicenseVerifier VERIFIER =
            LicenseVerifier.trusting("MCowBQYDK2VwAyEA6S4JBbfMhCuyaCT2Nosmr1MQSb5r3aNE7sQstTmQZCc=");

    @Autowired
    private DataSource dataSource;

    @TempDir
    Path oldVolume;

    @BeforeEach
    void clean() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM ens_license_revision");
        jdbc.update("DELETE FROM ens_license_file");
        jdbc.update("DELETE FROM ens_license_installation");
    }

    private LicenseManager replica(Path licenseFile, Path stateDir) {
        JdbcLicenseStore store = new JdbcLicenseStore(dataSource, LicensedFeature.PRODUCT);
        if (stateDir != null) {
            store = store.importingFileStateOnFirstUse(licenseFile, stateDir);
        }
        return new LicenseManager(LicensedFeature.PRODUCT, VERIFIER, store, Optional.empty(), Clock.systemUTC(),
                Optional.empty());
    }

    @Test
    void anUpgradedInstallationKeepsTheIdItKeptInFiles() throws Exception {
        String id = InstallationId.generate(LicensedFeature.PRODUCT);
        Path state = Files.createDirectories(oldVolume.resolve("readyroom"));
        Files.writeString(state.resolve("installation-id"), id + "\n");

        LicenseManager first = replica(state.resolve("license.lic"), state);
        assertEquals(id, first.installationId());
        assertTrue(first.licenseLocation().contains("database"), first.licenseLocation());

        // A second replica without the old volume sees the same id.
        assertEquals(id, replica(null, null).installationId());
    }

    @Test
    void replicasStartingTogetherAgreeOnOneId() {
        LicenseManager a = replica(null, null);
        LicenseManager b = replica(null, null);

        String id = a.installationId();
        assertEquals(id, b.installationId());
        assertTrue(id.length() > 8);
    }
}
