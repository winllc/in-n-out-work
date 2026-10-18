package com.winllc.innoutwork.license;

import com.enssolutions.license.JdbcLicenseStore;
import com.enssolutions.license.LicenseException;
import com.enssolutions.license.LicenseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

/**
 * The database license store, refusing to make up a new installation id for an installation that already had
 * one in files it cannot see. An upgrade straight to several replicas with the license volume turned off would
 * otherwise get a new id on its first start, and a license bound to the old one would stop working, silently.
 * <p>
 * "Already had one": the database was migrated before this release (Flyway's history has migrations installed
 * well before {@code V8__ens_license_state}), the database has no installation id yet, and there is no
 * {@code installation-id} file in ENS_LICENSE_STATE_DIR to import. ReadyRoom then runs unlicensed and says
 * why, until the old license volume is mounted for one start (or the operator confirms a new id with
 * {@code ENS_LICENSE_NEW_INSTALLATION_ID=true}). A new installation, whose migrations all ran at once, is not
 * affected.
 */
public final class FirstInstallationIdGuard implements LicenseStore {

    private static final Logger log = LoggerFactory.getLogger(FirstInstallationIdGuard.class);

    /** Migrations installed this much earlier than V8 mean the database predates the license tables. */
    static final Duration EARLIER_RELEASE = Duration.ofMinutes(5);

    private final JdbcLicenseStore delegate;
    private final DataSource dataSource;
    private final Path stateDir;
    private final boolean newIdConfirmed;

    public FirstInstallationIdGuard(JdbcLicenseStore delegate, DataSource dataSource, Path stateDir, boolean newIdConfirmed) {
        this.delegate = delegate;
        this.dataSource = dataSource;
        this.stateDir = stateDir;
        this.newIdConfirmed = newIdConfirmed;
    }

    @Override
    public String installationId(String candidate) throws IOException {
        if (!newIdConfirmed && delegate.storedInstallationId().isEmpty()
                && !Files.exists(stateDir.resolve("installation-id")) && migratedBeforeLicenseTables()) {
            String message = "This installation's id was kept in files by an earlier release, and neither the database"
                    + " nor " + stateDir + " has it: mount the old license volume for one start (Helm:"
                    + " persistence.license.enabled=true, replicaCount 1) so it is imported, or set"
                    + " ENS_LICENSE_NEW_INSTALLATION_ID=true to start with a new id (licenses bound to the old one"
                    + " then stop working)";
            log.error(message);
            throw new IOException(message);
        }
        return delegate.installationId(candidate);
    }

    boolean migratedBeforeLicenseTables() throws IOException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT count(*) FROM flyway_schema_history h, flyway_schema_history v8
                      WHERE v8.version = '8' AND h.installed_rank < v8.installed_rank
                        AND h.installed_on < v8.installed_on - (? * interval '1 second')""")) {
            s.setLong(1, EARLIER_RELEASE.toSeconds());
            try (ResultSet r = s.executeQuery()) {
                return r.next() && r.getLong(1) > 0;
            }
        } catch (SQLException e) {
            // No Flyway history (a database built otherwise): nothing to protect.
            log.debug("Could not read Flyway's history: {}", e.toString());
            return false;
        }
    }

    @Override
    public Optional<Object> licenseVersion() throws IOException {
        return delegate.licenseVersion();
    }

    @Override
    public Optional<StoredLicense> readLicense() throws IOException, LicenseException {
        return delegate.readLicense();
    }

    @Override
    public void writeLicense(String text) throws IOException {
        delegate.writeLicense(text);
    }

    @Override
    public int highestRevision(String licenseId) throws IOException {
        return delegate.highestRevision(licenseId);
    }

    @Override
    public int raiseRevision(String licenseId, int revision) throws IOException {
        return delegate.raiseRevision(licenseId, revision);
    }

    @Override
    public Duration recheckInterval() {
        return delegate.recheckInterval();
    }

    @Override
    public String location() {
        return delegate.location();
    }
}
