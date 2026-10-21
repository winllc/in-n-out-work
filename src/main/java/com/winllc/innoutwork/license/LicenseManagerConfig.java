package com.winllc.innoutwork.license;

import com.enssolutions.license.JdbcLicenseStore;
import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * The installed ENS license: a {@link LicenseManager} for product {@code readyroom} that trusts only
 * the keys built into {@link EnsKeys}.
 *
 * <p>The license, the installation id and the highest revision seen of each license are kept in the
 * application's database (ens-license 1.2.0's JdbcLicenseStore, tables from Flyway
 * {@code V8__ens_license_state.sql}), so every replica sees the same license, and an upload on one is in
 * force on all within seconds. On first use, when the database holds no installation id yet, the state an
 * earlier release kept in files is imported, so an existing installation keeps its id and licenses bound to
 * it keep working:
 * <ul>
 *   <li>{@code ENS_LICENSE_FILE}: the license file, default {@code /etc/ens/readyroom.lic}; the image
 *       sets {@code /var/lib/ens/readyroom/license.lic}.</li>
 *   <li>{@code ENS_LICENSE_STATE_DIR}: the installation id and revisions, default {@code /var/lib/ens/readyroom}.</li>
 * </ul>
 * After that the files are not read again. {@code ENS_LICENSE} works as before. An installation that had an id
 * in files the first start cannot see is not given a new one ({@link FirstInstallationIdGuard}).
 *
 * <p>The release date that perpetual maintenance is measured against is the build time from
 * Spring Boot's build-info (build.gradle {@code springBoot { buildInfo() }}). Without build-info
 * (running from an IDE) there is none, and perpetual licenses cover every release.
 *
 * <p>Kept apart from {@link LicensingConfiguration} so tests can supply a manager that trusts the
 * test key instead of this one.
 */
@Configuration
public class LicenseManagerConfig {

    private static final Logger log = LoggerFactory.getLogger(LicenseManagerConfig.class);

    /** The pooled DataSource itself, as the library asks: it runs its own short transactions. */
    @Bean
    LicenseManager licenseManager(ObjectProvider<BuildProperties> build, DataSource dataSource) {
        Optional<Instant> released = Optional.ofNullable(build.getIfAvailable()).map(BuildProperties::getTime);
        String product = LicensedFeature.PRODUCT;
        Path licenseFile = Path.of(env("ENS_LICENSE_FILE", "/etc/ens/" + product + ".lic"));
        Path stateDir = Path.of(env("ENS_LICENSE_STATE_DIR", "/var/lib/ens/" + product));
        // As LicenseManager.fromEnvironment(..., dataSource), plus the guard against a new id for an old installation.
        boolean newId = Boolean.parseBoolean(env("ENS_LICENSE_NEW_INSTALLATION_ID", "false"));
        if (newId) {
            log.error("ENS_LICENSE_NEW_INSTALLATION_ID is set: if the database has no installation id and no license,"
                    + " a new id is made, and licenses bound to an earlier one stop working. Unset it after this start.");
        }
        JdbcLicenseStore store = new JdbcLicenseStore(dataSource, product)
                .importingFileStateOnFirstUse(licenseFile, stateDir);
        return new LicenseManager(product, LicenseVerifier.trusting(EnsKeys.PUBLIC_KEYS),
                new FirstInstallationIdGuard(store, dataSource, stateDir, product,
                        Boolean.parseBoolean(env("ENS_LICENSE_EXPECT_INSTALLATION_ID", "false")), newId),
                released, Clock.systemUTC(), Optional.ofNullable(System.getenv("ENS_LICENSE")));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Once the schema is migrated (the store's tables are Flyway's), not when the bean is made. */
    @EventListener(ApplicationReadyEvent.class)
    public void describe(ApplicationReadyEvent event) {
        LicenseManager licenses = event.getApplicationContext().getBean(LicenseManager.class);
        LicenseCheck check = licenses.current();
        log.info("License: {} ({}, {} mode). Kept in {}", check.message(), check.state(), check.mode(),
                licenses.licenseLocation());
    }
}
