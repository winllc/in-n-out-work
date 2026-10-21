package com.winllc.innoutwork.security;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.support.FullApplicationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The live account check behind API tokens, webhooks and scheduled reports, against a real (in-memory) directory:
 * each way an account can stop being usable, read at once, never from the user cache, and failing closed.
 */
class DirectoryAccountsTest extends FullApplicationTest {

    @Autowired DirectoryAccounts accounts;
    @Autowired LdapService ldapService;
    @Autowired AppUserDetailsService users;

    @AfterEach
    void restore() {
        clearAccountStatus(ADMIN, BOB);
    }

    private static String generalized(Instant at) {
        return DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC).format(at);
    }

    /** Windows FILETIME: 100-nanosecond intervals since 1601. */
    private static String filetime(Instant at) {
        return String.valueOf((at.getEpochSecond() + 11_644_473_600L) * 10_000_000L);
    }

    @Test
    void anActiveAdministratorIsActiveAndAnAdministrator() {
        DirectoryAccounts.Account carol = accounts.check(ADMIN);
        assertTrue(carol.active());
        assertTrue(carol.isAdmin());
        assertTrue(accounts.check(BOB).active());
        assertFalse(accounts.check(BOB).isAdmin());
        assertEquals(DirectoryAccounts.Status.NOT_FOUND, accounts.check("cn=Nobody,ou=Users,dc=winllc,dc=com").status());
        assertEquals(DirectoryAccounts.Status.NOT_FOUND, accounts.check("not a dn").status());
    }

    @Test
    void activeDirectoryDisabledLockedAndExpiredAccounts() {
        setAttribute(BOB, "userAccountControl", "514");
        assertEquals(DirectoryAccounts.Status.DISABLED, accounts.check(BOB).status());
        setAttribute(BOB, "userAccountControl", "512");
        assertEquals(DirectoryAccounts.Status.ACTIVE, accounts.check(BOB).status());

        // lockoutTime stays set after an automatic unlock: it is not what decides; the computed bit is.
        setAttribute(BOB, "lockoutTime", "133000000000000000");
        assertEquals(DirectoryAccounts.Status.ACTIVE, accounts.check(BOB).status());
        setAttribute(BOB, "msDS-User-Account-Control-Computed", "16");
        assertEquals(DirectoryAccounts.Status.LOCKED, accounts.check(BOB).status());
        setAttribute(BOB, "msDS-User-Account-Control-Computed", "0");

        setAttribute(BOB, "accountExpires", filetime(Instant.now().minus(Duration.ofDays(1))));
        assertEquals(DirectoryAccounts.Status.EXPIRED, accounts.check(BOB).status());
        setAttribute(BOB, "accountExpires", filetime(Instant.now().plus(Duration.ofDays(30))));
        assertEquals(DirectoryAccounts.Status.ACTIVE, accounts.check(BOB).status());
        for (String never : new String[]{"0", "9223372036854775807"}) {
            setAttribute(BOB, "accountExpires", never);
            assertEquals(DirectoryAccounts.Status.ACTIVE, accounts.check(BOB).status(), never);
        }
    }

    @Test
    void openLdapAnd389LockedAndDisabledAccounts() {
        setAttribute(BOB, "nsAccountLock", "true");
        assertEquals(DirectoryAccounts.Status.DISABLED, accounts.check(BOB).status());
        removeAttribute(BOB, "nsAccountLock");

        setAttribute(BOB, "pwdAccountLockedTime", "000001010000Z");
        assertEquals(DirectoryAccounts.Status.LOCKED, accounts.check(BOB).status(), "permanently locked");
        setAttribute(BOB, "pwdAccountLockedTime", generalized(Instant.now().minus(Duration.ofMinutes(2))));
        assertEquals(DirectoryAccounts.Status.LOCKED, accounts.check(BOB).status(), "within the lockout duration");
        setAttribute(BOB, "pwdAccountLockedTime", generalized(Instant.now().minus(Duration.ofHours(2))));
        assertEquals(DirectoryAccounts.Status.ACTIVE, accounts.check(BOB).status(), "the lockout has run out");
    }

    @Test
    void anAccountMustStillMatchTheConfiguredFilter() {
        ApplicationProperties settings = new ApplicationProperties();
        settings.getLdap().getAccountStatus().setFilter("(employeeType=FT)");
        settings.getLdap().getAccountStatus().setCacheSeconds(0);
        DirectoryAccounts filtered = new DirectoryAccounts(ldapService, users, settings);
        assertTrue(filtered.check(ADMIN).active(), "Carol is FT");
        assertEquals(DirectoryAccounts.Status.NOT_FOUND, filtered.check(BOB).status(), "Bob is PT");
    }

    @Test
    void anUnreadableDirectoryRefuses() {
        LdapService broken = mock(LdapService.class);
        when(broken.lookupLiveUser(any(), any(), any()))
                .thenThrow(new org.springframework.ldap.CommunicationException(new javax.naming.CommunicationException("down")));
        DirectoryAccounts failing = new DirectoryAccounts(broken, users, new ApplicationProperties());
        DirectoryAccounts.Account account = failing.check(ADMIN);
        assertEquals(DirectoryAccounts.Status.UNAVAILABLE, account.status());
        assertFalse(account.active());
        assertFalse(account.isAdmin());
    }

    @Test
    void onlyUsableAccountsAreRememberedAndOnlyBriefly() {
        ApplicationProperties settings = new ApplicationProperties();
        settings.getLdap().getAccountStatus().setCacheSeconds(60);
        DirectoryAccounts cached = new DirectoryAccounts(ldapService, users, settings, Clock.systemUTC());

        setAttribute(BOB, "userAccountControl", "514");
        assertEquals(DirectoryAccounts.Status.DISABLED, cached.check(BOB).status());
        setAttribute(BOB, "userAccountControl", "512");
        assertTrue(cached.check(BOB).active(), "a refusal is never remembered");
        setAttribute(BOB, "userAccountControl", "514");
        assertTrue(cached.check(BOB).active(), "a usable account is remembered, up to a minute");
    }

    @Test
    void statusRulesOnTheirOwn() {
        DirectoryAccounts rules = new DirectoryAccounts(ldapService, users, new ApplicationProperties());
        assertEquals(DirectoryAccounts.Status.ACTIVE, rules.status(Map.of()));
        assertEquals(DirectoryAccounts.Status.DISABLED, rules.status(Map.of("useraccountcontrol", "66050")));
        assertEquals(DirectoryAccounts.Status.LOCKED, rules.status(Map.of("pwdaccountlockedtime", "garbage")),
                "an unreadable lock time locks");
    }
}
