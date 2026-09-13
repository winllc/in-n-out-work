package com.winllc.innoutwork.cron;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.DateTimeConstants;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.ExpectedLogin;
import com.winllc.innoutwork.model.GlobalCalendarRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tells managers when someone is not in by their expected time plus a grace period.
 * <p>
 * Each run makes a fixed handful of queries however many users there are: whether today is a holiday,
 * everyone who has checked in today, everyone's statuses for today, and everyone already alerted about
 * today. Users are then paged through and checked in memory; only an alert that is actually raised costs
 * more (the notification's own lookups and writes).
 */
@Component
public class SendAbsentNotificationsCron {

    private static final Logger log = LoggerFactory.getLogger(SendAbsentNotificationsCron.class);

    static final int PAGE_SIZE = 500;

    private final UserRecordRepository userRecordRepository;
    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final UserEventRecordRepository userEventRecordRepository;
    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;
    private final GlobalCalendarRecordRepository globalCalendarRecordRepository;
    private final ApplicationProperties properties;
    private final Clock clock;

    @Autowired
    public SendAbsentNotificationsCron(UserRecordRepository userRecordRepository,
                                       CheckInOutRecordRepository checkInOutRecordRepository,
                                       UserEventRecordRepository userEventRecordRepository, NotificationService notificationService,
                                       NotificationRepository notificationRepository,
                                       ApplicationProperties properties,
                                       GlobalCalendarRecordRepository globalCalendarRecordRepository) {
        this(userRecordRepository, checkInOutRecordRepository, userEventRecordRepository, notificationService,
                notificationRepository, properties, globalCalendarRecordRepository, Clock.systemDefaultZone());
    }

    SendAbsentNotificationsCron(UserRecordRepository userRecordRepository,
                                CheckInOutRecordRepository checkInOutRecordRepository,
                                UserEventRecordRepository userEventRecordRepository, NotificationService notificationService,
                                NotificationRepository notificationRepository,
                                ApplicationProperties properties,
                                GlobalCalendarRecordRepository globalCalendarRecordRepository, Clock clock) {
        this.userRecordRepository = userRecordRepository;
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.userEventRecordRepository = userEventRecordRepository;
        this.notificationService = notificationService;
        this.notificationRepository = notificationRepository;
        this.properties = properties;
        this.globalCalendarRecordRepository = globalCalendarRecordRepository;
        this.clock = clock;
    }

    // Not @Async: with it the scheduler returned at once, so the fixed delay never waited for a run to
    // finish and a slow run could overlap the next. spring.task.scheduling.pool.size gives each job a thread.
    @Scheduled(fixedDelayString = "#{@sendAbsentNotificationCronProperties.fixedRate}",
            initialDelayString = "#{@sendAbsentNotificationCronProperties.initialDelay}")
    public void sendNotifications() {
        long start = System.currentTimeMillis();
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();

        if (DateTimeConstants.WEEKEND_DAYS.contains(today.getDayOfWeek()) || isHoliday(today)) {
            log.debug("SendAbsentNotificationsCron: {} is not a working day; nothing to check", today);
            return;
        }

        ZoneId zone = clock.getZone();
        ZonedDateTime dayStart = today.atStartOfDay(zone);
        ZonedDateTime dayEnd = today.plusDays(1).atStartOfDay(zone).minusNanos(1);

        Set<String> checkedIn = lowercase(checkInOutRecordRepository
                .findDistinctDnsWithActionBetween(CheckInOutEnum.CHECK_IN, dayStart, dayEnd));
        Map<String, List<UserEventRecord>> statuses = new HashMap<>();
        for (UserEventRecord event : userEventRecordRepository.findByDate(today)) {
            if (event.getDn() != null) {
                statuses.computeIfAbsent(event.getDn().toLowerCase(), k -> new ArrayList<>()).add(event);
            }
        }
        Set<String> alreadyAlerted = lowercase(notificationRepository.findDistinctAboutUserDnsBetween(dayStart, dayEnd));

        int checkedUsers = 0;
        int sent = 0;
        Slice<UserRecord> slice;
        int page = 0;
        do {
            slice = userRecordRepository.findAllBy(PageRequest.of(page++, PAGE_SIZE, Sort.by("id")));
            for (UserRecord user : slice) {
                checkedUsers++;
                String dn = user.getDn() == null ? null : user.getDn().toLowerCase();
                if (dn == null || checkedIn.contains(dn) || alreadyAlerted.contains(dn)) {
                    continue;
                }
                List<UserEventRecord> todaysEvents = statuses.getOrDefault(dn, List.of());
                if (isAbsent(user, todaysEvents, now)) {
                    createAndSendNotification(user, todaysEvents);
                    alreadyAlerted.add(dn);
                    sent++;
                }
            }
        } while (slice.hasNext());

        log.info("End SendAbsentNotificationsCron. Checked {} user(s), sent {} notification(s) in {}ms",
                checkedUsers, sent, System.currentTimeMillis() - start);
    }

    void createAndSendNotification(UserRecord user, List<UserEventRecord> todaysEvents) {
        // The notification carries the same expected time the absence check just used.
        notificationService.createAbsentNotification(user.getDn(), expectedLoginTime(user, todaysEvents));
    }

    /**
     * Not checked in (the caller has ruled that out), past the expected time plus the grace period, and
     * not covered by an excusable status for today.
     */
    private boolean isAbsent(UserRecord user, List<UserEventRecord> todaysEvents, ZonedDateTime now) {
        if (!isPastCheckinWindow(user, todaysEvents, now)) {
            return false;
        }
        boolean excused = todaysEvents.stream()
                .filter(r -> r.getStatus() != UserStatusEnum.STANDARD)
                .anyMatch(r -> r.getStatus() != null && r.getStatus().isExcusable());
        return !excused;
    }

    private boolean isHoliday(LocalDate date) {
        return globalCalendarRecordRepository.findByDate(date).stream().anyMatch(GlobalCalendarRecord::isHoliday);
    }

    /** When the user is expected in today; see {@link ExpectedLogin#of}. Null when none is known. */
    static LocalTime expectedLoginTime(UserRecord user, List<UserEventRecord> todaysEvents) {
        return ExpectedLogin.of(user, todaysEvents).map(ExpectedLogin::time).orElse(null);
    }

    private boolean isPastCheckinWindow(UserRecord user, List<UserEventRecord> records, ZonedDateTime now) {
        LocalTime expectedLoginTime = expectedLoginTime(user, records);
        if (expectedLoginTime == null) {
            return false;
        }
        ZonedDateTime absentIfAfter = expectedLoginTime.atDate(now.toLocalDate()).atZone(clock.getZone())
                .plusMinutes(properties.getExtraTimeBeforeAbsentNotificationMinutes());
        return now.isAfter(absentIfAfter);
    }

    private static Set<String> lowercase(List<String> dns) {
        Set<String> lower = new HashSet<>();
        for (String dn : dns) {
            if (dn != null) {
                lower.add(dn.toLowerCase());
            }
        }
        return lower;
    }
}
