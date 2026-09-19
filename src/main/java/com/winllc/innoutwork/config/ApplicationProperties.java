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
