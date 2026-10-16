package com.winllc.innoutwork.license;

import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseState;

/**
 * What the pages are told about the license, taken once per request so every check on a page agrees.
 * Added to every model as {@code licenseInfo} by GlobalModelAttributes (and by
 * {@link LicenseExceptionHandler} for the pages it renders), beside the {@link LicenseCheck} itself as
 * {@code license}.
 */
public record LicenseInfo(LicenseCheck check) {

    /** True when the license is in full effect and unlocks the feature with this id (see {@link LicensedFeature}). */
    public boolean isEnabled(String feature) {
        return check.isFeatureEnabled(feature);
    }

    /** Anything but the license screen is usable: the menu is only worth showing then. */
    public boolean isUsable() {
        return check.mode() != LicenseState.Mode.UNLICENSED;
    }

    public boolean isReadOnly() {
        return check.mode() == LicenseState.Mode.READ_ONLY;
    }

    public boolean isEvaluationBanner() {
        return check.showEvaluationBanner();
    }

    public boolean isRenewalWarning() {
        return check.showRenewalWarning();
    }

    /** Days left before expiry (or before grace ends); -1 for a perpetual license. */
    public long getDaysLeft() {
        return check.daysLeft().orElse(-1L);
    }

    /**
     * Whether the configured classification banners are shown: when the license lists the feature.
     * Unlike the other features they stay up in read-only mode, because the data they mark is still on
     * screen; an expired license must not make classified pages look unmarked.
     */
    public boolean isClassificationBanners() {
        String id = LicensedFeature.PRODUCT + "." + LicensedFeature.CLASSIFICATION_BANNERS.id();
        return isUsable() && check.license().map(l -> l.features().contains(id)).orElse(false);
    }

    public String getMessage() {
        return check.message();
    }
}
