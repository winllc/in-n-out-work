package com.winllc.innoutwork.service;

import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Creates user records without ever creating two for one DN.
 * <p>
 * The database allows one row per DN, ignoring case (ux_user_records_dn_lower, added by
 * db/migrations/001_indexes_and_unique_user_dn.sql). Two requests for a user with no record yet, such as
 * a workstation's sign-in and unlock arriving together, can both find nothing and both try to insert;
 * the second insert is rejected, and here it falls back to the row the first one created.
 * <p>
 * Calls must not run inside a surrounding transaction: a rejected insert would mark that transaction
 * for rollback, and the fallback read would fail with it.
 */
final class UserRecordStore {

    private static final Logger log = LoggerFactory.getLogger(UserRecordStore.class);

    private final UserRecordRepository repository;

    UserRecordStore(UserRecordRepository repository) {
        this.repository = repository;
    }

    /** A record freshly inserted, or the one that already existed. */
    record Result(UserRecord record, boolean inserted) {
    }

    /** Inserts {@code newRecord} for {@code dn}, or returns the existing record if another request got there first. */
    Result insertOrFind(String dn, Supplier<UserRecord> newRecord) {
        try {
            return new Result(repository.save(newRecord.get()), true);
        } catch (DataIntegrityViolationException e) {
            Optional<UserRecord> existing = repository.findByDnIgnoreCase(dn);
            if (existing.isEmpty()) {
                throw e;
            }
            log.debug("User record for {} was created concurrently; using that one", dn);
            return new Result(existing.get(), false);
        }
    }

    /** The existing record for {@code dn}, or a new one from {@code newRecord}. */
    UserRecord findOrCreate(String dn, Supplier<UserRecord> newRecord) {
        return repository.findByDnIgnoreCase(dn)
                .orElseGet(() -> insertOrFind(dn, newRecord).record());
    }

    /**
     * Applies {@code change} to the record for {@code dn} and saves it, creating the record first if there
     * is none. A new record is inserted with the change already applied, so this is one write either way.
     */
    UserRecord update(String dn, Consumer<UserRecord> change) {
        Optional<UserRecord> existing = repository.findByDnIgnoreCase(dn);
        if (existing.isPresent()) {
            change.accept(existing.get());
            return repository.save(existing.get());
        }

        Result result = insertOrFind(dn, () -> {
            UserRecord created = new UserRecord();
            created.setDn(dn);
            change.accept(created);
            return created;
        });
        if (result.inserted()) {
            return result.record();
        }
        change.accept(result.record());
        return repository.save(result.record());
    }
}
