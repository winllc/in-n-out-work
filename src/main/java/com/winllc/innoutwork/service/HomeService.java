package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.DateTimeConstants;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.ExpectedLogin;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.data.home.AttendanceSummary;
import com.winllc.innoutwork.data.home.HomeDashboard;
import com.winllc.innoutwork.data.home.NotInYet;
import com.winllc.innoutwork.data.home.PersonalSummary;
import com.winllc.innoutwork.data.home.TeamSummary;
import com.winllc.innoutwork.data.home.UpcomingStatus;
import com.winllc.innoutwork.data.metrics.AccountabilityMetrics;
import com.winllc.innoutwork.data.metrics.UserRef;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.GlobalCalendarRecord;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the home page: the signed-in user's own day and recent attendance and, when people report
 * to them in the directory, the same picture for their team.
 * <p>
 * "Manager" here means having direct reports, not holding the MANAGER role, matching the My Reports
 * page: reporting lines come from the directory.
 */
@Service
public class HomeService {

    private static final Logger log = LoggerFactory.getLogger(HomeService.class);

    static final int HISTORY_DAYS = 30;
    static final int LIST_LIMIT = 50;

    private final UserService userService;
    private final UserRecordRepository userRecordRepository;
    private final UserEventRecordRepository userEventRecordRepository;
    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final GlobalCalendarRecordRepository globalCalendarRecordRepository;
    private final NotificationRepository notificationRepository;
    private final AccountabilityMetricsService accountabilityMetricsService;
    private final ApplicationProperties properties;
    private final Clock clock;

    @Autowired
    public HomeService(UserService userService, UserRecordRepository userRecordRepository,
                       UserEventRecordRepository userEventRecordRepository,
                       CheckInOutRecordRepository checkInOutRecordRepository,
                       GlobalCalendarRecordRepository globalCalendarRecordRepository,
                       NotificationRepository notificationRepository,
                       AccountabilityMetricsService accountabilityMetricsService,
                       ApplicationProperties properties) {
        this(userService, userRecordRepository, userEventRecordRepository, checkInOutRecordRepository,
                globalCalendarRecordRepository, notificationRepository, accountabilityMetricsService, properties,
                Clock.systemDefaultZone());
    }

    HomeService(UserService userService, UserRecordRepository userRecordRepository,
                UserEventRecordRepository userEventRecordRepository,
                CheckInOutRecordRepository checkInOutRecordRepository,
                GlobalCalendarRecordRepository globalCalendarRecordRepository,
                NotificationRepository notificationRepository,
                AccountabilityMetricsService accountabilityMetricsService,
                ApplicationProperties properties, Clock clock) {
        this.userService = userService;
        this.userRecordRepository = userRecordRepository;
        this.userEventRecordRepository = userEventRecordRepository;
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.globalCalendarRecordRepository = globalCalendarRecordRepository;
        this.notificationRepository = notificationRepository;
        this.accountabilityMetricsService = accountabilityMetricsService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param dn      the signed-in user's DN
     * @param session carries the day being viewed, as on every other page
     */
    public HomeDashboard forUser(String dn, HttpSession session) {
        long start = System.currentTimeMillis();
        LocalDate day = CheckInOutService.getDateTimeFromSession(session).toLocalDate();

        PersonalSummary me = personal(dn, day, session);

        List<UserStatus> reports = userService.getDirectReports(new LdapDn(dn), session);
        TeamSummary team = reports.isEmpty() ? null : team(dn, day, reports);

        log.debug("Home page for {} on {} built in {}ms ({} direct reports)", dn, day,
                System.currentTimeMillis() - start, reports.size());

        return new HomeDashboard(me, team);
    }

    // --- the user --------------------------------------------------------------------------------

    private PersonalSummary personal(String dn, LocalDate day, HttpSession session) {
        ZoneId zone = clock.getZone();
        UserStatus status = userService.getUserStatus(dn, session);
        Optional<UserRecord> record = userRecordRepository.findByDnIgnoreCase(dn);

        Optional<ExpectedLogin> expected = ExpectedLogin.of(record.orElse(null),
                userEventRecordRepository.findByDnIgnoreCaseAndDate(dn, day));

        LocalDate historyStart = day.minusDays(HISTORY_DAYS - 1L);
        List<CheckInOutRecord> history = checkInOutRecordRepository
                .findByDnIgnoreCaseAndTimestampIsBetweenOrderByTimestampDesc(dn,
                        historyStart.atStartOfDay(zone), day.plusDays(1).atStartOfDay(zone).minusNanos(1));

        ZonedDateTime lastReported = history.stream()
                .map(CheckInOutRecord::getTimestamp)
                .filter(t -> t != null)
                .max(Comparator.naturalOrder())
                .map(t -> t.withZoneSameInstant(zone))
                .orElse(null);
        boolean agentQuiet = lastReported == null
                || lastReported.toLocalDate().isBefore(day.minusDays(PersonalSummary.QUIET_AFTER_DAYS - 1L));

        List<UpcomingStatus> upcoming = userEventRecordRepository
                .findByDnIgnoreCaseAndDateBetween(dn, day.plusDays(1), day.plusDays(PersonalSummary.UPCOMING_DAYS))
                .stream()
                .filter(e -> e.getStatus() != null && e.getStatus() != UserStatusEnum.STANDARD)
                .sorted(Comparator.comparing(UserEventRecord::getDate))
                .map(e -> new UpcomingStatus(e.getDate(), e.getDn(), LdapDn.cnOf(e.getDn()), e.getStatus().getFriendlyName()))
                .toList();

        return new PersonalSummary(day, statusLabel(status.getStatus()), statusBadge(status.getStatus()),
                status.getLastStatusChangeAt(), status.getCheckedInAt(), status.getCheckedOutAt(),
                expected.orElse(null), record.map(UserRecord::getAverageLoginTime).orElse(null),
                attendance(dn, historyStart, day, history, zone), lastReported, agentQuiet, upcoming);
    }

    /** Each working day counted once: checked in, else covered by a status, else nothing recorded. */
    private AttendanceSummary attendance(String dn, LocalDate from, LocalDate to, List<CheckInOutRecord> history,
                                         ZoneId zone) {
        Set<LocalDate> holidays = globalCalendarRecordRepository.findByDateBetween(from, to).stream()
                .filter(GlobalCalendarRecord::isHoliday)
                .map(GlobalCalendarRecord::getDate)
                .collect(Collectors.toSet());
        Set<LocalDate> checkedInDays = history.stream()
                .filter(r -> r.getAction() == CheckInOutEnum.CHECK_IN && r.getTimestamp() != null)
                .map(r -> r.getTimestamp().withZoneSameInstant(zone).toLocalDate())
                .collect(Collectors.toSet());
        Set<LocalDate> statusDays = userEventRecordRepository.findByDnIgnoreCaseAndDateBetween(dn, from, to).stream()
                .filter(e -> e.getStatus() != null && e.getStatus() != UserStatusEnum.STANDARD)
                .map(UserEventRecord::getDate)
                .collect(Collectors.toSet());

        int working = 0;
        int checkedIn = 0;
        int statusOnly = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (DateTimeConstants.WEEKEND_DAYS.contains(date.getDayOfWeek()) || holidays.contains(date)) {
                continue;
            }
            working++;
            if (checkedInDays.contains(date)) {
                checkedIn++;
            } else if (statusDays.contains(date)) {
                statusOnly++;
            }
        }
        return new AttendanceSummary(working, checkedIn, statusOnly, working - checkedIn - statusOnly);
    }

    // --- the team --------------------------------------------------------------------------------

    private TeamSummary team(String managerDn, LocalDate day, List<UserStatus> reports) {
        List<String> reportDns = reports.stream().map(UserStatus::getDn).toList();
        AccountabilityMetrics accountability = accountabilityMetricsService.forTeam(day, reportDns);

        List<NotificationRecord> awaiting = notificationRepository
                .findByForUserDnIgnoreCaseAndStatusResponseDateNullAndIgnore(managerDn, false).stream()
                .sorted(Comparator.comparing(NotificationRecord::getNotificationDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(LIST_LIMIT)
                .toList();

        List<String> lowercaseDns = reportDns.stream().map(String::toLowerCase).toList();
        List<UpcomingStatus> upcoming = userEventRecordRepository
                .findByLowercaseDnInAndDateBetween(lowercaseDns, day.plusDays(1), day.plusDays(PersonalSummary.UPCOMING_DAYS))
                .stream()
                .filter(e -> e.getStatus() != null && e.getStatus() != UserStatusEnum.STANDARD)
                .map(e -> new UpcomingStatus(e.getDate(), e.getDn(), LdapDn.cnOf(e.getDn()), e.getStatus().getFriendlyName()))
                .sorted(Comparator.comparing(UpcomingStatus::date)
                        .thenComparing(UpcomingStatus::name, String.CASE_INSENSITIVE_ORDER))
                .limit(LIST_LIMIT)
                .toList();

        return new TeamSummary(reports.size(), accountability, notInYet(day, accountability.accountedFor().unaccounted()),
                awaiting, upcoming);
    }

    /**
     * Unaccounted reports with the time each was expected. Being unaccounted means having no status
     * for the day, so no late-arrival entry applies: the time is their preferred or average one.
     */
    private List<NotInYet> notInYet(LocalDate day, List<UserRef> unaccounted) {
        if (unaccounted.isEmpty()) {
            return List.of();
        }
        Map<String, UserRecord> records = new HashMap<>();
        userRecordRepository.findAllByLowercaseDnIn(unaccounted.stream().map(u -> u.dn().toLowerCase()).toList())
                .forEach(r -> records.put(r.getDn().toLowerCase(), r));

        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        int graceMinutes = properties.getExtraTimeBeforeAbsentNotificationMinutes();

        return unaccounted.stream()
                .map(user -> {
                    Optional<ExpectedLogin> expected = ExpectedLogin.of(records.get(user.dn().toLowerCase()), List.of());
                    boolean late = day.isBefore(today) || (day.equals(today) && expected
                            .map(e -> now.isAfter(e.time().atDate(day).atZone(clock.getZone()).plusMinutes(graceMinutes)))
                            .orElse(false));
                    return new NotInYet(user.dn(), user.name(), expected.map(ExpectedLogin::time).orElse(null), late);
                })
                .toList();
    }

    // --- display -----------------------------------------------------------------------------------

    /** What {@link UserService#getUserStatus} reports, in words. */
    static String statusLabel(String status) {
        if (status == null) {
            return "No activity yet";
        }
        return switch (status) {
            case "IN" -> "Checked in";
            case "OUT" -> "Checked out";
            case "AWAY" -> "Away";
            case "NONE" -> "No activity yet";
            default -> {
                try {
                    yield UserStatusEnum.valueOf(status).getFriendlyName();
                } catch (IllegalArgumentException e) {
                    yield status;
                }
            }
        };
    }

    /** Badge colours matching the status column of the user tables. */
    static String statusBadge(String status) {
        if (status == null) {
            return "bg-secondary-lt";
        }
        return switch (status) {
            case "IN" -> "bg-green-lt";
            case "OUT" -> "bg-blue-lt";
            case "AWAY" -> "bg-purple-lt";
            case "NONE" -> "bg-secondary-lt";
            default -> "bg-yellow-lt";
        };
    }
}
