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
    private String organizationName = "TOP";
    private String timeZone = "ETC";
    private String userBaseDn;
    private String userLdapFilter = "(objectclass=*)";
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
    }
}
