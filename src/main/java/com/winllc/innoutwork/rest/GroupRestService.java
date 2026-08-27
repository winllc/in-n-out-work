package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.data.GroupFavorite;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.model.GroupRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.GroupRecordRepository;
import com.winllc.innoutwork.service.GroupService;
import com.winllc.innoutwork.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/groups")
public class GroupRestService {

    private static final Logger log = LoggerFactory.getLogger(GroupRestService.class);

    private final UserService userRecordService;
    private final GroupRecordRepository groupRecordRepository;
    private final GroupService groupService;

    public GroupRestService(UserService userRecordService, GroupRecordRepository groupRecordRepository, GroupService groupService) {
        this.userRecordService = userRecordService;
        this.groupRecordRepository = groupRecordRepository;
        this.groupService = groupService;
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
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public LdapGroup groupHierarchy() {
        //return cacheService.getGroup(applicationProperties.getGroupsBaseDn());
        return null; //unused?
    }

    @PostMapping("/favoriteGroup")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public void favoriteGroup(Authentication authentication, @RequestBody GroupFavorite groupFavorite) {
        //return cacheService.getGroup(applicationProperties.getGroupsBaseDn());
        userRecordService.updateGroupFavorite(authentication, groupFavorite);

        log.debug("Group favorite: {}", groupFavorite);
    }

    @GetMapping("/managers/{groupName}")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public List<UserRecord> groupManagers(@PathVariable String groupName) {
        Optional<GroupRecord> byDnIgnoreCase = groupRecordRepository.findByGroupDnIgnoreCase(groupName);

        if(byDnIgnoreCase.isPresent()){
            GroupRecord record = byDnIgnoreCase.get();
            return record.getAltManagerList().stream().map(d -> {
                UserRecord rec = new UserRecord();
                rec.setDn(d);
                return rec;
            }).toList();
        }else{
            return Collections.emptyList();
        }
    }

    @PostMapping("/managers/update/{groupName}")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public void updateManagersForUser(@PathVariable String groupName,
                                      Authentication authentication, @RequestBody List<String> managerDns){
        //todo implement
        log.info("Updating managers for user %s to: %s".formatted(authentication.getName(), managerDns));

        GroupRecord record = groupService.getOrCreateGroupRecord(groupName);

        record.setAlternateManagers("");
        for(String managerDn : managerDns){
            record.addAltManager(managerDn);
        }

        groupRecordRepository.save(record);
    }
}
