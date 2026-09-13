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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CheckInOutRecordRepository extends JpaRepository<CheckInOutRecord, Long>, PagingAndSortingRepository<CheckInOutRecord, Long> {

    List<CheckInOutRecord> findByDnOrderByTimestampDesc(String dn);

    // DN lookups compare lower(dn) so they can use the lower(dn) indexes from
    // db/migrations/001_indexes_and_unique_user_dn.sql. Spring Data's derived ...IgnoreCase methods
    // compare upper(dn), which those indexes cannot serve.
    @Query("select r from CheckInOutRecord r where lower(r.dn) = lower(:dn)"
            + " and r.timestamp >= :from and r.timestamp <= :to order by r.timestamp desc")
    List<CheckInOutRecord> findByDnIgnoreCaseAndTimestampIsBetweenOrderByTimestampDesc(@Param("dn") String dn,
                                                                                    @Param("from") ZonedDateTime from,
                                                                                    @Param("to") ZonedDateTime to);

    @Query("select r from CheckInOutRecord r where lower(r.dn) = lower(:dn)"
            + " and r.timestamp >= :from and r.timestamp <= :to and r.action = :action order by r.timestamp desc")
    List<CheckInOutRecord> findByDnIgnoreCaseAndTimestampIsBetweenAndActionEqualsOrderByTimestampDesc(
            @Param("dn") String dn, @Param("from") ZonedDateTime from, @Param("to") ZonedDateTime to,
            @Param("action") CheckInOutEnum action);

    Optional<CheckInOutRecord> findFirstBySessionId(String sessionId);

    Page<CheckInOutRecord> findByTimestampBetween(ZonedDateTime start, ZonedDateTime end, Pageable pageable);

    @Query("select r from CheckInOutRecord r where lower(r.dn) = lower(:dn)"
            + " and r.timestamp >= :start and r.timestamp <= :end order by r.timestamp desc")
    List<CheckInOutRecord> findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(@Param("start") ZonedDateTime start,
                                                                                    @Param("end") ZonedDateTime end,
                                                                                    @Param("dn") String dn);

    List<CheckInOutRecord> findByTimestampBetweenOrderByTimestampDesc(ZonedDateTime start, ZonedDateTime end);

    @Query("select r from CheckInOutRecord r where lower(r.dn) = lower(:dn) order by r.timestamp desc")
    Page<CheckInOutRecord> findByDnIgnoreCaseOrderByTimestampDesc(Pageable pageable, @Param("dn") String dn);

    Long countCheckInOutRecordByActionAndTimestampIsBetween(CheckInOutEnum action, ZonedDateTime timestamp, ZonedDateTime timestamp2);

    List<CheckInOutRecord> findByDutySubOrganizationEqualsIgnoreCaseAndTimestampBetween(String dutySubOrganization, ZonedDateTime start, ZonedDateTime end);

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

    /**
     * Each DN's latest record in the window, where that record is {@code action}. The latest-record lookup
     * is limited to the same window (it used to scan the DN's entire history) and is served by the
     * (dn, timestamp) index.
     */
    @Query("""
    SELECT r
    FROM CheckInOutRecord r
    WHERE r.action = :action
      AND r.timestamp >= :from
      AND r.timestamp <= :to
      AND r.timestamp = (
          SELECT MAX(r2.timestamp)
          FROM CheckInOutRecord r2
          WHERE r2.dn = r.dn
            AND r2.timestamp >= :from
            AND r2.timestamp <= :to
      )
""")
    List<CheckInOutRecord> findLatestRecordsByDn(@Param("action") CheckInOutEnum action,
                                                 @Param("from") ZonedDateTime from,
                                                 @Param("to") ZonedDateTime to);

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

    /** Records in a window for a set of users; DNs compared lower-cased, so pass them lower-cased. */
    @Query("select r from CheckInOutRecord r where lower(r.dn) in :dns and r.timestamp >= :from and r.timestamp <= :to")
    List<CheckInOutRecord> findByLowercaseDnInAndTimestampBetween(@Param("dns") Collection<String> dns,
                                                                  @Param("from") ZonedDateTime from,
                                                                  @Param("to") ZonedDateTime to);

    /** Each DN's latest record up to {@code to}, grouped ignoring DN case. */
    @Query("select new com.winllc.innoutwork.data.metrics.LastSeen(lower(r.dn), max(r.timestamp))"
            + " from CheckInOutRecord r where r.dn is not null and r.timestamp >= :from and r.timestamp <= :to"
            + " group by lower(r.dn)")
    List<LastSeen> findLastSeenBetween(@Param("from") ZonedDateTime from, @Param("to") ZonedDateTime to);

    /** The same, for a set of users; DNs compared lower-cased, so pass them lower-cased. */
    @Query("select new com.winllc.innoutwork.data.metrics.LastSeen(lower(r.dn), max(r.timestamp))"
            + " from CheckInOutRecord r where lower(r.dn) in :dns and r.timestamp >= :from and r.timestamp <= :to"
            + " group by lower(r.dn)")
    List<LastSeen> findLastSeenByLowercaseDnInBetween(@Param("dns") Collection<String> dns,
                                                      @Param("from") ZonedDateTime from,
                                                      @Param("to") ZonedDateTime to);
}
