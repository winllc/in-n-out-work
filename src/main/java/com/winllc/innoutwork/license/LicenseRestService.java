package com.winllc.innoutwork.license;

import com.winllc.innoutwork.constant.UserRoleEnum;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The license status, for any signed-in user. Only administrators are told where the file is.
 */
@RestController
@RequestMapping("/api/license")
public class LicenseRestService {

    private final LicenseService licenseService;

    public LicenseRestService(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @GetMapping
    public LicenseService.Status status(Authentication authentication) {
        LicenseService.Status s = licenseService.status();
        boolean admin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> UserRoleEnum.ADMIN.name().equals(a.getAuthority()));
        if (admin) {
            return s;
        }
        return new LicenseService.Status(s.state(), s.product(), s.licensee(), s.licenseId(), s.expires(),
                s.features(), s.problem(), null);
    }
}
