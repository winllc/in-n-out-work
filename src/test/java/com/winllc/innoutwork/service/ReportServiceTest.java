package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.reports.GroupReport;
import com.winllc.innoutwork.data.reports.UserDayReport;
import com.winllc.innoutwork.data.reports.UserReport;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Group reports read every member's attendance for the range at once, and members through the user cache. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);
    private static final ZonedDateTime FROM = MONDAY.atStartOfDay(ZONE);
    private static final ZonedDateTime TO = MONDAY.plusDays(2).atStartOfDay(ZONE);
    private static final LdapDn GROUP = new LdapDn("cn=Engineering,ou=Groups,dc=winllc,dc=com");
    private static final String BOB = "cn=Bob,ou=Users,dc=winllc,dc=com";
    private static final String GONE = "cn=Gone,ou=Users,dc=winllc,dc=com";

    @Mock private LdapService ldapService;
    @Mock private CheckInOutRecordRepository checkIns;
    @Mock private UserEventRecordRepository events;
    @Mock private LoadingCache<String, LdapUser> userCache;

    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(ldapService, checkIns, events, userCache);
        LdapGroup group = new LdapGroup();
        group.setDn(GROUP.dn());
        group.setCn("Engineering");
        when(ldapService.lookupGroup(GROUP)).thenReturn(Optional.of(group));
        when(ldapService.getGroupMembers(GROUP)).thenReturn(List.of(BOB, GONE));
        when(userCache.get(BOB)).thenReturn(LdapUser.builder().dn(BOB).organization("Org").build());
        when(userCache.get(GONE)).thenReturn(null);
    }

    private static CheckInOutRecord record(String dn, CheckInOutEnum action, ZonedDateTime at) {
        return CheckInOutRecord.builder().dn(dn).action(action).timestamp(at).build();
    }

    @Test
    void eachMembersDaysComeFromOneBatchOfRecordsAndStatuses() {
        // Stored in a different case from the group membership, and returned out of order.
        when(checkIns.findByLowercaseDnInAndTimestampBetween(any(), any(), any())).thenReturn(List.of(
                record(BOB.toUpperCase(), CheckInOutEnum.CHECK_OUT, FROM.plusHours(17)),
                record(BOB, CheckInOutEnum.CHECK_IN, FROM.plusHours(8))));
        when(events.findByLowercaseDnInAndDateBetween(any(), any(), any())).thenReturn(List.of(
                UserEventRecord.builder().dn(BOB.toUpperCase()).date(MONDAY.plusDays(1)).status(UserStatusEnum.TDY).build()));

        GroupReport report = service.generateGroupReport(GROUP, FROM, TO);

        assertEquals(1, report.getUserReports().size(), "a member missing from the directory is left out");
        UserReport bob = report.getUserReports().getFirst();
        assertEquals("Org", bob.getOrganization());
        List<UserDayReport> days = bob.getDayReports();
        assertEquals(List.of(MONDAY, MONDAY.plusDays(1), MONDAY.plusDays(2)), days.stream().map(UserDayReport::getDay).toList());
        assertEquals(FROM.plusHours(8).toInstant(), days.get(0).getCheckInTime().toInstant());
        assertEquals(FROM.plusHours(17).toInstant(), days.get(0).getCheckOutTime().toInstant());
        assertEquals(UserStatusEnum.TDY.getFriendlyName(), days.get(1).getStatus());
        assertEquals(UserStatusEnum.STANDARD.getFriendlyName(), days.get(2).getStatus());

        List<String> lowered = List.of(BOB.toLowerCase(), GONE.toLowerCase());
        verify(checkIns).findByLowercaseDnInAndTimestampBetween(eq(lowered), eq(FROM),
                eq(MONDAY.plusDays(2).atTime(23, 59, 59).atZone(ZONE)));
        verify(events).findByLowercaseDnInAndDateBetween(lowered, MONDAY, MONDAY.plusDays(2));
        verify(checkIns, never()).findByDnIgnoreCaseAndTimestampIsBetweenOrderByTimestampDesc(anyString(), any(), any());
        verify(events, never()).findByDnIgnoreCaseAndDate(anyString(), any());
        verify(ldapService, never()).lookupUser(any());
    }

    @Test
    void anUnknownGroupHasNoReport() {
        when(ldapService.lookupGroup(any())).thenReturn(Optional.empty());

        assertNull(service.generateGroupReport(new LdapDn("cn=Nope"), FROM, TO));
        verifyNoInteractions(checkIns, events);
    }
}
