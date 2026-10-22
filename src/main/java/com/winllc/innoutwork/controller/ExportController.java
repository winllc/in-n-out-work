package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.export.ReportExports;
import com.winllc.innoutwork.export.TableWriter;
import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.RequiresFeature;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.InvalidRequestException;
import com.winllc.innoutwork.service.LdapService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static com.winllc.innoutwork.service.LdapService.escapeLdapFilter;

/**
 * CSV and XLSX downloads (RR-19): a group's attendance report, and lists of people (a search, a group's members),
 * streamed as they are read. Posts, from forms carrying the CSRF token, and each one is audited as data.exported
 * before the file starts. What a file holds is what its viewer may see: the group report's people are those whose
 * details they may see, and a list shows everyone else as a coworker sees them.
 */
@Controller
@RequestMapping("/app/export")
public class ExportController {

    private final ReportExports exports;
    private final LdapService ldapService;
    private final AccessPolicy accessPolicy;
    private final ApplicationProperties properties;
    private final AuditLog audit;

    public ExportController(ReportExports exports, LdapService ldapService, AccessPolicy accessPolicy,
                            ApplicationProperties properties, ObjectProvider<AuditLog> audit) {
        this.exports = exports;
        this.ldapService = ldapService;
        this.accessPolicy = accessPolicy;
        this.properties = properties;
        this.audit = audit.getIfAvailable(() -> AuditLog.NOOP);
    }

    /** A group's attendance, a row per member and day: ADMIN, or a manager of the group (as the report). */
    @RequiresFeature(LicensedFeature.METRICS)
    @PostMapping("/group-report")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, "
            + "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER) and @accessPolicy.canManageGroup(authentication, #groupDn)")
    public void groupReport(Authentication authentication, HttpServletResponse response,
                            @RequestParam String groupDn, @RequestParam String fromDate, @RequestParam String toDate,
                            @RequestParam(defaultValue = "csv") String format,
                            @RequestParam(defaultValue = "person") String zones) throws IOException {
        TableWriter.Format type = format(format);
        LocalDate from = day("fromDate", fromDate);
        LocalDate to = day("toDate", toDate);
        if (to.isBefore(from)) {
            throw InvalidRequestException.badRequest("bad_request", "The report must end on or after its first day.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > properties.getReports().getMaxRangeDays()) {
            throw InvalidRequestException.badRequest("bad_request", "An export may cover at most "
                    + properties.getReports().getMaxRangeDays() + " days.");
        }
        ReportExports.Zones zoneChoice = ReportExports.Zones.of(zones);
        audit.record(AuditEntry.of(AuditEntry.Category.DATA, "data.exported")
                .target("group", groupDn, LdapDn.cnOf(groupDn))
                .detail("report", "group")
                .detail("format", type.extension())
                .detail("from", from.toString())
                .detail("to", to.toString())
                .detail("timeZones", zoneChoice.name().toLowerCase()));
        start(response, type, "readyroom-" + slug(LdapDn.cnOf(groupDn)) + "-" + from + "-to-" + to);
        OutputStream out = response.getOutputStream();
        exports.writeGroupAttendance(groupDn, from, to, authentication, zoneChoice, type.open(out, "Attendance"));
        out.flush();
    }

    /**
     * People and their status today: a name search (as Find a user), or a group's members (its members, its managers
     * and ADMIN, as its page).
     */
    @PostMapping("/people")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).USER)")
    public void people(Authentication authentication, HttpServletResponse response,
                       @RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "") String group,
                       @RequestParam(defaultValue = "csv") String format) throws IOException {
        TableWriter.Format type = format(format);
        List<String> dns;
        String name;
        if (!group.isBlank()) {
            if (!accessPolicy.canViewGroup(authentication, group)) {
                throw new AccessDeniedException("Not a group this user may open");
            }
            dns = ldapService.getGroupMembers(new LdapDn(group)).stream().distinct().toList();
            name = "readyroom-" + slug(LdapDn.cnOf(group)) + "-people";
        } else {
            if (search.trim().length() < 3) {
                throw InvalidRequestException.badRequest("bad_request", "Search for at least three characters.");
            }
            String filter = "(&%s(cn=*%s*))".formatted(properties.getUserLdapFilter(), escapeLdapFilter(search.trim()));
            dns = ldapService.search(filter).stream().map(UserStatus::getDn).toList();
            name = "readyroom-people-" + slug(search);
        }
        AuditEntry entry = AuditEntry.of(AuditEntry.Category.DATA, "data.exported")
                .detail("report", "people")
                .detail("format", type.extension())
                .detail("count", dns.size());
        if (!group.isBlank()) {
            entry.target("group", group, LdapDn.cnOf(group));
        } else {
            entry.detail("search", search.trim());
        }
        audit.record(entry);
        start(response, type, name + "-" + LocalDate.now());
        OutputStream out = response.getOutputStream();
        exports.writePeople(dns, authentication, type.open(out, "People"));
        out.flush();
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, String>> invalid(InvalidRequestException e) {
        return com.winllc.innoutwork.rest.ApiErrors.of(e);
    }

    private static void start(HttpServletResponse response, TableWriter.Format type, String name) {
        response.setContentType(type.contentType());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + name + "." + type.extension() + "\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private static TableWriter.Format format(String format) {
        TableWriter.Format type = TableWriter.Format.of(format);
        if (type == null) {
            throw InvalidRequestException.badRequest("bad_request", "format must be csv or xlsx");
        }
        return type;
    }

    /** A day: YYYY-MM-DD, or the report form's ISO date-time (its date in the application's zone). */
    static LocalDate day(String name, String value) {
        if (value == null || value.isBlank()) {
            throw InvalidRequestException.badRequest("bad_request", name + " is required");
        }
        String v = value.trim();
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            try {
                return ZonedDateTime.parse(v).withZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate();
            } catch (DateTimeParseException e2) {
                throw InvalidRequestException.badRequest("bad_request", name + " must be a date, YYYY-MM-DD");
            }
        }
    }

    static String slug(String text) {
        return com.winllc.innoutwork.export.ExportFileNames.slug(text);
    }
}
