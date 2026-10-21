package com.winllc.innoutwork.api;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.RequiresFeature;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.TimeZones;
import com.winllc.innoutwork.util.Chunks;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** {@code GET /api/v1/attendance/events}: the Windows client's events over a date range. */
@RestController
@RequestMapping("/api/v1/attendance")
@RequiresFeature(LicensedFeature.ATTENDANCE)
@Tag(name = "Attendance", description = "Attendance records by date range")
public class AttendanceApiController {

    private final CheckInOutRecordRepository records;
    private final UserRecordRepository people;
    private final AccessPolicy accessPolicy;
    private final TimeZones timeZones;
    private final ApiSupport api;

    public AttendanceApiController(CheckInOutRecordRepository records, UserRecordRepository people,
                                   AccessPolicy accessPolicy, TimeZones timeZones, ApiSupport api) {
        this.records = records;
        this.people = people;
        this.accessPolicy = accessPolicy;
        this.timeZones = timeZones;
        this.api = api;
    }

    @GetMapping("/events")
    @PreAuthorize("hasAuthority('SCOPE_attendance:read')")
    @Operation(summary = "Attendance events by date range",
            description = "Check-ins, locks, unlocks and check-outs from the first day to the last (both included, "
                    + "days in the application's time zone), oldest first, of the people whose details the token's "
                    + "owner may see. Needs the attendance:read scope.")
    public ApiPage<ApiAttendanceEvent> events(
            Authentication authentication,
            @Parameter(description = "First day, YYYY-MM-DD", required = true, schema = @Schema(type = "string", format = "date")) @RequestParam String from,
            @Parameter(description = "Last day, YYYY-MM-DD, included", required = true,
                    schema = @Schema(type = "string", format = "date")) @RequestParam String to,
            @Parameter(description = "Only this person (DN)") @RequestParam(required = false) String dn,
            @Parameter(description = "Page, from 1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Items a page") @RequestParam(defaultValue = "100") int size) {
        ApiSupport.Range range = api.range(from, to);
        ZoneId app = timeZones.applicationZone();
        ZonedDateTime start = range.from().atStartOfDay(app);
        ZonedDateTime end = range.to().plusDays(1).atStartOfDay(app);
        PageRequest request = api.page(page, size, Sort.by("timestamp").ascending().and(Sort.by("id")));

        Optional<Set<String>> visible = people(authentication, dn, accessPolicy);
        Page<CheckInOutRecord> found = visible.isEmpty()
                ? records.findPageFromUntil(start, end, request)
                : visible.get().isEmpty() ? Page.empty(request)
                : records.findPageByLowercaseDnInFromUntil(visible.get(), start, end, request);

        Map<String, ZoneId> zones = zones(found.getContent().stream().map(CheckInOutRecord::getDn).toList(),
                people, timeZones);
        List<ApiAttendanceEvent> items = found.getContent().stream().map(r -> {
            ZoneId zone = zones.getOrDefault(r.getDn().toLowerCase(Locale.ROOT), app);
            return new ApiAttendanceEvent(r.getId(), r.getDn(), LdapDn.cnOf(r.getDn()),
                    r.getAction() == null ? null : r.getAction().name(), ApiSupport.time(r.getTimestamp(), zone),
                    r.getTimestamp().withZoneSameInstant(zone).toLocalDate().toString(),
                    Boolean.TRUE.equals(r.getForced()), zone.getId());
        }).toList();
        api.audited("api.attendance-events", "from=" + range.from() + ";to=" + range.to()
                + (dn == null ? "" : ";dn=" + dn) + ";page=" + page, items.size());
        return ApiPage.of(found, items);
    }

    /**
     * The people a query covers, by lower-cased DN: empty for everyone (an administrator, no {@code dn}), otherwise
     * those the viewer may see the details of, or just {@code dn}.
     *
     * @throws AccessDeniedException when {@code dn} is someone whose details the viewer may not see
     */
    static Optional<Set<String>> people(Authentication viewer, String dn, AccessPolicy accessPolicy) {
        if (dn != null && !dn.isBlank()) {
            if (!accessPolicy.canViewDetails(viewer, dn)) {
                throw new AccessDeniedException("Not a person this token may see the details of");
            }
            return Optional.of(Set.of(dn.trim().toLowerCase(Locale.ROOT)));
        }
        return accessPolicy.detailDns(viewer).map(dns -> {
            Set<String> lower = new LinkedHashSet<>();
            dns.forEach(d -> lower.add(d.toLowerCase(Locale.ROOT)));
            return lower;
        });
    }

    /** Each person's time zone, by lower-cased DN. */
    static Map<String, ZoneId> zones(Collection<String> dns, UserRecordRepository people, TimeZones timeZones) {
        Set<String> lower = new LinkedHashSet<>();
        dns.forEach(d -> lower.add(d.toLowerCase(Locale.ROOT)));
        Map<String, ZoneId> zones = new HashMap<>();
        for (List<String> chunk : Chunks.of(lower)) {
            for (UserRecord record : people.findAllByLowercaseDnIn(chunk)) {
                zones.put(record.getDn().toLowerCase(Locale.ROOT), timeZones.zoneFor(record));
            }
        }
        return zones;
    }
}
