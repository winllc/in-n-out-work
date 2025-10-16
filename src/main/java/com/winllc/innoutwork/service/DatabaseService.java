package com.winllc.innoutwork.service;

import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

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
        return checkinInOutRecordRepository.save(record);
    }

    public Optional<CheckInOutRecord> lookupBySessionId(String sessionId) {
        return checkinInOutRecordRepository.findBySessionId(sessionId);
    }

    public Page<CheckInOutRecord> findTodaysRecords(Pageable pageable) {
        ZonedDateTime beginning = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

        return checkinInOutRecordRepository.findByTimestampBetween(beginning, ending, pageable);
    }

    public List<CheckInOutRecord> findTodaysRecordsForUser(String dn) {
        ZonedDateTime beginning = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

        return checkinInOutRecordRepository.findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(beginning, ending, dn);
    }
}
