package com.winllc.innoutwork.api;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.audit.TokenSecrets;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.model.ApiToken;
import com.winllc.innoutwork.repository.ApiTokenRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.InvalidRequestException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Public API tokens (/api/v1). A token is {@code ensapi_} plus 32 random bytes, base64url: shown once when it is
 * created, stored only as its SHA-256. Only an administrator creates or revokes one, and it acts for them: it reads
 * what they may read now (their roles are loaded again on every call), within its scopes, until it expires or is
 * revoked. Creating and revoking are audited; a refused token is a failed sign-in in the audit trail.
 */
@Service
public class ApiTokenService {

    public static final String PREFIX = "ensapi_";
    static final int MAX_NAME = 200;

    private final ApiTokenRepository tokens;
    private final AuditLog audit;
    private final AccessPolicy accessPolicy;
    private final ApplicationProperties.Api settings;
    private final Clock clock;

    @Autowired
    public ApiTokenService(ApiTokenRepository tokens, AuditLog audit, AccessPolicy accessPolicy,
                           ApplicationProperties properties) {
        this(tokens, audit, accessPolicy, properties, Clock.systemUTC());
    }

    ApiTokenService(ApiTokenRepository tokens, AuditLog audit, AccessPolicy accessPolicy,
                    ApplicationProperties properties, Clock clock) {
        this.tokens = tokens;
        this.audit = audit;
        this.accessPolicy = accessPolicy;
        this.settings = properties.getApi();
        this.clock = clock;
    }

    /** A token just created: the secret is in {@code token}, here and nowhere else. */
    public record Created(ApiToken record, String token) {
        @Override
        public String toString() {
            return "Created[" + record + "]";
        }
    }

    public ApplicationProperties.Api settings() {
        return settings;
    }

    /**
     * Creates a token acting for {@code creator}, who must be an administrator.
     *
     * @param days          how long it is valid, 1 to application.api.max-token-days
     * @param ratePerMinute requests a minute, 1 to application.api.max-rate-per-minute; 0 for the default
     * @throws AccessDeniedException   when {@code creator} is not an administrator
     * @throws InvalidRequestException for no scopes, or a lifetime or rate out of range
     */
    public Created create(String name, Collection<ApiScope> scopes, int days, int ratePerMinute,
                          Authentication creator) {
        if (!accessPolicy.isAdmin(creator)) {
            throw new AccessDeniedException("Only administrators create API tokens");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw InvalidRequestException.badRequest("invalid_scopes", "Choose at least one scope.");
        }
        if (days < 1 || days > settings.getMaxTokenDays()) {
            throw InvalidRequestException.badRequest("invalid_expiry",
                    "A token is valid for 1 to " + settings.getMaxTokenDays() + " days.");
        }
        int rate = ratePerMinute <= 0 ? Math.min(settings.getDefaultRatePerMinute(), settings.getMaxRatePerMinute())
                : ratePerMinute;
        if (rate > settings.getMaxRatePerMinute()) {
            throw InvalidRequestException.badRequest("invalid_rate", "A token may make at most "
                    + settings.getMaxRatePerMinute()
                    + " requests a minute.");
        }
        String cleanName = name == null || name.isBlank() ? "Integration" : name.trim();
        if (cleanName.length() > MAX_NAME) {
            cleanName = cleanName.substring(0, MAX_NAME);
        }
        String token = TokenSecrets.generate(PREFIX);
        Instant now = clock.instant();

        ApiToken record = new ApiToken();
        record.setName(cleanName);
        record.setTokenHash(TokenSecrets.hash(token));
        record.setTokenPrefix(TokenSecrets.prefix(token, PREFIX));
        record.setScopes(ApiScope.join(EnumSet.copyOf(scopes)));
        record.setOwnerDn(creator.getName());
        record.setCreatedAt(now);
        record.setExpiresAt(now.plus(Duration.ofDays(days)));
        record.setRateLimitPerMinute(rate);
        ApiToken saved = audit.inTransaction(() -> {
            ApiToken s = tokens.save(record);
            audit.record(AuditEntry.of(AuditEntry.Category.ACCOUNT, "api.token-created")
                    .severity(AuditEntry.Severity.NOTICE)
                    .target("api-token", String.valueOf(s.getId()), s.getName())
                    .detail("tokenName", s.getName())
                    .detail("tokenPrefix", s.getTokenPrefix())
                    .detail("scopes", s.getScopes())
                    .detail("owner", s.getOwnerDn())
                    .detail("expiresAt", s.getExpiresAt().toString())
                    .detail("rateLimitPerMinute", s.getRateLimitPerMinute()));
            return s;
        });
        return new Created(saved, token);
    }

    /** Revokes token {@code id}: administrators only; it stops working at once. Empty when there is no such active one. */
    public Optional<ApiToken> revoke(long id, Authentication by) {
        if (!accessPolicy.isAdmin(by)) {
            throw new AccessDeniedException("Only administrators revoke API tokens");
        }
        return audit.inTransaction(() -> tokens.findById(id)
                .filter(t -> !t.isRevoked())
                .map(t -> {
                    t.setRevokedAt(clock.instant());
                    t.setRevokedBy(by.getName());
                    ApiToken s = tokens.save(t);
                    audit.record(AuditEntry.of(AuditEntry.Category.ACCOUNT, "api.token-revoked")
                            .severity(AuditEntry.Severity.NOTICE)
                            .target("api-token", String.valueOf(s.getId()), s.getName())
                            .detail("tokenName", s.getName())
                            .detail("tokenPrefix", s.getTokenPrefix())
                            .detail("owner", s.getOwnerDn()));
                    return s;
                }));
    }

    public List<ApiToken> list() {
        return tokens.findAllByOrderByCreatedAtDesc();
    }

    /** Why a presented token was refused, for the audit trail; never the token itself. */
    public enum Refusal {
        MALFORMED("malformed token"), UNKNOWN("unknown token"), REVOKED("revoked token"), EXPIRED("expired token");

        private final String reason;

        Refusal(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** The outcome of presenting a token: the token, or why it was refused. */
    public record Presented(ApiToken token, Refusal refusal) {
        public boolean accepted() {
            return token != null;
        }
    }

    /** The usable token presented, with its last-used time updated; otherwise why not. */
    public Presented authenticate(String presented) {
        if (presented == null || !presented.startsWith(PREFIX) || presented.length() > 200) {
            return new Presented(null, Refusal.MALFORMED);
        }
        Optional<ApiToken> found = tokens.findByTokenHash(TokenSecrets.hash(presented));
        if (found.isEmpty()) {
            return new Presented(null, Refusal.UNKNOWN);
        }
        ApiToken token = found.get();
        Instant now = clock.instant();
        if (token.isRevoked()) {
            return new Presented(null, Refusal.REVOKED);
        }
        if (token.isExpired(now)) {
            return new Presented(null, Refusal.EXPIRED);
        }
        // At most once a minute: an integration polling every few seconds should not write every time.
        if (token.getLastUsedAt() == null || token.getLastUsedAt().isBefore(now.minusSeconds(60))) {
            token.setLastUsedAt(now);
            tokens.save(token);
        }
        return new Presented(token, null);
    }

    /** The owner's common name, for display. */
    public static String ownerName(ApiToken token) {
        return token.getOwnerDn() == null ? null : LdapDn.cnOf(token.getOwnerDn());
    }
}
