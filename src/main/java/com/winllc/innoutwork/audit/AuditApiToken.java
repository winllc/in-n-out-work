package com.winllc.innoutwork.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A pull-API token for a SIEM: stored only as its SHA-256; read-only access to the audit trail. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "audit_api_tokens", indexes = {
        @Index(name = "ux_audit_api_tokens_hash", columnList = "token_hash", unique = true)
})
public class AuditApiToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** The first characters of the token, shown so an administrator can tell tokens apart. */
    @Column(name = "token_prefix", nullable = false, length = 16)
    private String tokenPrefix;

    @Column(name = "created_by", length = 1000)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by", length = 1000)
    private String revokedBy;

    public boolean isActive() {
        return revokedAt == null;
    }
}
