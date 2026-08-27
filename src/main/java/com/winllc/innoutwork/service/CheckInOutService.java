package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
public class CheckInOutService {

    private static final Logger log = LoggerFactory.getLogger(CheckInOutService.class);

    private final CheckInOutRecordRepository checkinInOutRecordRepository;
    private final UserRecordRepository userRecordRepository;

    public CheckInOutService(CheckInOutRecordRepository checkinInOutRecordRepository, UserRecordRepository userRecordRepository) {
        this.checkinInOutRecordRepository = checkinInOutRecordRepository;
        this.userRecordRepository = userRecordRepository;
    }

    public long getCheckInOutRecordCount() {
        return checkinInOutRecordRepository.count();
    }

    public CheckInOutRecord saveCheckInOutRecord(CheckInOutRecord record) {

        //if first record of day and is unlock, mark is as check_in;

        if(record.getAction() == CheckInOutEnum.UNLOCK){
            ZonedDateTime beginning = record.getZonedDateTimestamp().truncatedTo(ChronoUnit.DAYS);
            ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

            List<CheckInOutRecord> existingRecords = checkinInOutRecordRepository
                    .findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(beginning, ending, record.getDn());

            if(existingRecords.isEmpty()){
                // The day's first unlock is the user arriving, so it is recorded as a check-in.
                // Worth logging because the stored action differs from what was sent.
                log.debug("First activity of the day for {}; promoting UNLOCK to CHECK_IN", record.getDn());
                record.setAction(CheckInOutEnum.CHECK_IN);
            } else {
                log.debug("Unlock for {} follows {} earlier record(s) today; left as UNLOCK",
                        record.getDn(), existingRecords.size());
            }
        }

        if(record.getAction() == CheckInOutEnum.CHECK_IN){
            Optional<UserRecord> recordOptional = userRecordRepository.findByDnIgnoreCase(record.getDn());
            if (recordOptional.isEmpty()) {
                // No stored record means no rolling average to maintain for this user.
                log.debug("No user record for {}; skipping average login update", record.getDn());
            }
            recordOptional.ifPresent(userRecord -> {

                LocalTime averageLogin = calculateAverageLogin(record.getDn());
                if (averageLogin != null) {
                    log.debug("Average login time for {} updated from {} to {}",
                            record.getDn(), userRecord.getAverageLoginTime(), averageLogin);
                    userRecord.setAverageLoginTime(averageLogin);

                    userRecordRepository.save(userRecord);
                }
            });
        }

        CheckInOutRecord saved = checkinInOutRecordRepository.save(record);

        // One line per status event across the whole workforce, so this stays at debug.
        log.debug("Recorded {} for {} at {}", saved.getAction(), saved.getDn(), saved.getTimestamp());

        return saved;
    }

    private LocalTime calculateAverageLogin(String dn){
        ZonedDateTime from = ZonedDateTime.now().minusDays(30);
        ZonedDateTime to =ZonedDateTime.now();
        List<CheckInOutRecord> allCheckins = checkinInOutRecordRepository
                .findByDnIgnoreCaseAndTimestampIsBetweenAndActionEqualsOrderByTimestampDesc(dn, from, to, CheckInOutEnum.CHECK_IN);

        List<ZonedDateTime> timestamps = allCheckins.stream()
                .filter(r -> r.getTimestamp() != null)
                .map(CheckInOutRecord::getZonedDateTimestamp)
                .toList();

        log.debug("Averaging {} check-in(s) from the last 30 days for {}", timestamps.size(), dn);

        return calculateAverage(timestamps);
    }

    public static LocalTime calculateAverage(List<ZonedDateTime> timestamps) {
        if (timestamps == null || timestamps.isEmpty()) {
            return null;
        }

        List<LocalTime> localTimes = timestamps.stream()
                .map(t -> t.toLocalTime())
                .toList();


        long averageSeconds =
                (long) localTimes.stream()
                        .mapToLong(LocalTime::toSecondOfDay)
                        .average()
                        .orElseThrow(() -> new IllegalStateException("Failed to calculate average of timestamps"));

        return LocalTime.ofSecondOfDay(averageSeconds);
    }

    public Optional<CheckInOutRecord> lookupBySessionId(String sessionId) {
        return checkinInOutRecordRepository.findFirstBySessionId(sessionId);
    }

    public Page<CheckInOutRecord> findRecords(Pageable pageable, HttpSession session) {
        ZonedDateTime beginning = getDateTimeFromSession(session).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

        return checkinInOutRecordRepository.findByTimestampBetween(beginning, ending, pageable);
    }

    public List<CheckInOutRecord> findRecordsForUser(String dn, HttpSession session) {
        ZonedDateTime beginning = getDateTimeFromSession(session).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

        return checkinInOutRecordRepository.findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(beginning, ending, dn);
    }



    public List<CheckInOutRecord> findRecords(HttpSession session) {
        ZonedDateTime beginning = getDateTimeFromSession(session).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

        return checkinInOutRecordRepository.findByTimestampBetweenOrderByTimestampDesc(beginning, ending);
    }

    public static ZonedDateTime getDateTimeFromSession(HttpSession session) {
        ZonedDateTime selectedDateTime =
                (ZonedDateTime) session.getAttribute("systemTime");

        if (selectedDateTime == null) {
            selectedDateTime = ZonedDateTime.now().withZoneSameInstant(ZoneId.systemDefault());
        }
        return selectedDateTime;
    }
}
