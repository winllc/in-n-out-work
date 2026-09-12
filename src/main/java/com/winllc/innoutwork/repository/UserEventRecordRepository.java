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
    List<UserEventRecord> findByDnIgnoreCaseAndDateBetween(String dn, LocalDate start, LocalDate end);
    List<UserEventRecord> findByDnIgnoreCaseAndDate(String dn, LocalDate date);

    List<UserEventRecord> findByDate(LocalDate date);

    /** Status entries in a date range for a set of users; DNs compared lower-cased, so pass them lower-cased. */
    @Query("select e from UserEventRecord e where lower(e.dn) in :dns and e.date >= :from and e.date <= :to")
    List<UserEventRecord> findByLowercaseDnInAndDateBetween(@Param("dns") Collection<String> dns,
                                                           @Param("from") LocalDate from,
                                                           @Param("to") LocalDate to);
    Optional<UserEventRecord> findByDnIgnoreCaseAndDateAndStatusEquals(String dn, LocalDate date, UserStatusEnum status);
}
