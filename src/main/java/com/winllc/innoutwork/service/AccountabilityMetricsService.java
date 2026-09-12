package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.DateTimeConstants;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.metrics.AccountabilityMetrics;
import com.winllc.innoutwork.data.metrics.AccountedFor;
import com.winllc.innoutwork.data.metrics.AgentCoverage;
import com.winllc.innoutwork.data.metrics.LastSeen;
import com.winllc.innoutwork.data.metrics.StatusMixEntry;
import com.winllc.innoutwork.data.metrics.StoppedAgent;
import com.winllc.innoutwork.data.metrics.UserRef;
import com.winllc.innoutwork.model.GlobalCalendarRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Who is accounted for, how the expected population splits by status, and whether workstation
 * agents are still reporting: organisation-wide for the metrics page, per team for managers.
 * <p>
 * DNs are compared ignoring case throughout, as everywhere else in the application.
 */
@Service
public class AccountabilityMetricsService {

    private static final Logger log = LoggerFactory.getLogger(AccountabilityMetricsService.class);

    /** Anyone with activity this many days before the day measured is expected on it. */
    static final int ACTIVE_WINDOW_DAYS = 30;
    /** An agent that has sent nothing for this many days, counting the day measured, has stopped. */
    static final int REPORTING_WINDOW_DAYS = 7;
    /** The page lists at most this many unaccounted users and stopped agents. */
    static final int LIST_LIMIT = 100;

    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final UserEventRecordRepository userEventRecordRepository;
    private final UserRecordRepository userRecordRepository;
    private final GlobalCalendarRecordRepository globalCalendarRecordRepository;

    public AccountabilityMetricsService(CheckInOutRecordRepository checkInOutRecordRepository,
                                        UserEventRecordRepository userEventRecordRepository,
                                        UserRecordRepository userRecordRepository,
                                        GlobalCalendarRecordRepository globalCalendarRecordRepository) {
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.userEventRecordRepository = userEventRecordRepository;
        this.userRecordRepository = userRecordRepository;
        this.globalCalendarRecordRepository = globalCalendarRecordRepository;
    }

    /**
     * Organisation-wide figures for the metrics page. These are counts only: the unaccounted and
     * stopped-agent lists come back empty, so no individual is identified on that page.
     */
    public AccountabilityMetrics forDay(LocalDate day) {
        long start = System.currentTimeMillis();
        DayRecords records = dayRecords(day);

        // Who is expected: on a working day everyone recently active plus anyone given a status;
        // on a weekend or holiday, only those who turned up or were given a status anyway.
        Map<String, String> expected = new LinkedHashMap<>();
        if (records.workingDay()) {
            expected.putAll(byLowercaseDn(checkInOutRecordRepository.findDistinctDnsBetween(
                    day.minusDays(ACTIVE_WINDOW_DAYS).atStartOfDay(records.zone()), records.dayEnd())));
        }
        records.checkedIn().forEach(expected::putIfAbsent);
        records.statusDns().forEach(expected::putIfAbsent);

        AccountabilityMetrics metrics = assemble(records, expected, byLowercaseDn(userRecordRepository.findAllDns()),
                false);

        log.debug("Accountability metrics for {} built in {}ms: {}", day, System.currentTimeMillis() - start,
                metrics.accountedFor());

        return metrics;
    }

    /**
     * The same metrics for a known set of people, such as a manager's direct reports.
     * <p>
     * Unlike {@link #forDay}, every member is expected on a working day, active recently or not: a
     * manager needs to see the report on an unrecorded leave as unaccounted for. The unaccounted and
     * stopped-agent lists name the members, who are the manager's own reports.
     */
    public AccountabilityMetrics forTeam(LocalDate day, Collection<String> memberDns) {
        DayRecords records = dayRecords(day);
        Map<String, String> members = byLowercaseDn(memberDns);

        Map<String, String> expected = new LinkedHashMap<>();
        members.forEach((lower, dn) -> {
            if (records.workingDay() || records.checkedIn().containsKey(lower) || records.statuses().containsKey(lower)) {
                expected.put(lower, dn);
            }
        });

        return assemble(records, expected, members, true);
    }

    /** What the directory-wide queries say about one day, keyed by lower-cased DN. */
    private record DayRecords(LocalDate day, ZoneId zone, ZonedDateTime dayEnd, String nonWorkingReason,
                              Map<String, String> checkedIn, Map<String, UserStatusEnum> statuses,
                              Map<String, String> statusDns) {
        boolean workingDay() {
            return nonWorkingReason == null;
        }
    }

    private DayRecords dayRecords(LocalDate day) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime dayStart = day.atStartOfDay(zone);
        ZonedDateTime dayEnd = day.plusDays(1).atStartOfDay(zone).minusNanos(1);

        Map<String, String> checkedIn = byLowercaseDn(checkInOutRecordRepository
                .findDistinctDnsWithActionBetween(CheckInOutEnum.CHECK_IN, dayStart, dayEnd));
        List<UserEventRecord> events = userEventRecordRepository.findByDate(day);
        Map<String, UserStatusEnum> statuses = statusesByLowercaseDn(events);
        Map<String, String> statusDns = new LinkedHashMap<>(byLowercaseDn(events.stream()
                .filter(e -> e.getDn() != null && statuses.containsKey(e.getDn().toLowerCase()))
                .map(UserEventRecord::getDn)
                .toList()));

        return new DayRecords(day, zone, dayEnd, nonWorkingReason(day), checkedIn, statuses, statusDns);
    }

    /** @param nameIndividuals whether to fill the unaccounted and stopped-agent lists, or only count */
    private AccountabilityMetrics assemble(DayRecords records, Map<String, String> expected,
                                           Map<String, String> agentUsers, boolean nameIndividuals) {
        return new AccountabilityMetrics(
                accountedFor(records.day(), records.nonWorkingReason(), expected, records.checkedIn().keySet(),
                        records.statuses(), nameIndividuals),
                statusMix(expected.keySet(), records.checkedIn().keySet(), records.statuses()),
                agentCoverage(records.day(), records.zone(), records.dayEnd(), agentUsers, nameIndividuals));
    }

    private AccountedFor accountedFor(LocalDate day, String nonWorkingReason, Map<String, String> expected,
                                      Set<String> checkedIn, Map<String, UserStatusEnum> statuses,
                                      boolean nameIndividuals) {
        int accounted = 0;
        List<UserRef> unaccounted = new ArrayList<>();
        for (Map.Entry<String, String> user : expected.entrySet()) {
            if (checkedIn.contains(user.getKey()) || statuses.containsKey(user.getKey())) {
                accounted++;
            } else if (nonWorkingReason == null) {
                unaccounted.add(new UserRef(user.getValue(), LdapDn.cnOf(user.getValue())));
            }
        }
        unaccounted.sort(Comparator.comparing(UserRef::name, String.CASE_INSENSITIVE_ORDER));

        return new AccountedFor(day, nonWorkingReason, expected.size(), accounted,
                (int) expected.keySet().stream().filter(checkedIn::contains).count(),
                (int) expected.keySet().stream().filter(statuses::containsKey).count(),
                nameIndividuals ? List.copyOf(unaccounted.subList(0, Math.min(LIST_LIMIT, unaccounted.size()))) : List.of(),
                unaccounted.size());
    }

    /**
     * Places every expected user in one category, so the entries add up to the expected total. A
     * status wins over a check-in, matching the user tables: someone working from home who connects
     * in shows as working from home.
     */
    private static List<StatusMixEntry> statusMix(Collection<String> expected, Set<String> checkedIn,
                                                  Map<String, UserStatusEnum> statuses) {
        int checkedInCount = 0;
        int unaccountedCount = 0;
        Map<UserStatusEnum, Integer> byStatus = new LinkedHashMap<>();

        for (String dn : expected) {
            UserStatusEnum status = statuses.get(dn);
            if (status != null) {
                byStatus.merge(status, 1, Integer::sum);
            } else if (checkedIn.contains(dn)) {
                checkedInCount++;
            } else {
                unaccountedCount++;
            }
        }

        List<StatusMixEntry> mix = new ArrayList<>();
        if (checkedInCount > 0) {
            mix.add(new StatusMixEntry(StatusMixEntry.CHECKED_IN, "Checked in", checkedInCount));
        }
        for (UserStatusEnum status : UserStatusEnum.values()) {
            Integer count = byStatus.get(status);
            if (count != null) {
                mix.add(new StatusMixEntry(status.name(), status.getFriendlyName(), count));
            }
        }
        if (unaccountedCount > 0) {
            mix.add(new StatusMixEntry(StatusMixEntry.UNACCOUNTED, "Unaccounted for", unaccountedCount));
        }
        return mix;
    }

    private AgentCoverage agentCoverage(LocalDate day, ZoneId zone, ZonedDateTime dayEnd, Map<String, String> users,
                                        boolean nameIndividuals) {
        ZonedDateTime reportingSince = day.minusDays(REPORTING_WINDOW_DAYS - 1L).atStartOfDay(zone);

        Map<String, ZonedDateTime> lastSeen = new HashMap<>();
        for (LastSeen seen : checkInOutRecordRepository.findLastSeenUpTo(dayEnd)) {
            lastSeen.merge(seen.dn().toLowerCase(), seen.lastSeen(), (a, b) -> a.isAfter(b) ? a : b);
        }

        int reporting = 0;
        int never = 0;
        List<StoppedAgent> stopped = new ArrayList<>();
        for (Map.Entry<String, String> user : users.entrySet()) {
            ZonedDateTime seen = lastSeen.get(user.getKey());
            if (seen == null) {
                never++;
            } else if (!seen.isBefore(reportingSince)) {
                reporting++;
            } else {
                stopped.add(new StoppedAgent(user.getValue(), LdapDn.cnOf(user.getValue()),
                        seen.withZoneSameInstant(zone).toLocalDate()));
            }
        }
        // Most recently stopped first: the agents that just broke are the ones worth chasing.
        stopped.sort(Comparator.comparing(StoppedAgent::lastSeen).reversed()
                .thenComparing(StoppedAgent::name, String.CASE_INSENSITIVE_ORDER));

        return new AgentCoverage(users.size(), reporting, stopped.size(), never, REPORTING_WINDOW_DAYS,
                nameIndividuals ? List.copyOf(stopped.subList(0, Math.min(LIST_LIMIT, stopped.size()))) : List.of());
    }

    /** Why nobody is expected on {@code day}, or null when it is a working day. */
    private String nonWorkingReason(LocalDate day) {
        Optional<GlobalCalendarRecord> holiday = globalCalendarRecordRepository.findByDate(day).stream()
                .filter(GlobalCalendarRecord::isHoliday)
                .findFirst();
        if (holiday.isPresent()) {
            String title = holiday.get().getTitle();
            return title == null || title.isBlank() ? "Holiday" : title;
        }
        if (DateTimeConstants.WEEKEND_DAYS.contains(day.getDayOfWeek())) {
            return day.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US);
        }
        return null;
    }

    /**
     * One status per user for the day. {@code STANDARD} is the absence of a status. Should a user
     * have several, the one declared first in {@link UserStatusEnum} is used, so the result does
     * not depend on the order rows come back in.
     */
    private static Map<String, UserStatusEnum> statusesByLowercaseDn(List<UserEventRecord> events) {
        Map<String, UserStatusEnum> statuses = new HashMap<>();
        for (UserEventRecord event : events) {
            if (event.getDn() == null || event.getStatus() == null || event.getStatus() == UserStatusEnum.STANDARD) {
                continue;
            }
            statuses.merge(event.getDn().toLowerCase(), event.getStatus(),
                    (a, b) -> a.ordinal() <= b.ordinal() ? a : b);
        }
        return statuses;
    }

    /** Lower-cased DN to the first spelling seen, so names still display in their original case. */
    private static Map<String, String> byLowercaseDn(Collection<String> dns) {
        Map<String, String> byLower = new LinkedHashMap<>();
        new LinkedHashSet<>(dns).stream()
                .filter(dn -> dn != null && !dn.isBlank())
                .forEach(dn -> byLower.putIfAbsent(dn.toLowerCase(), dn));
        return byLower;
    }
}
