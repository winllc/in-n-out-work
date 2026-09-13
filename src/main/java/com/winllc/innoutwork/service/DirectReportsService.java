package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.home.AttendanceSummary;
import com.winllc.innoutwork.data.home.PersonalSummary;
import com.winllc.innoutwork.data.team.AbsenceAlerts;
import com.winllc.innoutwork.data.team.DailyAttendance;
import com.winllc.innoutwork.data.team.DirectReportsSummary;
import com.winllc.innoutwork.data.team.ReportAttendance;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The metrics on a manager's direct reports page: today's accountability for the team, and how the
 * last 30 days went, for the team as a whole and for each report.
 * <p>
 * Everything is read in a handful of queries for the whole team rather than one set per report, and
 * working days are counted exactly as on the home page (see {@link AttendanceCalculator}).
 */
@Service
public class DirectReportsService {

    private static final Logger log = LoggerFactory.getLogger(DirectReportsService.class);

    private final UserService userService;
    private final AccountabilityMetricsService accountabilityMetricsService;
    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final UserEventRecordRepository userEventRecordRepository;
    private final GlobalCalendarRecordRepository globalCalendarRecordRepository;
    private final NotificationRepository notificationRepository;

    public DirectReportsService(UserService userService, AccountabilityMetricsService accountabilityMetricsService,
                                CheckInOutRecordRepository checkInOutRecordRepository,
                                UserEventRecordRepository userEventRecordRepository,
                                GlobalCalendarRecordRepository globalCalendarRecordRepository,
                                NotificationRepository notificationRepository) {
        this.userService = userService;
        this.accountabilityMetricsService = accountabilityMetricsService;
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.userEventRecordRepository = userEventRecordRepository;
        this.globalCalendarRecordRepository = globalCalendarRecordRepository;
        this.notificationRepository = notificationRepository;
    }

    /**
     * @param managerDn the signed-in user's DN
     * @param session   carries the day being viewed, as on every other page
     */
    public DirectReportsSummary forManager(String managerDn, HttpSession session) {
        long start = System.currentTimeMillis();
        LocalDate day = CheckInOutService.getDateTimeFromSession(session).toLocalDate();

        // Lower-cased DN to the directory's spelling, one entry per report.
        Map<String, String> reports = new LinkedHashMap<>();
        for (LdapUser report : userService.findDirectReports(new LdapDn(managerDn))) {
            reports.putIfAbsent(report.getDn().toLowerCase(), report.getDn());
        }
        if (reports.isEmpty()) {
            return DirectReportsSummary.none(day);
        }

        ZoneId zone = ZoneId.systemDefault();
        LocalDate from = day.minusDays(DirectReportsSummary.HISTORY_DAYS - 1L);
        ZonedDateTime windowStart = from.atStartOfDay(zone);
        ZonedDateTime windowEnd = day.plusDays(1).atStartOfDay(zone).minusNanos(1);

        List<LocalDate> workingDays = AttendanceCalculator.workingDays(from, day,
                globalCalendarRecordRepository.findByDateBetween(from, day));

        Map<String, List<CheckInOutRecord>> recordsByDn = groupByLowercaseDn(
                checkInOutRecordRepository.findByLowercaseDnInAndTimestampBetween(reports.keySet(), windowStart, windowEnd),
                CheckInOutRecord::getDn);
        Map<String, List<UserEventRecord>> eventsByDn = groupByLowercaseDn(
                userEventRecordRepository.findByLowercaseDnInAndDateBetween(reports.keySet(), from, day),
                UserEventRecord::getDn);

        AttendanceSummary teamAttendance = new AttendanceSummary(0, 0, 0, 0);
        List<ZonedDateTime> teamArrivals = new ArrayList<>();
        Map<LocalDate, int[]> byDay = new LinkedHashMap<>();
        workingDays.forEach(d -> byDay.put(d, new int[3]));
        Set<LocalDate> working = byDay.keySet();

        List<ReportAttendance> rows = new ArrayList<>();
        for (Map.Entry<String, String> report : reports.entrySet()) {
            List<CheckInOutRecord> records = recordsByDn.getOrDefault(report.getKey(), List.of());
            List<UserEventRecord> events = eventsByDn.getOrDefault(report.getKey(), List.of());

            AttendanceSummary attendance = AttendanceCalculator.summarize(workingDays, records, events, zone);
            teamAttendance = teamAttendance.plus(attendance);

            Map<LocalDate, ZonedDateTime> firstCheckIns = AttendanceCalculator.firstCheckIns(records, zone);
            firstCheckIns.keySet().retainAll(working);
            teamArrivals.addAll(firstCheckIns.values());

            Set<LocalDate> statusDays = AttendanceCalculator.statusDays(events);
            for (Map.Entry<LocalDate, int[]> entry : byDay.entrySet()) {
                int slot = firstCheckIns.containsKey(entry.getKey()) ? 0 : statusDays.contains(entry.getKey()) ? 1 : 2;
                entry.getValue()[slot]++;
            }

            ZonedDateTime lastReported = records.stream()
                    .map(CheckInOutRecord::getTimestamp)
                    .filter(t -> t != null)
                    .max(Comparator.naturalOrder())
                    .map(t -> t.withZoneSameInstant(zone))
                    .orElse(null);
            boolean agentQuiet = lastReported == null
                    || lastReported.toLocalDate().isBefore(day.minusDays(PersonalSummary.QUIET_AFTER_DAYS - 1L));

            rows.add(new ReportAttendance(report.getValue(), LdapDn.cnOf(report.getValue()), attendance,
                    CheckInOutService.calculateAverage(new ArrayList<>(firstCheckIns.values())), lastReported, agentQuiet));
        }
        rows.sort(Comparator.comparing(ReportAttendance::name, String.CASE_INSENSITIVE_ORDER));

        List<DailyAttendance> daily = byDay.entrySet().stream()
                .map(e -> new DailyAttendance(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .toList();

        LocalTime averageArrival = CheckInOutService.calculateAverage(teamArrivals);
        AbsenceAlerts alerts = alerts(notificationRepository.findAboutLowercaseDnInBetween(reports.keySet(), windowStart, windowEnd));

        DirectReportsSummary summary = new DirectReportsSummary(day, from, reports.size(),
                accountabilityMetricsService.forTeam(day, reports.values()), teamAttendance, averageArrival,
                alerts, daily, rows);

        log.debug("Direct reports metrics for {} on {} built in {}ms ({} reports)", managerDn, day,
                System.currentTimeMillis() - start, reports.size());

        return summary;
    }

    /**
     * One absence notifies every manager told about it, all sharing a notification UUID, and a
     * response from any of them is copied to the rest. So alerts are counted per UUID, and one is
     * answered when a status was recorded. Marking notifications read sets only the response date,
     * so it does not count as an answer.
     */
    static AbsenceAlerts alerts(List<NotificationRecord> notifications) {
        Map<String, UserStatusEnum> responseByAlert = new LinkedHashMap<>();
        for (NotificationRecord n : notifications) {
            String alert = n.getNotificationUuid() != null ? n.getNotificationUuid() : "id:" + n.getId();
            responseByAlert.putIfAbsent(alert, null);
            if (n.getStatusResponse() != null) {
                responseByAlert.put(alert, n.getStatusResponse());
            }
        }

        Map<UserStatusEnum, Integer> outcomes = new EnumMap<>(UserStatusEnum.class);
        responseByAlert.values().stream()
                .filter(status -> status != null)
                .forEach(status -> outcomes.merge(status, 1, Integer::sum));

        List<AbsenceAlerts.AlertOutcome> ordered = outcomes.entrySet().stream()
                .sorted(Map.Entry.<UserStatusEnum, Integer>comparingByValue().reversed())
                .map(e -> new AbsenceAlerts.AlertOutcome(e.getKey().getFriendlyName(), e.getValue()))
                .toList();

        int answered = outcomes.values().stream().mapToInt(Integer::intValue).sum();
        return new AbsenceAlerts(responseByAlert.size(), answered, ordered);
    }

    private static <T> Map<String, List<T>> groupByLowercaseDn(List<T> items, Function<T, String> dn) {
        Map<String, List<T>> grouped = new HashMap<>();
        for (T item : items) {
            String value = dn.apply(item);
            if (value != null) {
                grouped.computeIfAbsent(value.toLowerCase(), k -> new ArrayList<>()).add(item);
            }
        }
        return grouped;
    }
}
