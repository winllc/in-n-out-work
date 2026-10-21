package com.winllc.innoutwork.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A SAML assertion already used to sign in, kept until it expires so it cannot be replayed. */
@Data
@NoArgsConstructor
@Entity
@Table(name = "sso_assertion_seen")
public class SsoAssertionSeen {

    @Id
    @Column(length = 256)
    private String assertionId;

    @Column(nullable = false)
    private Instant expiresAt;

    public SsoAssertionSeen(String assertionId, Instant expiresAt) {
        this.assertionId = assertionId;
        this.expiresAt = expiresAt;
    }
}
