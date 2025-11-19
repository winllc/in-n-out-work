package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.UserDetails;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.rest.UserService;
import com.winllc.innoutwork.service.LdapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import java.util.List;

@Controller
@RequestMapping("/app/user")
public class UserController {

    private final LdapService ldapService;
    private final UserService userService;

    public UserController(LdapService ldapService, UserService userService) {
        this.ldapService = ldapService;
        this.userService = userService;
    }

    @GetMapping("/details/{dn}")
    public ModelAndView details(@PathVariable String dn){

        UserDetails userDetails = new UserDetails();
        userDetails.setDn(dn);

        List<LdapGroup> groupsForUser = ldapService.findGroupsForUser(dn);
        userDetails.setMemberOf(groupsForUser);

        UserStatus userStatus = userService.getUserStatus(dn);
        userDetails.setNotes(userStatus.getNotes());
        userDetails.setStatus(userStatus.getStatus());

        return  new ModelAndView("userdetails", "user", userDetails);
    }

    @GetMapping("/search")
    public ModelAndView search(){

        return  new ModelAndView("usersearch");
    }
}
