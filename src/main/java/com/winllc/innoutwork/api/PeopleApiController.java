package com.winllc.innoutwork.api;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.RequiresFeature;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.TimeZones;
import com.winllc.innoutwork.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@code GET /api/v1/people}: the people ReadyRoom tracks and their status today. */
@RestController
@RequestMapping("/api/v1/people")
@RequiresFeature(LicensedFeature.ATTENDANCE)
@Tag(name = "People", description = "Tracked people and their status today")
public class PeopleApiController {

    private final UserRecordRepository people;
    private final UserService userService;
    private final AccessPolicy accessPolicy;
    private final TimeZones timeZones;
    private final ApiSupport api;

    public PeopleApiController(UserRecordRepository people, UserService userService, AccessPolicy accessPolicy,
                               TimeZones timeZones, ApiSupport api) {
        this.people = people;
        this.userService = userService;
        this.accessPolicy = accessPolicy;
        this.timeZones = timeZones;
        this.api = api;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_people:read')")
    @Operation(summary = "People and their status today",
            description = "Everyone ReadyRoom tracks, by DN, a page at a time. The token's owner sees the times and "
                    + "calendar status of the people whose details they may see (themselves, the people they are "
                    + "responsible for, everyone for an administrator); of anyone else what a coworker sees under "
                    + "the privacy settings. Needs the people:read scope.")
    public ApiPage<ApiPerson> people(
            Authentication authentication,
            @Parameter(description = "Part of the person's DN or name, any case") @RequestParam(required = false) String q,
            @Parameter(description = "Exact location (site), any case") @RequestParam(required = false) String location,
            @Parameter(description = "Exact organization, any case") @RequestParam(required = false) String organization,
            @Parameter(description = "Page, from 1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Items a page") @RequestParam(defaultValue = "100") int size) {
        Page<UserRecord> found = people.search(ApiSupport.containing(q), ApiSupport.lower(location),
                ApiSupport.lower(organization), api.page(page, size, Sort.by("dn").ascending().and(Sort.by("id"))));

        List<String> dns = found.getContent().stream().map(UserRecord::getDn).toList();
        Map<String, UserStatus> statuses = new HashMap<>();
        for (UserStatus s : userService.getUserStatuses(dns, null)) {
            statuses.put(s.getDn().toLowerCase(), s);
        }
        AccessPolicy.Scope scope = accessPolicy.scope(authentication);
        List<ApiPerson> items = new ArrayList<>();
        for (UserRecord record : found.getContent()) {
            UserStatus status = statuses.getOrDefault(record.getDn().toLowerCase(),
                    UserStatus.builder().dn(record.getDn()).status("NONE").build());
            boolean details = scope.canViewDetails(record.getDn());
            UserStatus shown = scope.show(status);
            ZoneId zone = timeZones.zoneFor(record);
            items.add(new ApiPerson(record.getDn(), LdapDn.cnOf(record.getDn()), record.getEmail(),
                    record.getOrganization(), record.getLocation(), record.getEmployeeType(), shown.getStatus(),
                    details ? ApiSupport.time(status.getCheckedInAt(), zone) : null,
                    details ? ApiSupport.time(status.getCheckedOutAt(), zone) : null,
                    details ? ApiSupport.time(status.getLastStatusChangeAt(), zone) : null,
                    zone.getId(), details));
        }
        api.audited("api.people", "q=" + (q == null ? "" : q) + ";location=" + (location == null ? "" : location)
                + ";organization=" + (organization == null ? "" : organization) + ";page=" + page, items.size());
        return ApiPage.of(found, items);
    }
}
