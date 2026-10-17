package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.PermissionUpdate;
import com.winllc.innoutwork.service.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/permissions")
public class PermissionsRestService {

    private static final Logger log = LoggerFactory.getLogger(PermissionsRestService.class);

    private final PermissionService permissionService;

    public PermissionsRestService(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /** Access to a group is granted by ADMIN or one of the group's managers (AccessPolicy). */
    @PostMapping("/update")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER) " +
            "and @accessPolicy.canManageGroup(authentication, #permissionUpdate.groupDn)")
    public void addGroupToUser(@RequestBody PermissionUpdate permissionUpdate) {
        // PermissionService logs the resulting grant/revoke at info.
        log.debug("updating permission to group: {}", permissionUpdate);

        if(permissionUpdate.isSelected()){
            permissionService.addGroupToUser(new LdapDn(permissionUpdate.getGroupDn()), new LdapDn(permissionUpdate.getUserDn()));
        }else{
            permissionService.removeGroupFromUser(new LdapDn(permissionUpdate.getGroupDn()), new LdapDn(permissionUpdate.getUserDn()));
        }
    }
}
