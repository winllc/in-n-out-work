package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.SsoSamlRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Repository
public interface SsoSamlRequestRepository extends JpaRepository<SsoSamlRequest, String> {

    /** Removes the request; 1 when this caller removed it (it is used once). */
    @Modifying
    @Transactional
    @Query("delete from SsoSamlRequest r where r.relayState = :relayState")
    int deleteByRelayState(@Param("relayState") String relayState);

    @Modifying
    @Transactional
    @Query("delete from SsoSamlRequest r where r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
