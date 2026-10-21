package com.winllc.innoutwork.security;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.support.FullApplicationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ldap.core.LdapTemplate;

import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;

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

    private static final String DOMAIN = "dc=winllc,dc=com";
    private static final String POLICY = "cn=lockout,ou=Policies,dc=winllc,dc=com";

    /** An OpenLDAP password policy entry with this pwdLockoutDuration (seconds), or none for an empty one. */
    private static void policy(String seconds) {
        LdapTemplate ldap = DIRECTORY.ldapTemplate();
        try {
            ldap.lookup("ou=Policies,dc=winllc,dc=com");
        } catch (RuntimeException e) {
            BasicAttributes ou = new BasicAttributes(true);
            ou.put(new BasicAttribute("objectClass", "organizationalUnit"));
            ou.put(new BasicAttribute("ou", "Policies"));
            ldap.bind("ou=Policies,dc=winllc,dc=com", null, ou);
        }
        try {
            ldap.unbind(POLICY);
        } catch (RuntimeException ignored) {
            // not there
        }
        BasicAttributes entry = new BasicAttributes(true);
        BasicAttribute classes = new BasicAttribute("objectClass");
        classes.add("device");
        classes.add("pwdPolicy");
        entry.put(classes);
        entry.put(new BasicAttribute("cn", "lockout"));
        entry.put(new BasicAttribute("pwdAttribute", "userPassword"));
        if (seconds != null) {
            entry.put(new BasicAttribute("pwdLockoutDuration", seconds));
        }
        ldap.bind(POLICY, null, entry);
    }

    @AfterEach
    void restoreDirectory() {
        removeAttribute(DOMAIN, "lockoutDuration");
        try {
            DIRECTORY.ldapTemplate().unbind(POLICY);
        } catch (RuntimeException ignored) {
            // not there
        }
    }

    @Test
    void activeDirectoryDisabledAndExpiredAccounts() {
        setAttribute(BOB, "userAccountControl", "514");
        assertEquals(DirectoryAccounts.Status.DISABLED, accounts.check(BOB).status());
        setAttribute(BOB, "userAccountControl", "512");
        assertEquals(DirectoryAccounts.Status.ACTIVE, accounts.check(BOB).status());

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
    void activeDirectoryLockoutTheComputedBitDecidesWhenThere() {
        setAttribute(BOB, "msDS-User-Account-Control-Computed", "16");
        assertEquals(DirectoryAccounts.Status.LOCKED, accounts.check(BOB).status());
        // lockoutTime stays set after an automatic unlock; with the computed attribute it does not decide.
        setAttribute(BOB, "msDS-User-Account-Control-Computed", "0");
        setAttribute(BOB, "lockoutTime", filetime(Instant.now().minus(Duration.ofMinutes(1))));
        assertEquals(DirectoryAccounts.Status.ACTIVE, accounts.check(BOB).status());
    }

    @Test
    void activeDirectoryLockoutTimeWithoutTheComputedBitNeedsAKnownElapsedDuration() {
        setAttribute(BOB, "lockoutTime", "0");
        assertEquals(DirectoryAccounts.Status.ACTIVE, accounts.check(BOB).status(), "never locked");

        setAttribute(BOB, "lockoutTime", filetime(Instant.now().minus(Duration.ofHours(2))));
        assertEquals(DirectoryAccounts.Status.LOCKED, accounts.check(BOB).status(), "the domain's duration is unknown");

        setAttribute(DOMAIN, "lockoutDuration", "-18000000000"); // 30 minutes
        assertEquals(DirectoryAccounts.Status.ACTIVE, accounts.check(BOB).status(), "unlocked automatically");
        setAttribute(BOB, "lockoutTime", filetime(Instant.now().minus(Duration.ofMinutes(5))));
        assertEquals(DirectoryAccounts.Status.LOCKED, accounts.check(BOB).status(), "within the 30 minutes");

        setAttribute(BOB, "lockoutTime", filetime(Instant.now().minus(Duration.ofDays(2))));
        setAttribute(DOMAIN, "lockoutDuration", "0");
        assertEquals(DirectoryAccounts.Status.LOCKED, accounts.check(BOB).status(), "0: until an administrator unlocks");
        setAttribute(DOMAIN, "lockoutDuration", "-9223372036854775808");
        assertEquals(DirectoryAccounts.Status.LOCKED, accounts.check(BOB).status(), "never unlocks by itself");
    }

    @Test
    void openLdapPermanentAndDefaultPolicyLocksAreLocked() {
        setAttribute(BOB, "nsAccountLock", "true");
        assertEquals(DirectoryAccounts.Status.DISABLED, accounts.check(BOB).status());
        removeAttribute(BOB, "nsAccountLock");

        setAttribute(BOB, "pwdAccountLockedTime", "000001010000Z");
        assertEquals(DirectoryAccounts.Status.LOCKED, accounts.check(BOB).status(), "permanently locked");
        // No pwdPolicySubentry: the default policy, whose duration cannot be read.
        setAttribute(BOB, "pwdAccountLockedTime", generalized(Instant.now().minus(Duration.ofDays(2))));
        assertEquals(DirectoryAccounts.Status.LOCKED, accounts.check(BOB).status(), "default policy: locked");

        ApplicationProperties settings = new ApplicationProperties();
        settings.getLdap().getAccountStatus().setCacheSeconds(0);
        settings.getLdap().getAccountStatus().setLockoutDuration(Duration.ofMinutes(15));
        DirectoryAccounts fallback = new DirectoryAccounts(ldapService, users, settings);
        assertEquals(DirectoryAccounts.Status.ACTIVE, fallback.check(BOB).status(), "the configured fallback has passed");
        setAttribute(BOB, "pwdAccountLockedTime", generalized(Instant.now().minus(Duration.ofMinutes(2))));
        assertEquals(DirectoryAccounts.Status.LOCKED, fallback.check(BOB).status(), "within the fallback");
    }

    @Test
    void openLdapLocksFollowTheAccountsPolicy() {
        setAttribute(BOB, "pwdPolicySubentry", POLICY);
        setAttribute(BOB, "pwdAccountLockedTime", generalized(Instant.now().minus(Duration.ofHours(2))));
        assertEquals(DirectoryAccounts.Status.LOCKED, accounts.check(BOB).status(), "the policy entry is missing");

        policy("300");
        assertEquals(DirectoryAccounts.Status.ACTIVE, accounts.check(BOB).status(), "unlocked automatically");
        setAttribute(BOB, "pwdAccountLockedTime", generalized(Instant.now().minus(Duration.ofMinutes(1))));
        assertEquals(DirectoryAccounts.Status.LOCKED, accounts.check(BOB).status(), "within the policy's 5 minutes");

        setAttribute(BOB, "pwdAccountLockedTime", generalized(Instant.now().minus(Duration.ofDays(2))));
        policy("0");
        assertEquals(DirectoryAccounts.Status.LOCKED, accounts.check(BOB).status(), "0: until an administrator unlocks");
        policy(null);
        assertEquals(DirectoryAccounts.Status.LOCKED, accounts.check(BOB).status(), "no duration in the policy");
    }

    @Test
    void directoryServer389UnlockTimes() {
        setAttribute(BOB, "accountUnlockTime", "19700101000000Z");
        assertEquals(DirectoryAccounts.Status.LOCKED, accounts.check(BOB).status(), "until an administrator unlocks");
        setAttribute(BOB, "accountUnlockTime", generalized(Instant.now().plus(Duration.ofMinutes(10))));
        assertEquals(DirectoryAccounts.Status.LOCKED, accounts.check(BOB).status(), "not yet unlocked");
        setAttribute(BOB, "accountUnlockTime", generalized(Instant.now().minus(Duration.ofMinutes(10))));
        assertEquals(DirectoryAccounts.Status.ACTIVE, accounts.check(BOB).status(), "unlocked");
    }

    @Test
    void anUnreadablePolicyRefusesAsUnreachable() {
        LdapService flaky = mock(LdapService.class);
        when(flaky.lookupLiveUser(any(), any(), any())).thenAnswer(i -> ldapService.lookupLiveUser(
                i.getArgument(0), i.getArgument(1), i.getArgument(2)));
        when(flaky.readAttributes(any(), any()))
                .thenThrow(new org.springframework.ldap.CommunicationException(new javax.naming.CommunicationException("down")));
        ApplicationProperties settings = new ApplicationProperties();
        settings.getLdap().getAccountStatus().setCacheSeconds(0);
        DirectoryAccounts failing = new DirectoryAccounts(flaky, users, settings);
        setAttribute(BOB, "pwdPolicySubentry", POLICY);
        setAttribute(BOB, "pwdAccountLockedTime", generalized(Instant.now().minus(Duration.ofHours(2))));
        assertEquals(DirectoryAccounts.Status.UNAVAILABLE, failing.check(BOB).status());
        removeAttribute(BOB, "pwdAccountLockedTime");
        removeAttribute(BOB, "pwdPolicySubentry");
        setAttribute(BOB, "lockoutTime", filetime(Instant.now().minus(Duration.ofHours(2))));
        assertEquals(DirectoryAccounts.Status.UNAVAILABLE, failing.check(BOB).status());
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}={1}")
    @org.junit.jupiter.params.provider.CsvSource({"userAccountControl, abc", "lockoutTime, soon",
            "accountExpires, never", "msDS-User-Account-Control-Computed, x10"})
    void aStatusValueThatIsNotANumberRefuses(String attribute, String value) {
        setAttribute(BOB, attribute, value);
        assertEquals(DirectoryAccounts.Status.UNAVAILABLE, accounts.check(BOB).status());
        assertFalse(accounts.check(BOB).active());
    }

    @Test
    void anActiveDirectoryEntryWithoutUserAccountControlRefuses() {
        // auto: an entry with the objectClass user is Active Directory's.
        DIRECTORY.ldapTemplate().modifyAttributes(BOB, new javax.naming.directory.ModificationItem[]{
                new javax.naming.directory.ModificationItem(javax.naming.directory.DirContext.ADD_ATTRIBUTE,
                        new BasicAttribute("objectClass", "user"))});
        try {
            assertEquals(DirectoryAccounts.Status.UNAVAILABLE, accounts.check(BOB).status(),
                    "the bind account cannot read userAccountControl");
            setAttribute(BOB, "userAccountControl", "512");
            assertEquals(DirectoryAccounts.Status.ACTIVE, accounts.check(BOB).status());
        } finally {
            DIRECTORY.ldapTemplate().modifyAttributes(BOB, new javax.naming.directory.ModificationItem[]{
                    new javax.naming.directory.ModificationItem(javax.naming.directory.DirContext.REMOVE_ATTRIBUTE,
                            new BasicAttribute("objectClass", "user"))});
        }
        assertEquals(DirectoryAccounts.Status.ACTIVE, accounts.check(BOB).status(), "not Active Directory");

        ApplicationProperties settings = new ApplicationProperties();
        settings.getLdap().getAccountStatus().setCacheSeconds(0);
        settings.getLdap().getAccountStatus().setActiveDirectory("true");
        DirectoryAccounts ad = new DirectoryAccounts(ldapService, users, settings);
        removeAttribute(BOB, "userAccountControl");
        assertEquals(DirectoryAccounts.Status.UNAVAILABLE, ad.check(BOB).status(), "configured as Active Directory");
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
        assertEquals(DirectoryAccounts.Status.ACTIVE, rules.status(Map.of("objectclass", "top inetOrgPerson")));
        assertEquals(DirectoryAccounts.Status.DISABLED, rules.status(Map.of("objectclass", "top person user",
                "useraccountcontrol", "66050")));
        assertEquals(DirectoryAccounts.Status.LOCKED, rules.status(Map.of("objectclass", "inetOrgPerson",
                "pwdaccountlockedtime", "garbage")), "an unreadable lock time locks");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> rules.status(Map.of()),
                "no objectClass: cannot tell Active Directory");
    }

    @Test
    void anEntryWhoseObjectClassIsHiddenRefuses() {
        LdapService hiding = mock(LdapService.class);
        com.winllc.innoutwork.data.LdapUser bob = com.winllc.innoutwork.data.LdapUser.builder().dn(BOB).build();
        when(hiding.lookupLiveUser(any(), any(), any()))
                .thenReturn(java.util.Optional.of(new LdapService.LiveUser(bob, Map.of("useraccountcontrol", "512"))));
        ApplicationProperties settings = new ApplicationProperties();
        settings.getLdap().getAccountStatus().setCacheSeconds(0);
        assertEquals(DirectoryAccounts.Status.UNAVAILABLE, new DirectoryAccounts(hiding, users, settings).check(BOB).status());
        when(hiding.lookupLiveUser(any(), any(), any()))
                .thenReturn(java.util.Optional.of(new LdapService.LiveUser(bob, Map.of("objectclass", " "))));
        assertEquals(DirectoryAccounts.Status.UNAVAILABLE, new DirectoryAccounts(hiding, users, settings).check(BOB).status());
        settings.getLdap().getAccountStatus().setActiveDirectory("false");
        assertEquals(DirectoryAccounts.Status.ACTIVE, new DirectoryAccounts(hiding, users, settings).check(BOB).status(),
                "configured: not Active Directory");
    }
}
