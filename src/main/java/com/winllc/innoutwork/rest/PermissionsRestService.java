package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.PermissionUpdate;
import com.winllc.innoutwork.model.PermissionRecord;
import com.winllc.innoutwork.service.PermissionService;
import com.winllc.innoutwork.service.UserRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/permissions")
public class PermissionsRestService {

    private static final Logger log = LoggerFactory.getLogger(PermissionsRestService.class);

    @Autowired
    private PermissionService permissionService;

    @PostMapping("/update")
    public void addGroupToUser(@RequestBody PermissionUpdate permissionUpdate) {
        log.info("updating permission to group: {}", permissionUpdate);

        if(permissionUpdate.isSelected()){
            permissionService.addGroupToUser(new LdapDn(permissionUpdate.getGroupDn()), new LdapDn(permissionUpdate.getUserDn()));
        }else{
            permissionService.removeGroupFromUser(new LdapDn(permissionUpdate.getGroupDn()), new LdapDn(permissionUpdate.getUserDn()));
        }
    }
}
