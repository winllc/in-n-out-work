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

    @Query("select d from WebhookDelivery d where d.state = :state and d.nextAttemptAt <= :now"
            + " and (d.claimedUntil is null or d.claimedUntil < :now) order by d.nextAttemptAt, d.id")
    List<WebhookDelivery> findDue(@Param("state") WebhookDelivery.State state, @Param("now") Instant now,
                                  Pageable page);

    List<WebhookDelivery> findByEndpointIdOrderByCreatedAtDescIdDesc(Long endpointId, Pageable page);

    long countByEndpointIdAndState(Long endpointId, WebhookDelivery.State state);

    @Modifying
    @Query("delete from WebhookDelivery d where d.state <> :pending and d.createdAt < :before")
    int deleteFinishedBefore(@Param("pending") WebhookDelivery.State pending, @Param("before") Instant before);

    @Modifying
    @Query("delete from WebhookDelivery d where d.endpointId = :endpointId")
    int deleteByEndpoint(@Param("endpointId") Long endpointId);

    /** Claims a due delivery until {@code until}: 1 if this caller has it now, 0 if another has it or it is done. */
    @Modifying
    @Query("update WebhookDelivery d set d.claimedUntil = :until where d.id = :id and d.state = :pending"
            + " and (d.claimedUntil is null or d.claimedUntil < :now)")
    int claim(@Param("id") Long id, @Param("pending") WebhookDelivery.State pending, @Param("now") Instant now,
              @Param("until") Instant until);
}
