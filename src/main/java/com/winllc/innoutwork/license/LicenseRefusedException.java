package com.winllc.innoutwork.license;

import com.enssolutions.license.LicenseCheck;

/**
 * A request the license does not allow. {@link LicenseExceptionHandler} answers it: 403 JSON for API
 * callers, a redirect to the license screen (unlicensed) or the "needs a license" page for browsers.
 */
public class LicenseRefusedException extends RuntimeException {

    /** What the JSON body's "error" says. */
    public enum Reason {
        /** No usable license: only sign-in and the license screen work. */
        LICENSE_REQUIRED("license_required"),
        /** Expired or not covering this release: nothing may be changed. */
        LICENSE_READ_ONLY("license_read_only"),
        /** The license is in effect but does not include the feature. */
        FEATURE_NOT_LICENSED("license_required");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Reason reason;
    private final transient LicenseCheck check;
    private final LicensedFeature feature;

    public LicenseRefusedException(Reason reason, LicenseCheck check, LicensedFeature feature, String message) {
        super(message);
        this.reason = reason;
        this.check = check;
        this.feature = feature;
    }

    public Reason getReason() {
        return reason;
    }

    public LicenseCheck getCheck() {
        return check;
    }

    /** The feature refused, or null when the refusal is about the license as a whole. */
    public LicensedFeature getFeature() {
        return feature;
    }
}
