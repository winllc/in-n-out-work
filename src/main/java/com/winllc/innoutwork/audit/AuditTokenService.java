package com.winllc.innoutwork.audit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Pull-API tokens. A token is {@code ensat_} plus 32 random bytes, base64url; it is shown once when created and
 * stored only as its SHA-256. Its only scope is reading the audit trail. Creating and revoking one are audited;
 * using one only updates its last-used time (auditing every pull would feed on itself).
 */
@Service
public class AuditTokenService {

    public static final String PREFIX = "ensat_";

    private final AuditApiTokenRepository tokens;
    private final AuditLog audit;
    private final Clock clock;

    @Autowired
    public AuditTokenService(AuditApiTokenRepository tokens, AuditLog audit) {
        this(tokens, audit, Clock.systemUTC());
    }

    AuditTokenService(AuditApiTokenRepository tokens, AuditLog audit, Clock clock) {
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
    }

    /** A token just created: the secret is in {@code token}, here and nowhere else. */
    public record Created(AuditApiToken record, String token) {
    }

    public Created create(String name, String createdBy) {
        String cleanName = name == null || name.isBlank() ? "SIEM" : name.trim();
        if (cleanName.length() > 200) {
            cleanName = cleanName.substring(0, 200);
        }
        String token = TokenSecrets.generate(PREFIX);

        AuditApiToken record = new AuditApiToken();
        record.setName(cleanName);
        record.setTokenHash(TokenSecrets.hash(token));
        record.setTokenPrefix(TokenSecrets.prefix(token, PREFIX));
        record.setCreatedBy(createdBy);
        record.setCreatedAt(clock.instant());
        AuditApiToken saved = audit.inTransaction(() -> {
            AuditApiToken s = tokens.save(record);
            audit.record(AuditEntry.of(AuditEntry.Category.AUDIT, "audit.token-created")
                    .severity(AuditEntry.Severity.NOTICE)
                    .target("audit-token", String.valueOf(s.getId()), s.getName())
                    .detail("tokenName", s.getName())
                    .detail("tokenPrefix", s.getTokenPrefix())
                    .detail("scope", "audit:read"));
            return s;
        });
        return new Created(saved, token);
    }

    public Optional<AuditApiToken> revoke(long id, String revokedBy) {
        return audit.inTransaction(() -> tokens.findById(id)
                .filter(AuditApiToken::isActive)
                .map(t -> {
                    t.setRevokedAt(clock.instant());
                    t.setRevokedBy(revokedBy);
                    AuditApiToken s = tokens.save(t);
                    audit.record(AuditEntry.of(AuditEntry.Category.AUDIT, "audit.token-revoked")
                            .severity(AuditEntry.Severity.NOTICE)
                            .target("audit-token", String.valueOf(s.getId()), s.getName())
                            .detail("tokenName", s.getName())
                            .detail("tokenPrefix", s.getTokenPrefix()));
                    return s;
                }));
    }

    public List<AuditApiToken> list() {
        return tokens.findAllByOrderByCreatedAtDesc();
    }

    /** The active token presented, with its last-used time updated; empty for an unknown or revoked one. */
    public Optional<AuditApiToken> authenticate(String presented) {
        if (presented == null || !presented.startsWith(PREFIX) || presented.length() > 200) {
            return Optional.empty();
        }
        Optional<AuditApiToken> found = tokens.findByTokenHash(TokenSecrets.hash(presented))
                .filter(AuditApiToken::isActive);
        found.ifPresent(t -> {
            Instant now = clock.instant();
            // At most once a minute: a SIEM polling every few seconds should not write every time.
            if (t.getLastUsedAt() == null || t.getLastUsedAt().isBefore(now.minusSeconds(60))) {
                t.setLastUsedAt(now);
                tokens.save(t);
            }
        });
        return found;
    }
}
