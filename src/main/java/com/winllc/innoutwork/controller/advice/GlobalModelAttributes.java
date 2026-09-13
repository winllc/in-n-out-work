package com.winllc.innoutwork.controller.advice;

import com.winllc.innoutwork.service.CheckInOutService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static com.winllc.innoutwork.constant.DateTimeConstants.DATE_FORMATTER;

@ControllerAdvice
public class GlobalModelAttributes {

    @Value("${application.update-profile-url:test.com}")
    private String defaultUserProfileUpdateUrl;

    /** Shown in the help overview; the same setting the absence notifications use. */
    @Value("${application.extra-time-before-absent-notification-minutes:60}")
    private int absenceGraceMinutes;

    @ModelAttribute
    public void addGlobalAttributes(Model model, HttpSession session, Authentication authentication) {

        ZonedDateTime selectedDateTime = CheckInOutService.getDateTimeFromSession(session);

        model.addAttribute("systemTime", DATE_FORMATTER.format(selectedDateTime));
        model.addAttribute("profileUpdateUrl", defaultUserProfileUpdateUrl);
        model.addAttribute("systemTimeZone", ZoneId.systemDefault().getId());
        model.addAttribute("passwordLogin", isPasswordLogin(authentication));
        model.addAttribute("absenceGraceMinutes", absenceGraceMinutes);
    }

    /**
     * Whether the session came from the username/password form, which is the only case where
     * logging out means anything: a client certificate is presented again on the next request and
     * signs the user straight back in, so certificate sessions get no logout.
     */
    static boolean isPasswordLogin(Authentication authentication) {
        return authentication instanceof UsernamePasswordAuthenticationToken && authentication.isAuthenticated();
    }
}
