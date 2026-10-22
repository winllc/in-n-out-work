package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.export.ReportScheduleService;
import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.RequiresFeature;
import com.winllc.innoutwork.model.ReportSchedule;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.InvalidRequestException;
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

import java.util.LinkedHashMap;
import java.util.Map;

/** Reports &rarr; Scheduled reports ({@code /app/reports/schedules}): e-mailed group attendance reports. */
@Controller
@RequiresFeature(LicensedFeature.METRICS)
@RequestMapping("/app/reports/schedules")
@PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, "
        + "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
public class ReportSchedulesController {

    static final String PATH = "/app/reports/schedules";

    private final ReportScheduleService schedules;
    private final AccessPolicy accessPolicy;

    public ReportSchedulesController(ReportScheduleService schedules, AccessPolicy accessPolicy) {
        this.schedules = schedules;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping
    public String page(Authentication authentication, @RequestParam(defaultValue = "") String groupDn, Model model) {
        Map<String, String> groups = new LinkedHashMap<>();
        if (!groupDn.isBlank() && schedules.mayRunReport(authentication, groupDn)) {
            groups.put(groupDn, LdapDn.cnOf(groupDn));
        }
        for (LdapDn group : accessPolicy.viewableGroups(authentication)) {
            if (schedules.mayRunReport(authentication, group.dn())) {
                groups.putIfAbsent(group.dn(), group.getCn());
            }
        }
        model.addAttribute("groups", groups);
        model.addAttribute("selectedGroup", groupDn);
        model.addAttribute("frequencies", ReportSchedule.Frequency.values());
        model.addAttribute("schedules", schedules.list(authentication));
        if (!model.containsAttribute("recipients")) {
            model.addAttribute("recipients", authentication.getName());
        }
        return "report-schedules";
    }

    @PostMapping
    public String create(Authentication authentication, @RequestParam(defaultValue = "") String name,
                         @RequestParam String groupDn, @RequestParam ReportSchedule.Frequency frequency,
                         @RequestParam(defaultValue = "csv") String format,
                         @RequestParam(defaultValue = "") String recipients,
                         @RequestParam(defaultValue = "7") int sendHour, Model model, RedirectAttributes flash) {
        try {
            schedules.create(name, groupDn, frequency, format, recipients, sendHour, authentication);
        } catch (InvalidRequestException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("recipients", recipients);
            model.addAttribute("name", name);
            return page(authentication, groupDn, model);
        }
        flash.addFlashAttribute("notice", "Scheduled. The first one goes out at the next sending time.");
        return "redirect:" + PATH;
    }

    @PostMapping("/{id}/delete")
    public String delete(Authentication authentication, @PathVariable long id, RedirectAttributes flash) {
        flash.addFlashAttribute("notice", schedules.delete(id, authentication)
                ? "Schedule deleted." : "There is no such schedule of yours.");
        return "redirect:" + PATH;
    }

    @PostMapping("/{id}/send")
    public String send(Authentication authentication, @PathVariable long id, RedirectAttributes flash) {
        flash.addFlashAttribute("notice", schedules.sendNow(id, authentication).orElse("There is no such schedule of yours."));
        return "redirect:" + PATH;
    }
}
