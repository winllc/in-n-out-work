package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.metrics.AccountabilityMetrics;
import com.winllc.innoutwork.data.metrics.LastSeen;
import com.winllc.innoutwork.data.metrics.StatusMixEntry;
import com.winllc.innoutwork.constant.NotificationTypeEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.service.AccountabilityMetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The queries behind the accountability metrics, run by Hibernate against a real database. A typo
 * in JPQL otherwise only shows up when the application starts.
 * <p>
 * H2 in PostgreSQL mode stands in for Postgres; the queries use nothing beyond standard JPQL.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:accountability;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccountabilityQueriesTest {

    /** The application class lives in a sibling package, so point entity and repository scanning at the root. */
    @Configuration
    @AutoConfigurationPackage(basePackages = "com.winllc.innoutwork")
    static class JpaConfig {
    }

    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 10);
    private static final ZonedDateTime START = DAY.atStartOfDay(ZONE);
    private static final ZonedDateTime END = DAY.plusDays(1).atStartOfDay(ZONE).minusNanos(1);
    /** Timestamps are stored to the microsecond, so edges are tested with whole seconds, as real rows have. */
    private static final ZonedDateTime LAST_SECOND = DAY.atTime(23, 59, 59).atZone(ZONE);

    private static final String ALICE = "cn=Alice Adams,ou=Users,dc=winllc,dc=com";
    private static final String BOB = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";
    private static final String CAROL = "cn=Carol Clark,ou=Users,dc=winllc,dc=com";

    @Autowired
    private CheckInOutRecordRepository checkIns;
    @Autowired
    private UserEventRecordRepository events;
    @Autowired
    private UserRecordRepository users;
    @Autowired
    private GlobalCalendarRecordRepository calendar;
    @Autowired
    private NotificationRepository notifications;

    private void event(String dn, CheckInOutEnum action, ZonedDateTime at) {
        checkIns.save(CheckInOutRecord.builder().dn(dn).action(action).timestamp(at).build());
    }

    @Test
    void distinctDnsWithAnActionAreThoseWithThatActionInsideTheWindow() {
        event(ALICE, CheckInOutEnum.CHECK_IN, START);                 // edge: inclusive
        event(ALICE, CheckInOutEnum.CHECK_IN, START.plusHours(13));   // duplicate DN
        event(BOB, CheckInOutEnum.LOCK, START.plusHours(9));          // other action
        event(CAROL, CheckInOutEnum.CHECK_IN, START.minusSeconds(1)); // day before
        event(null, CheckInOutEnum.CHECK_IN, START.plusHours(9));     // anonymous

        List<String> dns = checkIns.findDistinctDnsWithActionBetween(CheckInOutEnum.CHECK_IN, START, END);

        assertEquals(List.of(ALICE), dns);
    }

    @Test
    void distinctDnsBetweenIncludeEveryActionButNotAnonymousRows() {
        event(ALICE, CheckInOutEnum.CHECK_IN, START.plusHours(8));
        event(BOB, CheckInOutEnum.LOCK, LAST_SECOND);                 // edge: inclusive
        event(CAROL, CheckInOutEnum.CHECK_OUT, START.plusDays(1));    // day after
        event(null, CheckInOutEnum.CHECK_OUT, START.plusHours(17));

        assertEquals(List.of(ALICE, BOB), checkIns.findDistinctDnsBetween(START, END).stream().sorted().toList());
    }

    @Test
    void lastSeenIsTheLatestEventPerDnIgnoringCaseUpToTheCutOff() {
        event(ALICE, CheckInOutEnum.CHECK_IN, START.minusDays(3));
        event(ALICE.toUpperCase(), CheckInOutEnum.LOCK, START.minusDays(1));
        event(ALICE, CheckInOutEnum.CHECK_IN, END.plusDays(2));       // after the cut-off
        event(BOB, CheckInOutEnum.CHECK_IN, START.minusDays(10));
        event(null, CheckInOutEnum.CHECK_OUT, START);

        Map<String, ZonedDateTime> lastSeen = checkIns.findLastSeenBetween(START.minusDays(30), END).stream()
                .collect(Collectors.toMap(LastSeen::dn, LastSeen::lastSeen));

        assertEquals(2, lastSeen.size(), lastSeen.toString());
        assertEquals(START.minusDays(1).toInstant(), lastSeen.get(ALICE.toLowerCase()).toInstant());
        assertEquals(START.minusDays(10).toInstant(), lastSeen.get(BOB.toLowerCase()).toInstant());
    }

    @Test
    void lastSeenIsOnlyReadInsideTheWindow() {
        event(ALICE, CheckInOutEnum.CHECK_IN, START.minusDays(100));   // before the window
        event(BOB, CheckInOutEnum.CHECK_IN, START.minusDays(10));

        List<String> seen = checkIns.findLastSeenBetween(START.minusDays(89), END).stream().map(LastSeen::dn).toList();

        assertEquals(List.of(BOB.toLowerCase()), seen);
    }

    @Test
    void lastSeenForASetOfUsersIgnoresEveryoneElse() {
        event(ALICE.toUpperCase(), CheckInOutEnum.CHECK_IN, START.minusDays(2));
        event(ALICE, CheckInOutEnum.LOCK, START.minusDays(1));
        event(BOB, CheckInOutEnum.CHECK_IN, START.minusDays(1));

        List<LastSeen> seen = checkIns.findLastSeenByLowercaseDnInBetween(List.of(ALICE.toLowerCase()), START.minusDays(30), END);

        assertEquals(1, seen.size());
        assertEquals(START.minusDays(1).toInstant(), seen.getFirst().lastSeen().toInstant());
    }

    @Test
    void statusesAreFoundByDate() {
        events.save(UserEventRecord.builder().dn(ALICE).date(DAY).status(UserStatusEnum.TDY).build());
        events.save(UserEventRecord.builder().dn(BOB).date(DAY.minusDays(1)).status(UserStatusEnum.TDY).build());

        assertEquals(List.of(ALICE), events.findByDate(DAY).stream().map(UserEventRecord::getDn).toList());
    }

    @Test
    void statusesForASetOfUsersAreFoundByLowercaseDnWithinTheRange() {
        events.save(UserEventRecord.builder().dn(ALICE.toUpperCase()).date(DAY).status(UserStatusEnum.TDY).build());
        events.save(UserEventRecord.builder().dn(BOB).date(DAY.plusDays(14)).status(UserStatusEnum.TDY).build());
        events.save(UserEventRecord.builder().dn(BOB).date(DAY.plusDays(15)).status(UserStatusEnum.TDY).build());
        events.save(UserEventRecord.builder().dn(CAROL).date(DAY).status(UserStatusEnum.TDY).build());

        List<UserEventRecord> found = events.findByLowercaseDnInAndDateBetween(
                List.of(ALICE.toLowerCase(), BOB.toLowerCase()), DAY, DAY.plusDays(14));

        assertEquals(List.of(ALICE.toUpperCase(), BOB), found.stream().map(UserEventRecord::getDn).sorted().toList());
    }

    @Test
    void recordsForASetOfUsersAreFoundByLowercaseDnWithinTheWindow() {
        event(ALICE.toUpperCase(), CheckInOutEnum.CHECK_IN, START.plusHours(8));
        event(BOB, CheckInOutEnum.LOCK, LAST_SECOND);
        event(BOB, CheckInOutEnum.CHECK_IN, START.minusSeconds(1));   // before the window
        event(CAROL, CheckInOutEnum.CHECK_IN, START.plusHours(9));    // not in the set

        List<CheckInOutRecord> found = checkIns.findByLowercaseDnInAndTimestampBetween(
                List.of(ALICE.toLowerCase(), BOB.toLowerCase()), START, END);

        assertEquals(List.of(ALICE.toUpperCase(), BOB), found.stream().map(CheckInOutRecord::getDn).sorted().toList());
    }

    @Test
    void notificationsAboutASetOfUsersAreFoundByLowercaseDnWithinTheWindow() {
        notifications.save(notification(ALICE.toUpperCase(), START.plusHours(10)));
        notifications.save(notification(BOB, START.minusSeconds(1)));          // before the window
        notifications.save(notification(CAROL, START.plusHours(10)));          // not in the set

        List<NotificationRecord> found = notifications.findAboutLowercaseDnInBetween(
                List.of(ALICE.toLowerCase(), BOB.toLowerCase()), START, END);

        assertEquals(List.of(ALICE.toUpperCase()), found.stream().map(NotificationRecord::getAboutUserDn).toList());
    }

    private static NotificationRecord notification(String aboutDn, ZonedDateTime at) {
        return NotificationRecord.builder().aboutUserDn(aboutDn).forUserDn(BOB)
                .type(NotificationTypeEnum.ABSENT).notificationDate(at).build();
    }

    @Test
    void everyUsersDnIsListed() {
        users.save(UserRecord.builder().dn(ALICE).build());
        users.save(UserRecord.builder().dn(BOB).build());

        assertEquals(List.of(ALICE, BOB), users.findAllDns().stream().sorted().toList());
    }

    /** The whole calculation over real queries: a small day with every category represented. */
    @Test
    void theServiceWorksEndToEndOverTheRealQueries() {
        users.save(UserRecord.builder().dn(ALICE).build());
        users.save(UserRecord.builder().dn(BOB).build());
        users.save(UserRecord.builder().dn(CAROL).build());
        ZonedDateTime morning = DAY.atTime(8, 0).atZone(ZoneId.systemDefault());
        event(ALICE, CheckInOutEnum.CHECK_IN, morning);
        event(BOB, CheckInOutEnum.CHECK_IN, morning.minusDays(3));
        event(CAROL, CheckInOutEnum.CHECK_IN, morning.minusDays(20));
        events.save(UserEventRecord.builder().dn(BOB).date(DAY).status(UserStatusEnum.WORK_FROM_HOME).build());

        AccountabilityMetrics metrics = new AccountabilityMetricsService(checkIns, events, users, calendar).forDay(DAY);

        assertEquals(3, metrics.accountedFor().expected());
        assertEquals(2, metrics.accountedFor().accounted());
        assertEquals(1, metrics.accountedFor().unaccountedTotal());
        assertTrue(metrics.accountedFor().unaccounted().isEmpty(), "organisation-wide figures name nobody");
        assertEquals(List.of("CHECKED_IN", "WORK_FROM_HOME", "UNACCOUNTED"),
                metrics.statusMix().stream().map(StatusMixEntry::key).toList());
        assertEquals(2, metrics.agentCoverage().reporting());
        assertEquals(1, metrics.agentCoverage().stopped());
        assertEquals(0, metrics.agentCoverage().neverReported());
    }
}
