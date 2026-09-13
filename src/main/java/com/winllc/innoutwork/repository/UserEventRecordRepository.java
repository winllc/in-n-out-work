package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.model.UserEventRecord;
import org.springframework.data.jpa.repository.JpaRepository;
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
    // db/migrations/001_indexes_and_unique_user_dn.sql. Spring Data's derived ...IgnoreCase methods
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
}
