package com.winllc.innoutwork.license;

import com.enssolutions.license.LicenseManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The test configuration's license: a {@link LicenseManager} that trusts the published TEST key
 * instead of the production keys in {@link LicenseManagerConfig}, over its own temporary directory,
 * with a full readyroom license installed. Import it (with {@link LicensingConfiguration} for the
 * interceptor) wherever a slice needs the license. Tests about other license states replace the file
 * with {@link TestLicenses#installFile} and put the full one back afterwards.
 */
@Configuration
public class TestLicensing {

    @Bean
    LicenseManager licenseManager() {
        try {
            Path dir = Files.createTempDirectory("readyroom-license");
            dir.toFile().deleteOnExit();
            LicenseManager licenses = TestLicenses.manager(dir);
            TestLicenses.installFile(licenses, TestLicenses.full());
            return licenses;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
