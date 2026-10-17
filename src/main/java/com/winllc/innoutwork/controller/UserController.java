package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.RequiresFeature;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.RoleUpdateForm;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.data.team.DirectReportsSummary;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.DirectReportsService;
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

    private final UserService userRecordService;
    private final AccessPolicy accessPolicy;
    private final DirectReportsService directReportsService;

    public UserController(UserService userRecordService, AccessPolicy accessPolicy,
                          DirectReportsService directReportsService) {
        this.userRecordService = userRecordService;
        this.accessPolicy = accessPolicy;
        this.directReportsService = directReportsService;
    }

    @GetMapping("/details/{dn}")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).USER, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public ModelAndView details(HttpSession session, @PathVariable String dn, Authentication auth) {
        log.debug("%s requested details for: %s".formatted(auth.getName(), dn));

        UserStatus userDetails = userRecordService.getUserDetails(LdapDn.builder().dn(dn).build(), session);

        // Anyone may look a person up; their attendance, notes, history and calendar only those the viewer may see
        // the details of (AccessPolicy). The page shows the history and calendar when isGroupManager is set.
        boolean isGroupManager = accessPolicy.canViewDetails(auth, userDetails.getDn());
        if (!isGroupManager) {
            // As application.privacy.coworker-visibility says: their presence, in or out only, or nothing of it.
            userDetails = AccessPolicy.coworkerView(userDetails, accessPolicy.coworkerVisibility());
        }

        boolean isSelf = accessPolicy.isSelf(auth, userDetails.getDn());

        ModelAndView mav = new ModelAndView("userdetails");
        mav.addObject("user", userDetails);
        mav.addObject("roleForm", RoleUpdateForm.buildFromStatus(userDetails));
        mav.addObject("roles", UserRoleEnum.getVisibleRoles());
        mav.addObject("isGroupManager", isGroupManager);
        mav.addObject("isSelf", isSelf);

        return mav;
    }

    @PostMapping("/details")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN)")
    public String update(@ModelAttribute("roleForm") RoleUpdateForm form, Authentication auth) {
        log.debug("%s updating details for: %s".formatted(auth.getName(), form));

        String role = form.getRole();
        if(role != null && Stream.of(UserRoleEnum.values()).anyMatch(r -> r.name().equals(role))) {
            UserRoleEnum roleEnum = UserRoleEnum.valueOf(role);
            userRecordService.updateRole(LdapDn.builder().dn(form.getDn()).build(), roleEnum);
        }

        return "redirect:/app/user/details/" + form.getDn();
    }

    /**
     * Lists everyone reporting to the signed-in user in one table. Open to any authenticated user
     * because reporting lines come from the directory rather than the application role -- someone
     * with only USER can still have reports -- and the table only ever shows the caller's own team.
     */
    @RequiresFeature(LicensedFeature.METRICS)
    @GetMapping("/reports")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).USER, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public ModelAndView myReports(HttpSession session, Authentication auth) {
        DirectReportsSummary team = directReportsService.forManager(auth.getName(), session);

        ModelAndView mav = new ModelAndView("myreports");
        mav.addObject("reportCount", team.reportCount());
        mav.addObject("managerCn", LdapDn.builder().dn(auth.getName()).build().getCn());
        mav.addObject("team", team);
        return mav;
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).USER, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public ModelAndView search(){

        return new ModelAndView("usersearch");
    }
}
