package com.winllc.innoutwork.license;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.time.Clock;

/**
 * The license, and the gate in front of the licensed pages and calls.
 *
 * <p>ens.license.file (ENS_LICENSE_FILE) names the license; relative paths resolve against the working
 * directory. The production image points it at /etc/ens/license/license.lic. A missing or invalid file
 * is not an error: the application runs with its core features only.
 */
@Configuration
public class LicenseConfig implements WebMvcConfigurer {

    public static final String PRODUCT = "readyroom";

    private final LicenseService licenseService;

    public LicenseConfig(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @Bean
    static LicenseService licenseService(@Value("${ens.license.file:license.lic}") String file) {
        return new LicenseService(LicenseVerifier.forProduct(PRODUCT), Path.of(file), PRODUCT, Clock.systemUTC());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        for (LicensedFeature feature : LicensedFeature.values()) {
            if (!feature.urlPatterns().isEmpty()) {
                registry.addInterceptor(new LicenseInterceptor(licenseService, feature))
                        .addPathPatterns(feature.urlPatterns());
            }
        }
    }
}
