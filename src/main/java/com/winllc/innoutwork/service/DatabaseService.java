package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
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
        return checkinInOutRecordRepository.save(record);
    }

    public Optional<CheckInOutRecord> lookupBySessionId(String sessionId) {
        return checkinInOutRecordRepository.findFirstBySessionId(sessionId);
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

    public Map<CheckInOutEnum, Long> getTodaysStatistics(){
        Map<CheckInOutEnum, Long> metrics = new HashMap<>();

        ZonedDateTime beginning = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

        List<CheckInOutEnum> totalCurrentStatuses = checkinInOutRecordRepository
                .findTotalCurrentStatuses(beginning);

        Map<CheckInOutEnum, Long> collect = totalCurrentStatuses.stream()
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));

        collect.forEach((key, value) -> {
            metrics.put(key, value);
        });

        return metrics;
    }

    public List<CheckInOutRecord> findTodaysRecords() {
        ZonedDateTime beginning = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

        return checkinInOutRecordRepository.findByTimestampBetweenOrderByTimestampDesc(beginning, ending);
    }
}
