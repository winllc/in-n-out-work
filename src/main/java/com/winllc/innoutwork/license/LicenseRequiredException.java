package com.winllc.innoutwork.license;

/** A request reached a licensed feature the license does not cover. */
public class LicenseRequiredException extends RuntimeException {

    private final LicensedFeature feature;

    public LicenseRequiredException(LicensedFeature feature) {
        super(feature.label() + " requires an ENS Solutions license.");
        this.feature = feature;
    }

    public LicensedFeature getFeature() {
        return feature;
    }
}
