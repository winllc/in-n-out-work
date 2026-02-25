package com.winllc.innoutwork.controller;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

@Controller
@RequestMapping("/app/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);


    @GetMapping
    @PreAuthorize("hasAnyAuthority(" +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public ModelAndView details(HttpSession session, Authentication auth) {

        ModelAndView mav = new ModelAndView("settings");

        return mav;
    }


}
