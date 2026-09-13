package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.UserRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRecordRepository extends JpaRepository<UserRecord, Long>, PagingAndSortingRepository<UserRecord, Long> {

    // DN lookups compare lower(dn) so they can use the lower(dn) indexes from
    // db/migrations/001_indexes_and_unique_user_dn.sql. Spring Data's derived ...IgnoreCase methods
    // compare upper(dn), which those indexes cannot serve.
    @Query("select u from UserRecord u where lower(u.dn) = lower(:dn)")
    Optional<UserRecord> findByDnIgnoreCase(@Param("dn") String dn);
    Slice<UserRecord> findAllBy(Pageable pageable);
    List<UserRecord> findByDnLikeIgnoreCase(String dnPattern);

    /**
     * Bulk counterpart to {@link #findByDnIgnoreCase(String)}, for the directory refresh:
     * one query per batch of DNs instead of one per user.
     * <p>
     * DNs are compared lower-cased, matching the case-insensitive lookup used elsewhere,
     * so callers must pass already-lower-cased values.
     */
    @Query("select u from UserRecord u where lower(u.dn) in :dns")
    List<UserRecord> findAllByLowercaseDnIn(@Param("dns") Collection<String> dns);

    /** Every user's DN, without loading the records. */
    /**
     * The user's record, locked until the surrounding transaction ends, so that two status events for the
     * same user are processed one after the other. Requires an active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserRecord u where lower(u.dn) = lower(:dn)")
    Optional<UserRecord> findByDnForUpdate(@Param("dn") String dn);

    @Query("select u.dn from UserRecord u")
    List<String> findAllDns();
}
