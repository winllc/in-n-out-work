package com.winllc.innoutwork.controller.advice;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.service.CheckInOutService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.ZoneId;
import java.util.List;
import java.time.ZonedDateTime;

import static com.winllc.innoutwork.constant.DateTimeConstants.DATE_FORMATTER;

@ControllerAdvice
public class GlobalModelAttributes {

    @Value("${application.update-profile-url:test.com}")
    private String defaultUserProfileUpdateUrl;

    /** Shown in the help overview; the same setting the absence notifications use. */
    @Value("${application.extra-time-before-absent-notification-minutes:60}")
    private int absenceGraceMinutes;

    /** Drives the banner and the disabled controls; see DemoSecurityConfig for what enforces it. */
    @Value("${application.demo.enabled:false}")
    private boolean demoMode;

    @Value("${application.demo.banner:DEMO - read only}")
    private String demoBanner;

    /** Bound rather than read through @Value: the accounts are a list of objects. */
    private final ApplicationProperties properties;

    public GlobalModelAttributes(ApplicationProperties properties) {
        this.properties = properties;
    }

    @ModelAttribute
    public void addGlobalAttributes(Model model, HttpSession session, Authentication authentication) {

        ZonedDateTime selectedDateTime = CheckInOutService.getDateTimeFromSession(session);

        model.addAttribute("systemTime", DATE_FORMATTER.format(selectedDateTime));
        model.addAttribute("profileUpdateUrl", defaultUserProfileUpdateUrl);
        model.addAttribute("systemTimeZone", ZoneId.systemDefault().getId());
        model.addAttribute("passwordLogin", isPasswordLogin(authentication));
        model.addAttribute("absenceGraceMinutes", absenceGraceMinutes);
        model.addAttribute("demoMode", demoMode);
        model.addAttribute("demoBanner", demoBanner);
        // Only when demo mode is on. These carry passwords, so they must never reach a template on a
        // deployment that did not ask to publish them, whatever the configuration happens to hold.
        model.addAttribute("demoAccounts",
                demoMode ? properties.getDemo().getAccounts() : List.of());
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
