package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.license.LicensingConfiguration;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.Approvers;
import com.winllc.innoutwork.service.PersonalDataService;
import com.winllc.innoutwork.service.RetentionService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Administration &gt; Privacy: retention's state, a person's data exported on their behalf, and its erasure on
 * request. Administrators only; open in every license state.
 *
 * <p>Erasure takes two steps. Choosing the person shows what ReadyRoom holds about them, whether they are still in
 * the directory, and a form carrying a one-time token kept in the administrator's session (good for
 * {@value #TOKEN_MINUTES} minutes, for that person only). Erasing then needs that token and the person's name typed
 * again, and the browser asks once more. Nobody erases themselves.
 */
@Controller
@RequestMapping(LicensingConfiguration.ADMIN_PRIVACY_SCREEN)
@PreAuthorize("hasAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN)")
public class AdminPrivacyController {

    static final String TOKEN_ATTRIBUTE = AdminPrivacyController.class.getName() + ".erasure";
    static final int TOKEN_MINUTES = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The first step's token, for one person, until it expires. */
    /** Kept in the browser session, which is in the database: serializable. */
    record PendingErasure(String dn, String token, Instant expires) implements java.io.Serializable {
    }

    private final PersonalDataService personalData;
    private final RetentionService retention;
    private final ApplicationProperties properties;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    private AuditLog audit = AuditLog.NOOP;

    @Autowired
    public AdminPrivacyController(PersonalDataService personalData, RetentionService retention,
                                  ApplicationProperties properties, AccessPolicy accessPolicy) {
        this(personalData, retention, properties, accessPolicy, Clock.systemUTC());
    }

    AdminPrivacyController(PersonalDataService personalData, RetentionService retention,
                           ApplicationProperties properties, AccessPolicy accessPolicy, Clock clock) {
        this.personalData = personalData;
        this.retention = retention;
        this.properties = properties;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setAuditLog(AuditLog audit) {
        this.audit = audit;
    }

    /** Retention's state; with {@code dn}, step one of erasing that person. */
    @GetMapping
    public String page(Authentication authentication, @RequestParam(required = false) String dn, HttpSession session,
                       Model model) {
        model.addAttribute("retentionDays", properties.getRetention().getEventsDays());
        model.addAttribute("retentionCron", properties.getRetention().getCron());
        model.addAttribute("purgedBefore", retention.purgedBefore());
        model.addAttribute("lastPurge", retention.lastRunAt());
        model.addAttribute("visibility", accessPolicy.coworkerVisibility() == null ? "full"
                : accessPolicy.coworkerVisibility().name().toLowerCase());

        if (dn != null && !dn.isBlank()) {
            LdapDn person;
            try {
                person = new LdapDn(dn.strip());
            } catch (IllegalArgumentException e) {
                model.addAttribute("error", "Not a DN: " + dn);
                return "admin-privacy";
            }
            model.addAttribute("person", person.dn());
            model.addAttribute("personCn", person.getCn());
            model.addAttribute("counts", personalData.inventory(person.dn()));
            model.addAttribute("inDirectory", personalData.inDirectory(person.dn()));
            if (Approvers.sameDn(person.dn(), authentication.getName())) {
                model.addAttribute("self", true);
            } else {
                String token = HexFormat.of().formatHex(randomBytes());
                session.setAttribute(TOKEN_ATTRIBUTE, new PendingErasure(person.dn(), token,
                        clock.instant().plusSeconds(TOKEN_MINUTES * 60L)));
                model.addAttribute("token", token);
            }
        }
        return "admin-privacy";
    }

    /** A person's data, downloaded on their behalf (a subject access request); audited. */
    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> export(Authentication authentication, @RequestParam String dn) {
        return PrivacyController.download(audit, personalData, new LdapDn(dn.strip()).dn(), authentication.getName());
    }

    /** Step two: erases the person the token was issued for, once their name is typed again. */
    @PostMapping("/erase")
    public String erase(Authentication authentication, @RequestParam String dn, @RequestParam String token,
                        @RequestParam(defaultValue = "") String confirmName, HttpSession session,
                        RedirectAttributes redirect) {
        Object pending = session.getAttribute(TOKEN_ATTRIBUTE);
        session.removeAttribute(TOKEN_ATTRIBUTE);
        if (!(pending instanceof PendingErasure erasure) || !erasure.token().equals(token)
                || !Approvers.sameDn(erasure.dn(), dn) || clock.instant().isAfter(erasure.expires())) {
            redirect.addFlashAttribute("error", "That confirmation has expired or is not for this person: start again.");
            return "redirect:" + LicensingConfiguration.ADMIN_PRIVACY_SCREEN;
        }
        if (Approvers.sameDn(erasure.dn(), authentication.getName())) {
            redirect.addFlashAttribute("error", "You cannot erase yourself: ask another administrator.");
            return "redirect:" + LicensingConfiguration.ADMIN_PRIVACY_SCREEN;
        }
        String cn = LdapDn.cnOf(erasure.dn());
        if (!cn.equalsIgnoreCase(confirmName.strip())) {
            redirect.addFlashAttribute("error", "The name typed does not match " + cn + "; nothing was erased.");
            return "redirect:" + LicensingConfiguration.ADMIN_PRIVACY_SCREEN;
        }

        PersonalDataService.Erasure result = personalData.erase(erasure.dn());
        long total = result.removed().values().stream().mapToLong(Long::longValue).sum();
        redirect.addFlashAttribute("message", "Erased the personal data of " + cn + " (" + total + " records).");
        if (result.inDirectory()) {
            redirect.addFlashAttribute("warning", cn + " is still in the directory: ReadyRoom will create their"
                    + " account again, without the erased data, at their next sign-in or the next directory refresh."
                    + " Remove or disable them in the directory to stop that.");
        }
        return "redirect:" + LicensingConfiguration.ADMIN_PRIVACY_SCREEN;
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
