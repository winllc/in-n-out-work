package com.winllc.innoutwork.license;

/**
 * ReadyRoom's features as the ENS licensing portal's catalog names them (products-page
 * src/data/licensing.ts, product {@code readyroom}). A license file lists the ones it unlocks as
 * {@code "readyroom.<id>"}. Every feature is gated, even those every license includes today, so a
 * feature can move to another tier by changing the catalog alone. Never rename an id.
 *
 * <p>Where each one is enforced:
 * <ul>
 *   <li>{@code @RequiresFeature} on the controllers and endpoints, which {@link LicenseInterceptor}
 *       refuses with 403 while the feature is off;</li>
 *   <li>{@link LicensedJobs#mayRun} at the top of the scheduled jobs;</li>
 *   <li>{@code licenseInfo.isEnabled('<id>')} in the templates, to hide menus, cards and buttons.</li>
 * </ul>
 */
public enum LicensedFeature {

    /** The Windows client's check-in/out API (/api/check/**), the home dashboard's status, auto check-out. */
    ATTENDANCE("attendance", "Attendance tracking",
            "Logon, lock, unlock and logoff from the Windows client, turned into daily status."),
    /** The profile calendar, /api/event/**, the organization calendar (/api/settings/calendar/**). */
    CALENDAR("calendar", "Calendar",
            "Leave, telework and events on a shared calendar."),
    /** SendAbsentNotificationsCron. No pages of its own. */
    ABSENCE_ALERTS("absence-alerts", "Absence alerts",
            "Emails managers when someone has not checked in."),
    /** /app/metrics, My Reports, the group report and its PDF export, /api/users/reports. */
    METRICS("metrics", "Metrics and reports",
            "Accountability metrics, personal and group reports, and PDF export."),
    /** /app/orgchart/**, /api/orgnodes/** and the organization parse rules in Settings. */
    ORG_CHART("org-chart", "Org chart",
            "The organization chart, with live status."),
    /** The configured top and bottom banner (application.banner-text). */
    CLASSIFICATION_BANNERS("classification-banners", "Classification banners",
            "Configurable banners for classified networks.");

    public static final String PRODUCT = "readyroom";

    private final String id;
    private final String label;
    private final String description;

    LicensedFeature(String id, String label, String description) {
        this.id = id;
        this.label = label;
        this.description = description;
    }

    /** The id after "readyroom." in a license file. */
    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
