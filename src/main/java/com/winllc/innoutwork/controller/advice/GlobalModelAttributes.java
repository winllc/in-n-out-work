package com.winllc.innoutwork.controller.advice;

import com.winllc.innoutwork.service.DatabaseService;
import jakarta.servlet.http.HttpSession;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.ZonedDateTime;

@ControllerAdvice
public class GlobalModelAttributes {
    @ModelAttribute
    public void addGlobalAttributes(Model model, HttpSession session) {

        ZonedDateTime selectedDateTime = DatabaseService.getDateTimeFromSession(session);

        model.addAttribute("systemTime", selectedDateTime);
    }
}
