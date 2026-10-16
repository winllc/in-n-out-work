package com.winllc.innoutwork.license;

import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseState;
import com.winllc.innoutwork.service.TrackedPeopleLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The license's size, in tracked people (user records). Up to 10% over it new people are still added,
 * with a warning; beyond that they are not (the directory sync skips them, and a new person's first
 * sign-in or check-in is not recorded). Nobody already tracked is ever removed.
 *
 * <p>Only applied while the license is in full effect. Without one, or read-only, the check-in API and
 * the directory sync are refused or paused anyway, and the record a first sign-in creates must still be
 * created: it is how an administrator gets in to install a license.
 */
@Component
public class LicensedPeopleLimit implements TrackedPeopleLimit {

    private static final Logger log = LoggerFactory.getLogger(LicensedPeopleLimit.class);

    private final LicenseManager licenses;

    public LicensedPeopleLimit(LicenseManager licenses) {
        this.licenses = licenses;
    }

    @Override
    public boolean mayAdd(long currentCount) {
        LicenseCheck check = licenses.current();
        if (check.mode() != LicenseState.Mode.FULL) {
            return true;
        }
        if (!check.mayAdd(currentCount)) {
            log.warn("License limit: {}", check.usageMessage(currentCount + 1));
            return false;
        }
        if (check.usage(currentCount + 1) != LicenseCheck.Usage.WITHIN) {
            log.warn("License limit: {}", check.usageMessage(currentCount + 1));
        }
        return true;
    }
}
