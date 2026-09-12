package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.data.metrics.LastSeen;
import com.winllc.innoutwork.model.CheckInOutRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CheckInOutRecordRepository extends JpaRepository<CheckInOutRecord, Long>, PagingAndSortingRepository<CheckInOutRecord, Long> {

    List<CheckInOutRecord> findByDnOrderByTimestampDesc(String dn);

    List<CheckInOutRecord> findByDnIgnoreCaseAndTimestampIsBetweenOrderByTimestampDesc(String dn, ZonedDateTime timestamp, ZonedDateTime timestamp2);

    List<CheckInOutRecord> findByDnIgnoreCaseAndTimestampIsBetweenAndActionEqualsOrderByTimestampDesc(String dn, ZonedDateTime timestamp, ZonedDateTime timestamp2, CheckInOutEnum action);

    Optional<CheckInOutRecord> findFirstBySessionId(String sessionId);

    Page<CheckInOutRecord> findByTimestampBetween(ZonedDateTime start, ZonedDateTime end, Pageable pageable);

    List<CheckInOutRecord> findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(ZonedDateTime start, ZonedDateTime end, String dn);

    List<CheckInOutRecord> findByTimestampBetweenOrderByTimestampDesc(ZonedDateTime start, ZonedDateTime end);

    Page<CheckInOutRecord> findByDnIgnoreCaseOrderByTimestampDesc(Pageable pageable, String dn);

    Long countCheckInOutRecordByActionAndTimestampIsBetween(CheckInOutEnum action, ZonedDateTime timestamp, ZonedDateTime timestamp2);

    List<CheckInOutRecord> findByDutySubOrganizationEqualsIgnoreCaseAndTimestampBetween(String dutySubOrganization, ZonedDateTime start, ZonedDateTime end);

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

    @Query("""
    SELECT r
    FROM CheckInOutRecord r
    WHERE r.timestamp = (
        SELECT MAX(r2.timestamp)
        FROM CheckInOutRecord r2
        WHERE r2.dn = r.dn
    ) AND r.action = :action
      AND r.timestamp >= :from
      AND r.timestamp <= :to
""")
    List<CheckInOutRecord> findLatestRecordsByDn(CheckInOutEnum action, ZonedDateTime from, ZonedDateTime to);

    /** DNs, as stored, with at least one record of {@code action} in the window. */
    @Query("select distinct r.dn from CheckInOutRecord r where r.dn is not null and r.action = :action"
            + " and r.timestamp >= :from and r.timestamp <= :to")
    List<String> findDistinctDnsWithActionBetween(@Param("action") CheckInOutEnum action,
                                                  @Param("from") ZonedDateTime from,
                                                  @Param("to") ZonedDateTime to);

    /** DNs, as stored, with any record in the window. */
    @Query("select distinct r.dn from CheckInOutRecord r where r.dn is not null"
            + " and r.timestamp >= :from and r.timestamp <= :to")
    List<String> findDistinctDnsBetween(@Param("from") ZonedDateTime from, @Param("to") ZonedDateTime to);

    /** Each DN's latest record up to {@code to}, grouped ignoring DN case. */
    @Query("select new com.winllc.innoutwork.data.metrics.LastSeen(lower(r.dn), max(r.timestamp))"
            + " from CheckInOutRecord r where r.dn is not null and r.timestamp <= :to group by lower(r.dn)")
    List<LastSeen> findLastSeenUpTo(@Param("to") ZonedDateTime to);
}
