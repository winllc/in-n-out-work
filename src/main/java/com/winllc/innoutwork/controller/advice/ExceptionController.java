package com.winllc.innoutwork.controller.advice;

import com.winllc.innoutwork.audit.AccessDenials;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice
public class ExceptionController {

    private static final Logger log = LoggerFactory.getLogger(ExceptionController.class);

    /** Records each refusal as access.denied; absent in test slices without the audit store. */
    private AccessDenials denials;

    @Autowired(required = false)
    public void setAccessDenials(AccessDenials denials) {
        this.denials = denials;
    }

    /** A refused @PreAuthorize (AuthorizationDeniedException), or a service's own refusal. */
    @ExceptionHandler({ AccessDeniedException.class })
    public ModelAndView handleAuthenticationException(HttpServletRequest req, Exception ex) {
        // Expected whenever someone reaches for a page they lack rights to, so this is
        // not an error - but knowing which URL was refused is what makes it actionable.
        log.info("Access denied for {} {}", req.getMethod(), req.getRequestURI());
        log.debug(ex.getMessage(), ex);
        if (denials != null) {
            denials.denied(req, "permission_denied", ex.getMessage(), null);
        }

        ModelAndView mav = new ModelAndView();
        mav.setStatus(HttpStatus.FORBIDDEN);
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
