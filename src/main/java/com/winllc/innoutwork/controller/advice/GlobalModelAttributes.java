package com.winllc.innoutwork.controller.advice;

import com.winllc.innoutwork.service.DatabaseService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.ZonedDateTime;

@ControllerAdvice
public class GlobalModelAttributes {

    @Value("${application.update-profile-url:test.com}")
    private String defaultUserProfileUpdateUrl;

    @ModelAttribute
    public void addGlobalAttributes(Model model, HttpSession session) {

        ZonedDateTime selectedDateTime = DatabaseService.getDateTimeFromSession(session);

        model.addAttribute("systemTime", selectedDateTime);
        model.addAttribute("profileUpdateUrl", defaultUserProfileUpdateUrl);
    }
}
