package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.data.DirectorySyncResult;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.license.LicensedPeopleLimit;
import com.winllc.innoutwork.license.TestLicenses;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.enssolutions.license.LicenseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The license's size in tracked people (user records): new people beyond it are not added, wherever
 * a record would be created, and nobody is ever removed.
 */
class TrackedPeopleLimitTest {

    @TempDir
    Path dir;

    /** Room for {@code limit} people in all. */
    private static TrackedPeopleLimit roomFor(long limit) {
        return currentCount -> currentCount < limit;
    }

    // --- the license's rule ---------------------------------------------------------------------------------

    @Test
    void theLicenseAllowsTenPercentOverThenStops() {
        LicenseManager licenses = TestLicenses.manager(dir);
        TestLicenses.installFile(licenses, TestLicenses.license().quantity(100).sign());
        LicensedPeopleLimit limit = new LicensedPeopleLimit(licenses);

        assertTrue(limit.mayAdd(99));
        assertTrue(limit.mayAdd(109), "the 110th is within the 10% overage");
        assertFalse(limit.mayAdd(110), "the 111th is not");
    }

    @Test
    void withoutAFullLicenseTheLimitDoesNotStopSignIn() {
        // Unlicensed or read-only, the check-in API and the sync are refused anyway; the record a first
        // sign-in creates is how an administrator gets in to install a license.
        LicenseManager licenses = TestLicenses.manager(dir);
        assertTrue(new LicensedPeopleLimit(licenses).mayAdd(1_000_000));

        TestLicenses.installFile(licenses, TestLicenses.license().quantity(1).expired(2).sign());
        assertTrue(new LicensedPeopleLimit(licenses).mayAdd(1_000_000));
    }

    // --- one record at a time (sign-in, profile, roles, permissions) ----------------------------------------

    @Test
    void aNewRecordBeyondTheLimitIsNotInserted() {
        UserRecordRepository repository = mock(UserRecordRepository.class);
        when(repository.count()).thenReturn(10L);
        when(repository.findByDnIgnoreCase("cn=new")).thenReturn(Optional.empty());
        UserRecordStore store = new UserRecordStore(repository, roomFor(10));

        TrackedPeopleLimitException e = assertThrows(TrackedPeopleLimitException.class,
                () -> store.insertOrFind("cn=new", () -> UserRecord.builder().dn("cn=new").build()));
        assertEquals("cn=new", e.getDn());
        verify(repository, never()).save(any());
    }

    @Test
    void someoneAlreadyTrackedIsFoundEvenAtTheLimit() {
        UserRecordRepository repository = mock(UserRecordRepository.class);
        UserRecord existing = UserRecord.builder().id(1L).dn("cn=old").build();
        when(repository.count()).thenReturn(10L);
        when(repository.findByDnIgnoreCase("cn=old")).thenReturn(Optional.of(existing));
        UserRecordStore store = new UserRecordStore(repository, roomFor(10));

        assertSame(existing, store.findOrCreate("cn=old", () -> UserRecord.builder().dn("cn=old").build()));
        assertSame(existing, store.insertOrFind("cn=old", () -> UserRecord.builder().dn("cn=old").build()).record());
        verify(repository, never()).save(any());
    }

    @Test
    void belowTheLimitARecordIsInserted() {
        UserRecordRepository repository = mock(UserRecordRepository.class);
        when(repository.count()).thenReturn(9L);
        when(repository.save(any(UserRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        UserRecordStore store = new UserRecordStore(repository, roomFor(10));

        assertTrue(store.insertOrFind("cn=new", () -> UserRecord.builder().dn("cn=new").build()).inserted());
    }

    // --- the directory sync ----------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void theSyncAddsNewPeopleOnlyUpToTheLimitAndStillUpdatesEveryone() {
        UserRecordRepository repository = mock(UserRecordRepository.class);
        LdapService ldap = mock(LdapService.class);
        LdapUser known = LdapUser.builder().dn("cn=known").organization("NEW-ORG").build();
        List<LdapUser> newcomers = List.of(
                LdapUser.builder().dn("cn=a").organization("O").build(),
                LdapUser.builder().dn("cn=b").organization("O").build(),
                LdapUser.builder().dn("cn=c").organization("O").build());
        when(ldap.findAllUsers()).thenReturn(List.of(known, newcomers.get(0), newcomers.get(1), newcomers.get(2)));
        UserRecord knownRecord = UserRecord.builder().id(1L).dn("cn=known").organization("OLD-ORG").build();
        when(repository.findAllByLowercaseDnIn(anyCollection())).thenReturn(List.of(knownRecord));
        when(repository.count()).thenReturn(8L);

        UserService users = new UserService(repository, ldap, null, null, null, null, roomFor(10));
        DirectorySyncResult result = users.syncUserRecordsFromDirectory();

        assertEquals(2, result.created());
        assertEquals(1, result.notAdded());
        assertEquals(1, result.updated());
        ArgumentCaptor<Collection<UserRecord>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(repository).saveAll(saved.capture());
        assertEquals(List.of("cn=known", "cn=a", "cn=b"), saved.getValue().stream().map(UserRecord::getDn).toList());
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteAll(any());
    }

    // --- the check-in API ------------------------------------------------------------------------------------

    @Test
    void anUntrackedPersonsEventsAreRefusedAtTheLimit() {
        CheckInOutRecordRepository checkIns = mock(CheckInOutRecordRepository.class);
        UserRecordRepository repository = mock(UserRecordRepository.class);
        when(repository.findByDnForUpdate(anyString())).thenReturn(Optional.empty());
        when(repository.findByDnIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(repository.count()).thenReturn(10L);
        CheckInOutService service = new CheckInOutService(checkIns, repository, roomFor(10));

        for (CheckInOutEnum action : List.of(CheckInOutEnum.CHECK_IN, CheckInOutEnum.LOCK, CheckInOutEnum.UNLOCK)) {
            CheckInOutRecord event = CheckInOutRecord.builder().dn("cn=new").action(action)
                    .timestamp(ZonedDateTime.now()).build();
            assertThrows(TrackedPeopleLimitException.class, () -> service.saveCheckInOutRecord(event), action.name());
        }
        verify(checkIns, never()).save(any());
    }

    @Test
    void someoneTrackedChecksInWhateverTheCount() {
        CheckInOutRecordRepository checkIns = mock(CheckInOutRecordRepository.class);
        UserRecordRepository repository = mock(UserRecordRepository.class);
        when(repository.findByDnIgnoreCase("cn=old")).thenReturn(Optional.of(UserRecord.builder().dn("cn=old").build()));
        when(repository.count()).thenReturn(1_000L);
        when(checkIns.save(any(CheckInOutRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        CheckInOutService service = new CheckInOutService(checkIns, repository, roomFor(10));

        service.saveCheckInOutRecord(CheckInOutRecord.builder().dn("cn=old").action(CheckInOutEnum.LOCK)
                .timestamp(ZonedDateTime.now()).build());
        verify(checkIns).save(any(CheckInOutRecord.class));
    }
}
