package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.RequestState;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.EventRange;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.StatusRequest;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.StatusRequestRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Requests, approvals, rejections and cancellations: who may do which, what reaches the calendar (only what is
 * approved), what is audited (without notes) and who is told.
 */
class StatusRequestServiceTest {

    private static final String ALICE = "cn=Alice,ou=Users,dc=winllc,dc=com";       // the requester
    private static final String MANAGER = "cn=Manny,ou=Users,dc=winllc,dc=com";     // Alice's manager
    private static final String ADMIN = "cn=Ada,ou=Users,dc=winllc,dc=com";
    private static final String COWORKER = "cn=Carl,ou=Users,dc=winllc,dc=com";
    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    /** Monday 19 October 2026, mid-morning. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T14:00:00Z"), ZONE);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    private final Map<Long, StatusRequest> stored = new HashMap<>();
    private final List<UserEventRecord> calendarDays = new ArrayList<>();
    private final List<AuditEntry> audited = new ArrayList<>();

    private StatusRequestRepository requests;
    private UserEventRecordRepository events;
    private Approvers approvers;
    private AccessPolicy accessPolicy;
    private Mailer mailer;
    private StatusRequestService service;

    @BeforeEach
    void setUp() {
        requests = mock(StatusRequestRepository.class);
        AtomicLong ids = new AtomicLong();
        when(requests.save(any())).thenAnswer(inv -> {
            StatusRequest r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(ids.incrementAndGet());
            }
            stored.put(r.getId(), r);
            return r;
        });
        when(requests.findByIdForUpdate(anyLong())).thenAnswer(inv -> Optional.ofNullable(stored.get(inv.<Long>getArgument(0))));
        when(requests.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(stored.get(inv.<Long>getArgument(0))));

        events = mock(UserEventRecordRepository.class);
        when(events.save(any())).thenAnswer(inv -> {
            UserEventRecord e = inv.getArgument(0);
            if (!calendarDays.contains(e)) {
                calendarDays.add(e);
            }
            return e;
        });
        when(events.findByDnIgnoreCaseAndDate(anyString(), any())).thenAnswer(inv -> calendarDays.stream()
                .filter(e -> e.getDate().equals(inv.getArgument(1))).toList());
        when(events.findByRequestIdFrom(anyLong(), any())).thenAnswer(inv -> calendarDays.stream()
                .filter(e -> inv.getArgument(0).equals(e.getRequestId()))
                .filter(e -> !e.getDate().isBefore(inv.getArgument(1))).toList());
        org.mockito.Mockito.doAnswer(inv -> {
            calendarDays.removeAll(new ArrayList<>(inv.<List<UserEventRecord>>getArgument(0)));
            return null;
        }).when(events).deleteAll(any());

        approvers = mock(Approvers.class);
        when(approvers.of(ALICE)).thenReturn(List.of(MANAGER));
        when(approvers.mayDecide(any(), anyString())).thenAnswer(inv -> {
            Authentication viewer = inv.getArgument(0);
            String person = inv.getArgument(1);
            return !Approvers.sameDn(viewer.getName(), person)
                    && (viewer.getName().equals(ADMIN) || (viewer.getName().equals(MANAGER) && person.equals(ALICE)));
        });
        accessPolicy = mock(AccessPolicy.class);
        when(accessPolicy.isAdmin(any())).thenAnswer(inv -> inv.<Authentication>getArgument(0).getName().equals(ADMIN));

        mailer = mock(Mailer.class);
        when(mailer.isConfigured()).thenReturn(true);
        @SuppressWarnings("unchecked")
        LoadingCache<String, LdapUser> users = mock(LoadingCache.class);
        when(users.get(anyString())).thenAnswer(inv -> LdapUser.builder().dn(inv.getArgument(0))
                .email(com.winllc.innoutwork.data.LdapDn.cnOf(inv.getArgument(0)).toLowerCase() + "@example.com").build());

        ApplicationProperties properties = new ApplicationProperties();
        service = new StatusRequestService(requests, new StatusCalendar(events), new StatusPolicy(properties), approvers,
                accessPolicy, mailer, properties, users, CLOCK);
        service.setAuditLog(new RecordingAudit());
    }

    private static Authentication as(String dn) {
        return new TestingAuthenticationToken(dn, null, "USER");
    }

    private StatusRequest aliceAsksForLeave() {
        return service.create(ALICE, UserStatusEnum.SCHEDULED_LEAVE,
                EventRange.inclusive(TODAY.plusDays(7), TODAY.plusDays(9)), null, "family wedding");
    }

    // --- asking -------------------------------------------------------------------------------------------------

    @Test
    void aRequestIsPendingAndPutsNothingOnTheCalendar() {
        StatusRequest request = aliceAsksForLeave();

        assertEquals(RequestState.REQUESTED, request.getState());
        assertEquals(3, request.getDays());
        assertEquals("family wedding", request.getNote());
        assertTrue(calendarDays.isEmpty(), "a request excuses nothing until it is approved");
        verify(events, never()).save(any());
        assertEquals("calendar.request-created", audited.getLast().action());
        assertFalse(audited.getLast().details().containsValue("family wedding"), "notes stay out of the audit trail");
        verify(mailer).sendAfterCommit(eq("manny@example.com"), anyString(), eq(StatusRequestService.MAIL_TEMPLATE), anyMap());
    }

    @Test
    void onlyStatusesThatNeedApprovalAreRequested() {
        EventRange tomorrow = EventRange.inclusive(TODAY.plusDays(1), TODAY.plusDays(1));

        assertEquals("not_a_request_status", assertThrows(InvalidRequestException.class,
                () -> service.create(ALICE, UserStatusEnum.LATE_ARRIVAL, tomorrow, null, null)).error());
        assertEquals("not_a_request_status", assertThrows(InvalidRequestException.class,
                () -> service.create(ALICE, UserStatusEnum.ABSENT, tomorrow, null, null)).error());
        verify(requests, never()).save(any());
    }

    // --- deciding -----------------------------------------------------------------------------------------------

    @Test
    void theManagersApprovalPutsEachDayOnTheCalendar() {
        StatusRequest request = aliceAsksForLeave();

        StatusRequest approved = service.approve(as(MANAGER), request.getId(), "enjoy");

        assertEquals(RequestState.APPROVED, approved.getState());
        assertEquals(MANAGER, approved.getDecidedByDn());
        assertEquals("enjoy", approved.getDecisionNote());
        assertEquals(3, calendarDays.size());
        assertTrue(calendarDays.stream().allMatch(e -> e.getStatus() == UserStatusEnum.SCHEDULED_LEAVE
                && e.getRequestId().equals(request.getId()) && e.getDn().equals(ALICE)));
        AuditEntry entry = audited.getLast();
        assertEquals("calendar.request-approved", entry.action());
        assertEquals(3, entry.details().get("daysWritten"));
        assertFalse(entry.details().containsValue("enjoy"));
        verify(mailer).sendAfterCommit(eq("alice@example.com"), anyString(), anyString(), anyMap());
    }

    @Test
    void nobodyApprovesTheirOwnRequestNotEvenAnAdministrator() {
        StatusRequest own = service.create(ADMIN, UserStatusEnum.WORK_FROM_HOME,
                EventRange.inclusive(TODAY.plusDays(1), TODAY.plusDays(1)), null, null);

        assertThrows(AccessDeniedException.class, () -> service.approve(as(ADMIN), own.getId(), null));
        assertEquals(RequestState.REQUESTED, stored.get(own.getId()).getState());
        assertTrue(calendarDays.isEmpty());
    }

    @Test
    void aCoworkerCannotDecide() {
        StatusRequest request = aliceAsksForLeave();

        assertThrows(AccessDeniedException.class, () -> service.approve(as(COWORKER), request.getId(), null));
        assertThrows(AccessDeniedException.class, () -> service.reject(as(COWORKER), request.getId(), null));
        assertEquals(RequestState.REQUESTED, stored.get(request.getId()).getState());
    }

    @Test
    void anAdministratorMayDecideAnyonesRequest() {
        StatusRequest request = aliceAsksForLeave();

        assertEquals(RequestState.REJECTED, service.reject(as(ADMIN), request.getId(), "busy week").getState());
        assertTrue(calendarDays.isEmpty(), "a rejection writes nothing");
        assertEquals("calendar.request-rejected", audited.getLast().action());
    }

    @Test
    void aDecidedRequestCannotBeDecidedAgain() {
        StatusRequest request = aliceAsksForLeave();
        service.reject(as(MANAGER), request.getId(), null);

        InvalidRequestException again = assertThrows(InvalidRequestException.class,
                () -> service.approve(as(ADMIN), request.getId(), null));
        assertEquals("not_pending", again.error());
        assertEquals(409, again.status().value());
        assertTrue(calendarDays.isEmpty());
    }

    // --- withdrawing --------------------------------------------------------------------------------------------

    @Test
    void cancellingAnApprovedRequestTakesItsComingDaysOffTheCalendar() {
        StatusRequest started = service.create(ALICE, UserStatusEnum.SCHEDULED_LEAVE,
                EventRange.inclusive(TODAY.minusDays(1), TODAY.plusDays(1)), null, null);
        service.approve(as(MANAGER), started.getId(), null);
        assertEquals(3, calendarDays.size());

        StatusRequest cancelled = service.cancel(as(ALICE), started.getId());

        assertEquals(RequestState.CANCELLED, cancelled.getState());
        assertEquals(List.of(TODAY.minusDays(1)), calendarDays.stream().map(UserEventRecord::getDate).toList(),
                "yesterday stays as history; today and tomorrow come off");
        assertEquals(2, audited.getLast().details().get("daysRemoved"));
        assertEquals("APPROVED", audited.getLast().details().get("previousState"));
    }

    @Test
    void onlyTheRequesterOrAnAdministratorCancels() {
        StatusRequest request = aliceAsksForLeave();

        assertThrows(AccessDeniedException.class, () -> service.cancel(as(MANAGER), request.getId()));
        assertEquals(RequestState.CANCELLED, service.cancel(as(ADMIN), request.getId()).getState());
    }

    @Test
    void aRequestThatIsOverOrClosedCannotBeCancelled() {
        StatusRequest past = service.create(ALICE, UserStatusEnum.SCHEDULED_LEAVE,
                EventRange.inclusive(TODAY.minusDays(3), TODAY.minusDays(2)), null, null);
        service.approve(as(MANAGER), past.getId(), null);

        assertEquals("not_cancellable", assertThrows(InvalidRequestException.class,
                () -> service.cancel(as(ALICE), past.getId())).error());

        StatusRequest rejected = aliceAsksForLeave();
        service.reject(as(MANAGER), rejected.getId(), null);
        assertEquals("not_cancellable", assertThrows(InvalidRequestException.class,
                () -> service.cancel(as(ALICE), rejected.getId())).error());
    }

    // --- reading ------------------------------------------------------------------------------------------------

    @Test
    void anAdministratorsQueueLeavesOutTheirOwn() {
        StatusRequest alices = aliceAsksForLeave();
        StatusRequest own = service.create(ADMIN, UserStatusEnum.TDY,
                EventRange.inclusive(TODAY.plusDays(1), TODAY.plusDays(2)), null, null);
        when(requests.findByStateOrderByCreatedAtAsc(RequestState.REQUESTED)).thenReturn(List.of(alices, own));

        assertEquals(List.of(alices), service.pendingFor(as(ADMIN)));
    }

    @Test
    void aManagersQueueIsTheirPeoplesRequests() {
        StatusRequest alices = aliceAsksForLeave();
        when(approvers.peopleDecidedBy(MANAGER)).thenReturn(Set.of(Approvers.key(ALICE)));
        when(requests.findByStateAndLowercaseRequesterIn(RequestState.REQUESTED, Set.of(Approvers.key(ALICE))))
                .thenReturn(List.of(alices));

        assertEquals(List.of(alices), service.pendingFor(as(MANAGER)));
        when(approvers.peopleDecidedBy(COWORKER)).thenReturn(Set.of());
        assertEquals(List.of(), service.pendingFor(as(COWORKER)));
    }

    @Test
    void aRequestIsVisibleToItsRequesterAndDecidersOnly() {
        StatusRequest request = aliceAsksForLeave();

        assertEquals(request, service.get(as(ALICE), request.getId()));
        assertEquals(request, service.get(as(MANAGER), request.getId()));
        assertThrows(AccessDeniedException.class, () -> service.get(as(COWORKER), request.getId()));
    }

    /** Records what is audited; runs "transactions" in place. */
    private class RecordingAudit implements AuditLog {
        @Override
        public void record(AuditEntry entry) {
            audited.add(entry);
        }

        @Override
        public void recordQuietly(AuditEntry entry) {
            audited.add(entry);
        }

        @Override
        public <T> T inTransaction(Supplier<T> change) {
            return change.get();
        }

        @Override
        public void requireAvailable() {
        }
    }
}
