package com.winllc.innoutwork.license;

import java.util.List;

/**
 * The features an ENS Solutions license unlocks. Everything not listed here is core and always on:
 * the Windows client's check-in/out calls, the home dashboard, profile and calendar, groups, user
 * search, in-app notifications, settings and sign-in.
 *
 * <p>This is the one place the split is decided. The ids are what a license's {@code features} line
 * names, and the URL patterns are what {@link LicenseInterceptor} refuses while a feature is off.
 * Templates ask by id ({@code licenseInfo.isEnabled('metrics')}) to hide what leads to them.
 */
public enum LicensedFeature {

    METRICS("metrics", "Metrics",
            "/app/metrics", "/app/metrics/**"),
    REPORTS("reports", "Reports",
            "/app/user/reports", "/app/user/reports/**",
            "/app/group/report", "/app/group/report/**",
            "/api/users/reports", "/api/users/reports/**"),
    ORG_CHART("org-chart", "Org Chart",
            "/app/orgchart", "/app/orgchart/**",
            "/api/orgnodes", "/api/orgnodes/**"),
    /** The absence e-mail job; see SendAbsentNotificationsCron. No pages of its own. */
    ABSENCE_ALERTS("absence-alerts", "Absence alerts");

    private final String id;
    private final String label;
    private final List<String> urlPatterns;

    LicensedFeature(String id, String label, String... urlPatterns) {
        this.id = id;
        this.label = label;
        this.urlPatterns = List.of(urlPatterns);
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public List<String> urlPatterns() {
        return urlPatterns;
    }
}
