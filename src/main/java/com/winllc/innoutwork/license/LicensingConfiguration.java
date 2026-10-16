package com.winllc.innoutwork.license;

import com.enssolutions.license.LicenseManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts {@link LicenseInterceptor} in front of every page and call, except what must keep working with
 * no license at all: sign-in and sign-out, the license screen (and its upload), GET /api/license, the
 * error page, static assets and actuator (health and info).
 *
 * <p>The {@link LicenseManager} comes from {@link LicenseManagerConfig}, or in tests from a
 * configuration that trusts the test key.
 */
@Configuration
public class LicensingConfiguration implements WebMvcConfigurer {

    /** The license screen. Administrators manage the license here; everyone else is told to ask one. */
    public static final String LICENSE_SCREEN = "/app/admin/license";

    static final String[] UNGATED = {
            "/login", "/logout", "/error",
            LICENSE_SCREEN, LICENSE_SCREEN + "/**",
            "/api/license",
            "/actuator", "/actuator/**",
            "/libs/**", "/css/**", "/js/**", "/images/**", "/external/**", "/favicon.ico",
    };

    private final ObjectProvider<LicenseManager> licenses;

    public LicensingConfiguration(ObjectProvider<LicenseManager> licenses) {
        this.licenses = licenses;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LicenseInterceptor(licenses.getObject()))
                .excludePathPatterns(UNGATED);
    }
}
