package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.RetentionState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RetentionStateRepository extends JpaRepository<RetentionState, Integer> {

    /** The one row, locked until the transaction ends: purge batches on several instances take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RetentionState s where s.id = 1")
    Optional<RetentionState> lockState();
}
