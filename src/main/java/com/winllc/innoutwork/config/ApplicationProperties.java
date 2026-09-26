package com.winllc.innoutwork.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
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
    private int checkOutAfterMinutes = 240;
    private int extraTimeBeforeAbsentNotificationMinutes = 60;
    private String calendarStatusEventColor = "blue";
    private String calendarAbsentStatusEventColor = "purple";
    private String calendarActivityEventColor = "green";
    private String calendarGlobalEventColor = "orange";
    private String notificationSenderEmail = "noreply@test.com";
    private String applicationBaseUrl = "https://localhost";
    private boolean groupsInitiallyExpanded = false;
    private String dutySubOrgGroupsBaseDn = "";
    private String dutySubOrgFilter = "";
    private Ldap ldap = new Ldap();
    private Demo demo = new Demo();
    private WindowsAuth windowsAuth = new WindowsAuth();

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
     * A read-only, sign-in-free view of the application, for showing it to people who have no account
     * in the directory. Off unless switched on; see DemoSecurityConfig.
     *
     * <p>Turning this on removes authentication from the whole application and shows everything an
     * administrator can see. Only ever point it at a directory and database holding data that is safe
     * for anyone who can reach the URL to read.
     */
    @Data
    public static class Demo {
        /**
         * Off by default, and the only thing that turns demo mode on. While it is on, nothing about the
         * normal sign-in applies: every request is served as the demo user.
         */
        private boolean enabled = false;
        /**
         * The directory entry the demo view is presented as. Required when enabled - the application
         * refuses to start without it rather than serve a demo with no identity behind it. Point it at a
         * seeded demo account, never a real person.
         */
        private String userDn = "";
        /** Shown in the banner on every page, so nobody mistakes the demo for the real thing. */
        private String banner = "DEMO - read only";
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
        private int pageSize = 500;
        /**
         * How long a user's group memberships are kept before the directory is asked again. Permission
         * checks read them on every request; 0 turns the cache off.
         */
        private int groupMembershipCacheSeconds = 300;
        /**
         * Most pages a single paged search will read before it gives up and returns what it has.
         * A directory that keeps handing back a cookie - a referral, a proxy that mishandles the
         * paged results control - would otherwise loop forever and hold the request open with it.
         * At the default page size that is half a million entries, far past any real result set.
         */
        private int maxPages = 1000;
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
