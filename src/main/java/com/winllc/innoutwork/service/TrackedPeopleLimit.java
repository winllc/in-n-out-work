package com.winllc.innoutwork.service;

/**
 * How many people ReadyRoom may track: one user record per person. Asked before a new record is
 * inserted, never about existing ones; nothing is ever removed for being over the limit.
 * The license provides the real one (license.LicensedPeopleLimit).
 */
@FunctionalInterface
public interface TrackedPeopleLimit {

    /** No limit, for code and tests that are not about the license. */
    TrackedPeopleLimit NONE = currentCount -> true;

    /** Whether one more person may be added to the {@code currentCount} already tracked. */
    boolean mayAdd(long currentCount);
}
