package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.StatusClass;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.EventRange;
import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.RequiresFeature;
import com.winllc.innoutwork.model.StatusRequest;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.InvalidRequestException;
import com.winllc.innoutwork.service.StatusPolicy;
import com.winllc.innoutwork.service.StatusRequestService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Supplier;

/**
 * The approvals page: the pending requests the signed-in user decides (their reports', the people they are a
 * delegate of, everyone's for an administrator), and their own requests. Server-rendered forms, so it works without
 * scripts; StatusRequestService decides who may do what on every post.
 */
@RequiresFeature(LicensedFeature.CALENDAR)
@Controller
@RequestMapping("/app/approvals")
@PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).USER)")
public class ApprovalsController {

    private final StatusRequestService requests;
    private final StatusPolicy statusPolicy;
    private final AccessPolicy accessPolicy;
    private final ApplicationProperties properties;

    public ApprovalsController(StatusRequestService requests, StatusPolicy statusPolicy, AccessPolicy accessPolicy,
                               ApplicationProperties properties) {
        this.requests = requests;
        this.statusPolicy = statusPolicy;
        this.accessPolicy = accessPolicy;
        this.properties = properties;
    }

    @GetMapping
    public String page(Authentication authentication, Model model) {
        List<StatusRequest> pending = requests.pendingFor(authentication);
        model.addAttribute("pending", pending);
        model.addAttribute("mine", requests.mine(authentication.getName()));
        model.addAttribute("isAdmin", accessPolicy.isAdmin(authentication));
        model.addAttribute("requestStatuses", statusPolicy.statuses(StatusClass.REQUEST));
        model.addAttribute("maxRangeDays", properties.getEvents().getMaxRangeDays());
        model.addAttribute("today", LocalDate.now());
        return "approvals";
    }

    /** Ask for a status over whole days, {@code fromDate} to {@code lastDate} inclusive. */
    @PostMapping("/request")
    public String request(Authentication authentication, @RequestParam String status, @RequestParam String fromDate,
                          @RequestParam String lastDate, @RequestParam(required = false) String note,
                          RedirectAttributes redirect) {
        return run(redirect, () -> {
            UserStatusEnum requested;
            try {
                requested = UserStatusEnum.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw InvalidRequestException.badRequest("invalid_status", "Unknown status: " + status);
            }
            LocalDate last;
            try {
                last = LocalDate.parse(lastDate);
            } catch (DateTimeParseException e) {
                throw InvalidRequestException.badRequest("invalid_range", "The last day is not a date");
            }
            EventRange range = EventRange.parse(fromDate, last.plusDays(1).toString(),
                    properties.getEvents().getMaxRangeDays());
            StatusRequest created = requests.create(authentication.getName(), requested, range, null, note);
            return "Requested " + created.getStatusName() + " for " + created.getDays()
                    + (created.getDays() == 1 ? " day" : " days") + ". It counts once it is approved.";
        });
    }

    @PostMapping("/{id}/approve")
    public String approve(Authentication authentication, @PathVariable long id,
                          @RequestParam(required = false) String comment, RedirectAttributes redirect) {
        return run(redirect, () -> {
            StatusRequest approved = requests.approve(authentication, id, comment);
            return "Approved " + approved.getStatusName() + " for " + approved.getRequesterCn() + ".";
        });
    }

    @PostMapping("/{id}/reject")
    public String reject(Authentication authentication, @PathVariable long id,
                         @RequestParam(required = false) String comment, RedirectAttributes redirect) {
        return run(redirect, () -> {
            StatusRequest rejected = requests.reject(authentication, id, comment);
            return "Rejected " + rejected.getStatusName() + " for " + rejected.getRequesterCn() + ".";
        });
    }

    @PostMapping("/{id}/cancel")
    public String cancel(Authentication authentication, @PathVariable long id, RedirectAttributes redirect) {
        return run(redirect, () -> {
            StatusRequest cancelled = requests.cancel(authentication, id);
            return "Cancelled the " + cancelled.getStatusName() + " request.";
        });
    }

    /** Runs a step and redirects back with its outcome; a refusal for who asks (403) is not caught. */
    private static String run(RedirectAttributes redirect, Supplier<String> step) {
        try {
            redirect.addFlashAttribute("message", step.get());
        } catch (InvalidRequestException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/app/approvals";
    }
}
