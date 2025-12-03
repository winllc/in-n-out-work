package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.UserDetails;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.rest.UserRestService;
import com.winllc.innoutwork.service.LdapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import java.util.List;

@Controller
@RequestMapping("/app/user")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);


    private final LdapService ldapService;
    private final UserRestService userRestService;

    public UserController(LdapService ldapService, UserRestService userRestService) {
        this.ldapService = ldapService;
        this.userRestService = userRestService;
    }

    @GetMapping("/details/{dn}")
    @PreAuthorize("hasAuthority('SUPER_USER')")
    public ModelAndView details(@PathVariable String dn, Authentication auth) {
        log.debug("%s requested details for: %s".formatted(auth.getName(), dn));

        UserDetails userDetails = new UserDetails();
        userDetails.setDn(dn);

        List<LdapGroup> groupsForUser = ldapService.findGroupsForUser(dn);
        userDetails.setMemberOf(groupsForUser);

        UserStatus userStatus = userRestService.getUserStatus(dn);
        userDetails.setNotes(userStatus.getNotes());
        userDetails.setStatus(userStatus.getStatus());
        userDetails.setOrganization(userStatus.getOrganization());
        userDetails.setEmployeeType(userStatus.getEmployeeType());

        return  new ModelAndView("userdetails", "user", userDetails);
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('SUPER_USER')")
    public ModelAndView search(){

        return  new ModelAndView("usersearch");
    }
}
