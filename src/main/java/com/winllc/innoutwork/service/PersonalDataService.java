package com.winllc.innoutwork.service;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.model.AttendanceSummary;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.GroupRecord;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.StatusRequest;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.AttendanceSummaryRepository;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GroupRecordRepository;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.PermissionRecordRepository;
import com.winllc.innoutwork.repository.StatusRequestRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A person's data in ReadyRoom (RR-12): what is held about them, a self-service export of it, and its erasure by an
 * administrator. Keyed by DN, ignoring case, as everywhere.
 *
 * <p>What is ReadyRoom's own about a person: their user record (profile notes, preferred and average login time,
 * role, favourite groups, delegates and the directory fields copied at sign-in), group access granted in ReadyRoom,
 * their check-in records, calendar statuses, status requests, absence alerts about them (and those sent to them, as
 * a manager or delegate), and the monthly summaries retention wrote. Their name, e-mail, phone, groups and manager
 * come from the directory and stay there.
 *
 * <p>Erasure deletes all of that, and takes them off other people's delegates and groups' alternate managers. It
 * keeps the audit trail, which is append-only and records ids, DNs, dates and states but not notes, and it leaves
 * their name where it stands in other people's records as the one who decided a request or answered an alert.
 */
@Service
public class PersonalDataService {

    private static final Logger log = LoggerFactory.getLogger(PersonalDataService.class);

    /** The export's format identifier. */
    public static final String FORMAT = "readyroom-personal-data/1";

    private final UserRecordRepository users;
    private final PermissionRecordRepository permissions;
    private final CheckInOutRecordRepository checkIns;
    private final UserEventRecordRepository events;
    private final StatusRequestRepository requests;
    private final NotificationRepository notifications;
    private final AttendanceSummaryRepository summaries;
    private final GroupRecordRepository groups;
    private final LdapService ldapService;
    private final Clock clock;

    private AuditLog audit = AuditLog.NOOP;

    @Autowired
    public PersonalDataService(UserRecordRepository users, PermissionRecordRepository permissions,
                               CheckInOutRecordRepository checkIns, UserEventRecordRepository events,
                               StatusRequestRepository requests, NotificationRepository notifications,
                               AttendanceSummaryRepository summaries, GroupRecordRepository groups,
                               LdapService ldapService) {
        this(users, permissions, checkIns, events, requests, notifications, summaries, groups, ldapService,
                Clock.systemDefaultZone());
    }

    PersonalDataService(UserRecordRepository users, PermissionRecordRepository permissions,
                        CheckInOutRecordRepository checkIns, UserEventRecordRepository events,
                        StatusRequestRepository requests, NotificationRepository notifications,
                        AttendanceSummaryRepository summaries, GroupRecordRepository groups, LdapService ldapService,
                        Clock clock) {
        this.users = users;
        this.permissions = permissions;
        this.checkIns = checkIns;
        this.events = events;
        this.requests = requests;
        this.notifications = notifications;
        this.summaries = summaries;
        this.groups = groups;
        this.ldapService = ldapService;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setAuditLog(AuditLog audit) {
        this.audit = audit;
    }

    // --- what is held ---------------------------------------------------------------------------------------------

    /** How many of each kind of record ReadyRoom holds about {@code dn}, for the erasure page. */
    public Map<String, Long> inventory(String dn) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Optional<UserRecord> record = users.findByDnIgnoreCase(dn);
        counts.put("userRecord", record.isPresent() ? 1L : 0L);
        counts.put("groupAccess", record.map(r -> (long) permissions.findGroupDnsByUserId(r.getId()).size()).orElse(0L));
        counts.put("checkIns", checkIns.countByDnIgnoreCase(dn));
        counts.put("calendarStatuses", (long) events.findAllByDn(dn).size());
        counts.put("statusRequests", (long) requests.findByRequester(dn).size());
        counts.put("absenceAlerts", notifications.countAboutOrFor(dn));
        counts.put("monthlySummaries", summaries.countByDnIgnoreCase(dn));
        counts.put("delegateOf", (long) delegateOf(dn).size());
        counts.put("groupManagerOf", (long) groupManagerOf(dn).size());
        return counts;
    }

    /** Whether {@code dn} is still in the directory: if so, their account comes back at their next sign-in. */
    public boolean inDirectory(String dn) {
        try {
            return ldapService.lookupUser(new LdapDn(dn)).isPresent();
        } catch (RuntimeException e) {
            log.warn("Could not look {} up in the directory: {}", LogSafe.of(dn), e.getMessage());
            return false;
        }
    }

    // --- export -----------------------------------------------------------------------------------------------------

    /**
     * Everything ReadyRoom holds about {@code dn}, as one JSON-ready map ({@value #FORMAT}): every value a string,
     * number, boolean, list or map, times in ISO 8601. The session id of check-ins, a credential, is left out.
     */
    public Map<String, Object> export(String dn) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", FORMAT);
        out.put("exportedAt", iso(ZonedDateTime.now(clock)));
        out.put("subject", Map.of("dn", dn, "name", LdapDn.cnOf(dn)));
        out.put("sources", "Your name, e-mail, phone, groups and manager come from the directory and are kept there;"
                + " this is what ReadyRoom itself stores about you.");

        Optional<UserRecord> record = users.findByDnIgnoreCase(dn);
        out.put("profile", record.map(PersonalDataService::profile).orElse(null));
        out.put("groupAccess", record.map(r -> permissions.findGroupDnsByUserId(r.getId())).orElse(List.of()));
        out.put("delegateOf", delegateOf(dn).stream().map(UserRecord::getDn).toList());
        out.put("groupManagerOf", groupManagerOf(dn).stream().map(GroupRecord::getGroupDn).toList());

        List<Map<String, Object>> checkInList = new ArrayList<>();
        for (CheckInOutRecord r : checkIns.findAllByDn(dn)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timestamp", iso(r.getTimestamp()));
            m.put("action", name(r.getAction()));
            m.put("automaticCheckOut", Boolean.TRUE.equals(r.getForced()));
            m.put("windowsUserId", r.getWindowsUserId());
            m.put("organization", r.getOrganization());
            m.put("employeeType", r.getEmployeeType());
            m.put("location", r.getLocation());
            m.put("branch", r.getBranch());
            m.put("dutySubOrganization", r.getDutySubOrganization());
            // The check-in's client address (it binds the logoff); absent on other records and older check-ins.
            if (r.getClientAddress() != null && !CheckInOutRecord.ANY_ADDRESS.equals(r.getClientAddress())) {
                m.put("clientAddress", r.getClientAddress());
            }
            checkInList.add(m);
        }
        out.put("checkIns", checkInList);

        List<Map<String, Object>> statusList = new ArrayList<>();
        for (UserEventRecord e : events.findAllByDn(dn)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", iso(e.getDate()));
            m.put("status", name(e.getStatus()));
            m.put("lateArrivalTime", iso(e.getLoginByTime()));
            m.put("requestId", e.getRequestId());
            statusList.add(m);
        }
        out.put("calendarStatuses", statusList);

        List<Map<String, Object>> requestList = new ArrayList<>();
        for (StatusRequest r : requests.findByRequester(dn)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("status", name(r.getStatus()));
            m.put("from", iso(r.getFromDate()));
            m.put("to", iso(r.getToDate()));
            m.put("lateArrivalTime", iso(r.getLoginByTime()));
            m.put("note", r.getNote());
            m.put("state", name(r.getState()));
            m.put("createdAt", iso(r.getCreatedAt()));
            m.put("decidedBy", r.getDecidedByDn());
            m.put("decidedAt", iso(r.getDecidedAt()));
            m.put("decisionComment", r.getDecisionNote());
            m.put("cancelledBy", r.getCancelledByDn());
            m.put("cancelledAt", iso(r.getCancelledAt()));
            requestList.add(m);
        }
        out.put("statusRequests", requestList);

        List<Map<String, Object>> alertList = new ArrayList<>();
        for (NotificationRecord n : notifications.findByAboutUserDnIgnoreCase(dn)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("raisedAt", iso(n.getNotificationDate()));
            m.put("sentTo", n.getForUserDn());
            m.put("expectedCheckInTime", iso(n.getExpectedCheckInTime()));
            m.put("response", name(n.getStatusResponse()));
            m.put("respondedBy", n.getStatusResponseByDn());
            m.put("respondedAt", iso(n.getStatusResponseDate()));
            alertList.add(m);
        }
        out.put("absenceAlertsAboutYou", alertList);

        List<Map<String, Object>> summaryList = new ArrayList<>();
        for (AttendanceSummary s : summaries.findByDn(dn)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", s.getMonth().toString().substring(0, 7));
            m.put("daysCheckedIn", s.getDaysCheckedIn());
            m.put("minutesIn", s.getMinutesIn());
            Map<String, Integer> days = new LinkedHashMap<>();
            s.getStatusDays().forEach((status, count) -> days.put(status.name(), count));
            m.put("daysPerStatus", days);
            summaryList.add(m);
        }
        out.put("monthlySummaries", summaryList);
        return out;
    }

    private static Map<String, Object> profile(UserRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dn", r.getDn());
        m.put("notes", r.getNotes());
        m.put("preferredLoginTime", iso(r.getChosenLoginTime()));
        m.put("averageLoginTime", iso(r.getAverageLoginTime()));
        m.put("role", name(r.getUserRole()));
        m.put("favoriteGroups", r.getFavoriteGroupsList());
        m.put("delegates", r.getAltManagerList());
        m.put("organization", r.getOrganization());
        m.put("employeeType", r.getEmployeeType());
        m.put("location", r.getLocation());
        m.put("branch", r.getBranch());
        m.put("dutySubOrganization", r.getDutySubOrganization());
        m.put("phoneNumber", r.getPhoneNumber());
        m.put("email", r.getEmail());
        return m;
    }

    // --- erasure ----------------------------------------------------------------------------------------------------

    /** What an erasure removed. */
    public record Erasure(String dn, Map<String, Long> removed, boolean inDirectory) {
    }

    /**
     * Erases {@code dn}'s personal data, in one transaction recorded as {@code person.erased} (the counts, whether
     * they are still in the directory; nothing of the data): their user record and group access, check-ins,
     * calendar statuses, status requests, absence alerts about them or sent to them, monthly summaries, and their
     * name from other people's delegates and groups' alternate managers. The caller checks it is an administrator,
     * and not themselves.
     */
    public Erasure erase(String dn) {
        boolean inDirectory = inDirectory(dn);
        Map<String, Long> removed = audit.inTransaction(() -> {
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("calendarStatuses", (long) events.deleteByDn(dn));
            counts.put("statusRequests", (long) requests.deleteByRequester(dn));
            counts.put("checkIns", (long) checkIns.deleteByDn(dn));
            counts.put("absenceAlerts", (long) notifications.deleteAboutOrFor(dn));
            List<AttendanceSummary> own = summaries.findByDn(dn);
            summaries.deleteAll(own);
            counts.put("monthlySummaries", (long) own.size());

            long delegations = 0;
            for (UserRecord other : delegateOf(dn)) {
                other.setAlternateManagers(without(other.getAltManagerList(), dn));
                users.save(other);
                delegations++;
            }
            counts.put("delegateOf", delegations);
            long groupManagers = 0;
            for (GroupRecord group : groupManagerOf(dn)) {
                group.setAlternateManagers(without(group.getAltManagerList(), dn));
                groups.save(group);
                groupManagers++;
            }
            counts.put("groupManagerOf", groupManagers);

            Optional<UserRecord> record = users.findByDnIgnoreCase(dn);
            long access = 0;
            if (record.isPresent()) {
                access = permissions.deleteByUserId(record.get().getId());
                users.deleteById(record.get().getId());
            }
            counts.put("groupAccess", access);
            counts.put("userRecord", record.isPresent() ? 1L : 0L);

            AuditEntry entry = AuditEntry.of(AuditEntry.Category.ACCOUNT, "person.erased")
                    .target("user", dn, LdapDn.cnOf(dn))
                    .detail("inDirectory", inDirectory);
            counts.forEach(entry::detail);
            audit.record(entry);
            return counts;
        });
        log.info("Erased the personal data of {}: {}{}", dn, removed,
                inDirectory ? " (still in the directory: their account returns at their next sign-in)" : "");
        return new Erasure(dn, removed, inDirectory);
    }

    /** The people who have {@code dn} as a delegate. */
    private List<UserRecord> delegateOf(String dn) {
        return users.findByAlternateManagersLike(mentioning(dn)).stream()
                .filter(r -> r.getAltManagerList().stream().anyMatch(m -> Approvers.sameDn(m, dn)))
                .toList();
    }

    /** The groups ReadyRoom lists {@code dn} as an alternate manager of. */
    private List<GroupRecord> groupManagerOf(String dn) {
        return groups.findByAlternateManagersLike(mentioning(dn)).stream()
                .filter(g -> g.getAltManagerList().stream().anyMatch(m -> Approvers.sameDn(m, dn)))
                .toList();
    }

    private static String without(List<String> dns, String dn) {
        String rest = String.join(";", dns.stream().filter(m -> !Approvers.sameDn(m, dn)).toList());
        return rest.isEmpty() ? null : rest;
    }

    private static String mentioning(String dn) {
        String escaped = LdapDn.normalize(dn).toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    /** ISO 8601: an offset date-time for instants (2026-03-02T08:00:00-05:00), the plain form for dates and times. */
    private static String iso(TemporalAccessor value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ZonedDateTime zoned) {
            return zoned.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        return value.toString();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
