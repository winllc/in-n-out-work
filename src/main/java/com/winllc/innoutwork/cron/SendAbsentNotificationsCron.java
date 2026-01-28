package com.winllc.innoutwork.cron;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SendAbsentNotificationsCron {

    private static final Logger log = LoggerFactory.getLogger(SendAbsentNotificationsCron.class);

    private final UserRecordRepository userRecordRepository;
    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final UserEventRecordRepository userEventRecordRepository;
    private final UserService userService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private ApplicationProperties properties;

    public SendAbsentNotificationsCron(UserRecordRepository userRecordRepository,
                                       CheckInOutRecordRepository checkInOutRecordRepository,
                                       UserEventRecordRepository userEventRecordRepository, UserService userService) {
        this.userRecordRepository = userRecordRepository;
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.userEventRecordRepository = userEventRecordRepository;
        this.userService = userService;
    }

    @Async
    @Scheduled(fixedDelayString = "#{@inactiveCronProperties.fixedRate}")
    public void sendNotifications() {
        log.info("Starting SendAbsentNotificationsCron");

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

        List<CheckInOutRecord> todaysRecords = checkInOutRecordRepository.findByDnIgnoreCaseAndTimestampIsBetweenOrderByTimestampDesc(user.getDn(),
                beginning, end);

        boolean notCheckedIn = todaysRecords.stream()
                .noneMatch(r -> r.getAction() == CheckInOutEnum.CHECK_IN);

        if(notCheckedIn) {

            if(isPastCheckinWindow(user)) {
                Optional<UserEventRecord> recordOptional =
                        userEventRecordRepository.findByDnIgnoreCaseAndDate(user.getDn(), LocalDate.now());

                if (recordOptional.isPresent()) {
                    UserEventRecord record = recordOptional.get();
                    return !record.getStatus().isExcusable() || record.getStatus() == UserStatusEnum.STANDARD;
                }
                return true;
            }
        }

        return false;
    }

    private boolean isPastCheckinWindow(UserRecord user) {
        int additionalWaitMinutes = properties.getExtraTimeBeforeAbsentNotificationMinutes();
        LocalTime averageLoginTime = user.getAverageLoginTime();

        if(averageLoginTime != null) {
            ZonedDateTime absentIfAfter = averageLoginTime.atDate(LocalDate.now()).atZone(ZoneId.systemDefault())
                    .plusMinutes(additionalWaitMinutes);

            ZonedDateTime now = ZonedDateTime.now();
            return now.isAfter(absentIfAfter);
        }else{
            return false;
        }
    }
}
