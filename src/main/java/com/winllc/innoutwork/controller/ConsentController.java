package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.security.ConsentBanner;
import com.winllc.innoutwork.security.ConsentFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.Instant;

/**
 * The notice-and-consent banner (see {@link ConsentFilter}): shown before any sign-in while it is enabled, and
 * acknowledged with an explicit "I agree", which is recorded in the browser session and the audit trail.
 */
@Controller
public class ConsentController {

    private final ConsentBanner banner;
    private final ObjectProvider<AuditLog> audit;

    public ConsentController(ConsentBanner banner, ObjectProvider<AuditLog> audit) {
        this.banner = banner;
        this.audit = audit;
    }

    @GetMapping(ConsentFilter.CONSENT_URL)
    public String banner(Model model) {
        if (!banner.isEnabled()) {
            return "redirect:/";
        }
        model.addAttribute("banner", banner);
        return "consent";
    }

    @PostMapping(ConsentFilter.CONSENT_URL)
    public String acknowledge(HttpServletRequest request) {
        if (!banner.isEnabled()) {
            return "redirect:/";
        }
        AuditLog log = audit.getIfAvailable();
        if (log != null) {
            // Before the session is marked: if the audit store is down, the banner stays unacknowledged.
            log.record(AuditEntry.of(AuditEntry.Category.AUTHENTICATION, "auth.consent-acknowledged")
                    .actor(AuditEntry.Actor.anonymous(null))
                    .detail("title", banner.getTitle()));
        }
        request.getSession().setAttribute(ConsentBanner.ACKNOWLEDGED, Instant.now());
        return "redirect:" + ConsentFilter.target(request.getSession());
    }
}
