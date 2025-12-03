package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.GroupFavorite;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.CacheService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.UserRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/groups")
public class GroupService {

    private static final Logger log = LoggerFactory.getLogger(GroupService.class);

    private final CacheService cacheService;
    private final LdapService ldapService;
    private final ApplicationProperties applicationProperties;
    private final UserRecordService userRecordService;

    public GroupService(LdapService ldapService, ApplicationProperties applicationProperties,
                        CacheService cacheService, UserRecordService userRecordService) {
        this.ldapService = ldapService;
        this.applicationProperties = applicationProperties;
        this.cacheService = cacheService;
        this.userRecordService = userRecordService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SUPER_USER')")
    public Map<String, Object> getGroups() {
        List<LdapGroup> groups = ldapService.getGroups();

        Map<String, Object> response = new HashMap<>();
        response.put("data", groups);

        return response;
    }

    @GetMapping("/hierarchy")
    @PreAuthorize("hasAuthority('SUPER_USER')")
    public LdapGroup groupHierarchy() {
        return cacheService.getGroup(applicationProperties.getGroupsBaseDn());
    }

    @PostMapping("/favoriteGroup")
    @PreAuthorize("hasAuthority('SUPER_USER')")
    public void favoriteGroup(Authentication authentication, @RequestBody GroupFavorite groupFavorite) {
        //return cacheService.getGroup(applicationProperties.getGroupsBaseDn());
        userRecordService.updateGroupFavorite(authentication, groupFavorite);

        log.info("Group favorite: {}", groupFavorite);
    }
}
