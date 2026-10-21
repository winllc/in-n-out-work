package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.CalendarEvent;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.Approvers;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.StatusCalendar;
import com.winllc.innoutwork.service.StatusPolicy;
import com.winllc.innoutwork.service.StatusRequestService;
import com.winllc.innoutwork.service.UserService;
import com.winllc.innoutwork.support.InMemoryDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The group calendar against a real directory, where {@code GroupCalendarRestServiceTest} stubs the
 * membership. What this adds is the join: group members come back from LDAP in the directory's own
 * spelling, status rows are stored in whatever case the user signed in with, and the two only line
 * up if the comparison is case-insensitive the whole way through.
 */
class GroupCalendarDirectoryTest {

    private static final String BASE = InMemoryDirectory.BASE_DN;
    private static final String ENGINEERING = "cn=Engineering,ou=Groups," + BASE;
    private static final String EMPTY_OU = "ou=Companies," + BASE;
    private static final String ALICE = "cn=Alice Adams,ou=Users," + BASE;
    private static final String BOB = "cn=Bob Barker,ou=Users," + BASE;

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-30T00:00:00Z");

    private static InMemoryDirectory directory;

    private UserEventRecordRepository userEventRecordRepository;
    private UserEventRestService service;

    @BeforeAll
    static void startDirectory() {
        directory = InMemoryDirectory.start();
    }

    @AfterAll
    static void stopDirectory() {
        directory.close();
    }

    @BeforeEach
    void setUp() {
        ApplicationProperties props = new ApplicationProperties();
        props.setUserBaseDn(BASE);
        props.setCalendarStatusEventColor("blue");
        props.setCalendarAbsentStatusEventColor("purple");

        userEventRecordRepository = mock(UserEventRecordRepository.class);
        LdapService ldapService = new LdapService(directory.ldapTemplate(), props);
        AccessPolicy accessPolicy = mock(AccessPolicy.class);
        // Everyone visible: the narrowing by viewer has its own test in GroupCalendarRestServiceTest.
        when(accessPolicy.scope(any())).thenReturn(AccessPolicy.Scope.of(dn -> true));

        service = new UserEventRestService(userEventRecordRepository, mock(CheckInOutRecordRepository.class),
                props, mock(GlobalCalendarRecordRepository.class), mock(StatusPolicy.class),
                mock(StatusCalendar.class), mock(StatusRequestService.class), mock(Approvers.class),
                ldapService, mock(UserService.class), accessPolicy);
    }

    private static final Authentication VIEWER =
            new TestingAuthenticationToken("cn=viewer,ou=Users," + BASE, "");

    private List<CalendarEvent> eventsFor(String groupDn) {
        return service.getGroupEvents(VIEWER, groupDn, FROM, TO);
    }

    /** The DNs queried are the group's real members, lower-cased for the repository's comparison. */
    @Test
    void theGroupsRealMembersAreWhatIsQueried() {
        when(userEventRecordRepository.findByLowercaseDnInAndDateBetween(any(), any(), any())).thenReturn(List.of());

        eventsFor(ENGINEERING);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> dns = ArgumentCaptor.forClass(Collection.class);
        verify(userEventRecordRepository).findByLowercaseDnInAndDateBetween(dns.capture(), any(), any());

        assertEquals(Set.of(ALICE.toLowerCase(), BOB.toLowerCase()), new HashSet<>(dns.getValue()));
    }

    /** A status stored in a different case than the directory's still lands on the right member. */
    @Test
    void aStatusStoredInAnotherCaseIsStillMatchedToTheMember() {
        when(userEventRecordRepository.findByLowercaseDnInAndDateBetween(any(), any(), any()))
                .thenReturn(List.of(UserEventRecord.builder()
                        .dn(BOB.toUpperCase())
                        .date(LocalDate.of(2026, 9, 10))
                        .status(UserStatusEnum.WORK_FROM_HOME)
                        .build()));

        List<CalendarEvent> events = eventsFor(ENGINEERING);

        assertEquals(1, events.size());
        assertEquals("Bob Barker: Work From Home", events.get(0).getTitle());
        // The directory's spelling, so the link resolves whatever case the status was stored in.
        assertEquals("/app/user/details/" + BOB, events.get(0).getUrl());
    }

    /** A DN the directory holds nothing for is an empty calendar, not an error. */
    @Test
    void aGroupWithNoMembersIsAnEmptyCalendar() {
        assertTrue(eventsFor(EMPTY_OU).isEmpty());
        verify(userEventRecordRepository, never()).findByLowercaseDnInAndDateBetween(any(), any(), any());
    }
}
