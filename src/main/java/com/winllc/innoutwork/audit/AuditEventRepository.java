package com.winllc.innoutwork.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuditEventRepository extends JpaRepository<AuditEventRecord, Long>,
        JpaSpecificationExecutor<AuditEventRecord> {

    List<AuditEventRecord> findBySeqGreaterThanOrderBySeqAsc(long seq, Pageable page);

    Optional<AuditEventRecord> findFirstByOrderBySeqAsc();

    Optional<AuditEventRecord> findFirstByOrderBySeqDesc();

    @Query("select distinct e.action from AuditEventRecord e")
    List<String> findDistinctActions();

    /** The newest seq that is older than {@code before} and no later than {@code maxSeq}. */
    @Query("select max(e.seq) from AuditEventRecord e where e.time < :before and e.seq <= :maxSeq")
    Long findLastSeqBefore(@Param("before") Instant before, @Param("maxSeq") long maxSeq);

    /** Retention pruning: the only delete there is. */
    @Modifying
    @Query("delete from AuditEventRecord e where e.seq <= :seq")
    int deleteUpTo(@Param("seq") long seq);
}
