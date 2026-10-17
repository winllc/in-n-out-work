package com.winllc.innoutwork.service;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Administrators set delegates; self-chosen ones are kept for review. */
class DelegateServiceTest {

    private static final String ALICE = "cn=Alice,ou=Users,dc=winllc,dc=com";
    private static final String BOB = "cn=Bob,ou=Users,dc=winllc,dc=com";
    private static final String CAROL = "cn=Carol,ou=Users,dc=winllc,dc=com";

    private UserRecordRepository records;
    private LdapService ldap;
    private UserService users;
    private UserRecord alice;
    private final List<AuditEntry> audited = new ArrayList<>();
    private DelegateService service;

    @BeforeEach
    void setUp() {
        records = mock(UserRecordRepository.class);
        ldap = mock(LdapService.class);
        users = mock(UserService.class);
        alice = UserRecord.builder().dn(ALICE).alternateManagers(CAROL).alternateManagersReviewPending(true).build();
        when(users.getUserByDn(new LdapDn(ALICE))).thenReturn(Optional.of(alice));
        when(records.findByDnIgnoreCase(ALICE)).thenReturn(Optional.of(alice));
        when(records.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ldap.lookupUser(any(LdapDn.class))).thenAnswer(inv -> {
            String dn = inv.<LdapDn>getArgument(0).dn();
            return dn.contains("Nobody") ? Optional.empty() : Optional.of(LdapUser.builder().dn(dn).build());
        });
        service = new DelegateService(records, users, ldap);
        service.setAuditLog(new AuditLog() {
            @Override public void record(AuditEntry entry) { audited.add(entry); }
            @Override public void recordQuietly(AuditEntry entry) { audited.add(entry); }
            @Override public <T> T inTransaction(Supplier<T> change) { return change.get(); }
            @Override public void requireAvailable() { }
        });
    }

    @Test
    void anAdministratorReplacesTheDelegatesWhichAreThenNoLongerToReview() {
        UserRecord saved = service.setDelegates(ALICE, List.of(BOB, " " + BOB.toUpperCase(), ""));

        assertEquals(List.of(BOB), saved.getAltManagerList(), "normalised, without repeats or blanks");
        assertFalse(saved.isAlternateManagersReviewPending());
        AuditEntry entry = audited.getLast();
        assertEquals("person.updated", entry.action());
        assertEquals("alternate-managers", entry.details().get("via"));
        assertEquals(CAROL, entry.details().get("alternateManagers.old"));
        assertEquals(BOB, entry.details().get("alternateManagers.new"));
        assertEquals(true, entry.details().get("reviewed"));
    }

    @Test
    void nobodyIsTheirOwnDelegateAndDelegatesMustBeInTheDirectory() {
        assertEquals("invalid_delegate", assertThrows(InvalidRequestException.class,
                () -> service.setDelegates(ALICE, List.of(ALICE.toUpperCase()))).error());
        assertEquals("invalid_delegate", assertThrows(InvalidRequestException.class,
                () -> service.setDelegates(ALICE, List.of("cn=Nobody,ou=Users,dc=winllc,dc=com"))).error());
        assertEquals("invalid_delegate", assertThrows(InvalidRequestException.class,
                () -> service.setDelegates(ALICE, List.of("not a dn"))).error());
        verify(records, never()).save(any());
        assertEquals(CAROL, alice.getAlternateManagers());
    }

    @Test
    void removingEveryDelegateLeavesNone() {
        assertNull(service.setDelegates(ALICE, List.of()).getAlternateManagers());
    }

    @Test
    void keepingSelfChosenDelegatesLeavesThemAndClearsTheReview() {
        UserRecord kept = service.keep(ALICE);

        assertEquals(CAROL, kept.getAlternateManagers());
        assertFalse(kept.isAlternateManagersReviewPending());
        assertEquals("alternate-managers-reviewed", audited.getLast().details().get("via"));
    }

    @Test
    void theReviewListPutsSelfChosenDelegatesFirst() {
        UserRecord byAdmin = UserRecord.builder().dn(BOB).alternateManagers(CAROL).build();
        when(records.findWithAlternateManagers()).thenReturn(List.of(byAdmin, alice));

        assertEquals(List.of(alice, byAdmin), service.withDelegates(false));
        assertEquals(List.of(alice), service.withDelegates(true));
    }
}
