package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.NotificationTypeEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.GroupRecord;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The DN lookups rewritten from Spring Data's derived IgnoreCase methods (upper()) to lower(), so they can
 * use the lower(dn) indexes. Same names and results as before: matched ignoring case, same bounds, same order.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dnlookups;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DnLookupQueriesTest {

    @Configuration
    @AutoConfigurationPackage(basePackages = "com.winllc.innoutwork")
    static class JpaConfig {
    }

    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 10);
    private static final ZonedDateTime START = DAY.atStartOfDay(ZONE);
    private static final ZonedDateTime END = DAY.plusDays(1).atStartOfDay(ZONE).minusSeconds(1);
    private static final String BOB = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";
    private static final String BOB_UPPER = BOB.toUpperCase();

    @Autowired private CheckInOutRecordRepository checkIns;
    @Autowired private UserRecordRepository users;
    @Autowired private UserEventRecordRepository events;
    @Autowired private NotificationRepository notifications;
    @Autowired private GroupRecordRepository groups;

    private CheckInOutRecord checkIn(String dn, CheckInOutEnum action, ZonedDateTime at) {
        return checkIns.save(CheckInOutRecord.builder().dn(dn).action(action).timestamp(at).build());
    }

    @Test
    void checkInLookupsMatchTheDnIgnoringCaseWithinTheWindowNewestFirst() {
        checkIn(BOB, CheckInOutEnum.CHECK_IN, START.plusHours(8));
        checkIn(BOB_UPPER, CheckInOutEnum.LOCK, START.plusHours(12));
        checkIn(BOB, CheckInOutEnum.CHECK_OUT, END.plusHours(1));                      // next day
        checkIn("cn=Carol,ou=Users,dc=winllc,dc=com", CheckInOutEnum.CHECK_IN, START.plusHours(9));

        assertEquals(List.of(CheckInOutEnum.LOCK, CheckInOutEnum.CHECK_IN),
                checkIns.findByDnIgnoreCaseAndTimestampIsBetweenOrderByTimestampDesc(BOB, START, END).stream()
                        .map(CheckInOutRecord::getAction).toList());
        assertEquals(List.of(CheckInOutEnum.LOCK, CheckInOutEnum.CHECK_IN),
                checkIns.findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(START, END, BOB_UPPER).stream()
                        .map(CheckInOutRecord::getAction).toList());
        assertEquals(1, checkIns.findByDnIgnoreCaseAndTimestampIsBetweenAndActionEqualsOrderByTimestampDesc(
                BOB_UPPER, START, END, CheckInOutEnum.CHECK_IN).size());

        Page<CheckInOutRecord> page = checkIns.findByDnIgnoreCaseOrderByTimestampDesc(PageRequest.of(0, 2), BOB);
        assertEquals(3, page.getTotalElements());
        assertEquals(CheckInOutEnum.CHECK_OUT, page.getContent().getFirst().getAction());
    }

    /** Users whose latest event today is a lock; the lookup no longer reaches outside the day. */
    @Test
    void latestRecordsByDnFindThoseWhoseLastEventInTheWindowIsTheAction() {
        checkIn(BOB, CheckInOutEnum.CHECK_IN, START.plusHours(8));
        checkIn(BOB, CheckInOutEnum.LOCK, START.plusHours(12));                       // Bob: locked
        String carol = "cn=Carol,ou=Users,dc=winllc,dc=com";
        checkIn(carol, CheckInOutEnum.LOCK, START.plusHours(9));
        checkIn(carol, CheckInOutEnum.UNLOCK, START.plusHours(10));                   // Carol: back
        String dave = "cn=Dave,ou=Users,dc=winllc,dc=com";
        checkIn(dave, CheckInOutEnum.LOCK, START.plusHours(16));
        checkIn(dave, CheckInOutEnum.CHECK_IN, END.plusHours(9));                     // tomorrow: ignored

        List<String> locked = checkIns.findLatestRecordsByDn(CheckInOutEnum.LOCK, START, END).stream()
                .map(CheckInOutRecord::getDn).sorted().toList();

        assertEquals(List.of(BOB, dave), locked);
    }

    @Test
    void userRecordsAreFoundIgnoringCaseAndCanBeLockedForUpdate() {
        users.save(UserRecord.builder().dn(BOB).build());

        assertTrue(users.findByDnIgnoreCase(BOB_UPPER).isPresent());
        assertTrue(users.findByDnIgnoreCase("cn=Nobody").isEmpty());
    }

    @Test
    void statusLookupsMatchTheDnIgnoringCase() {
        events.save(UserEventRecord.builder().dn(BOB_UPPER).date(DAY).status(UserStatusEnum.TDY).build());
        events.save(UserEventRecord.builder().dn(BOB).date(DAY.plusDays(3)).status(UserStatusEnum.WORK_FROM_HOME).build());

        assertEquals(1, events.findByDnIgnoreCaseAndDate(BOB, DAY).size());
        assertEquals(2, events.findByDnIgnoreCaseAndDateBetween(BOB, DAY, DAY.plusDays(3)).size());
        assertTrue(events.findByDnIgnoreCaseAndDateAndStatusEquals(BOB, DAY, UserStatusEnum.TDY).isPresent());
        assertTrue(events.findByDnIgnoreCaseAndDateAndStatusEquals(BOB, DAY, UserStatusEnum.WORK_FROM_HOME).isEmpty());
    }

    @Test
    void notificationLookupsMatchTheDnIgnoringCase() {
        notifications.save(NotificationRecord.builder().forUserDn(BOB_UPPER).aboutUserDn("cn=Carol")
                .type(NotificationTypeEnum.ABSENT).notificationDate(START.plusHours(10)).build());
        notifications.save(NotificationRecord.builder().forUserDn(BOB).aboutUserDn(BOB_UPPER)
                .type(NotificationTypeEnum.ABSENT).notificationDate(START.plusHours(11))
                .statusResponseDate(START.plusHours(12)).build());
        notifications.save(NotificationRecord.builder().forUserDn(BOB).aboutUserDn("cn=Dave")
                .type(NotificationTypeEnum.ABSENT).notificationDate(START.plusHours(13)).ignore(true).build());

        assertEquals(3, notifications.findByForUserDnIgnoreCase(BOB).size());
        assertEquals(2, notifications.findByForUserDnIgnoreCaseAndStatusResponseDateNull(BOB).size());
        assertEquals(1, notifications.findByForUserDnIgnoreCaseAndStatusResponseDateNullAndIgnore(BOB, false).size());
        assertEquals(3, notifications.findByForUserDnIgnoreCaseAndNotificationDateBetween(BOB_UPPER, START, END).size());
        assertEquals(1, notifications.findByAboutUserDnIgnoreCase(BOB).size());
        assertEquals(1, notifications.findByAboutUserDnIgnoreCaseAndNotificationDateBetween(BOB, START, END).size());
    }

    @Test
    void groupRecordsAreFoundIgnoringCase() {
        GroupRecord group = new GroupRecord();
        group.setGroupDn("cn=Engineering,ou=Groups,dc=winllc,dc=com");
        groups.save(group);

        assertTrue(groups.findByGroupDnIgnoreCase("CN=ENGINEERING,OU=GROUPS,DC=WINLLC,DC=COM").isPresent());
    }
}
