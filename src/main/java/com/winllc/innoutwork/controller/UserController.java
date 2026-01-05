package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.rest.UserRestService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import java.util.stream.Stream;

@Controller
@RequestMapping("/app/user")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final LdapService ldapService;
    private final UserRestService userRestService;
    private final UserService userRecordService;

    public UserController(LdapService ldapService, UserRestService userRestService, UserService userRecordService) {
        this.ldapService = ldapService;
        this.userRestService = userRestService;
        this.userRecordService = userRecordService;
    }

    @GetMapping("/details/{dn}")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public ModelAndView details(HttpSession session, @PathVariable String dn, Authentication auth) {
        log.debug("%s requested details for: %s".formatted(auth.getName(), dn));

        UserStatus userDetails = userRecordService.getUserDetails(LdapDn.builder().dn(dn).build(), session);

        ModelAndView mav = new ModelAndView("userdetails");
        mav.addObject("user", userDetails);
        mav.addObject("roles", UserRoleEnum.getVisibleRoles());

        return mav;
    }

    @PostMapping("/details")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN)")
    public String update(@ModelAttribute("user") UserStatus userDetails, Authentication auth) {
        log.debug("%s updating details for: %s".formatted(auth.getName(), userDetails));

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

        return new ModelAndView("usersearch");
    }
}
