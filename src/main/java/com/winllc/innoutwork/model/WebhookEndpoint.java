package com.winllc.innoutwork.model;

import com.winllc.innoutwork.webhook.WebhookEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.Set;

/**
 * Where signed webhooks go (RR-14): an HTTPS URL, the events it receives, and its signing secret, encrypted
 * (webhook.SecretBox). Created by an administrator, who it acts for: while they are no longer one, nothing is sent.
 * Schema: V9__public_api.sql.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "secretCiphertext")
@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 2000)
    private String url;

    /** {@link WebhookEventType} ids, space separated. */
    @Column(nullable = false, length = 200)
    private String events;

    @Column(name = "secret_ciphertext", nullable = false, length = 400)
    private String secretCiphertext;

    /** The first characters of the secret, shown so an administrator can tell secrets apart. */
    @Column(name = "secret_prefix", nullable = false, length = 16)
    private String secretPrefix;

    @Column(name = "owner_dn", nullable = false, length = 1000)
    private String ownerDn;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "disabled_by", length = 1000)
    private String disabledBy;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    public Set<WebhookEventType> getEventSet() {
        return WebhookEventType.parse(events);
    }

    public boolean isEnabled() {
        return disabledAt == null;
    }
}
