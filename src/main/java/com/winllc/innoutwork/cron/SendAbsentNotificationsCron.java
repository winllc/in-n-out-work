package com.winllc.innoutwork.cron;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.DateTimeConstants;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.GlobalCalendarRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.*;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SendAbsentNotificationsCron {

    private static final Logger log = LoggerFactory.getLogger(SendAbsentNotificationsCron.class);


    private final UserRecordRepository userRecordRepository;
    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final UserEventRecordRepository userEventRecordRepository;
    private final NotificationService notificationService;
    private final GlobalCalendarRecordRepository globalCalendarRecordRepository;
    private final ApplicationProperties properties;

    public SendAbsentNotificationsCron(UserRecordRepository userRecordRepository,
                                       CheckInOutRecordRepository checkInOutRecordRepository,
                                       UserEventRecordRepository userEventRecordRepository, NotificationService notificationService,
                                       ApplicationProperties properties,
                                       GlobalCalendarRecordRepository globalCalendarRecordRepository) {
        this.userRecordRepository = userRecordRepository;
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.userEventRecordRepository = userEventRecordRepository;
        this.notificationService = notificationService;
        this.properties = properties;
        this.globalCalendarRecordRepository = globalCalendarRecordRepository;
    }

    @Async
    @Scheduled(fixedDelayString = "#{@sendAbsentNotificationCronProperties.fixedRate}",
            initialDelayString = "#{@sendAbsentNotificationCronProperties.initialDelay}")
    public void sendNotifications() {
        // The end-of-run summary below is the line worth keeping at info.
        log.debug("Starting SendAbsentNotificationsCron");

        AtomicInteger notificationsSent = new AtomicInteger();
        int page = 0;
        int size = 100;

        Slice<UserRecord> slice;

        do {
            Pageable pageable = PageRequest.of(page, size, Sort.by("id"));
            slice = userRecordRepository.findAllBy(pageable);

            slice.forEach(entity -> {
                if (isUserAbsent(entity) && !notificationAlreadySent(entity)) {
                    createAndSendNotification(entity);
                    notificationsSent.getAndIncrement();
                }
            });

            page++;
        } while (slice.hasNext());

        log.info("End SendAbsentNotificationsCron. Sent notifications: %s".formatted(notificationsSent.get()));
    }

    void createAndSendNotification(UserRecord user) {
        List<UserEventRecord> todaysEvents =
                userEventRecordRepository.findByDnIgnoreCaseAndDate(user.getDn(), LocalDate.now());

        // The notification carries the same expected time the absence check just used.
        notificationService.createAbsentNotification(user.getDn(), expectedLoginTime(user, todaysEvents));
    }

    private boolean notificationAlreadySent(UserRecord user) {

        return !notificationService.getNotificationsForUserFromToday(user.getDn()).isEmpty();
    }

    private boolean isUserAbsent(UserRecord user) {
        ZonedDateTime beginning = LocalDate.now().atStartOfDay(ZoneId.systemDefault());
        ZonedDateTime end = beginning.plusDays(1).minusNanos(1);

        LocalDate today = LocalDate.now();

        if(isWeekend(today) || isHoliday(today)){
            return false;
        }

        List<CheckInOutRecord> todaysRecords = checkInOutRecordRepository.findByDnIgnoreCaseAndTimestampIsBetweenOrderByTimestampDesc(user.getDn(),
                beginning, end);

        boolean notCheckedIn = todaysRecords.stream()
                .noneMatch(r -> r.getAction() == CheckInOutEnum.CHECK_IN);

        if(notCheckedIn) {

            List<UserEventRecord> records =
                    userEventRecordRepository.findByDnIgnoreCaseAndDate(user.getDn(), LocalDate.now());

            if(isPastCheckinWindow(user, records)) {

                if (!CollectionUtils.isEmpty(records)) {
                    boolean excused = records.stream()
                            .filter(r -> r.getStatus() != UserStatusEnum.STANDARD)
                            .anyMatch(r -> r.getStatus().isExcusable());

                    return !excused;
                }
                return true;
            }
        }

        return false;
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return DateTimeConstants.WEEKEND_DAYS.contains(dayOfWeek);
    }

    private boolean isHoliday(LocalDate date) {
        List<GlobalCalendarRecord> events = globalCalendarRecordRepository.findByDate(date);
        if(!CollectionUtils.isEmpty(events)){
            return events.stream()
                    .anyMatch(e -> e.isHoliday());
        }
        return false;
    }

    /**
     * When the user is expected in today: a late-arrival entry for today, else the login time they
     * chose on their profile, else their average login time. Null when none is known.
     */
    static LocalTime expectedLoginTime(UserRecord user, List<UserEventRecord> todaysEvents) {
        LocalTime lateArrivalTime = todaysEvents.stream()
                .filter(record -> record.getStatus() == UserStatusEnum.LATE_ARRIVAL)
                .map(UserEventRecord::getLoginByTime)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        if (lateArrivalTime != null) {
            return lateArrivalTime;
        }
        return user.getChosenLoginTime() != null ? user.getChosenLoginTime() : user.getAverageLoginTime();
    }

    private boolean isPastCheckinWindow(UserRecord user, List<UserEventRecord> records) {
        int additionalWaitMinutes = properties.getExtraTimeBeforeAbsentNotificationMinutes();

        LocalTime expectedLoginTime = expectedLoginTime(user, records);

        if(expectedLoginTime != null) {
            ZonedDateTime absentIfAfter = expectedLoginTime.atDate(LocalDate.now()).atZone(ZoneId.systemDefault())
                    .plusMinutes(additionalWaitMinutes);

            ZonedDateTime now = ZonedDateTime.now();
            return now.isAfter(absentIfAfter);
        }else{
            return false;
        }
    }
}
