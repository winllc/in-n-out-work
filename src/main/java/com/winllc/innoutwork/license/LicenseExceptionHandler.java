package com.winllc.innoutwork.license;

import com.enssolutions.license.LicenseManager;
import com.winllc.innoutwork.audit.AccessDenials;
import com.winllc.innoutwork.service.TrackedPeopleLimitException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Answers the license's refusals with 403. API callers (/api/**, or anything asking for JSON and not
 * HTML) get
 * <pre>{"error":"license_required","state":"MISSING","message":"..."}</pre>
 * with {@code license_read_only} for a read-only license, {@code "feature"} added when one feature is
 * refused, and {@code license_limit} when a new person would exceed the licensed size. Never a
 * redirect: the Windows client would follow one to an HTML page and take that as success.
 *
 * <p>Browsers are sent to the license screen while there is no usable license, and otherwise get the
 * "needs a license" page. Ordered first so ExceptionController's catch-all does not take these as errors.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LicenseExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LicenseExceptionHandler.class);

    private final LicenseManager licenses;

    /** Records each refusal as access.denied; absent in test slices without the audit store. */
    private AccessDenials denials;

    public LicenseExceptionHandler(LicenseManager licenses) {
        this.licenses = licenses;
    }

    @Autowired(required = false)
    public void setAccessDenials(AccessDenials denials) {
        this.denials = denials;
    }

    @ExceptionHandler(LicenseRefusedException.class)
    public Object refused(HttpServletRequest req, LicenseRefusedException ex) {
        log.info("Refused {} {}: {} ({})", req.getMethod(), req.getRequestURI(), ex.getReason().code(),
                ex.getFeature() != null ? ex.getFeature().id() : ex.getCheck().state());
        if (denials != null) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("licenseState", ex.getCheck().state().name());
            extra.put("feature", ex.getFeature() != null ? ex.getFeature().id() : null);
            denials.denied(req, ex.getReason().name().toLowerCase(), ex.getMessage(), extra);
        }

        if (wantsJson(req)) {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("error", ex.getReason().code());
            body.put("state", ex.getCheck().state().name());
            if (ex.getFeature() != null) {
                body.put("feature", ex.getFeature().id());
            }
            body.put("message", ex.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body(body);
        }
        if (ex.getReason() == LicenseRefusedException.Reason.LICENSE_REQUIRED) {
            return new ModelAndView("redirect:" + LicensingConfiguration.LICENSE_SCREEN);
        }
        String title = ex.getFeature() != null
                ? ex.getFeature().label() + " is not licensed"
                : "ReadyRoom is read-only";
        return page(title, ex.getMessage());
    }

    @ExceptionHandler(TrackedPeopleLimitException.class)
    public Object overLimit(HttpServletRequest req, TrackedPeopleLimitException ex) {
        log.warn("Refused {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        if (denials != null) {
            denials.denied(req, "license_limit", ex.getMessage(), null);
        }
        if (wantsJson(req)) {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("error", "license_limit");
            body.put("state", licenses.current().state().name());
            body.put("message", ex.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return page("Licensed size reached", ex.getMessage());
    }

    private ModelAndView page(String title, String message) {
        // Model advice does not run for exception handlers, so the layout's license checks get theirs here.
        LicenseInfo info = new LicenseInfo(licenses.current());
        ModelAndView mav = new ModelAndView("license-required");
        mav.setStatus(HttpStatus.FORBIDDEN);
        mav.addObject("title", title);
        mav.addObject("message", message);
        mav.addObject("license", info.check());
        mav.addObject("licenseInfo", info);
        return mav;
    }

    static boolean wantsJson(HttpServletRequest req) {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (path.startsWith("/api/")) {
            return true;
        }
        String accept = req.getHeader("Accept");
        return accept != null && !accept.contains(MediaType.TEXT_HTML_VALUE) && accept.contains("json");
    }
}
