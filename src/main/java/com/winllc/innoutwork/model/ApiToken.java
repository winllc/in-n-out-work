package com.winllc.innoutwork.model;

import com.winllc.innoutwork.api.ApiScope;
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
import lombok.ToString;

import java.time.Instant;
import java.util.Set;

/**
 * A public API token (/api/v1, RR-14): stored only as its SHA-256, with its scopes, an expiry, a rate limit and the
 * administrator it acts for ({@link #ownerDn}), whose rights it never exceeds. Schema: V9__public_api.sql.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "tokenHash")
@Entity
@Table(name = "api_tokens", indexes = {
        @Index(name = "ux_api_tokens_hash", columnList = "token_hash", unique = true)
})
public class ApiToken {

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

    /** {@link ApiScope} ids, space separated. */
    @Column(nullable = false, length = 200)
    private String scopes;

    /** The administrator who created it: the token sees what they may see, now, and no more. */
    @Column(name = "owner_dn", nullable = false, length = 1000)
    private String ownerDn;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Requests a minute; past it, 429. */
    @Column(name = "rate_limit_per_minute", nullable = false)
    private int rateLimitPerMinute;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by", length = 1000)
    private String revokedBy;

    public Set<ApiScope> getScopeSet() {
        return ApiScope.parse(scopes);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /** Neither revoked nor expired at {@code now}. */
    public boolean isUsable(Instant now) {
        return !isRevoked() && !isExpired(now);
    }

    /** For the page: usable now. */
    public boolean isActive() {
        return isUsable(Instant.now());
    }
}
