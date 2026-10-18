package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.license.LicensingConfiguration;
import com.winllc.innoutwork.service.PersonalDataService;
import com.winllc.innoutwork.service.RetentionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * "My data": what ReadyRoom holds about the signed-in user, who sees it and for how long, and a download of all of
 * it as JSON (audited as {@code data.exported}, report {@code personal-data}). Open in every license state.
 */
@Controller
@RequestMapping(LicensingConfiguration.PRIVACY_SCREEN)
@PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).USER)")
public class PrivacyController {

    private final PersonalDataService personalData;
    private final RetentionService retention;
    private final ApplicationProperties properties;

    private AuditLog audit = AuditLog.NOOP;

    public PrivacyController(PersonalDataService personalData, RetentionService retention,
                             ApplicationProperties properties) {
        this.personalData = personalData;
        this.retention = retention;
        this.properties = properties;
    }

    @Autowired(required = false)
    public void setAuditLog(AuditLog audit) {
        this.audit = audit;
    }

    @GetMapping
    public String page(Authentication authentication, Model model) {
        model.addAttribute("counts", personalData.inventory(authentication.getName()));
        model.addAttribute("retentionDays", properties.getRetention().getEventsDays());
        model.addAttribute("purgedBefore", retention.purgedBefore());
        model.addAttribute("visibility", properties.getPrivacy().getCoworkerVisibility().name().toLowerCase());
        return "privacy";
    }

    /** Everything ReadyRoom holds about the signed-in user, as a JSON download; audited. */
    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> export(Authentication authentication) {
        return download(audit, personalData, authentication.getName(), "self");
    }

    /** The download, for the user themselves or an administrator: audited first, so an unrecorded export fails. */
    static ResponseEntity<Map<String, Object>> download(AuditLog audit, PersonalDataService personalData, String dn,
                                                        String requestedBy) {
        Map<String, Object> data = personalData.export(dn);
        audit.record(AuditEntry.of(AuditEntry.Category.DATA, "data.exported")
                .target("user", dn, LdapDn.cnOf(dn))
                .detail("report", "personal-data")
                .detail("format", "json")
                .detail("requestedBy", requestedBy)
                .detail("checkIns", size(data.get("checkIns")))
                .detail("calendarStatuses", size(data.get("calendarStatuses")))
                .detail("statusRequests", size(data.get("statusRequests")))
                .detail("monthlySummaries", size(data.get("monthlySummaries"))));
        String file = "readyroom-data-" + LdapDn.cnOf(dn).replaceAll("[^A-Za-z0-9._-]", "_") + "-"
                + LocalDate.now() + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .body(data);
    }

    private static int size(Object list) {
        return list instanceof List<?> l ? l.size() : 0;
    }
}
