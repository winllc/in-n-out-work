package com.winllc.innoutwork.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    /**
     * Serves the branded login page. Access is permitted for everyone in SecurityConfig;
     * the form posts back to /login where Spring Security's LDAP provider authenticates.
     */
    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
