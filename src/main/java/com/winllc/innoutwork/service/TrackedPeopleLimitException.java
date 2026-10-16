package com.winllc.innoutwork.service;

/**
 * A new person would have been tracked, but the license's size does not allow more. Answered with 403
 * (error license_limit) by license.LicenseExceptionHandler.
 */
public class TrackedPeopleLimitException extends RuntimeException {

    private final String dn;

    public TrackedPeopleLimitException(String dn) {
        super("ReadyRoom is tracking as many people as its license allows, so " + dn
                + " was not added. Ask your administrator to update the license.");
        this.dn = dn;
    }

    public String getDn() {
        return dn;
    }
}
