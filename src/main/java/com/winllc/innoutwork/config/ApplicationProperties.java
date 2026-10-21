package com.winllc.innoutwork.config;

import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.security.CoworkerVisibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code application.*}. Validated at startup: an impossible value (a negative interval, a base URL that is not
 * http(s), a sender that is not an e-mail address) stops startup with the setting's name instead of taking effect.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "application")
public class ApplicationProperties {

    /**
     * The filter identifying user entries when none is configured.
     *
     * <p>Override with {@code application.user-ldap-filter} for a directory that models
     * people as something other than inetOrgPerson, or to narrow the set further.
     */
    public static final String DEFAULT_USER_LDAP_FILTER = "(objectclass=inetOrgPerson)";

    private String organizationName = "TOP";
    private String timeZone = "ETC";
    @NotBlank(message = "application.user-base-dn (APPLICATION_USERBASEDN) must name where users are in the directory")
    private String userBaseDn;
    private String userLdapFilter = DEFAULT_USER_LDAP_FILTER;
    private String userLdapOrganizationAttribute = "organization";
    private String userLdapEmployeeTypeAttribute = "employeeType";
    private String userLdapLocationAttribute = "location";
    private String userLdapBranchAttribute = "branch";
    private String userLdapManagerIdAttribute = "title";
    private String userLdapEmailAttribute = "mail";
    private String userLdapPhoneAttribute = "telephoneNumber";
    private String userLdapDutySubOrganizationAttribute = "dutySubOrganization";
    private String managerLdapIdAttribute = "Email";
    private boolean lookupOnDnAttribute = false;
    private String userDnAttribute = "";
    private int cacheDurationRefreshMinutes = 60;
    private int cacheDurationExpirationMinutes = 120;
    private List<String> superUserDns = new ArrayList<>();
    private List<TopLevelGroupProperties> groups = new ArrayList<>();
    private Map<String, String> attributeUpdateRequestUrlMappings = new HashMap<>();
    private String updateProfileUrl = "https://google.com";
    /** Minutes a session stays locked before MarkInactiveCron checks it out. */
    @Min(1)
    private int checkOutAfterMinutes = 240;
    @Min(0)
    private int extraTimeBeforeAbsentNotificationMinutes = 60;
    // Calendar event backgrounds, behind white text: each at least 4.5:1 against it (WCAG 1.4.3). The organization
    // calendar was orange (2:1) and requests gray (3.9:1) before.
    private String calendarStatusEventColor = "blue";
    private String calendarAbsentStatusEventColor = "purple";
    private String calendarActivityEventColor = "green";
    private String calendarGlobalEventColor = "#a84400";
    /** Days of a status request still waiting for approval. */
    private String calendarRequestedEventColor = "#5c636a";
    /**
     * The From address of absence alert e-mails. No default: required (MailConfig checks it) once a mail server
     * is configured.
     */
    @Email
    private String notificationSenderEmail = "";
    @Pattern(regexp = "^https?://\\S+$",
            message = "application.application-base-url (APPLICATION_BASE_URL) must be an http(s) URL")
    private String applicationBaseUrl = "https://localhost";
    private boolean groupsInitiallyExpanded = false;
    private String dutySubOrgGroupsBaseDn = "";
    private String dutySubOrgFilter = "";
    @Valid
    private Ldap ldap = new Ldap();
    private Demo demo = new Demo();
    private WindowsAuth windowsAuth = new WindowsAuth();
    @Valid
    private Logoff logoff = new Logoff();
    @Valid
    private Events events = new Events();
    @Valid
    private Privacy privacy = new Privacy();
    @Valid
    private Retention retention = new Retention();
    @Valid
    private Ha ha = new Ha();
    @Valid
    private Api api = new Api();
    @Valid
    private Webhooks webhooks = new Webhooks();
    @Valid
    private Reports reports = new Reports();

    /**
     * How long check-in and status records are kept (service.RetentionService). Audit events have their own retention
     * (ENS_AUDIT_RETENTION_DAYS) and are never purged by this.
     */
    @Data
    public static class Retention {
        /** The shortest retention: the home page, My Reports and the average login time read the last 30 days. */
        public static final int MIN_EVENTS_DAYS = 31;

        /**
         * Days to keep check-in records, calendar statuses, status requests and absence alerts; older ones are rolled
         * up into monthly summaries and deleted. 0 (the default) keeps them forever, as before.
         */
        @Min(value = 0, message = "application.retention.events-days (APPLICATION_RETENTION_EVENTSDAYS) must be 0"
                + " (keep forever) or at least " + MIN_EVENTS_DAYS)
        private int eventsDays = 0;

        /** When the purge runs: a Spring cron expression (second minute hour day month weekday). Daily at 02:17. */
        private String cron = "0 17 2 * * *";

        @AssertTrue(message = "application.retention.events-days (APPLICATION_RETENTION_EVENTSDAYS) must be 0 (keep"
                + " forever) or at least " + MIN_EVENTS_DAYS + ": the home page and reports read the last 30 days")
        public boolean isEventsDaysValid() {
            return eventsDays <= 0 || eventsDays >= MIN_EVENTS_DAYS;
        }

        @AssertTrue(message = "application.retention.cron (APPLICATION_RETENTION_CRON) must be a Spring cron expression"
                + " with six fields, e.g. 0 17 2 * * *")
        public boolean isCronValid() {
            return cron != null && org.springframework.scheduling.support.CronExpression.isValidExpression(cron);
        }
    }

    /** What people see of each other, and keep. See AccessPolicy and the README's "Privacy and retention". */
    @Data
    public static class Privacy {
        /**
         * What someone sees of a coworker whose details they may not see (search, a group's or an organization's
         * members, a person's page, the org chart): {@code full} (default, as before), {@code status} (in or out only)
         * or {@code none}. Managers see their own people and administrators everyone in full whatever this says.
         */
        @NotNull(message = "application.privacy.coworker-visibility (APPLICATION_PRIVACY_COWORKERVISIBILITY) must be full, status or none")
        private CoworkerVisibility coworkerVisibility = CoworkerVisibility.FULL;
    }

    /**
     * Statuses people put on calendars ({@code /api/event/**}, the organization calendar). See UserEventRestService.
     */
    @Data
    public static class Events {
        /**
         * The most days one call may set a status or an organization calendar event on. A year (366, for a leap
         * year), so a mistaken drag or a scripted call cannot fill a calendar for ever. Checked by every API that
         * creates events.
         */
        @Min(value = 1, message = "application.events.max-range-days (APPLICATION_EVENTS_MAXRANGEDAYS) must be at least 1")
        @Max(value = 3660, message = "application.events.max-range-days (APPLICATION_EVENTS_MAXRANGEDAYS) must be at most 3660")
        private int maxRangeDays = 366;

        /**
         * The statuses a person asks for rather than sets: they take effect, and stop absence alerts, only once
         * the person's manager, a delegate or an administrator approves them. By default every status that excuses
         * an absence (UserStatusEnum.excusable). Any other status the person can pick (UserStatusEnum.selectable)
         * they set themselves; the rest only managers and administrators record. See service.StatusPolicy.
         */
        private List<UserStatusEnum> requestStatuses = new ArrayList<>(DEFAULT_REQUEST_STATUSES);

        /** STANDARD clears a day's status; there is nothing to approve. */
        @AssertTrue(message = "application.events.request-statuses (APPLICATION_EVENTS_REQUESTSTATUSES) cannot list"
                + " STANDARD: it clears a status, so there is nothing to approve")
        public boolean isRequestStatusesValid() {
            return requestStatuses == null || !requestStatuses.contains(UserStatusEnum.STANDARD);
        }
    }

    /** Every status that excuses an absence: leave, telework, temporary duty, out of office, excused absence. */
    public static final List<UserStatusEnum> DEFAULT_REQUEST_STATUSES = List.of(
            UserStatusEnum.OUT_OF_OFFICE, UserStatusEnum.WORK_FROM_HOME, UserStatusEnum.TDY,
            UserStatusEnum.SCHEDULED_LEAVE, UserStatusEnum.ABSENT_EXCUSED);

    /**
     * The Windows client's logoff call, {@code POST /api/check/out}. It runs after the user's profile has gone,
     * so it usually cannot sign in; it is identified by the session id its logon was answered with instead.
     * See CheckInOutRestService.
     */
    /** The public API, /api/v1 (api.*), and its tokens. See the README's "Public API". */
    @Data
    public static class Api {
        /** The longest a token may be valid for, in days; each token's own expiry is chosen when it is made. */
        @Min(value = 1, message = "application.api.max-token-days (APPLICATION_API_MAXTOKENDAYS) must be at least 1")
        @Max(value = 3660, message = "application.api.max-token-days (APPLICATION_API_MAXTOKENDAYS) must be at most 3660")
        private int maxTokenDays = 366;

        /** Requests a minute a new token is given unless the administrator chooses otherwise. */
        @Min(value = 1, message = "application.api.default-rate-per-minute (APPLICATION_API_DEFAULTRATEPERMINUTE) must be at least 1")
        private int defaultRatePerMinute = 120;

        /** The most requests a minute any token may be given. */
        @Min(value = 1, message = "application.api.max-rate-per-minute (APPLICATION_API_MAXRATEPERMINUTE) must be at least 1")
        private int maxRatePerMinute = 1200;

        /** The most days one attendance or absence query may cover. */
        @Min(value = 1, message = "application.api.max-range-days (APPLICATION_API_MAXRANGEDAYS) must be at least 1")
        private int maxRangeDays = 366;

        /** The most items one page may hold. */
        @Min(value = 1, message = "application.api.max-page-size (APPLICATION_API_MAXPAGESIZE) must be at least 1")
        @Max(value = 5000, message = "application.api.max-page-size (APPLICATION_API_MAXPAGESIZE) must be at most 5000")
        private int maxPageSize = 500;
    }

    /** Report exports, scheduled reports and the time zones reports are in (export.*, service.TimeZones). */
    @Data
    public static class Reports {
        /**
         * Each site's time zone, as {@code <location>=<IANA zone>} (the location as the directory's location
         * attribute spells it, any case), e.g. {@code New York=America/New_York}. A person's times in reports, exports
         * and the API are in their own time zone (their profile), else their site's, else application.timezone.
         */
        private List<String> siteTimeZones = new ArrayList<>();

        /** The most days one export or scheduled report may cover. */
        @Min(value = 1, message = "application.reports.max-range-days (APPLICATION_REPORTS_MAXRANGEDAYS) must be at least 1")
        private int maxRangeDays = 366;

        /** The largest attachment a scheduled report sends; a bigger one is not sent, and its run says so. */
        @Min(value = 10_000, message = "application.reports.max-attachment-bytes (APPLICATION_REPORTS_MAXATTACHMENTBYTES) must be at least 10000")
        private long maxAttachmentBytes = 10L * 1024 * 1024;

        /** The most recipients one scheduled report may have. */
        @Min(value = 1, message = "application.reports.max-recipients (APPLICATION_REPORTS_MAXRECIPIENTS) must be at least 1")
        private int maxRecipients = 20;

        @AssertTrue(message = "application.reports.site-time-zones (APPLICATION_REPORTS_SITETIMEZONES) must be"
                + " <location>=<IANA time zone> entries, e.g. New York=America/New_York")
        public boolean isSiteTimeZonesValid() {
            try {
                siteZones();
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        /** {@link #siteTimeZones} by lower-cased location. */
        public Map<String, java.time.ZoneId> siteZones() {
            Map<String, java.time.ZoneId> zones = new HashMap<>();
            for (String entry : siteTimeZones) {
                int eq = entry.lastIndexOf('=');
                if (eq <= 0) {
                    throw new IllegalArgumentException("not location=zone: " + entry);
                }
                zones.put(entry.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT),
                        java.time.ZoneId.of(entry.substring(eq + 1).trim()));
            }
            return zones;
        }
    }

    /** Signed outbound webhooks (webhook.*): absence alerts and status changes, to HTTPS endpoints. */
    @Data
    public static class Webhooks {
        /**
         * The key webhook signing secrets are encrypted with in the database (AES-256-GCM): 32 characters or more,
         * the same on every replica. Empty (the default): webhooks cannot be created or sent. The Helm chart makes
         * one and keeps it.
         */
        private String secretKey = "";

        /**
         * Address ranges (CIDR) on a private, loopback or link-local network that webhooks may still be sent to,
         * e.g. 10.20.0.0/16 for an internal SIEM. Empty (the default): only public addresses. Cloud metadata
         * addresses are refused whatever this says.
         */
        private List<String> allowedCidrs = new ArrayList<>();

        /** Tries per delivery, the first included, before it is given up as failed. */
        @Min(value = 1, message = "application.webhooks.max-attempts (APPLICATION_WEBHOOKS_MAXATTEMPTS) must be 1 to 20")
        @Max(value = 20, message = "application.webhooks.max-attempts (APPLICATION_WEBHOOKS_MAXATTEMPTS) must be 1 to 20")
        private int maxAttempts = 8;

        /** How long delivered and failed deliveries are kept in the delivery log, in days. */
        @Min(value = 1, message = "application.webhooks.delivery-log-days (APPLICATION_WEBHOOKS_DELIVERYLOGDAYS) must be at least 1")
        private int deliveryLogDays = 30;

        @NotNull
        private Duration connectTimeout = Duration.ofSeconds(5);

        @NotNull
        private Duration readTimeout = Duration.ofSeconds(10);

        /**
         * The most one try may take altogether (connecting, TLS, sending, the answer's status line), whatever the
         * receiver does: past it the connection is closed, so a receiver sending a byte at a time cannot hold it.
         */
        @NotNull
        private Duration attemptTimeout = Duration.ofSeconds(30);

        /** How long one sending run may start new tries for; the next run, seconds later, carries on. */
        @NotNull
        private Duration runBudget = Duration.ofSeconds(40);

        /** The most tries one endpoint gets in one run. */
        @Min(value = 1, message = "application.webhooks.per-endpoint-max (APPLICATION_WEBHOOKS_PERENDPOINTMAX) must be at least 1")
        private int perEndpointMax = 20;

        /** The most time one endpoint's tries may take in one run, so one slow receiver cannot starve the others. */
        @NotNull
        private Duration perEndpointBudget = Duration.ofSeconds(20);

        /** Whether webhooks can be used: a secret key of 32 characters or more is set. */
        public boolean isConfigured() {
            return secretKey != null && secretKey.length() >= 32;
        }

        @AssertTrue(message = "application.webhooks.secret-key (APPLICATION_WEBHOOKS_SECRETKEY) must be empty or at"
                + " least 32 characters")
        public boolean isSecretKeyValid() {
            return secretKey == null || secretKey.isEmpty() || secretKey.length() >= 32;
        }

        @AssertTrue(message = "application.webhooks.allowed-cidrs (APPLICATION_WEBHOOKS_ALLOWEDCIDRS) must be CIDR"
                + " ranges such as 10.20.0.0/16 or fd00:1::/64")
        public boolean isAllowedCidrsValid() {
            try {
                allowedCidrs.forEach(com.winllc.innoutwork.webhook.Cidr::parse);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }

    /**
     * Running several replicas, and what happens around a time the whole service was down (service.ServiceOutages).
     */
    @Data
    public static class Ha {
        /**
         * After an outage of the whole service, during which the Windows client's events were lost: how long people
         * whose check-in time fell in it or before it have to be seen again (their next logon or unlock checks them
         * in) before an absence alert is sent. 0 sends alerts as if there had been no outage.
         */
        @NotNull
        private Duration outageGrace = Duration.ofMinutes(30);
    }

    @Data
    public static class Logoff {
        /**
         * How long after a logon its session id can still identify the logoff; older ones are refused. A week, so a
         * workstation left logged on over a weekend or a long shift still records its logoff.
         */
        @Min(1)
        private int sessionMaxAgeHours = 168;
        /**
         * Refused logoffs without a sign-in (unknown, used or expired session id) from one client address per minute.
         * Past it, refused ones get 429 and are no longer audited one by one. Accepted logoffs never count, so many
         * workstations behind one address are not held back. 0 turns it off.
         */
        @Min(0)
        private int refusedPerMinute = 30;
        /**
         * Accept a logoff's session id only from the client address its logon came from; one from another address
         * is refused and audited. Turn off only where workstations' addresses change between logon and logoff
         * (roaming laptops, short DHCP leases, VPN reconnects) and those logoffs matter more than the binding.
         */
        private boolean bindToAddress = true;
    }

    /**
     * Windows (Kerberos) sign-in for the Windows client's check-in, lock and unlock calls, alongside client
     * certificates. See WindowsAuthSecurityConfig and the README's "Windows authentication" section.
     */
    @Data
    public static class WindowsAuth {
        /** Off by default; when off, nothing about authentication changes. */
        private boolean enabled = false;
        /** The app's service principal, e.g. HTTP/inout.winllc.com@WINLLC.COM; the keytab must hold its key. */
        private String servicePrincipal = "";
        /** Path of the keytab file exported for the service principal. Keep it out of the image and the jar. */
        private String keytabLocation = "";
        /** A krb5.conf to use instead of the system one (/etc/krb5.conf); blank for the system file. */
        private String krb5ConfigLocation = "";
        /**
         * Directory attribute holding the Windows account name: sAMAccountName (the default, matched against the
         * name before the @) or userPrincipalName (set strip-realm to false to match the whole principal).
         */
        private String accountAttribute = "sAMAccountName";
        /** Match the account attribute against "jdoe" rather than "jdoe@WINLLC.COM". */
        private boolean stripRealm = true;
        /**
         * Realms whose users may sign in. Empty means the service principal's own realm, so a user of a trusted
         * domain with the same account name cannot be taken for a local user.
         */
        private List<String> allowedRealms = new ArrayList<>();
        /** Log the JDK's Kerberos detail while the keytab is loaded. */
        private boolean debug = false;
    }

    /**
     * A read-only walkthrough of the application, for showing it to people who have no account in the
     * directory. Off unless switched on; see DemoSecurityConfig.
     *
     * <p>Visitors still sign in - the login page and the directory decide who they are, exactly as
     * normally - so what demo mode changes is that nothing can be written, and that the credentials
     * are published on the login page so a visitor can pick a role to look around as.
     *
     * <p>Publishing credentials means the accounts listed here are readable by anyone who can reach
     * the site. Only ever list seeded demo accounts, in a deployment whose directory and database hold
     * data that is safe for those people to read.
     */
    @Data
    public static class Demo {
        /** Off by default, and the only thing that turns demo mode on. */
        private boolean enabled = false;
        /** Shown in the banner on every page, so nobody mistakes the demo for the real thing. */
        private String banner = "DEMO - read only";
        /**
         * The accounts offered on the login page. Empty means no tile is shown - demo mode still
         * applies, visitors just have to be told the credentials some other way.
         */
        private List<DemoAccount> accounts = new ArrayList<>();
    }

    /**
     * One row of the login page's demo tile: a role to look around as, and what to sign in with.
     *
     * <p>The role is a label for the visitor, not something this grants. What an account can actually
     * do comes from the directory and from super-user-dns exactly as it always does, so a label here
     * that does not match the account is only ever misleading, never permission.
     */
    @Data
    public static class DemoAccount {
        /** What the visitor sees this account as, e.g. "Administrator". */
        private String role = "";
        /** What they type into the username field: a uid, common name or email. */
        private String username = "";
        /** The account's password, shown in full. Seeded demo accounts only. */
        private String password = "";
        /** One line on what this role can see, shown beside the credentials. */
        private String description = "";
    }

    /** Directory access tuning; see LdapConnectionConfig for the connection pool. */
    @Data
    public static class Ldap {
        /** Whether JNDI pools connections; read directly by LdapConnectionConfig. */
        private boolean pooled = true;
        /**
         * Entries per page for searches that can return many entries (all users, an org's users, counts).
         * Keeps each page under the server's size limit (Active Directory 1000, OpenLDAP 500) so results are
         * not silently cut off. 0 turns paging off, for a directory without the paged results control.
         */
        @Min(0)
        private int pageSize = 500;
        /**
         * How long a user's group memberships are kept before the directory is asked again. Permission
         * checks read them on every request; 0 turns the cache off.
         */
        @Min(0)
        private int groupMembershipCacheSeconds = 300;
        /**
         * Most pages a single paged search will read before it gives up and returns what it has.
         * A directory that keeps handing back a cookie - a referral, a proxy that mishandles the
         * paged results control - would otherwise loop forever and hold the request open with it.
         * At the default page size that is half a million entries, far past any real result set.
         */
        @Min(1)
        private int maxPages = 1000;
        /** Whether an account is still usable, for the tokens, webhooks and reports that act for someone. */
        @Valid
        private AccountStatus accountStatus = new AccountStatus();
    }

    /**
     * application.ldap.account-status: how ReadyRoom decides, live from the directory, that the person an API token,
     * a webhook or a scheduled report acts for may still act (security.DirectoryAccounts). Their entry must still
     * match application.user-ldap-filter and {@link #filter}, and, unless {@link #builtInChecks} is off, not be:
     * disabled (Active Directory userAccountControl bit 0x2; 389/OpenLDAP nsAccountLock=true), locked out (Active
     * Directory msDS-User-Account-Control-Computed bit 0x10; OpenLDAP ppolicy pwdAccountLockedTime, permanently
     * 000001010000Z or set within {@link #lockoutDuration}) or expired (Active Directory accountExpires).
     */
    @Data
    public static class AccountStatus {
        /** The built-in checks above. */
        private boolean builtInChecks = true;
        /** An LDAP filter an account must also match to be usable, e.g. (!(employeeType=terminated)); empty: none. */
        private String filter = "";
        /** OpenLDAP ppolicy: the policy's pwdLockoutDuration; a pwdAccountLockedTime newer than this locks. */
        @NotNull
        private Duration lockoutDuration = Duration.ofMinutes(15);
        /** How long a usable account is remembered (refusals never are): 0 to 60 seconds. */
        @Min(value = 0, message = "application.ldap.account-status.cache-seconds must be 0 to 60")
        @Max(value = 60, message = "application.ldap.account-status.cache-seconds must be 0 to 60")
        private int cacheSeconds = 60;
    }

    /**
     * Stores the user filter as a complete, parenthesised LDAP filter.
     *
     * <p>It gets written both ways in practice - {@code (objectclass=inetOrgPerson)} and the
     * bare {@code objectclass=inetOrgPerson} - and callers combine it into larger filters. A
     * bare value dropped into an AND reads as {@code (&objectclass=inetOrgPerson(cn=*x*))},
     * which JNDI rejects with "Unbalanced parenthesis", so the form is settled here once
     * rather than at each call site. A blank value falls back to the default, since a filter
     * of nothing would otherwise match nothing.
     */
    public void setUserLdapFilter(String userLdapFilter) {
        if (userLdapFilter == null || userLdapFilter.isBlank()) {
            this.userLdapFilter = DEFAULT_USER_LDAP_FILTER;
            return;
        }

        String trimmed = userLdapFilter.trim();

        this.userLdapFilter = trimmed.startsWith("(") ? trimmed : "(" + trimmed + ")";
    }
}
