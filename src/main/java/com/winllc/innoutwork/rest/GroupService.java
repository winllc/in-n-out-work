package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.service.CacheService;
import com.winllc.innoutwork.service.LdapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/groups")
public class GroupService {

    private final CacheService cacheService;
    private final LdapService ldapService;
    private final ApplicationProperties applicationProperties;

    public GroupService(LdapService ldapService, ApplicationProperties applicationProperties,
                        CacheService cacheService) {
        this.ldapService = ldapService;
        this.applicationProperties = applicationProperties;
        this.cacheService = cacheService;
    }

    @GetMapping
    public Map<String, Object> getGroups() {
        List<LdapGroup> groups = ldapService.getGroups();

        Map<String, Object> response = new HashMap<>();
        response.put("data", groups);

        return response;
    }

    @GetMapping("/hierarchy")
    public LdapGroup groupHierarchy() {
        return cacheService.getGroup(applicationProperties.getGroupsBaseDn());
    }
}
