package com.winllc.innoutwork.cron;

import com.winllc.innoutwork.config.ApplicationProperties;
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
import com.winllc.innoutwork.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.*;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SendAbsentNotificationsCron {

    private static final Logger log = LoggerFactory.getLogger(SendAbsentNotificationsCron.class);

    private static final Set<DayOfWeek> WEEKEND_DAYS = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);


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

    private void createAndSendNotification(UserRecord user) {
        //todo create and send notification
        notificationService.createAbsentNotification(user.getDn());
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
        return WEEKEND_DAYS.contains(dayOfWeek);
    }

    private boolean isHoliday(LocalDate date) {
        List<GlobalCalendarRecord> events = globalCalendarRecordRepository.findByDate(date);
        if(!CollectionUtils.isEmpty(events)){
            return events.stream()
                    .anyMatch(e -> e.isHoliday());
        }
        return false;
    }

    private boolean isPastCheckinWindow(UserRecord user, List<UserEventRecord> records) {
        int additionalWaitMinutes = properties.getExtraTimeBeforeAbsentNotificationMinutes();

        LocalTime lateArrivalTime = records.stream()
                .filter(record -> record.getStatus() == UserStatusEnum.LATE_ARRIVAL)
                .map(UserEventRecord::getLoginByTime)
                .findFirst()
                .orElse(null);

        LocalTime expectedLoginTime;
        if(lateArrivalTime != null) {
            expectedLoginTime = lateArrivalTime;
        } else if(user.getChosenLoginTime() == null) {
            expectedLoginTime = user.getAverageLoginTime();
        }else{
            expectedLoginTime = user.getChosenLoginTime();
        }

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
