package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.UserRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRecordRepository extends JpaRepository<UserRecord, Long>, PagingAndSortingRepository<UserRecord, Long> {

    Optional<UserRecord> findByDnIgnoreCase(String dn);
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
}
