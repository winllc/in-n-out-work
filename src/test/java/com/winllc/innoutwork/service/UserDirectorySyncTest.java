package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.DirectorySyncResult;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers {@link UserService#syncUserRecordsFromDirectory()} - the batched refresh behind
 * {@link com.winllc.innoutwork.cron.RefreshUserRecordsCron}.
 */
@ExtendWith(MockitoExtension.class)
class UserDirectorySyncTest {

    @Mock
    private UserRecordRepository userRecordRepository;

    @Mock
    private LdapService ldapService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        // Only the repository and the directory matter on this path; the service's other
        // collaborators are never touched by the sync.
        userService = new UserService(userRecordRepository, ldapService, null, null, null, null);
    }

    @Test
    void createsARecordForADirectoryUserThatHasNone() {
        when(ldapService.findAllUsers()).thenReturn(List.of(user("cn=alice", "ORG1", "FT")));
        when(userRecordRepository.findAllByLowercaseDnIn(anyCollection())).thenReturn(List.of());

        DirectorySyncResult result = userService.syncUserRecordsFromDirectory();

        UserRecord saved = captureSaved().get(0);
        assertEquals("cn=alice", saved.getDn());
        assertEquals("ORG1", saved.getOrganization());
        assertEquals("FT", saved.getEmployeeType());
        assertEquals(UserRoleEnum.USER, saved.getUserRole(), "new records default to USER");

        assertEquals(1, result.created());
        assertEquals(0, result.updated());
    }

    @Test
    void updatesOnlyTheRecordsWhoseMetadataChanged() {
        LdapUser moved = user("cn=alice", "ORG2", "FT");
        LdapUser same = user("cn=bob", "ORG1", "PT");
        when(ldapService.findAllUsers()).thenReturn(List.of(moved, same));

        UserRecord aliceRecord = record("cn=alice", "ORG1", "FT");
        UserRecord bobRecord = record("cn=bob", "ORG1", "PT");
        when(userRecordRepository.findAllByLowercaseDnIn(anyCollection()))
                .thenReturn(List.of(aliceRecord, bobRecord));

        DirectorySyncResult result = userService.syncUserRecordsFromDirectory();

        // Only the moved user is written; the unchanged one costs a comparison, not a write.
        List<UserRecord> saved = captureSaved();
        assertEquals(1, saved.size());
        assertEquals("cn=alice", saved.get(0).getDn());
        assertEquals("ORG2", saved.get(0).getOrganization());

        assertEquals(0, result.created());
        assertEquals(1, result.updated());
        assertEquals(1, result.unchanged());
    }

    /** A steady-state run must not touch the database at all. */
    @Test
    void writesNothingWhenEverythingAlreadyMatches() {
        when(ldapService.findAllUsers()).thenReturn(List.of(user("cn=alice", "ORG1", "FT")));
        when(userRecordRepository.findAllByLowercaseDnIn(anyCollection()))
                .thenReturn(List.of(record("cn=alice", "ORG1", "FT")));

        DirectorySyncResult result = userService.syncUserRecordsFromDirectory();

        verify(userRecordRepository, never()).saveAll(any());
        assertEquals(0, result.written());
        assertEquals(1, result.unchanged());
    }

    /**
     * Attribute mapping failures surface as nulls, so a blank incoming value must not
     * wipe a column that still holds good data.
     */
    @Test
    void doesNotClearExistingMetadataWhenTheDirectoryValueIsBlank() {
        LdapUser blanked = LdapUser.builder().dn("cn=alice").organization(null).employeeType("  ").build();
        when(ldapService.findAllUsers()).thenReturn(List.of(blanked));
        when(userRecordRepository.findAllByLowercaseDnIn(anyCollection()))
                .thenReturn(List.of(record("cn=alice", "ORG1", "FT")));

        DirectorySyncResult result = userService.syncUserRecordsFromDirectory();

        verify(userRecordRepository, never()).saveAll(any());
        assertEquals(1, result.unchanged());
    }

    /** Everything a user or admin sets inside the app must survive a refresh. */
    @Test
    void leavesApplicationOwnedFieldsAlone() {
        when(ldapService.findAllUsers()).thenReturn(List.of(user("cn=alice", "ORG2", "FT")));

        UserRecord existing = record("cn=alice", "ORG1", "FT");
        existing.setNotes("back Monday");
        existing.setFavoriteGroups("cn=groupA");
        existing.setUserRole(UserRoleEnum.ADMIN);
        existing.setAlternateManagers("cn=carol");
        existing.setChosenLoginTime(LocalTime.of(8, 30));
        when(userRecordRepository.findAllByLowercaseDnIn(anyCollection())).thenReturn(List.of(existing));

        userService.syncUserRecordsFromDirectory();

        UserRecord saved = captureSaved().get(0);
        assertEquals("ORG2", saved.getOrganization(), "directory metadata is refreshed");
        assertEquals("back Monday", saved.getNotes());
        assertEquals("cn=groupA", saved.getFavoriteGroups());
        assertEquals(UserRoleEnum.ADMIN, saved.getUserRole());
        assertEquals("cn=carol", saved.getAlternateManagers());
        assertEquals(LocalTime.of(8, 30), saved.getChosenLoginTime());
    }

    /** DNs are matched case-insensitively, as everywhere else in the app. */
    @Test
    void matchesExistingRecordsIgnoringDnCase() {
        when(ldapService.findAllUsers()).thenReturn(List.of(user("CN=Alice", "ORG2", "FT")));
        when(userRecordRepository.findAllByLowercaseDnIn(anyCollection()))
                .thenReturn(List.of(record("cn=alice", "ORG1", "FT")));

        DirectorySyncResult result = userService.syncUserRecordsFromDirectory();

        assertEquals(0, result.created(), "an existing row must not be duplicated for a case variant");
        assertEquals(1, result.updated());
    }

    /** The point of the design: query count grows with batches, not with users. */
    @Test
    void queriesOncePerBatchRatherThanOncePerUser() {
        int userCount = UserService.SYNC_BATCH_SIZE + 10;
        List<LdapUser> directory = new ArrayList<>();
        for (int i = 0; i < userCount; i++) {
            directory.add(user("cn=user" + i, "ORG1", "FT"));
        }
        when(ldapService.findAllUsers()).thenReturn(directory);
        when(userRecordRepository.findAllByLowercaseDnIn(anyCollection())).thenReturn(List.of());

        DirectorySyncResult result = userService.syncUserRecordsFromDirectory();

        // 510 users -> 2 selects and 2 saves, not 510 of each.
        verify(userRecordRepository, times(2)).findAllByLowercaseDnIn(anyCollection());
        verify(userRecordRepository, times(2)).saveAll(any());
        verify(userRecordRepository, never()).findByDnIgnoreCase(any());
        assertEquals(userCount, result.created());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> dns = ArgumentCaptor.forClass(Collection.class);
        verify(userRecordRepository, atLeastOnce()).findAllByLowercaseDnIn(dns.capture());
        assertEquals(UserService.SYNC_BATCH_SIZE, dns.getAllValues().get(0).size());
    }

    @Test
    void skipsEntriesWithoutADnAndDeduplicates() {
        when(ldapService.findAllUsers()).thenReturn(Arrays.asList(
                user("cn=alice", "ORG1", "FT"),
                user("cn=alice", "ORG1", "FT"),   // duplicate DN
                LdapUser.builder().dn(null).build(),
                null));
        when(userRecordRepository.findAllByLowercaseDnIn(anyCollection())).thenReturn(List.of());

        DirectorySyncResult result = userService.syncUserRecordsFromDirectory();

        assertEquals(1, captureSaved().size(), "the duplicate DN is collapsed");
        assertEquals(2, result.skipped());
        assertEquals(4, result.scanned());
    }

    @Test
    void doesNothingWhenTheDirectoryReturnsNoUsers() {
        when(ldapService.findAllUsers()).thenReturn(List.of());

        assertEquals(DirectorySyncResult.EMPTY, userService.syncUserRecordsFromDirectory());

        verifyNoInteractions(userRecordRepository);
    }

    @SuppressWarnings("unchecked")
    private List<UserRecord> captureSaved() {
        ArgumentCaptor<List<UserRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(userRecordRepository, atLeastOnce()).saveAll(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    private static LdapUser user(String dn, String organization, String employeeType) {
        return LdapUser.builder()
                .dn(dn)
                .organization(organization)
                .employeeType(employeeType)
                .build();
    }

    private static UserRecord record(String dn, String organization, String employeeType) {
        UserRecord record = new UserRecord();
        record.setDn(dn);
        record.setOrganization(organization);
        record.setEmployeeType(employeeType);
        return record;
    }
}
