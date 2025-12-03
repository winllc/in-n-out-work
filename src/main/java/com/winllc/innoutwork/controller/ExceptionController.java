package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.service.LdapService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice
public class ExceptionController {

    private static final Logger log = LoggerFactory.getLogger(ExceptionController.class);


    @ExceptionHandler({ AuthorizationDeniedException.class })
    public ModelAndView handleAuthenticationException(Exception ex) {
        log.debug(ex.getMessage(), ex);

        ModelAndView mav = new ModelAndView();
        mav.addObject("exception", ex);
        mav.setViewName("unauthorized");
        return mav;
    }



    @ExceptionHandler(Exception.class)
    public ModelAndView handleError(HttpServletRequest req, Exception ex) {
        log.error("Request: " + req.getRequestURL() + " raised " + ex);
        log.debug(ex.getMessage(), ex);

        ModelAndView mav = new ModelAndView();
        mav.addObject("exception", ex);
        mav.addObject("url", req.getRequestURL());
        mav.setViewName("error");
        return mav;
    }
}
