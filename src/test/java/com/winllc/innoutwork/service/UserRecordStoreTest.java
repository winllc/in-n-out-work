package com.winllc.innoutwork.service;

import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Creating user records when a concurrent request may create the same one first. */
class UserRecordStoreTest {

    private static final String DN = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";

    private UserRecordRepository repository;
    private UserRecordStore store;

    @BeforeEach
    void setUp() {
        repository = mock(UserRecordRepository.class);
        when(repository.save(any(UserRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        store = new UserRecordStore(repository);
    }

    private static UserRecord existing(String notes) {
        return UserRecord.builder().id(7L).dn(DN).notes(notes).build();
    }

    @Test
    void anInsertThatSucceedsReportsItsRecord() {
        UserRecordStore.Result result = store.insertOrFind(DN, () -> UserRecord.builder().dn(DN).build());

        assertTrue(result.inserted());
        assertEquals(DN, result.record().getDn());
    }

    @Test
    void anInsertRejectedAsADuplicateReturnsTheRecordThatWon() {
        when(repository.save(any(UserRecord.class))).thenThrow(new DataIntegrityViolationException("duplicate"));
        when(repository.findByDnIgnoreCase(DN)).thenReturn(Optional.of(existing("theirs")));

        UserRecordStore.Result result = store.insertOrFind(DN, () -> UserRecord.builder().dn(DN).build());

        assertFalse(result.inserted());
        assertEquals(7L, result.record().getId());
    }

    /** A different constraint failing must not be mistaken for a lost race. */
    @Test
    void aRejectionWithNoExistingRecordIsRethrown() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("dn is null");
        when(repository.save(any(UserRecord.class))).thenThrow(failure);
        when(repository.findByDnIgnoreCase(DN)).thenReturn(Optional.empty());

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> store.insertOrFind(DN, () -> UserRecord.builder().dn(DN).build())));
    }

    @Test
    void findOrCreateReturnsAnExistingRecordWithoutInserting() {
        when(repository.findByDnIgnoreCase(DN)).thenReturn(Optional.of(existing("mine")));

        assertEquals(7L, store.findOrCreate(DN, () -> UserRecord.builder().dn(DN).build()).getId());
        verify(repository, times(0)).save(any());
    }

    @Test
    void updateChangesAnExistingRecordAndSavesItOnce() {
        UserRecord record = existing(null);
        when(repository.findByDnIgnoreCase(DN)).thenReturn(Optional.of(record));

        store.update(DN, r -> r.setNotes("updated"));

        assertEquals("updated", record.getNotes());
        verify(repository, times(1)).save(record);
    }

    @Test
    void updateCreatesAMissingRecordWithTheChangeAlreadyApplied() {
        when(repository.findByDnIgnoreCase(DN)).thenReturn(Optional.empty());

        UserRecord created = store.update(DN, r -> r.setNotes("new"));

        assertEquals(DN, created.getDn());
        assertEquals("new", created.getNotes());
        verify(repository, times(1)).save(any(UserRecord.class));
    }

    /** Lost the race to create it: the change lands on the record that won instead of being dropped. */
    @Test
    void updateAppliesTheChangeToTheRecordACompetingRequestCreated() {
        UserRecord theirs = existing("theirs");
        when(repository.findByDnIgnoreCase(DN)).thenReturn(Optional.empty(), Optional.of(theirs));
        when(repository.save(any(UserRecord.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenAnswer(inv -> inv.getArgument(0));

        UserRecord result = store.update(DN, r -> r.setNotes("mine"));

        assertSame(theirs, result);
        assertEquals("mine", theirs.getNotes());
    }
}
