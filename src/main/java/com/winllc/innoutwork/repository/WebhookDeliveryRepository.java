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
            + " order by d.nextAttemptAt, d.id")
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
}
