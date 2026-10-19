package com.winllc.innoutwork.license;

import com.enssolutions.license.EnsLicense;
import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseException;
import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseState;
import com.winllc.innoutwork.audit.AccessDenials;
import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.audit.AuditUnavailableException;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.repository.UserRecordRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.function.Consumer;

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

    private AuditLog audit = AuditLog.NOOP;
    private AccessDenials denials;
    private Consumer<LicenseCheck> licenseObserver;

    public LicenseController(LicenseManager licenses, UserRecordRepository userRecords) {
        this.licenses = licenses;
        this.userRecords = userRecords;
    }

    @Autowired(required = false)
    public void setAuditLog(AuditLog audit) {
        this.audit = audit;
    }

    @Autowired(required = false)
    public void setAccessDenials(AccessDenials denials) {
        this.denials = denials;
    }

    /** The audit trail's license-mode watcher, told about an install at once. */
    @Autowired(required = false)
    public void setLicenseObserver(Consumer<LicenseCheck> licenseObserver) {
        this.licenseObserver = licenseObserver;
    }

    /** One catalog feature, as the screen lists it. */
    public record FeatureRow(String id, String label, String description, boolean included) {
    }

    @GetMapping
    public String show(Authentication authentication, Model model, HttpServletRequest request,
                       HttpServletResponse response) {
        LicenseCheck check = licenses.current();
        if (!isAdmin(authentication)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            denied(request);
            if (check.mode() == LicenseState.Mode.UNLICENSED) {
                return "license-unavailable";
            }
            return "unauthorized";
        }

        model.addAttribute("check", check);
        model.addAttribute("installationId", licenses.installationId());
        model.addAttribute("licenseFile", licenses.licenseLocation());
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
                         RedirectAttributes flash, HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        if (!isAdmin(authentication)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            denied(request);
            return "unauthorized";
        }

        String licenseText;
        if (file != null && !file.isEmpty()) {
            if (file.getSize() > MAX_UPLOAD_BYTES) {
                audit.record(AuditEntry.of(AuditEntry.Category.LICENSE, "license.rejected")
                        .failure("too large").detail("bytes", file.getSize()));
                flash.addFlashAttribute("error", "That file is too large to be a license.");
                return redirect();
            }
            licenseText = new String(file.getBytes(), StandardCharsets.UTF_8);
        } else if (text != null && !text.isBlank()) {
            if (text.length() > MAX_UPLOAD_BYTES) {
                audit.record(AuditEntry.of(AuditEntry.Category.LICENSE, "license.rejected")
                        .failure("too large").detail("bytes", text.length()));
                flash.addFlashAttribute("error", "That is too long to be a license.");
                return redirect();
            }
            licenseText = text;
        } else {
            flash.addFlashAttribute("error", "Choose the license file (.lic) to upload.");
            return redirect();
        }

        try {
            // The license file is outside the database, so it cannot share the audit record's transaction:
            // the store is checked first, and an install that cannot be audited is not attempted.
            audit.requireAvailable();
        } catch (AuditUnavailableException e) {
            flash.addFlashAttribute("error", "The license was not installed: " + e.getMessage());
            return redirect();
        }

        LicenseCheck previous = licenses.current();
        try {
            LicenseCheck check = licenses.install(licenseText);
            boolean installed = switch (check.state()) {
                case WRONG_PRODUCT, WRONG_INSTALLATION, SUPERSEDED -> false;
                default -> true;
            };
            if (installed) {
                log.info("License installed by {}: {} ({})", authentication.getName(), check.message(), check.state());
                audit.record(licenseEntry("license.installed", check)
                        .change("licenseId", previous.license().map(EnsLicense::id).orElse(null),
                                check.license().map(EnsLicense::id).orElse(null))
                        .change("state", previous.state().name(), check.state().name()));
                flash.addFlashAttribute(check.isFullyLicensed() ? "notice" : "error",
                        (check.isFullyLicensed() ? "License installed. " : "License installed, but: ") + check.message());
            } else {
                log.info("License upload by {} refused: {} ({})", authentication.getName(), check.message(), check.state());
                audit.record(licenseEntry("license.rejected", check).failure(check.state().name().toLowerCase())
                        .detail("message", check.message()));
                flash.addFlashAttribute("error", "That license was not installed. " + check.message());
            }
        } catch (LicenseException e) {
            log.info("License upload by {} refused: {}", authentication.getName(), e.getMessage());
            audit.record(AuditEntry.of(AuditEntry.Category.LICENSE, "license.rejected")
                    .failure("invalid")
                    .detail("message", e.getMessage()));
            flash.addFlashAttribute("error", "That isn't a valid ENS license: " + e.getMessage() + ".");
        } catch (AuditUnavailableException e) {
            log.error("License upload by {}: the audit log could not be written", authentication.getName());
            flash.addFlashAttribute("error", e.getMessage());
        }
        if (licenseObserver != null) {
            licenseObserver.accept(licenses.current());
        }
        return redirect();
    }

    /** What a license event records about the file: never its text. */
    private static AuditEntry licenseEntry(String action, LicenseCheck check) {
        AuditEntry entry = AuditEntry.of(AuditEntry.Category.LICENSE, action)
                .detail("state", check.state().name())
                .detail("mode", check.mode().name());
        check.license().ifPresent(l -> {
            entry.target("license", l.id(), l.licensee());
            entry.detail("licenseId", l.id());
            entry.detail("revision", l.revision());
            entry.detail("edition", l.edition());
            entry.detail("licensee", l.licensee());
            entry.detail("expires", l.expiresAt().map(Instant::toString).orElse(null));
        });
        return entry;
    }

    private void denied(HttpServletRequest request) {
        if (denials != null) {
            denials.denied(request, "permission_denied", "The license screen is for administrators.", null);
        }
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
