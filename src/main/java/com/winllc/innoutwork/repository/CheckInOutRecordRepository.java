package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.CheckInOutRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CheckInOutRecordRepository extends JpaRepository<CheckInOutRecord, Long>, PagingAndSortingRepository<CheckInOutRecord, Long> {

    List<CheckInOutRecord> findByDnOrderByTimestampDesc(String dn);

    List<CheckInOutRecord> findByDnAndTimestampIsBetweenOrderByTimestampDesc(String dn, ZonedDateTime timestamp, ZonedDateTime timestamp2);

    Optional<CheckInOutRecord> findBySessionId(String sessionId);

    Page<CheckInOutRecord> findByTimestampBetween(ZonedDateTime start, ZonedDateTime end, Pageable pageable);

    List<CheckInOutRecord> findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(ZonedDateTime start, ZonedDateTime end, String dn);
}
