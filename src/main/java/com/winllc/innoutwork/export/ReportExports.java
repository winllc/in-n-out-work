package com.winllc.innoutwork.export;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.data.reports.UserDayReport;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.RetentionService;
import com.winllc.innoutwork.service.TimeZones;
import com.winllc.innoutwork.service.UserService;
import com.winllc.innoutwork.util.Chunks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rows of the downloadable reports (RR-19), written to a {@link TableWriter} as they are read: a group's
 * attendance, one row per person and day, and a list of people with their status today. Both show only what the
 * viewer may see (AccessPolicy): the attendance of the people whose details they may see, and of everyone else in a
 * list what a coworker sees. Members are read a chunk at a time, so memory does not grow with the group.
 */
@Service
public class ReportExports {

    public static final List<String> ATTENDANCE_COLUMNS = List.of("Name", "DN", "Organization", "Location",
            "Employee type", "Date", "Status", "First in", "Last out", "Minutes in", "Time zone");

    public static final List<String> PEOPLE_COLUMNS = List.of("Name", "DN", "Email", "Phone", "Organization",
            "Location", "Employee type", "Status today", "Checked in", "Checked out", "Last change", "Time zone");

    /** Members read at once. */
    static final int CHUNK = 200;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** Whose time zone an attendance export's days and times are in. */
    public enum Zones {
        /** Each person's own (their profile's, else their site's): the default. */
        PERSON,
        /** The viewer's, for every row. */
        MINE;

        public static Zones of(String value) {
            return "mine".equalsIgnoreCase(value) ? MINE : PERSON;
        }
    }

    private final LdapService ldapService;
    private final CheckInOutRecordRepository records;
    private final UserEventRecordRepository events;
    private final UserRecordRepository people;
    private final UserService userService;
    private final AccessPolicy accessPolicy;
    private final TimeZones timeZones;
    private final LoadingCache<String, LdapUser> userCache;
    private final ObjectProvider<RetentionService> retention;

    public ReportExports(LdapService ldapService, CheckInOutRecordRepository records, UserEventRecordRepository events,
                         UserRecordRepository people, UserService userService, AccessPolicy accessPolicy,
                         TimeZones timeZones, @Qualifier("ldapUserLoadingCache") LoadingCache<String, LdapUser> userCache,
                         ObjectProvider<RetentionService> retention) {
        this.ldapService = ldapService;
        this.records = records;
        this.events = events;
        this.people = people;
        this.userService = userService;
        this.accessPolicy = accessPolicy;
        this.timeZones = timeZones;
        this.userCache = userCache;
        this.retention = retention;
    }

    /** The members of a group whose details {@code viewer} may see, by name. */
    public List<String> visibleMembers(String groupDn, Authentication viewer) {
        AccessPolicy.Scope scope = accessPolicy.scope(viewer);
        Map<String, String> unique = new LinkedHashMap<>();
        for (String dn : ldapService.getGroupMembers(new LdapDn(groupDn))) {
            if (dn != null && scope.canViewDetails(dn)) {
                unique.putIfAbsent(dn.toLowerCase(Locale.ROOT), dn);
            }
        }
        List<String> members = new ArrayList<>(unique.values());
        members.sort(Comparator.comparing(LdapDn::cnOf, String.CASE_INSENSITIVE_ORDER).thenComparing(d -> d));
        return members;
    }

    /**
     * A group's attendance from {@code from} to {@code to}: a row per member and day.
     *
     * @return the rows written
     */
    public long writeGroupAttendance(String groupDn, LocalDate from, LocalDate to, Authentication viewer, Zones zones,
                                     TableWriter out) throws IOException {
        List<String> members = visibleMembers(groupDn, viewer);
        ZoneId viewerZone = zones == Zones.MINE
                ? timeZones.zoneFor(people.findByDnIgnoreCase(viewer.getName()).orElse(null)) : null;
        RetentionService purge = retention.getIfAvailable();
        LocalDate purgedBefore = purge == null ? null : purge.purgedBefore();
        ZoneId app = timeZones.applicationZone();
        // A day either side: people's own days can start up to a day off the application's.
        ZonedDateTime queryFrom = from.minusDays(1).atStartOfDay(app);
        ZonedDateTime queryTo = to.plusDays(2).atStartOfDay(app);

        out.header(ATTENDANCE_COLUMNS);
        long rows = 0;
        for (List<String> chunk : Chunks.of(members, CHUNK)) {
            List<String> lower = chunk.stream().map(d -> d.toLowerCase(Locale.ROOT)).toList();
            Map<String, List<CheckInOutRecord>> recordsByDn = new HashMap<>();
            records.findByLowercaseDnInAndTimestampBetween(lower, queryFrom, queryTo)
                    .forEach(r -> recordsByDn.computeIfAbsent(r.getDn().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(r));
            Map<String, Map<LocalDate, UserEventRecord>> statusByDn = new HashMap<>();
            for (UserEventRecord e : events.findByLowercaseDnInAndDateBetween(lower, from, to)) {
                if (e.getStatus() != null && e.getStatus() != UserStatusEnum.STANDARD) {
                    statusByDn.computeIfAbsent(e.getDn().toLowerCase(Locale.ROOT), k -> new HashMap<>())
                            .putIfAbsent(e.getDate(), e);
                }
            }
            Map<String, UserRecord> recordByDn = new HashMap<>();
            people.findAllByLowercaseDnIn(lower).forEach(r -> recordByDn.putIfAbsent(r.getDn().toLowerCase(Locale.ROOT), r));

            for (String dn : chunk) {
                String key = dn.toLowerCase(Locale.ROOT);
                UserRecord record = recordByDn.get(key);
                LdapUser user = userCache.get(dn);
                ZoneId zone = viewerZone != null ? viewerZone : timeZones.zoneFor(record);
                List<CheckInOutRecord> mine = recordsByDn.getOrDefault(key, List.of());
                Map<LocalDate, List<CheckInOutRecord>> byDay = new HashMap<>();
                for (CheckInOutRecord r : mine) {
                    byDay.computeIfAbsent(r.getTimestamp().withZoneSameInstant(zone).toLocalDate(), k -> new ArrayList<>()).add(r);
                }
                Map<LocalDate, UserEventRecord> statuses = statusByDn.getOrDefault(key, Map.of());
                for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                    List<CheckInOutRecord> dayRecords = new ArrayList<>(byDay.getOrDefault(day, List.of()));
                    dayRecords.sort(Comparator.comparing(CheckInOutRecord::getTimestamp).reversed());
                    UserDayReport report = UserDayReport.build(day, statuses.get(day), dayRecords, zone);
                    boolean purged = purgedBefore != null && day.isBefore(purgedBefore);
                    int minutes = report.getCheckedInMinutes();
                    out.row(Arrays.asList(LdapDn.cnOf(dn), dn,
                            first(user == null ? null : user.getOrganization(), record == null ? null : record.getOrganization()),
                            first(user == null ? null : user.getLocation(), record == null ? null : record.getLocation()),
                            first(user == null ? null : user.getEmployeeType(), record == null ? null : record.getEmployeeType()),
                            day.toString(),
                            purged ? "Purged (see the report's monthly summary)" : report.getStatus(),
                            report.getCheckInTime() == null ? null : TIME.format(report.getCheckInTime()),
                            report.getCheckOutTime() == null ? null : TIME.format(report.getCheckOutTime()),
                            minutes > 0 ? minutes : null,
                            zone.getId()));
                    rows++;
                }
            }
        }
        out.close();
        return rows;
    }

    /**
     * People and their status today, in the order given: in full for those whose details {@code viewer} may see,
     * as a coworker sees them otherwise.
     *
     * @return the rows written
     */
    public long writePeople(List<String> dns, Authentication viewer, TableWriter out) throws IOException {
        AccessPolicy.Scope scope = accessPolicy.scope(viewer);
        out.header(PEOPLE_COLUMNS);
        long rows = 0;
        for (List<String> chunk : Chunks.of(dns, CHUNK)) {
            List<String> lower = chunk.stream().map(d -> d.toLowerCase(Locale.ROOT)).toList();
            Map<String, UserRecord> recordByDn = new HashMap<>();
            people.findAllByLowercaseDnIn(lower).forEach(r -> recordByDn.putIfAbsent(r.getDn().toLowerCase(Locale.ROOT), r));
            for (UserStatus status : userService.getUserStatuses(chunk, null)) {
                boolean details = scope.canViewDetails(status.getDn());
                UserStatus shown = scope.show(status);
                UserRecord record = recordByDn.get(status.getDn().toLowerCase(Locale.ROOT));
                LdapUser user = userCache.get(status.getDn());
                ZoneId zone = timeZones.zoneFor(record);
                out.row(Arrays.asList(LdapDn.cnOf(status.getDn()), status.getDn(),
                        first(user == null ? null : user.getEmail(), record == null ? null : record.getEmail()),
                        first(user == null ? null : user.getPhoneNumber(), record == null ? null : record.getPhoneNumber()),
                        first(user == null ? null : user.getOrganization(), record == null ? null : record.getOrganization()),
                        first(user == null ? null : user.getLocation(), record == null ? null : record.getLocation()),
                        first(user == null ? null : user.getEmployeeType(), record == null ? null : record.getEmployeeType()),
                        shown.getStatus(),
                        details ? time(status.getCheckedInAt(), zone) : null,
                        details ? time(status.getCheckedOutAt(), zone) : null,
                        details ? time(status.getLastStatusChangeAt(), zone) : null,
                        zone.getId()));
                rows++;
            }
        }
        out.close();
        return rows;
    }

    private static String time(ZonedDateTime time, ZoneId zone) {
        return time == null ? null : TIME.format(time.withZoneSameInstant(zone));
    }

    private static String first(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
