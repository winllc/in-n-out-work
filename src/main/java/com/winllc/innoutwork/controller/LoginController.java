package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.security.sso.SsoSignIn;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class LoginController {

    private final ObjectProvider<SsoSignIn> sso;

    public LoginController(ObjectProvider<SsoSignIn> sso) {
        this.sso = sso;
    }

    /**
     * Serves the branded login page. Access is permitted for everyone in SecurityConfig;
     * the form posts back to /login where Spring Security's LDAP provider authenticates.
     * Single sign-on, when configured, adds a button per method.
     */
    @GetMapping("/login")
    public String login(Model model) {
        SsoSignIn signIn = sso.getIfAvailable();
        model.addAttribute("ssoOptions", signIn == null ? List.of() : signIn.options());
        return "login";
    }
}
