package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.NotificationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<NotificationRecord, Long>, PagingAndSortingRepository<NotificationRecord, Long> {

    List<NotificationRecord> findByForUserDnIgnoreCaseAndStatusResponseDateNull(String dn);
    List<NotificationRecord> findByForUserDnIgnoreCase(String dn);
    List<NotificationRecord> findByForUserDnIgnoreCaseAndNotificationDateBetween(String dn, ZonedDateTime start, ZonedDateTime end);
    List<NotificationRecord> findByAboutUserDnIgnoreCase(String dn);
    List<NotificationRecord> findByAboutUserDnIgnoreCaseAndNotificationDateBetween(String dn, ZonedDateTime start, ZonedDateTime end);

}
