package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.weaving.LoadTimeWeaverAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class DatabaseService {

    private final CheckInOutRecordRepository checkinInOutRecordRepository;
    private final UserRecordRepository userRecordRepository;

    public DatabaseService(CheckInOutRecordRepository checkinInOutRecordRepository, UserRecordRepository userRecordRepository) {
        this.checkinInOutRecordRepository = checkinInOutRecordRepository;
        this.userRecordRepository = userRecordRepository;
    }

    public long getCheckInOutRecordCount() {
        return checkinInOutRecordRepository.count();
    }

    public CheckInOutRecord saveCheckInOutRecord(CheckInOutRecord record) {

        //if first record of day and is unlock, mark is as check_in;

        if(record.getAction() == CheckInOutEnum.UNLOCK){
            ZonedDateTime beginning = record.getTimestamp().truncatedTo(ChronoUnit.DAYS);
            ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

            List<CheckInOutRecord> existingRecords = checkinInOutRecordRepository
                    .findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(beginning, ending, record.getDn());

            if(existingRecords.isEmpty()){
                record.setAction(CheckInOutEnum.CHECK_IN);
            }
        }

        if(record.getAction() == CheckInOutEnum.CHECK_IN){
            Optional<UserRecord> recordOptional = userRecordRepository.findByDnIgnoreCase(record.getDn());
            recordOptional.ifPresent(userRecord -> {

                LocalTime averageLogin = calculateAverageLogin(record.getDn());
                userRecord.setAverageLoginTime(averageLogin);

                userRecordRepository.save(userRecord);
            });
        }

        return checkinInOutRecordRepository.save(record);
    }

    private LocalTime calculateAverageLogin(String dn){
        ZonedDateTime from = ZonedDateTime.now().minusDays(30);
        ZonedDateTime to =ZonedDateTime.now();
        List<CheckInOutRecord> allCheckins = checkinInOutRecordRepository
                .findByDnIgnoreCaseAndTimestampIsBetweenAndActionEqualsOrderByTimestampDesc(dn, from, to, CheckInOutEnum.CHECK_IN);

        List<ZonedDateTime> timestamps = allCheckins.stream()
                .filter(r -> r.getTimestamp() != null)
                .map(CheckInOutRecord::getTimestamp)
                .toList();

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
                        .orElseThrow();

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
            selectedDateTime = ZonedDateTime.now();
        }
        return selectedDateTime;
    }
}
