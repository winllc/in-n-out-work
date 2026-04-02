package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.rest.UserRestService;
import com.winllc.innoutwork.security.PermissionEvaluator;
import com.winllc.innoutwork.service.GroupService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import java.util.Objects;
import java.util.stream.Stream;

@Controller
@RequestMapping("/app/user")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userRecordService;
    @Autowired
    private PermissionEvaluator permissionEvaluator;

    public UserController(UserService userRecordService) {
        this.userRecordService = userRecordService;
    }

    @GetMapping("/details/{dn}")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).USER, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public ModelAndView details(HttpSession session, @PathVariable String dn, Authentication auth) {
        log.debug("%s requested details for: %s".formatted(auth.getName(), dn));

        UserStatus userDetails = userRecordService.getUserDetails(LdapDn.builder().dn(dn).build(), session);

        boolean isGroupManager = auth.getAuthorities().stream()
                .anyMatch(a ->
                        Objects.requireNonNull(a.getAuthority()).equalsIgnoreCase(UserRoleEnum.ADMIN.name())
                        || a.getAuthority().equalsIgnoreCase(UserRoleEnum.MANAGER.name()));

        if(!isGroupManager) {
            isGroupManager = permissionEvaluator.userManagerCheck(dn, auth);
        }

       //todo if manager
       // groupService.getManagersForGroup()

        ModelAndView mav = new ModelAndView("userdetails");
        mav.addObject("user", userDetails);
        mav.addObject("roles", UserRoleEnum.getVisibleRoles());
        mav.addObject("isGroupManager", isGroupManager);

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
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).USER, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public ModelAndView search(){

        return new ModelAndView("usersearch");
    }
}
