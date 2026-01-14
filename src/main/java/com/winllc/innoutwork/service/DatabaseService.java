package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

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

    public DatabaseService(CheckInOutRecordRepository checkinInOutRecordRepository) {
        this.checkinInOutRecordRepository = checkinInOutRecordRepository;
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

        return checkinInOutRecordRepository.save(record);
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
