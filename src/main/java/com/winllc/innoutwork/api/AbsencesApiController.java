package com.winllc.innoutwork.api;

import com.winllc.innoutwork.constant.RequestState;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.RequiresFeature;
import com.winllc.innoutwork.model.StatusRequest;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.StatusRequestRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.InvalidRequestException;
import com.winllc.innoutwork.service.TimeZones;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** {@code GET /api/v1/absences} and {@code /api/v1/absences/requests}: leave, telework and absences. */
@RestController
@RequestMapping("/api/v1/absences")
@RequiresFeature(LicensedFeature.CALENDAR)
@Tag(name = "Absences", description = "Absences, leave and leave requests")
public class AbsencesApiController {

    private final UserEventRecordRepository events;
    private final StatusRequestRepository requests;
    private final AccessPolicy accessPolicy;
    private final TimeZones timeZones;
    private final ApiSupport api;

    public AbsencesApiController(UserEventRecordRepository events, StatusRequestRepository requests,
                                 AccessPolicy accessPolicy, TimeZones timeZones, ApiSupport api) {
        this.events = events;
        this.requests = requests;
        this.accessPolicy = accessPolicy;
        this.timeZones = timeZones;
        this.api = api;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_absences:read')")
    @Operation(summary = "Absence and leave days by date range",
            description = "Each day from the first to the last (both included) that has a status other than a "
                    + "standard working day on the calendar of someone whose details the token's owner may see: "
                    + "approved leave and telework, statuses people set, and absences managers recorded. Needs the "
                    + "absences:read scope.")
    public ApiPage<ApiAbsence> absences(
            Authentication authentication,
            @Parameter(description = "First day, YYYY-MM-DD", required = true, schema = @Schema(type = "string", format = "date")) @RequestParam String from,
            @Parameter(description = "Last day, YYYY-MM-DD, included", required = true,
                    schema = @Schema(type = "string", format = "date")) @RequestParam String to,
            @Parameter(description = "Only this person (DN)") @RequestParam(required = false) String dn,
            @Parameter(description = "Page, from 1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Items a page") @RequestParam(defaultValue = "100") int size) {
        ApiSupport.Range range = api.range(from, to);
        PageRequest request = api.page(page, size, Sort.by("date").ascending().and(Sort.by("dn")).and(Sort.by("id")));
        Optional<Set<String>> visible = AttendanceApiController.people(authentication, dn, accessPolicy);
        Page<UserEventRecord> found = visible.isEmpty()
                ? events.findPageOtherThanBetween(UserStatusEnum.STANDARD, range.from(), range.to(), request)
                : visible.get().isEmpty() ? Page.empty(request)
                : events.findPageByLowercaseDnInOtherThanBetween(visible.get(), UserStatusEnum.STANDARD,
                range.from(), range.to(), request);
        List<ApiAbsence> items = found.getContent().stream().map(e -> new ApiAbsence(e.getDn(), LdapDn.cnOf(e.getDn()),
                e.getDate().toString(), e.getStatus().name(), e.getStatus().getFriendlyName(), source(e),
                e.getRequestId(), e.getLoginByTime() == null ? null : e.getLoginByTime().toString())).toList();
        api.audited("api.absences", "from=" + range.from() + ";to=" + range.to() + (dn == null ? "" : ";dn=" + dn)
                + ";page=" + page, items.size());
        return ApiPage.of(found, items);
    }

    @GetMapping("/requests")
    @PreAuthorize("hasAuthority('SCOPE_absences:read')")
    @Operation(summary = "Leave requests covering a date range",
            description = "Requests for leave, telework and other statuses that cover any day from the first to the "
                    + "last, of people whose details the token's owner may see, with their decision. Notes and "
                    + "comments are left out. Needs the absences:read scope.")
    public ApiPage<ApiLeaveRequest> leaveRequests(
            Authentication authentication,
            @Parameter(description = "First day, YYYY-MM-DD", required = true, schema = @Schema(type = "string", format = "date")) @RequestParam String from,
            @Parameter(description = "Last day, YYYY-MM-DD, included", required = true,
                    schema = @Schema(type = "string", format = "date")) @RequestParam String to,
            @Parameter(description = "Only this person (DN)") @RequestParam(required = false) String dn,
            @Parameter(description = "Only requests in this state: REQUESTED, APPROVED, REJECTED or CANCELLED")
            @RequestParam(required = false) String state,
            @Parameter(description = "Page, from 1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Items a page") @RequestParam(defaultValue = "100") int size) {
        ApiSupport.Range range = api.range(from, to);
        Set<RequestState> states = states(state);
        PageRequest request = api.page(page, size, Sort.by("fromDate").ascending().and(Sort.by("id")));
        Optional<Set<String>> visible = AttendanceApiController.people(authentication, dn, accessPolicy);
        Page<StatusRequest> found = visible.isEmpty()
                ? requests.findPageOverlapping(states, range.from(), range.to(), request)
                : visible.get().isEmpty() ? Page.empty(request)
                : requests.findPageByLowercaseRequesterInOverlapping(visible.get(), states, range.from(), range.to(),
                request);
        ZoneId zone = timeZones.applicationZone();
        List<ApiLeaveRequest> items = found.getContent().stream().map(r -> new ApiLeaveRequest(r.getId(),
                r.getRequesterDn(), LdapDn.cnOf(r.getRequesterDn()), r.getStatus().name(), r.getStatusName(),
                r.getFromDate().toString(), r.getToDate().toString(), r.getState().name(),
                ApiSupport.time(r.getCreatedAt(), zone), r.getDecidedByDn(), ApiSupport.time(r.getDecidedAt(), zone)))
                .toList();
        api.audited("api.leave-requests", "from=" + range.from() + ";to=" + range.to()
                + (dn == null ? "" : ";dn=" + dn) + (state == null ? "" : ";state=" + state) + ";page=" + page,
                items.size());
        return ApiPage.of(found, items);
    }

    private static Set<RequestState> states(String state) {
        if (state == null || state.isBlank()) {
            return EnumSet.allOf(RequestState.class);
        }
        try {
            return EnumSet.of(RequestState.valueOf(state.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw InvalidRequestException.badRequest("bad_request",
                    "state must be REQUESTED, APPROVED, REJECTED or CANCELLED");
        }
    }

    /** Where a calendar day's status came from. */
    static String source(UserEventRecord e) {
        if (e.getRequestId() != null) {
            return "request";
        }
        return e.getStatus() != null && e.getStatus().isSelectable() ? "self" : "manager";
    }
}
