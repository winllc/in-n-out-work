package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.data.GroupFavorite;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.service.UserRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/groups")
public class GroupRestService {

    private static final Logger log = LoggerFactory.getLogger(GroupRestService.class);

    private final UserRecordService userRecordService;

    public GroupRestService(UserRecordService userRecordService) {
        this.userRecordService = userRecordService;
    }

    /*
    @GetMapping
    @PreAuthorize("hasAuthority('SUPER_USER')")
    public Map<String, Object> getGroups() {
        List<LdapGroup> groups = ldapService.getGroups();

        Map<String, Object> response = new HashMap<>();
        response.put("data", groups);

        return response;
    }

     */

    @GetMapping("/hierarchy")
    @PreAuthorize("hasAuthority('SUPER_USER')")
    public LdapGroup groupHierarchy() {
        //return cacheService.getGroup(applicationProperties.getGroupsBaseDn());
        return null; //unused?
    }

    @PostMapping("/favoriteGroup")
    @PreAuthorize("hasAuthority('SUPER_USER')")
    public void favoriteGroup(Authentication authentication, @RequestBody GroupFavorite groupFavorite) {
        //return cacheService.getGroup(applicationProperties.getGroupsBaseDn());
        userRecordService.updateGroupFavorite(authentication, groupFavorite);

        log.info("Group favorite: {}", groupFavorite);
    }
}
