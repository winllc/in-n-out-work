package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.data.UserDetails;
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

    @Autowired
    private LdapService ldapService;

    @GetMapping("/details/{dn}")
    public ModelAndView details(@PathVariable String dn){

        UserDetails userDetails = new UserDetails();
        userDetails.setDn(dn);

        List<String> groupsForUser = ldapService.findGroupsForUser(dn);
        userDetails.setMemberOf(groupsForUser);

        return  new ModelAndView("userdetails", "user", userDetails);
    }

    @GetMapping("/search")
    public ModelAndView search(){

        return  new ModelAndView("usersearch");
    }
}
