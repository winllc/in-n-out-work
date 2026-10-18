package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.AttendanceSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface AttendanceSummaryRepository extends JpaRepository<AttendanceSummary, Long> {

    /** Summaries of a set of people for one month; DNs compared lower-cased, so pass them lower-cased. */
    @Query("select s from AttendanceSummary s where lower(s.dn) in :dns and s.month = :month")
    List<AttendanceSummary> findByLowercaseDnInAndMonth(@Param("dns") Collection<String> dns,
                                                        @Param("month") LocalDate month);

    /** Summaries of a set of people over months {@code from} to {@code to}, both first days of months. */
    @Query("select s from AttendanceSummary s where lower(s.dn) in :dns and s.month >= :from and s.month <= :to"
            + " order by s.month, lower(s.dn)")
    List<AttendanceSummary> findByLowercaseDnInAndMonthBetween(@Param("dns") Collection<String> dns,
                                                               @Param("from") LocalDate from,
                                                               @Param("to") LocalDate to);

    @Query("select s from AttendanceSummary s where lower(s.dn) = lower(:dn) order by s.month")
    List<AttendanceSummary> findByDn(@Param("dn") String dn);

    long countByDnIgnoreCase(String dn);
}
