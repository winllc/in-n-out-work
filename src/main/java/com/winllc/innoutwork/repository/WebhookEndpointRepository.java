package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, Long> {

    List<WebhookEndpoint> findAllByOrderByCreatedAtDesc();

    List<WebhookEndpoint> findByDisabledAtIsNull();
}
