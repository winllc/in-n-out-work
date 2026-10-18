package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.model.UserEventRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserEventRecordRepository  extends JpaRepository<UserEventRecord, Long>, PagingAndSortingRepository<UserEventRecord, Long> {
    // DN lookups compare lower(dn) so they can use the lower(dn) indexes from
    // db/migration/V1__baseline.sql. Spring Data's derived ...IgnoreCase methods
    // compare upper(dn), which those indexes cannot serve.
    @Query("select e from UserEventRecord e where lower(e.dn) = lower(:dn) and e.date >= :start and e.date <= :end")
    List<UserEventRecord> findByDnIgnoreCaseAndDateBetween(@Param("dn") String dn, @Param("start") LocalDate start,
                                                           @Param("end") LocalDate end);
    @Query("select e from UserEventRecord e where lower(e.dn) = lower(:dn) and e.date = :date")
    List<UserEventRecord> findByDnIgnoreCaseAndDate(@Param("dn") String dn, @Param("date") LocalDate date);

    List<UserEventRecord> findByDate(LocalDate date);

    /** Status entries in a date range for a set of users; DNs compared lower-cased, so pass them lower-cased. */
    @Query("select e from UserEventRecord e where lower(e.dn) in :dns and e.date >= :from and e.date <= :to")
    List<UserEventRecord> findByLowercaseDnInAndDateBetween(@Param("dns") Collection<String> dns,
                                                           @Param("from") LocalDate from,
                                                           @Param("to") LocalDate to);
    @Query("select e from UserEventRecord e where lower(e.dn) = lower(:dn) and e.date = :date and e.status = :status")
    Optional<UserEventRecord> findByDnIgnoreCaseAndDateAndStatusEquals(@Param("dn") String dn, @Param("date") LocalDate date,
                                                                        @Param("status") UserStatusEnum status);

    // --- retention and erasure ------------------------------------------------------------------------------

    /** The earliest day at or after {@code from} and before {@code to} with a status, or null. */
    @Query("select min(e.date) from UserEventRecord e where e.date >= :from and e.date < :to")
    LocalDate findEarliestBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Lower-cased DNs with a status on {@code date}. */
    @Query("select distinct lower(e.dn) from UserEventRecord e where e.date = :date")
    List<String> findDistinctLowercaseDnsOn(@Param("date") LocalDate date);

    @Modifying
    @Query("delete from UserEventRecord e where lower(e.dn) = lower(:dn)")
    int deleteByDn(@Param("dn") String dn);

    @Query("select e from UserEventRecord e where lower(e.dn) = lower(:dn) order by e.date")
    List<UserEventRecord> findAllByDn(@Param("dn") String dn);

    /** The days an approved request put on the calendar, from {@code from} on. */
    @Query("select e from UserEventRecord e where e.requestId = :requestId and e.date >= :from")
    List<UserEventRecord> findByRequestIdFrom(@Param("requestId") Long requestId, @Param("from") LocalDate from);
}
