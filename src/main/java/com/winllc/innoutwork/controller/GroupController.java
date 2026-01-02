package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.data.GenerateReportForm;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.reports.GroupReport;
import com.winllc.innoutwork.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Controller
@RequestMapping("/app/group")
public class GroupController {

    private final DateTimeFormatter dtf = DateTimeFormatter.ISO_ZONED_DATE_TIME;

    @Autowired
    private ReportService reportService;

    @PostMapping("/report")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public ModelAndView generateReport(@ModelAttribute("form") GenerateReportForm form){
        ModelAndView mav = new ModelAndView("groupReport");

        if(form.getFromDate() != null && form.getToDate() != null) {
            ZonedDateTime fromDate = ZonedDateTime.parse(form.getFromDate(), dtf);
            ZonedDateTime toDate = ZonedDateTime.parse(form.getToDate(), dtf);

            GroupReport report = reportService.generateGroupReport(LdapDn.builder().dn(form.getGroupDn()).build(),
                    fromDate,
                    toDate);

            mav.addObject("report", report);
        }

        return mav;
    }
}
