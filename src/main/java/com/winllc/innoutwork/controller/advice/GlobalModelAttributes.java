package com.winllc.innoutwork.controller.advice;

import com.winllc.innoutwork.service.DatabaseService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.ZonedDateTime;

import static com.winllc.innoutwork.constant.DateTimeConstants.DATE_FORMATTER;

@ControllerAdvice
public class GlobalModelAttributes {

    @Value("${application.update-profile-url:test.com}")
    private String defaultUserProfileUpdateUrl;

    @ModelAttribute
    public void addGlobalAttributes(Model model, HttpSession session) {

        ZonedDateTime selectedDateTime = DatabaseService.getDateTimeFromSession(session);

        model.addAttribute("systemTime", DATE_FORMATTER.format(selectedDateTime));
        model.addAttribute("profileUpdateUrl", defaultUserProfileUpdateUrl);
    }
}
