package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.ReportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ReportScheduleRepository extends JpaRepository<ReportSchedule, Long> {

    List<ReportSchedule> findAllByOrderByCreatedAtDesc();

    @Query("select s from ReportSchedule s where lower(s.ownerDn) = lower(:dn) order by s.createdAt desc")
    List<ReportSchedule> findByOwner(@Param("dn") String dn);

    @Query("select s from ReportSchedule s where s.enabled = true and s.nextRunAt <= :now order by s.nextRunAt, s.id")
    List<ReportSchedule> findDue(@Param("now") Instant now);

    /** Schedules naming {@code pattern} (a lower-cased LIKE pattern escaped with '!') among their recipients. */
    @Query("select s from ReportSchedule s where lower(s.recipients) like :pattern escape '!'")
    List<ReportSchedule> findByRecipientsLike(@Param("pattern") String pattern);
}
