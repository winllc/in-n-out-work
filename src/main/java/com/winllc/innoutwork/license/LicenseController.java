package com.winllc.innoutwork.license;

import com.enssolutions.license.EnsLicense;
import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseException;
import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseState;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.repository.UserRecordRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * The license screen, Administration &rarr; License: what is installed and what it unlocks, the size in
 * use, this installation's id (customers bind licenses to it on the ENS portal) and an upload form.
 *
 * <p>Administrators only (the ADMIN authority). It works in every license state, so an administrator
 * can always sign in and install one. Anyone else sent here because there is no usable license gets a
 * plain "not licensed, ask your administrator" page, and the access-denied page otherwise.
 */
@Controller
@RequestMapping(LicensingConfiguration.LICENSE_SCREEN)
public class LicenseController {

    private static final Logger log = LoggerFactory.getLogger(LicenseController.class);

    /** Comfortably more than any license; anything larger is not one. */
    static final long MAX_UPLOAD_BYTES = 64 * 1024;

    private final LicenseManager licenses;
    private final UserRecordRepository userRecords;

    public LicenseController(LicenseManager licenses, UserRecordRepository userRecords) {
        this.licenses = licenses;
        this.userRecords = userRecords;
    }

    /** One catalog feature, as the screen lists it. */
    public record FeatureRow(String id, String label, String description, boolean included) {
    }

    @GetMapping
    public String show(Authentication authentication, Model model, HttpServletResponse response) {
        LicenseCheck check = licenses.current();
        if (!isAdmin(authentication)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            if (check.mode() == LicenseState.Mode.UNLICENSED) {
                return "license-unavailable";
            }
            return "unauthorized";
        }

        model.addAttribute("check", check);
        model.addAttribute("installationId", licenses.installationId());
        model.addAttribute("licenseFile", licenses.licenseFile().toString());
        model.addAttribute("features", features(check));
        check.license().ifPresent(l -> {
            model.addAttribute("installed", l);
            model.addAttribute("validFrom", day(l.notBefore()));
            model.addAttribute("expires", l.expiresAt().map(LicenseController::day).orElse("Never (perpetual)"));
            model.addAttribute("graceEnds", l.expiresAt()
                    .filter(e -> l.graceDays() > 0)
                    .map(e -> day(e.plus(Duration.ofDays(l.graceDays()))))
                    .orElse(null));
            model.addAttribute("maintenance", l.maintenanceUntil().map(LicenseController::day).orElse(null));
        });
        model.addAttribute("usage", check.license().isPresent()
                ? check.usageMessage(userRecords.count())
                : userRecords.count() + " tracked people.");
        return "license";
    }

    @PostMapping
    public String upload(Authentication authentication,
                         @RequestParam(value = "file", required = false) MultipartFile file,
                         @RequestParam(value = "text", required = false) String text,
                         RedirectAttributes flash, HttpServletResponse response) throws IOException {
        if (!isAdmin(authentication)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return "unauthorized";
        }

        String licenseText;
        if (file != null && !file.isEmpty()) {
            if (file.getSize() > MAX_UPLOAD_BYTES) {
                flash.addFlashAttribute("error", "That file is too large to be a license.");
                return redirect();
            }
            licenseText = new String(file.getBytes(), StandardCharsets.UTF_8);
        } else if (text != null && !text.isBlank()) {
            if (text.length() > MAX_UPLOAD_BYTES) {
                flash.addFlashAttribute("error", "That is too long to be a license.");
                return redirect();
            }
            licenseText = text;
        } else {
            flash.addFlashAttribute("error", "Choose the license file (.lic) to upload.");
            return redirect();
        }

        try {
            LicenseCheck check = licenses.install(licenseText);
            boolean installed = switch (check.state()) {
                case WRONG_PRODUCT, WRONG_INSTALLATION, SUPERSEDED -> false;
                default -> true;
            };
            if (installed) {
                log.info("License installed by {}: {} ({})", authentication.getName(), check.message(), check.state());
                flash.addFlashAttribute(check.isFullyLicensed() ? "notice" : "error",
                        (check.isFullyLicensed() ? "License installed. " : "License installed, but: ") + check.message());
            } else {
                log.info("License upload by {} refused: {} ({})", authentication.getName(), check.message(), check.state());
                flash.addFlashAttribute("error", "That license was not installed. " + check.message());
            }
        } catch (LicenseException e) {
            log.info("License upload by {} refused: {}", authentication.getName(), e.getMessage());
            flash.addFlashAttribute("error", "That isn't a valid ENS license: " + e.getMessage() + ".");
        }
        return redirect();
    }

    static List<FeatureRow> features(LicenseCheck check) {
        return Arrays.stream(LicensedFeature.values())
                .map(f -> new FeatureRow(f.id(), f.label(), f.description(), check.license()
                        .map(EnsLicense::features)
                        .map(listed -> listed.contains(LicensedFeature.PRODUCT + "." + f.id()))
                        .orElse(false)))
                .toList();
    }

    private static String day(Instant instant) {
        return instant.toString().substring(0, 10);
    }

    private static String redirect() {
        return "redirect:" + LicensingConfiguration.LICENSE_SCREEN;
    }

    static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> UserRoleEnum.ADMIN.name().equals(a.getAuthority()));
    }
}
