package com.winllc.innoutwork.license;

import com.enssolutions.license.EnsLicense;
import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseManager;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The license status, for any signed-in user, in every license state. Only administrators are told
 * the installation id and where the file is.
 */
@RestController
@RequestMapping("/api/license")
public class LicenseRestService {

    private final LicenseManager licenses;

    public LicenseRestService(LicenseManager licenses) {
        this.licenses = licenses;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Status(
            String state,
            String mode,
            String product,
            String message,
            String licensee,
            String licenseId,
            Integer revision,
            String edition,
            Instant expires,
            Long daysLeft,
            List<String> features,
            Boolean evaluation,
            String installationId,
            String file) {
    }

    @GetMapping
    public Status status(Authentication authentication) {
        LicenseCheck check = licenses.current();
        boolean admin = LicenseController.isAdmin(authentication);
        Optional<EnsLicense> l = check.license();
        return new Status(
                check.state().name(),
                check.mode().name(),
                LicensedFeature.PRODUCT,
                check.message(),
                l.map(EnsLicense::licensee).orElse(null),
                l.map(EnsLicense::id).orElse(null),
                l.map(EnsLicense::revision).orElse(null),
                l.map(EnsLicense::edition).orElse(null),
                l.flatMap(EnsLicense::expiresAt).orElse(null),
                check.daysLeft().orElse(null),
                LicenseController.features(check).stream()
                        .filter(f -> check.isFeatureEnabled(f.id()))
                        .map(LicenseController.FeatureRow::id)
                        .toList(),
                check.showEvaluationBanner(),
                admin ? licenses.installationId() : null,
                admin ? licenses.licenseFile().toString() : null);
    }
}
