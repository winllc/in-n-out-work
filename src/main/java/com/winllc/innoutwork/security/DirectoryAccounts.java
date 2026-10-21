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
 *
 * <p>Locked out, where any doubt means locked:
 * <ul>
 *   <li><b>Active Directory:</b> msDS-User-Account-Control-Computed bit 0x10 when the server returns it. Otherwise a
 *       non-zero lockoutTime is locked unless the domain's lockoutDuration (read from the domain's root entry) is known,
 *       non-zero and not "never", and has passed since then.</li>
 *   <li><b>OpenLDAP ppolicy:</b> a pwdAccountLockedTime is locked unless the pwdLockoutDuration of the account's
 *       policy (its pwdPolicySubentry) is known, non-zero, and has passed. No pwdPolicySubentry (the default policy,
 *       which cannot be read), an unreadable policy or a duration of 0 mean locked; for the first, an operator may
 *       set application.ldap.account-status.lockout-duration as the default policy's duration.</li>
 *   <li><b>389 Directory Server:</b> an accountUnlockTime in the future, or 19700101000000Z (until unlocked).</li>
 * </ul>
 */
@Service
public class DirectoryAccounts {

    private static final Logger log = LoggerFactory.getLogger(DirectoryAccounts.class);

    /** The attributes the built-in checks read, as they are named in the directories that have them. */
    static final List<String> STATUS_ATTRIBUTES = List.of("userAccountControl", "msDS-User-Account-Control-Computed",
            "lockoutTime", "accountExpires", "nsAccountLock", "accountUnlockTime", "pwdAccountLockedTime",
            "pwdPolicySubentry", "objectClass");

    /** 389 Directory Server's accountUnlockTime for an account locked until an administrator unlocks it. */
    static final String LOCKED_UNTIL_UNLOCKED_389 = "19700101000000Z";

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
        Status status;
        try {
            status = settings.isBuiltInChecks() ? status(entry.get().status(), ldapDn) : Status.ACTIVE;
        } catch (RuntimeException e) {
            // A policy that cannot be read, or a status attribute that cannot be understood: refused, never active.
            log.warn("Could not establish whether {} is usable: {}", LogSafe.of(dn), LogSafe.of(e.getMessage()));
            return new Account(Status.UNAVAILABLE, null);
        }
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

    /** The built-in checks on the status attributes read (lower-cased names), with no policy to read. */
    Status status(Map<String, String> attributes) {
        return status(attributes, null);
    }

    /**
     * The built-in checks, on the status attributes read (lower-cased names). Lockout policies are read from the
     * directory when needed; a read that fails throws, and the caller refuses.
     */
    Status status(Map<String, String> attributes, LdapDn dn) {
        if (activeDirectory(attributes) && attributes.get("useraccountcontrol") == null) {
            throw new IllegalStateException("its entry came back without userAccountControl: the bind account must be"
                    + " able to read it (and lockoutTime, accountExpires, msDS-User-Account-Control-Computed)");
        }
        long uac = number(attributes.get("useraccountcontrol"));
        if ((uac & 0x2) != 0) {
            return Status.DISABLED;
        }
        if ("true".equalsIgnoreCase(trim(attributes.get("nsaccountlock")))) {
            return Status.DISABLED;
        }
        if (adLocked(attributes, dn) || openLdapLocked(attributes) || ds389Locked(attributes)) {
            return Status.LOCKED;
        }
        String expires = trim(attributes.get("accountexpires"));
        if (expires != null && !expires.isEmpty()) {
            long value = number(expires);
            if (value != 0 && value != Long.MAX_VALUE && value > 0) {
                Instant at = filetime(value);
                if (!at.isAfter(clock.instant())) {
                    return Status.EXPIRED;
                }
            }
        }
        return Status.ACTIVE;
    }

    /** Active Directory: see the class comment. */
    private boolean adLocked(Map<String, String> attributes, LdapDn dn) {
        String computed = trim(attributes.get("msds-user-account-control-computed"));
        if (computed != null && !computed.isEmpty()) {
            return (number(computed) & 0x10) != 0;
        }
        long lockoutTime = number(attributes.get("lockouttime"));
        if (lockoutTime == 0) {
            return false;
        }
        Duration duration = adLockoutDuration(dn);
        return duration == null || !clock.instant().isAfter(filetime(lockoutTime).plus(duration));
    }

    /**
     * The domain's lockoutDuration (a negative count of 100-nanosecond intervals, on the domain's root entry): null
     * when it cannot be found, is 0 (until an administrator unlocks) or is "never" (Long.MIN_VALUE).
     */
    private Duration adLockoutDuration(LdapDn dn) {
        String domain = domainOf(dn);
        if (domain == null) {
            return null;
        }
        String value = ldapService.readAttributes(domain, List.of("lockoutDuration"))
                .map(a -> a.get("lockoutduration")).orElse(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        long intervals;
        try {
            intervals = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (intervals == 0 || intervals == Long.MIN_VALUE) {
            return null;
        }
        return Duration.ofNanos(Math.abs(intervals) / 10_000_000L * 1_000_000_000L
                + Math.abs(intervals) % 10_000_000L * 100);
    }

    /** The DC= components of {@code dn}: the domain's root entry. */
    static String domainOf(LdapDn dn) {
        if (dn == null) {
            return null;
        }
        try {
            javax.naming.ldap.LdapName name = new javax.naming.ldap.LdapName(dn.toString());
            List<String> dcs = new java.util.ArrayList<>();
            for (javax.naming.ldap.Rdn rdn : name.getRdns()) {
                if (rdn.getType().equalsIgnoreCase("dc")) {
                    dcs.add(0, rdn.toString());
                }
            }
            return dcs.isEmpty() ? null : String.join(",", dcs);
        } catch (javax.naming.InvalidNameException e) {
            return null;
        }
    }

    /** OpenLDAP ppolicy: see the class comment. */
    private boolean openLdapLocked(Map<String, String> attributes) {
        String lockedAt = trim(attributes.get("pwdaccountlockedtime"));
        if (lockedAt == null || lockedAt.isEmpty()) {
            return false;
        }
        if (PERMANENTLY_LOCKED.equals(lockedAt)) {
            return true;
        }
        Instant at = generalizedTime(lockedAt);
        if (at == null) {
            return true;
        }
        Duration duration;
        String policy = trim(attributes.get("pwdpolicysubentry"));
        if (policy == null || policy.isEmpty()) {
            duration = settings.getLockoutDuration();
        } else {
            String value = ldapService.readAttributes(policy, List.of("pwdLockoutDuration"))
                    .map(a -> a.get("pwdlockoutduration")).orElse(null);
            duration = null;
            if (value != null && !value.isBlank()) {
                try {
                    duration = Duration.ofSeconds(Long.parseLong(value.trim()));
                } catch (NumberFormatException ignored) {
                    // unreadable: locked
                }
            }
        }
        return duration == null || duration.isZero() || duration.isNegative()
                || !clock.instant().isAfter(at.plus(duration));
    }

    /** 389 Directory Server: see the class comment. */
    private boolean ds389Locked(Map<String, String> attributes) {
        String unlock = trim(attributes.get("accountunlocktime"));
        if (unlock == null || unlock.isEmpty()) {
            return false;
        }
        if (LOCKED_UNTIL_UNLOCKED_389.equals(unlock)) {
            return true;
        }
        Instant at = generalizedTime(unlock);
        return at == null || at.isAfter(clock.instant());
    }

    /** Windows FILETIME: 100-nanosecond intervals since 1601-01-01. */
    private static Instant filetime(long value) {
        return Instant.ofEpochSecond(value / 10_000_000L - 11_644_473_600L);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** A number attribute: 0 when absent; a value that is not a number refuses (throws), never reads as 0. */
    private static long number(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("an account status attribute is not a number: " + s);
        }
    }

    /** Whether to treat the entry as Active Directory's: as configured, or (auto) one with the objectClass user. */
    private boolean activeDirectory(Map<String, String> attributes) {
        String setting = settings.getActiveDirectory() == null ? "auto" : settings.getActiveDirectory().trim();
        if (setting.equalsIgnoreCase("true")) {
            return true;
        }
        if (setting.equalsIgnoreCase("false")) {
            return false;
        }
        String classes = attributes.get("objectclass");
        if (classes == null) {
            return false;
        }
        for (String c : classes.split("\\s+")) {
            if (c.equalsIgnoreCase("user")) {
                return true;
            }
        }
        return false;
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
