package com.winllc.innoutwork.license;

import java.util.Set;

/**
 * What the pages are told about the license, taken once per request so every check on a page agrees.
 * Added to every model as {@code licenseInfo} by GlobalModelAttributes, beside the raw
 * {@link LicenseService.Status} as {@code license}.
 */
public record LicenseInfo(LicenseService.Status status) {

    public static LicenseInfo of(LicenseService licenseService) {
        return new LicenseInfo(licenseService.status());
    }

    /** True when the license covers the feature with this id (see {@link LicensedFeature}). */
    public boolean isEnabled(String feature) {
        Set<String> features = status.features();
        return status.isLicensed() && (features.contains("*") || features.contains(feature));
    }

    /** The one-line status shown to administrators. */
    public String getSummary() {
        return switch (status.state()) {
            case LICENSED -> "Licensed to " + status.licensee()
                    + (status.expires() == null ? " (perpetual)" : " until " + status.expires());
            case MISSING -> "Unlicensed: core features only (no license file)";
            case INVALID, EXPIRED -> "Unlicensed: core features only (" + status.problem() + ")";
        };
    }
}
