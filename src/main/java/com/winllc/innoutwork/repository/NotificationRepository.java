package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.NotificationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<NotificationRecord, Long>, PagingAndSortingRepository<NotificationRecord, Long> {

    // DN lookups compare lower(dn) so they can use the lower(dn) indexes from
    // db/migrations/001_indexes_and_unique_user_dn.sql. Spring Data's derived ...IgnoreCase methods
    // compare upper(dn), which those indexes cannot serve.
    @Query("select n from NotificationRecord n where lower(n.forUserDn) = lower(:dn)"
            + " and n.statusResponseDate is null and n.ignore = :ignored")
    List<NotificationRecord> findByForUserDnIgnoreCaseAndStatusResponseDateNullAndIgnore(@Param("dn") String dn,
                                                                                        @Param("ignored") boolean ignored);
    @Query("select n from NotificationRecord n where lower(n.forUserDn) = lower(:dn) and n.statusResponseDate is null")
    List<NotificationRecord> findByForUserDnIgnoreCaseAndStatusResponseDateNull(@Param("dn") String dn);
    @Query("select n from NotificationRecord n where lower(n.forUserDn) = lower(:dn)")
    List<NotificationRecord> findByForUserDnIgnoreCase(@Param("dn") String dn);
    @Query("select n from NotificationRecord n where lower(n.forUserDn) = lower(:dn)"
            + " and n.notificationDate >= :start and n.notificationDate <= :end")
    List<NotificationRecord> findByForUserDnIgnoreCaseAndNotificationDateBetween(@Param("dn") String dn,
                                                                               @Param("start") ZonedDateTime start,
                                                                               @Param("end") ZonedDateTime end);
    @Query("select n from NotificationRecord n where lower(n.aboutUserDn) = lower(:dn)")
    List<NotificationRecord> findByAboutUserDnIgnoreCase(@Param("dn") String dn);
    List<NotificationRecord> findByNotificationUuid(String uuid);

    /** Everyone a notification was raised about in a window, as stored. */
    @Query("select distinct n.aboutUserDn from NotificationRecord n where n.aboutUserDn is not null"
            + " and n.notificationDate >= :from and n.notificationDate <= :to")
    List<String> findDistinctAboutUserDnsBetween(@Param("from") ZonedDateTime from, @Param("to") ZonedDateTime to);
    @Query("select n from NotificationRecord n where lower(n.aboutUserDn) = lower(:dn)"
            + " and n.notificationDate >= :start and n.notificationDate <= :end")
    List<NotificationRecord> findByAboutUserDnIgnoreCaseAndNotificationDateBetween(@Param("dn") String dn,
                                                                                 @Param("start") ZonedDateTime start,
                                                                                 @Param("end") ZonedDateTime end);

    /** Notifications about a set of users in a window; DNs compared lower-cased, so pass them lower-cased. */
    @Query("select n from NotificationRecord n where lower(n.aboutUserDn) in :dns"
            + " and n.notificationDate >= :from and n.notificationDate <= :to")
    List<NotificationRecord> findAboutLowercaseDnInBetween(@Param("dns") Collection<String> dns,
                                                           @Param("from") ZonedDateTime from,
                                                           @Param("to") ZonedDateTime to);

}
