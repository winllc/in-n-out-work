package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.migration.PostgresTestSupport;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Writes that used to race, run for real against PostgreSQL with the migration applied: concurrent first
 * sign-ins creating a user record, and concurrent unlocks deciding who checked in.
 */
@Import(CheckInOutService.class)
class ConcurrentWritesTest extends PostgresTestSupport {

    private static final String BOB = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";

    @Autowired private CheckInOutService checkInOutService;
    @Autowired private UserRecordRepository userRecords;
    @Autowired private CheckInOutRecordRepository checkIns;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    void freshDatabase() throws Exception {
        jdbc.execute("TRUNCATE user_records, permission_records, check_in_out_records RESTART IDENTITY CASCADE");
        runMigration();
    }

    private static CheckInOutRecord unlock(String dn) {
        CheckInOutRecord record = new CheckInOutRecord();
        record.setDn(dn);
        record.setAction(CheckInOutEnum.UNLOCK);
        record.setTimestamp(ZonedDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.MINUTES));
        return record;
    }

    // --- user records ----------------------------------------------------------------------------------

    /** The case a concurrent request creates: the row already exists by the time this insert lands. */
    @Test
    void insertingARecordThatAlreadyExistsReturnsTheExistingOne() {
        UserRecord existing = userRecords.save(UserRecord.builder().dn(BOB).notes("first").build());

        UserRecordStore.Result result = new UserRecordStore(userRecords)
                .insertOrFind(BOB.toUpperCase(), () -> UserRecord.builder().dn(BOB.toUpperCase()).build());

        assertFalse(result.inserted());
        assertEquals(existing.getId(), result.record().getId());
        assertEquals(1, userRecords.count());
    }

    @Test
    void simultaneousFirstSignInsCreateOneRecord() throws Exception {
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Long>> ids = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String spelling = i % 2 == 0 ? BOB : BOB.toUpperCase();
                ids.add(pool.submit(() -> {
                    go.await();
                    return new UserRecordStore(userRecords)
                            .findOrCreate(spelling, () -> UserRecord.builder().dn(spelling).build()).getId();
                }));
            }
            go.countDown();

            Long first = ids.getFirst().get(30, TimeUnit.SECONDS);
            for (Future<Long> id : ids) {
                assertEquals(first, id.get(30, TimeUnit.SECONDS), "every caller gets the same record");
            }
            assertEquals(1, userRecords.count());
        } finally {
            pool.shutdownNow();
        }
    }

    // --- check-ins ---------------------------------------------------------------------------------------

    /** A second event for the same user waits for the first to commit instead of reading alongside it. */
    @Test
    void aUsersCheckInEventsAreProcessedOneAtATime() throws Exception {
        userRecords.save(UserRecord.builder().dn(BOB).build());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactions);

        // Another event is mid-flight: it holds the user's lock inside its transaction.
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            userRecords.findByDnForUpdate(BOB).orElseThrow();
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(locked.await(30, TimeUnit.SECONDS));

        CompletableFuture<CheckInOutRecord> second = CompletableFuture.supplyAsync(
                () -> checkInOutService.saveCheckInOutRecord(unlock(BOB)));

        assertThrows(TimeoutException.class, () -> second.get(1, TimeUnit.SECONDS), "should wait for the lock");
        release.countDown();
        holder.get(30, TimeUnit.SECONDS);
        assertNotNull(second.get(30, TimeUnit.SECONDS).getId());
    }

    /** Unlocks arriving together: exactly one is the day's arrival, the rest stay unlocks. */
    @Test
    void simultaneousFirstUnlocksProduceExactlyOneCheckIn() throws Exception {
        int threads = 6;
        userRecords.save(UserRecord.builder().dn(BOB).build());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<CheckInOutRecord>> saved = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                saved.add(pool.submit(() -> {
                    go.await();
                    return checkInOutService.saveCheckInOutRecord(unlock(BOB));
                }));
            }
            go.countDown();
            for (Future<CheckInOutRecord> record : saved) {
                record.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<CheckInOutEnum> actions = checkIns.findAll().stream().map(CheckInOutRecord::getAction).toList();
        assertEquals(threads, actions.size());
        assertEquals(1, actions.stream().filter(a -> a == CheckInOutEnum.CHECK_IN).count(), actions.toString());
    }
}
