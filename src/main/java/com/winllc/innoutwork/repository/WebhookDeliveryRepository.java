package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.WebhookDelivery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    /**
     * The endpoints with deliveries due now, not paused, oldest due first: each run then takes a few of each, so one
     * endpoint's backlog never fills the run.
     */
    @Query("select d.endpointId from WebhookDelivery d where d.state = :pending and d.nextAttemptAt <= :now"
            + " and (d.claimedUntil is null or d.claimedUntil < :now)"
            + " and d.endpointId in (select e.id from WebhookEndpoint e where e.pausedUntil is null or e.pausedUntil <= :now)"
            + " group by d.endpointId order by min(d.nextAttemptAt)")
    List<Long> findEndpointsWithDue(@Param("pending") WebhookDelivery.State pending, @Param("now") Instant now,
                                    Pageable page);

    /** One endpoint's deliveries due now, oldest first. */
    @Query("select d from WebhookDelivery d where d.endpointId = :endpointId and d.state = :pending"
            + " and d.nextAttemptAt <= :now and (d.claimedUntil is null or d.claimedUntil < :now)"
            + " order by d.nextAttemptAt, d.id")
    List<WebhookDelivery> findDueForEndpoint(@Param("endpointId") Long endpointId,
                                             @Param("pending") WebhookDelivery.State pending,
                                             @Param("now") Instant now, Pageable page);

    List<WebhookDelivery> findByEndpointIdOrderByCreatedAtDescIdDesc(Long endpointId, Pageable page);

    long countByEndpointIdAndState(Long endpointId, WebhookDelivery.State state);

    @Modifying
    @Query("delete from WebhookDelivery d where d.state <> :pending and d.createdAt < :before")
    int deleteFinishedBefore(@Param("pending") WebhookDelivery.State pending, @Param("before") Instant before);

    @Modifying
    @Query("delete from WebhookDelivery d where d.endpointId = :endpointId")
    int deleteByEndpoint(@Param("endpointId") Long endpointId);

    /**
     * Claims a due delivery until {@code until}, as it was read ({@code attempts} tries so far): 1 if this caller has
     * it now; 0 if another has it, it is done, it was rescheduled, or another sender has tried it since.
     */
    @Modifying
    @Query("update WebhookDelivery d set d.claimedUntil = :until where d.id = :id and d.state = :pending"
            + " and d.attempts = :attempts and d.nextAttemptAt <= :now"
            + " and (d.claimedUntil is null or d.claimedUntil < :now)")
    int claim(@Param("id") Long id, @Param("pending") WebhookDelivery.State pending, @Param("attempts") int attempts,
              @Param("now") Instant now, @Param("until") Instant until);

    /**
     * Records a try's result on the row as it was when claimed ({@code attempts} tries before): never over a newer
     * one, so a stale copy cannot lower its count of tries. Ends the claim. 1 when recorded.
     */
    @Modifying
    @Query("update WebhookDelivery d set d.attempts = d.attempts + 1, d.state = :state, d.lastAttemptAt = :at,"
            + " d.lastStatus = :status, d.lastError = :error, d.nextAttemptAt = :next, d.deliveredAt = :delivered,"
            + " d.claimedUntil = null where d.id = :id and d.attempts = :attempts")
    int recordResult(@Param("id") Long id, @Param("attempts") int attempts,
                     @Param("state") WebhookDelivery.State state, @Param("at") Instant at,
                     @Param("status") Integer status, @Param("error") String error, @Param("next") Instant next,
                     @Param("delivered") Instant delivered);

    /** Gives a claimed delivery back untried, if it is still as it was claimed. */
    @Modifying
    @Query("update WebhookDelivery d set d.claimedUntil = null where d.id = :id and d.attempts = :attempts")
    int release(@Param("id") Long id, @Param("attempts") int attempts);
}
