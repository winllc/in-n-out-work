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
    private String userBaseDn;
    private String userLdapFilter = "(objectclass=*)";
    private String userLdapOrganizationAttribute = "organization";
    private String userLdapEmployeeTypeAttribute = "employeeType";
    private String userLdapLocationAttribute = "location";
    private boolean lookupOnDnAttribute = false;
    private String userDnAttribute = "";
    private int cacheDurationRefreshMinutes = 60;
    private int cacheDurationExpirationMinutes = 120;
    private List<String> superUserDns = new ArrayList<>();
    private List<TopLevelGroupProperties> groups = new ArrayList<>();
    private Map<String, String> attributeUpdateRequestUrlMappings = new HashMap<>();
    private String updateProfileUrl = "https://google.com";
}
