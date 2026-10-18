package com.winllc.innoutwork.migration;

import com.winllc.innoutwork.constant.RequestState;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.EventRange;
import com.winllc.innoutwork.model.StatusRequest;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.StatusRequestRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.service.StatusCalendar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V2 (status requests) on PostgreSQL: its constraints, the queries the approval queue and the calendar make, and the
 * link from an approved request's days back to it.
 */
class StatusRequestPostgresTest extends PostgresTestSupport {

    private static final String ALICE = "cn=Alice Archer,ou=Users,dc=winllc,dc=com";

    @Autowired private StatusRequestRepository requests;
    @Autowired private UserEventRecordRepository events;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM user_event_records");
        jdbc.update("DELETE FROM status_requests");
    }

    private StatusRequest request(String dn, LocalDate from, LocalDate to, RequestState state) {
        return requests.save(StatusRequest.builder().requesterDn(dn).status(UserStatusEnum.SCHEDULED_LEAVE)
                .fromDate(from).toDate(to).state(state).createdAt(ZonedDateTime.now()).note("dentist").build());
    }

    @Test
    void theQueueAndTheCalendarFindRequestsIgnoringDnCase() {
        LocalDate monday = LocalDate.of(2026, 11, 2);
        StatusRequest pending = request(ALICE.toUpperCase(), monday, monday.plusDays(2), RequestState.REQUESTED);
        request(ALICE, monday.plusDays(10), monday.plusDays(11), RequestState.REJECTED);

        assertEquals(List.of(pending.getId()), requests.findByStateAndLowercaseRequesterIn(RequestState.REQUESTED,
                Set.of(ALICE.toLowerCase())).stream().map(StatusRequest::getId).toList());
        assertEquals(2, requests.findByRequester(ALICE).size());
        assertEquals(1, requests.findByRequesterAndStateOverlapping(ALICE, RequestState.REQUESTED,
                monday.plusDays(2), monday.plusDays(30)).size(), "overlaps on its last day");
        assertEquals(0, requests.findByRequesterAndStateOverlapping(ALICE, RequestState.REQUESTED,
                monday.plusDays(3), monday.plusDays(30)).size());
    }

    @Test
    void anApprovedRequestsDaysPointBackAtItAndComeOffWhenCancelled() {
        LocalDate monday = LocalDate.of(2026, 11, 2);
        StatusRequest approved = request(ALICE, monday, monday.plusDays(2), RequestState.APPROVED);
        StatusCalendar calendar = new StatusCalendar(events);
        TransactionTemplate tx = new TransactionTemplate(transactions);

        tx.executeWithoutResult(s -> calendar.set(ALICE, UserStatusEnum.SCHEDULED_LEAVE,
                EventRange.inclusive(monday, monday.plusDays(2)).dates(), null, approved.getId()));
        assertEquals(3, events.findByRequestIdFrom(approved.getId(), monday).size());

        int removed = tx.execute(s -> calendar.removeRequestDays(approved.getId(), monday.plusDays(1)));

        assertEquals(2, removed);
        List<UserEventRecord> left = events.findByDnIgnoreCaseAndDateBetween(ALICE, monday, monday.plusDays(5));
        assertEquals(List.of(monday), left.stream().map(UserEventRecord::getDate).toList());
    }

    @Test
    void aDaySetLaterByThePersonReplacesTheRequestsDayAndLosesTheLink() {
        LocalDate day = LocalDate.of(2026, 11, 9);
        StatusRequest approved = request(ALICE, day, day, RequestState.APPROVED);
        StatusCalendar calendar = new StatusCalendar(events);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(s -> calendar.set(ALICE, UserStatusEnum.SCHEDULED_LEAVE, List.of(day), null, approved.getId()));

        tx.executeWithoutResult(s -> calendar.set(ALICE, UserStatusEnum.LATE_ARRIVAL, List.of(day),
                java.time.LocalTime.of(10, 0), null));

        List<UserEventRecord> days = events.findByDnIgnoreCaseAndDate(ALICE, day);
        assertEquals(1, days.size());
        assertEquals(UserStatusEnum.LATE_ARRIVAL, days.getFirst().getStatus());
        assertNull(days.getFirst().getRequestId());
    }

    @Test
    void deletingARequestLeavesItsPastDaysWithoutTheLink() {
        LocalDate day = LocalDate.of(2026, 11, 16);
        StatusRequest approved = request(ALICE, day, day, RequestState.APPROVED);
        new TransactionTemplate(transactions).executeWithoutResult(s -> new StatusCalendar(events)
                .set(ALICE, UserStatusEnum.SCHEDULED_LEAVE, List.of(day), null, approved.getId()));

        jdbc.update("DELETE FROM status_requests WHERE id = ?", approved.getId());

        assertNull(jdbc.queryForObject("SELECT request_id FROM user_event_records WHERE date = ?", Long.class, day));
    }

    @Test
    void theSchemaRefusesImpossibleRequests() {
        String insert = "INSERT INTO status_requests (requester_dn, status, from_date, to_date, state, created_at)"
                + " VALUES (?, ?, ?, ?, ?, now())";
        LocalDate day = LocalDate.of(2026, 11, 2);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, ALICE, "SCHEDULED_LEAVE", day, day, "MAYBE"));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, ALICE, "ON_A_BEACH", day, day, "REQUESTED"));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, ALICE, "SCHEDULED_LEAVE", day, day.minusDays(1), "REQUESTED"));
        assertEquals(1, jdbc.update(insert, ALICE, "SCHEDULED_LEAVE", day, day, "REQUESTED"));
    }

    @Test
    void aDecisionLocksTheRequestUntilItCommits() {
        StatusRequest pending = request(ALICE, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 2), RequestState.REQUESTED);

        String plan = new TransactionTemplate(transactions).execute(s -> {
            assertTrue(requests.findByIdForUpdate(pending.getId()).isPresent());
            return jdbc.queryForObject("SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation"
                    + " WHERE c.relname = 'status_requests' AND l.mode = 'RowShareLock'", Long.class) > 0 ? "locked" : "not";
        });

        assertEquals("locked", plan);
    }
}
