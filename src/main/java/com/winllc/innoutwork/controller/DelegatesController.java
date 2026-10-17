package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.service.DelegateService;
import com.winllc.innoutwork.service.InvalidRequestException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Administration &gt; Delegates: everyone with delegates (alternate managers), those people chose for themselves
 * before administrators managed them first, to keep or remove. A person's delegates are edited on their details
 * page. Administrators only.
 */
@Controller
@RequestMapping("/app/admin/delegates")
@PreAuthorize("hasAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN)")
public class DelegatesController {

    private final DelegateService delegates;

    public DelegatesController(DelegateService delegates) {
        this.delegates = delegates;
    }

    /** One row: the person and their delegates, by name. */
    public record Row(String dn, String cn, List<String> delegateDns, List<String> delegateCns, boolean reviewPending) {
        static Row of(UserRecord record) {
            List<String> dns = record.getAltManagerList();
            return new Row(record.getDn(), LdapDn.cnOf(record.getDn()), dns, dns.stream().map(LdapDn::cnOf).toList(),
                    record.isAlternateManagersReviewPending());
        }
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "false") boolean pending, Model model) {
        model.addAttribute("rows", delegates.withDelegates(pending).stream().map(Row::of).toList());
        model.addAttribute("pendingOnly", pending);
        model.addAttribute("pendingCount", delegates.pendingReviewCount());
        return "delegates";
    }

    /** Keeps the delegates a person chose before: no longer marked for review. */
    @PostMapping("/keep")
    public String keep(@RequestParam String dn, RedirectAttributes redirect) {
        try {
            delegates.keep(dn);
            redirect.addFlashAttribute("message", "Kept the delegates of " + LdapDn.cnOf(dn) + ".");
        } catch (InvalidRequestException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/app/admin/delegates";
    }

    /** Removes all of a person's delegates: only their directory manager and administrators then decide for them. */
    @PostMapping("/clear")
    public String clear(@RequestParam String dn, RedirectAttributes redirect) {
        try {
            delegates.setDelegates(dn, List.of());
            redirect.addFlashAttribute("message", "Removed the delegates of " + LdapDn.cnOf(dn) + ".");
        } catch (InvalidRequestException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/app/admin/delegates";
    }
}
