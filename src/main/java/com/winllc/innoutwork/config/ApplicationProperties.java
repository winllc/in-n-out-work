package com.winllc.innoutwork.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "application")
public class ApplicationProperties {
    private String baseDn;
    private String userLdapFilter = "(objectclass=*)";
    private String userLdapOrganizationAttribute = "organization";
    private String userLdapEmployeeTypeAttribute = "employeeType";
    private boolean lookupOnDnAttribute = false;
    private String userDnAttribute = "";
    private String groupsBaseDn;
    private int cacheDurationMinutes = 60;
    private List<String> superUserDns = new ArrayList<>();
}
