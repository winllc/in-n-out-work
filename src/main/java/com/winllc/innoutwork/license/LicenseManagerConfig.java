package com.winllc.innoutwork.license;

import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.Optional;

/**
 * The installed ENS license: a {@link LicenseManager} for product {@code readyroom} that trusts only
 * the keys built into {@link EnsKeys}.
 *
 * <p>Files, from the environment (the library's defaults outside a container):
 * <ul>
 *   <li>{@code ENS_LICENSE_FILE}: the license file, default {@code /etc/ens/readyroom.lic}; the image
 *       sets {@code /var/lib/ens/readyroom/license.lic}. The license screen's upload writes it.</li>
 *   <li>{@code ENS_LICENSE_STATE_DIR}: the installation id and the highest revision seen of each
 *       license, default {@code /var/lib/ens/readyroom}. Keep it on a persistent volume: lose it and
 *       the installation gets a new id, so licenses bound to the old one stop working.</li>
 * </ul>
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

    @Bean
    LicenseManager licenseManager(ObjectProvider<BuildProperties> build) {
        Optional<Instant> released = Optional.ofNullable(build.getIfAvailable()).map(BuildProperties::getTime);
        LicenseManager licenses = LicenseManager.fromEnvironment(
                LicensedFeature.PRODUCT, LicenseVerifier.trusting(EnsKeys.PUBLIC_KEYS), released);
        LicenseCheck check = licenses.current();
        log.info("License: {} ({}, {} mode). License file {}", check.message(), check.state(), check.mode(),
                licenses.licenseFile());
        return licenses;
    }
}
