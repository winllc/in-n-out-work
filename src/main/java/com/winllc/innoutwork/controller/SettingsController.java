package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.model.OrgParseRuleRecord;
import com.winllc.innoutwork.repository.OrgParseRuleRecordRepository;
import jakarta.servlet.http.HttpSession;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Controller
@RequestMapping("/app/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final OrgParseRuleRecordRepository orgParseRuleRecordRepository;

    public SettingsController(OrgParseRuleRecordRepository orgParseRuleRecordRepository) {
        this.orgParseRuleRecordRepository = orgParseRuleRecordRepository;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority(" +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public ModelAndView details(HttpSession session, Authentication auth, Model model) {

        ModelAndView mav = new ModelAndView("settings");

        mav.addObject("userDn", auth != null ? auth.getName() : "");
        mav.addObject("orgParseRules",
                orgParseRuleRecordRepository.findAll(Sort.by("orgName")));
        // A failed save redirects the submitted form back as a flash attribute (already in the
        // model here); only seed an empty one when it isn't present so invalid input is preserved.
        if (!model.containsAttribute("orgParseRuleForm")) {
            mav.addObject("orgParseRuleForm", new OrgParseRuleRecord());
        }

        return mav;
    }

    @PostMapping("/orgparserules")
    @PreAuthorize("hasAnyAuthority(" +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public String saveOrgParsingRule(@ModelAttribute("orgParseRuleForm") OrgParseRuleRecord form,
                                     RedirectAttributes redirectAttributes) {
        log.debug("Saving org parse rule: {}", form);

        if (StringUtils.isBlank(form.getOrgName()) || StringUtils.isBlank(form.getOrgParseRegex())) {
            redirectAttributes.addFlashAttribute("error", "Organization name and parse regex are both required.");
            redirectAttributes.addFlashAttribute("orgParseRuleForm", form);
            return "redirect:/app/settings";
        }

        // Validate the regex up front so we never persist a pattern that would blow up parsing later.
        try {
            Pattern.compile(form.getOrgParseRegex());
        } catch (PatternSyntaxException e) {
            redirectAttributes.addFlashAttribute("error",
                    "Invalid regex '%s': %s".formatted(form.getOrgParseRegex(), e.getDescription()));
            redirectAttributes.addFlashAttribute("orgParseRuleForm", form);
            return "redirect:/app/settings";
        }

        OrgParseRuleRecord record;
        if (form.getId() != null) {
            record = orgParseRuleRecordRepository.findById(form.getId()).orElseGet(OrgParseRuleRecord::new);
        } else {
            record = new OrgParseRuleRecord();
        }

        record.setOrgName(form.getOrgName().trim());
        record.setOrgParseRegex(form.getOrgParseRegex().trim());

        orgParseRuleRecordRepository.save(record);

        redirectAttributes.addFlashAttribute("message",
                "Saved parse rule for '%s'.".formatted(record.getOrgName()));
        return "redirect:/app/settings";
    }

    @PostMapping("/orgparserules/delete/{id}")
    @PreAuthorize("hasAnyAuthority(" +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public String deleteOrgParsingRule(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        log.debug("Deleting org parse rule: {}", id);

        orgParseRuleRecordRepository.deleteById(id);

        redirectAttributes.addFlashAttribute("message", "Deleted parse rule.");
        return "redirect:/app/settings";
    }

}
