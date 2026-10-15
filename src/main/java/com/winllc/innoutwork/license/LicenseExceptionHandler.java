package com.winllc.innoutwork.license;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Answers a {@link LicenseRequiredException} with 403: a JSON body for API callers, the "needs a license"
 * page for browsers. Ordered first so ExceptionController's catch-all does not take it as an error.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LicenseExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LicenseExceptionHandler.class);

    private final LicenseService licenseService;

    public LicenseExceptionHandler(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @ExceptionHandler(LicenseRequiredException.class)
    public Object handleLicenseRequired(HttpServletRequest req, LicenseRequiredException ex) {
        LicensedFeature feature = ex.getFeature();
        log.info("Refused {} {}: {} is not licensed", req.getMethod(), req.getRequestURI(), feature.id());

        if (wantsJson(req)) {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("error", "license_required");
            body.put("feature", feature.id());
            body.put("message", ex.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body(body);
        }

        // Model advice does not run for exception handlers, so the layout's license checks get theirs here.
        LicenseInfo info = LicenseInfo.of(licenseService);
        ModelAndView mav = new ModelAndView("license-required");
        mav.setStatus(HttpStatus.FORBIDDEN);
        mav.addObject("feature", feature);
        mav.addObject("license", info.status());
        mav.addObject("licenseInfo", info);
        return mav;
    }

    private static boolean wantsJson(HttpServletRequest req) {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (path.startsWith("/api/")) {
            return true;
        }
        String accept = req.getHeader("Accept");
        return accept != null && !accept.contains(MediaType.TEXT_HTML_VALUE) && accept.contains("json");
    }
}
