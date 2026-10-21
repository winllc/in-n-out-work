package com.winllc.innoutwork.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Whether the person an API token, a webhook or a scheduled report acts for may still act, read live from the
 * directory: never from the user cache, which keeps an entry for up to two hours. Their entry must still exist, match
 * the user filter and application.ldap.account-status.filter, and not be disabled, locked out or expired
 * ({@link ApplicationProperties.AccountStatus}). A directory that cannot be read refuses (fails closed). A usable
 * account is remembered for at most application.ldap.account-status.cache-seconds (60); refusals are never
 * remembered.
 */
@Service
public class DirectoryAccounts {

    private static final Logger log = LoggerFactory.getLogger(DirectoryAccounts.class);

    /** The attributes the built-in checks read, as they are named in the directories that have them. */
    static final List<String> STATUS_ATTRIBUTES = List.of("userAccountControl", "msDS-User-Account-Control-Computed",
            "accountExpires", "nsAccountLock", "pwdAccountLockedTime");

    /** OpenLDAP ppolicy's value for an account locked until an administrator unlocks it. */
    static final String PERMANENTLY_LOCKED = "000001010000Z";

    public enum Status {
        ACTIVE, NOT_FOUND, DISABLED, LOCKED, EXPIRED, UNAVAILABLE;

        public String reason() {
            return switch (this) {
                case ACTIVE -> "active";
                case NOT_FOUND -> "not in the directory, or no longer matching its filters";
                case DISABLED -> "disabled in the directory";
                case LOCKED -> "locked out in the directory";
                case EXPIRED -> "expired in the directory";
                case UNAVAILABLE -> "the directory could not be read";
            };
        }
    }

    /** What the directory says of an account: its status, and when active its details and roles now. */
    public record Account(Status status, UserDetails details) {
        public boolean active() {
            return status == Status.ACTIVE && details != null;
        }

        public boolean isAdmin() {
            return active() && details.getAuthorities().stream()
                    .anyMatch(a -> UserRoleEnum.ADMIN.name().equals(a.getAuthority()));
        }
    }

    private final LdapService ldapService;
    private final AppUserDetailsService users;
    private final ApplicationProperties.AccountStatus settings;
    private final Cache<String, Account> active;
    private final Clock clock;

    @Autowired
    public DirectoryAccounts(LdapService ldapService, AppUserDetailsService users, ApplicationProperties properties) {
        this(ldapService, users, properties, Clock.systemUTC());
    }

    DirectoryAccounts(LdapService ldapService, AppUserDetailsService users, ApplicationProperties properties,
                      Clock clock) {
        this.ldapService = ldapService;
        this.users = users;
        this.settings = properties.getLdap().getAccountStatus();
        this.clock = clock;
        this.active = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(Math.max(0, Math.min(60, settings.getCacheSeconds()))))
                .maximumSize(settings.getCacheSeconds() <= 0 ? 0 : 10_000)
                .build();
    }

    /** {@code dn}'s account now. */
    public Account check(String dn) {
        if (dn == null || dn.isBlank()) {
            return new Account(Status.NOT_FOUND, null);
        }
        String key = dn.toLowerCase(Locale.ROOT);
        Account remembered = active.getIfPresent(key);
        if (remembered != null) {
            return remembered;
        }
        Account account = read(dn);
        if (account.active()) {
            active.put(key, account);
        } else {
            log.info("Account {} refused for an integration: {}", LogSafe.of(dn), account.status().reason());
        }
        return account;
    }

    private Account read(String dn) {
        LdapDn ldapDn;
        try {
            ldapDn = new LdapDn(dn);
        } catch (IllegalArgumentException e) {
            return new Account(Status.NOT_FOUND, null);
        }
        java.util.Optional<LdapService.LiveUser> entry;
        try {
            entry = ldapService.lookupLiveUser(ldapDn, settings.getFilter(),
                    settings.isBuiltInChecks() ? STATUS_ATTRIBUTES : List.of());
        } catch (RuntimeException e) {
            log.warn("Could not read {} from the directory: {}", LogSafe.of(dn), LogSafe.of(e.getMessage()));
            return new Account(Status.UNAVAILABLE, null);
        }
        if (entry.isEmpty()) {
            return new Account(Status.NOT_FOUND, null);
        }
        Status status = settings.isBuiltInChecks() ? status(entry.get().status()) : Status.ACTIVE;
        if (status != Status.ACTIVE) {
            return new Account(status, null);
        }
        try {
            return new Account(Status.ACTIVE, users.detailsFor(ldapDn, entry.get().user()));
        } catch (RuntimeException e) {
            log.warn("Could not load the roles of {}: {}", LogSafe.of(dn), LogSafe.of(e.getMessage()));
            return new Account(Status.UNAVAILABLE, null);
        }
    }

    /** The built-in checks, on the status attributes read (lower-cased names). */
    Status status(Map<String, String> attributes) {
        long uac = number(attributes.get("useraccountcontrol"));
        if ((uac & 0x2) != 0) {
            return Status.DISABLED;
        }
        if ("true".equalsIgnoreCase(trim(attributes.get("nsaccountlock")))) {
            return Status.DISABLED;
        }
        long computed = number(attributes.get("msds-user-account-control-computed"));
        if ((computed & 0x10) != 0) {
            return Status.LOCKED;
        }
        String lockedAt = trim(attributes.get("pwdaccountlockedtime"));
        if (lockedAt != null && !lockedAt.isEmpty()) {
            if (PERMANENTLY_LOCKED.equals(lockedAt)) {
                return Status.LOCKED;
            }
            Instant at = generalizedTime(lockedAt);
            // Unreadable, or within the policy's lockout duration: locked.
            if (at == null || !clock.instant().isAfter(at.plus(settings.getLockoutDuration()))) {
                return Status.LOCKED;
            }
        }
        String expires = trim(attributes.get("accountexpires"));
        if (expires != null && !expires.isEmpty()) {
            long value = number(expires);
            if (value != 0 && value != Long.MAX_VALUE && value > 0) {
                // 100-nanosecond intervals since 1601-01-01.
                Instant at = Instant.ofEpochSecond(value / 10_000_000L - 11_644_473_600L);
                if (!at.isAfter(clock.instant())) {
                    return Status.EXPIRED;
                }
            }
        }
        return Status.ACTIVE;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static long number(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** yyyyMMddHHmmss[.fraction]Z, as LDAP writes times; null when it cannot be read. */
    static Instant generalizedTime(String value) {
        String v = value.trim();
        if (!v.endsWith("Z") || v.length() < 15) {
            return null;
        }
        try {
            return LocalDateTime.parse(v.substring(0, 14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    .toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
