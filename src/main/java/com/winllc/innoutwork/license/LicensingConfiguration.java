package com.winllc.innoutwork.license;

import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseCheck;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.function.Consumer;

/**
 * Puts {@link LicenseInterceptor} in front of every page and call, except what must keep working with
 * no license at all: sign-in and sign-out, the license screen (and its upload), GET /api/license, the
 * audit log (/app/admin/audit) and its pull API (/api/audit/events), the error page, static assets and
 * actuator (health and info).
 *
 * <p>The {@link LicenseManager} comes from {@link LicenseManagerConfig}, or in tests from a
 * configuration that trusts the test key.
 */
@Configuration
public class LicensingConfiguration implements WebMvcConfigurer {

    /** The license screen. Administrators manage the license here; everyone else is told to ask one. */
    public static final String LICENSE_SCREEN = "/app/admin/license";

    /** The audit log. Not a licensed feature. */
    public static final String AUDIT_SCREEN = "/app/admin/audit";

    /** A person's own data: what is held, and its export. Not a licensed feature. */
    public static final String PRIVACY_SCREEN = "/app/privacy";

    /** Erasure of a person's data, by administrators. Not a licensed feature. */
    public static final String ADMIN_PRIVACY_SCREEN = "/app/admin/privacy";

    static final String[] UNGATED = {
            "/login", "/logout", "/error",
            // The notice-and-consent banner comes before sign-in, so before anyone could install a license.
            "/consent",
            LICENSE_SCREEN, LICENSE_SCREEN + "/**",
            "/api/license",
            // The audit trail stays available in every license state: administrators can still open, export
            // and verify it, and SIEMs keep pulling it (token-authenticated).
            AUDIT_SCREEN, AUDIT_SCREEN + "/**",
            "/api/audit/events",
            // A person's rights over their data do not depend on the license: their export, and an administrator's
            // erasure, work in every license state (writes included, so erasure is possible while read-only).
            PRIVACY_SCREEN, PRIVACY_SCREEN + "/**", ADMIN_PRIVACY_SCREEN, ADMIN_PRIVACY_SCREEN + "/**",
            "/actuator", "/actuator/**",
            "/libs/**", "/css/**", "/js/**", "/images/**", "/external/**", "/favicon.ico",
    };

    private final ObjectProvider<LicenseManager> licenses;
    /** The audit trail's license-mode watcher, when there is an audit store (not in most test slices). */
    private final ObjectProvider<Consumer<LicenseCheck>> licenseObservers;

    public LicensingConfiguration(ObjectProvider<LicenseManager> licenses,
                                  ObjectProvider<Consumer<LicenseCheck>> licenseObservers) {
        this.licenses = licenses;
        this.licenseObservers = licenseObservers;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LicenseInterceptor(licenses.getObject(), licenseObservers.getIfAvailable()))
                .excludePathPatterns(UNGATED);
    }
}
