package com.winllc.innoutwork.migration;

import com.enssolutions.license.InstallationId;
import com.enssolutions.license.JdbcLicenseStore;
import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseVerifier;
import com.winllc.innoutwork.license.FirstInstallationIdGuard;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /**
     * An upgrade straight to several replicas, with the old license volume not mounted: the database predates the
     * license tables and nothing can be imported, so no new id is made up (a license bound to the old one would
     * silently stop working).
     */
    @Test
    void anEarlierInstallationWithoutItsStateGetsNoNewId() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        java.sql.Timestamp before = jdbc.queryForObject(
                "SELECT installed_on FROM flyway_schema_history WHERE version = '1'", java.sql.Timestamp.class);
        jdbc.update("UPDATE flyway_schema_history SET installed_on = installed_on - interval '30 days' WHERE version = '1'");
        try {
            Path empty = Files.createDirectories(oldVolume.resolve("empty"));
            JdbcLicenseStore store = new JdbcLicenseStore(dataSource, LicensedFeature.PRODUCT)
                    .importingFileStateOnFirstUse(empty.resolve("license.lic"), empty);
            LicenseManager guarded = new LicenseManager(LicensedFeature.PRODUCT, VERIFIER,
                    new FirstInstallationIdGuard(store, dataSource, empty, LicensedFeature.PRODUCT, false, false), Optional.empty(), Clock.systemUTC(),
                    Optional.empty());

            IllegalStateException refused = assertThrows(IllegalStateException.class, guarded::installationId);
            assertTrue(refused.getMessage().contains("mount the old license volume"), refused.getMessage());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ens_license_installation", Integer.class));

            LicenseManager confirmed = new LicenseManager(LicensedFeature.PRODUCT, VERIFIER,
                    new FirstInstallationIdGuard(store, dataSource, empty, LicensedFeature.PRODUCT, false, true), Optional.empty(), Clock.systemUTC(),
                    Optional.empty());
            assertTrue(confirmed.installationId().length() > 8, "ENS_LICENSE_NEW_INSTALLATION_ID=true accepts a new id");
        } finally {
            jdbc.update("UPDATE flyway_schema_history SET installed_on = ? WHERE version = '1'", before);
        }
    }

    /** A new installation, whose migrations all ran together, gets its id as before. */
    @Test
    void aNewInstallationGetsAnId() throws Exception {
        Path empty = Files.createDirectories(oldVolume.resolve("new"));
        JdbcLicenseStore store = new JdbcLicenseStore(dataSource, LicensedFeature.PRODUCT)
                .importingFileStateOnFirstUse(empty.resolve("license.lic"), empty);
        LicenseManager licenses = new LicenseManager(LicensedFeature.PRODUCT, VERIFIER,
                new FirstInstallationIdGuard(store, dataSource, empty, LicensedFeature.PRODUCT, false, false), Optional.empty(), Clock.systemUTC(),
                Optional.empty());
        assertTrue(licenses.installationId().length() > 8);
    }

    private LicenseManager guarded(Path stateDir, boolean expect, boolean newId) {
        JdbcLicenseStore store = new JdbcLicenseStore(dataSource, LicensedFeature.PRODUCT)
                .importingFileStateOnFirstUse(stateDir.resolve("license.lic"), stateDir);
        return new LicenseManager(LicensedFeature.PRODUCT, VERIFIER,
                new FirstInstallationIdGuard(store, dataSource, stateDir, LicensedFeature.PRODUCT, expect, newId),
                Optional.empty(), Clock.systemUTC(), Optional.empty());
    }

    /**
     * A fresh or rebuilt database behind an existing installation runs every migration at once, so it looks new.
     * Without a license volume (the chart then sets ENS_LICENSE_EXPECT_INSTALLATION_ID) no id is made up.
     */
    @Test
    void withoutTheVolumeAnEmptyDatabaseGetsNoNewId() throws Exception {
        Path empty = Files.createDirectories(oldVolume.resolve("none"));

        assertThrows(IllegalStateException.class, guarded(empty, true, false)::installationId);
        assertEquals(0, new JdbcTemplate(dataSource).queryForObject("SELECT count(*) FROM ens_license_installation",
                Integer.class));
    }

    /** Several pods, one with the old volume's file: those without it never insert an id of their own. */
    @Test
    void onlyThePodWithTheFileSetsTheId() throws Exception {
        String id = InstallationId.generate(LicensedFeature.PRODUCT);
        Path withFile = Files.createDirectories(oldVolume.resolve("with"));
        Files.writeString(withFile.resolve("installation-id"), id + "\n");
        Path without = Files.createDirectories(oldVolume.resolve("without"));

        assertThrows(IllegalStateException.class, guarded(without, true, false)::installationId);
        assertEquals(id, guarded(withFile, true, false).installationId());
        assertEquals(id, guarded(without, true, false).installationId(), "then everyone has the imported one");
    }

    /** ENS_LICENSE_NEW_INSTALLATION_ID works only while the database holds no license state. */
    @Test
    void aNewIdIsRefusedOnceTheDatabaseHoldsLicenseState() throws Exception {
        Path empty = Files.createDirectories(oldVolume.resolve("confirm"));
        new JdbcTemplate(dataSource).update("INSERT INTO ens_license_revision (product, license_id, revision)"
                + " VALUES (?, 'L-1', 3)", LicensedFeature.PRODUCT);

        assertThrows(IllegalStateException.class, guarded(empty, true, true)::installationId);

        new JdbcTemplate(dataSource).update("DELETE FROM ens_license_revision");
        assertTrue(guarded(empty, true, true).installationId().length() > 8);
    }
}
