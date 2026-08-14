package com.winllc.innoutwork.controller.advice;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice
public class ExceptionController {

    private static final Logger log = LoggerFactory.getLogger(ExceptionController.class);


    @ExceptionHandler({ AuthorizationDeniedException.class })
    public ModelAndView handleAuthenticationException(HttpServletRequest req, Exception ex) {
        // Expected whenever someone reaches for a page they lack rights to, so this is
        // not an error - but knowing which URL was refused is what makes it actionable.
        log.info("Access denied for {} {}", req.getMethod(), req.getRequestURI());
        log.debug(ex.getMessage(), ex);

        ModelAndView mav = new ModelAndView();
        mav.addObject("exception", ex);
        mav.setViewName("unauthorized");
        return mav;
    }



    @ExceptionHandler(Exception.class)
    public ModelAndView handleError(HttpServletRequest req, Exception ex) {
        // Pass the exception as the last argument so the stack trace is logged, rather
        // than only its toString().
        log.error("Request {} {} failed", req.getMethod(), req.getRequestURL(), ex);

        ModelAndView mav = new ModelAndView();
        mav.addObject("exception", ex);
        mav.addObject("url", req.getRequestURL());
        mav.setViewName("error");
        return mav;
    }
}
