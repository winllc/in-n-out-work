package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CheckInOutRecordRepository extends JpaRepository<CheckInOutRecord, Long>, PagingAndSortingRepository<CheckInOutRecord, Long> {

    List<CheckInOutRecord> findByDnOrderByTimestampDesc(String dn);

    List<CheckInOutRecord> findByDnIgnoreCaseAndTimestampIsBetweenOrderByTimestampDesc(String dn, ZonedDateTime timestamp, ZonedDateTime timestamp2);

    Optional<CheckInOutRecord> findFirstBySessionId(String sessionId);

    Page<CheckInOutRecord> findByTimestampBetween(ZonedDateTime start, ZonedDateTime end, Pageable pageable);

    List<CheckInOutRecord> findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(ZonedDateTime start, ZonedDateTime end, String dn);

    List<CheckInOutRecord> findByTimestampBetweenOrderByTimestampDesc(ZonedDateTime start, ZonedDateTime end);

    Page<CheckInOutRecord> findByDnIgnoreCaseOrderByTimestampDesc(Pageable pageable, String dn);

    Long countCheckInOutRecordByActionAndTimestampIsBetween(CheckInOutEnum action, ZonedDateTime timestamp, ZonedDateTime timestamp2);

    @Query("""
    SELECT r.action
    FROM CheckInOutRecord r
    WHERE r.timestamp >= :from
      AND r.timestamp <= :to
      AND r.timestamp = (
          SELECT MAX(r2.timestamp)
          FROM CheckInOutRecord r2
          WHERE r2.dn = r.dn
            AND r2.timestamp >= :from
            AND r2.timestamp <= :to
      )
""")
    List<CheckInOutEnum> findTotalCurrentStatuses(ZonedDateTime from, ZonedDateTime to);

    @Query("""
    SELECT r
    FROM CheckInOutRecord r
    WHERE r.timestamp >= :from
      AND r.timestamp <= :to
      AND r.timestamp = (
          SELECT MAX(r2.timestamp)
          FROM CheckInOutRecord r2
          WHERE r2.dn = r.dn
            AND r2.timestamp >= :from
            AND r2.timestamp <= :to
      )
""")
    List<CheckInOutRecord> findTotalCurrentRecords(ZonedDateTime from, ZonedDateTime to);
}
