package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.UserDetails;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.rest.UserRestService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.PermissionService;
import com.winllc.innoutwork.service.UserRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Controller
@RequestMapping("/app/user")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);


    private final LdapService ldapService;
    private final UserRestService userRestService;
    @Autowired
    private UserRecordService userRecordService;

    public UserController(LdapService ldapService, UserRestService userRestService) {
        this.ldapService = ldapService;
        this.userRestService = userRestService;
    }

    @GetMapping("/details/{dn}")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public ModelAndView details(@PathVariable String dn, Authentication auth) {
        log.debug("%s requested details for: %s".formatted(auth.getName(), dn));

        UserDetails userDetails = new UserDetails();
        userDetails.setDn(dn);

        Optional<UserRecord> userByDn = userRecordService.getUserByDn(LdapDn.builder().dn(dn).build());
        if(userByDn.isPresent()) {
            UserRecord userRecord = userByDn.get();
            if(userRecord.getUserRole() != null) {
                userDetails.setRole(userRecord.getUserRole().name());
            }
        }

        List<LdapGroup> groupsForUser = ldapService.findGroupsForUser(dn);
        userDetails.setMemberOf(groupsForUser);

        UserStatus userStatus = userRestService.getUserStatus(dn);
        userDetails.setDn(dn);
        userDetails.setNotes(userStatus.getNotes());
        userDetails.setStatus(userStatus.getStatus());
        userDetails.setOrganization(userStatus.getOrganization());
        userDetails.setEmployeeType(userStatus.getEmployeeType());
        if(userDetails.getRole() == null) {
            userDetails.setRole(UserRoleEnum.USER.name());
        }

        ModelAndView mav = new ModelAndView("userdetails");
        mav.addObject("user", userDetails);
        mav.addObject("roles", UserRoleEnum.getVisibleRoles());

        return mav;
    }

    @PostMapping("/details")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN)")
    public String update(@ModelAttribute("user") UserDetails userDetails, Authentication auth) {

        String role = userDetails.getRole();
        if(role != null && Stream.of(UserRoleEnum.values()).anyMatch(r -> r.name().equals(role))) {
            UserRoleEnum roleEnum = UserRoleEnum.valueOf(role);
            userRecordService.updateRole(LdapDn.builder().dn(userDetails.getDn()).build(), roleEnum);
        }

        return "redirect:/app/user/details/" + userDetails.getDn();
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public ModelAndView search(){

        return  new ModelAndView("usersearch");
    }
}
