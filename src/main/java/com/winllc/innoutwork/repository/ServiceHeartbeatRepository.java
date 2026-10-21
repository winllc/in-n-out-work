package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.ServiceHeartbeat;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ServiceHeartbeatRepository extends JpaRepository<ServiceHeartbeat, Integer> {

    /** The one row, locked until the transaction ends, so instances beating at once take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from ServiceHeartbeat h where h.id = 1")
    Optional<ServiceHeartbeat> lockHeartbeat();

    /** The database's clock: every instance's beats are timed by the same one, so skew between pods fakes no outage. */
    @Query(value = "select current_timestamp", nativeQuery = true)
    Object databaseNow();
}
