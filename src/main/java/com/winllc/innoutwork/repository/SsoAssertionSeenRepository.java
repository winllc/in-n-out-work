package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.SsoAssertionSeen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Repository
public interface SsoAssertionSeenRepository extends JpaRepository<SsoAssertionSeen, String> {

    /** Records a first use; a second use of the same id fails on the primary key, whichever replica sees it. */
    @Modifying
    @Transactional
    @Query(value = "insert into sso_assertion_seen (assertion_id, expires_at) values (:id, :expiresAt)",
            nativeQuery = true)
    int insertFirstUse(@Param("id") String assertionId, @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Transactional
    @Query("delete from SsoAssertionSeen a where a.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
