package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.http.ResponseEntity;
import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.RequiresFeature;
import com.winllc.innoutwork.data.GenerateReportForm;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.reports.GroupReport;
import com.winllc.innoutwork.service.ReportService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import java.time.ZonedDateTime;

import static com.winllc.innoutwork.constant.DateTimeConstants.ISO_DATE_TIME_FORMATTER;

@Controller
@RequestMapping("/app/group")
public class GroupController {

    private final ReportService reportService;

    public GroupController(ReportService reportService) {
        this.reportService = reportService;
    }

    private AuditLog audit = AuditLog.NOOP;

    @Autowired(required = false)
    public void setAuditLog(AuditLog audit) {
        this.audit = audit;
    }

    @RequiresFeature(LicensedFeature.METRICS)
    @PostMapping("/report")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public ModelAndView generateReport(@ModelAttribute("form") GenerateReportForm form){
        ModelAndView mav = new ModelAndView("groupReport");

        if(form.getFromDate() != null && form.getToDate() != null) {
            ZonedDateTime fromDate = ZonedDateTime.parse(form.getFromDate(), ISO_DATE_TIME_FORMATTER);
            ZonedDateTime toDate = ZonedDateTime.parse(form.getToDate(), ISO_DATE_TIME_FORMATTER);

            GroupReport report = reportService.generateGroupReport(LdapDn.builder().dn(form.getGroupDn()).build(),
                    fromDate,
                    toDate);

            mav.addObject("report", report);
            audit.record(AuditEntry.of(AuditEntry.Category.DATA, "data.exported")
                    .target("group", form.getGroupDn(), LdapDn.cnOf(form.getGroupDn()))
                    .detail("report", "group")
                    .detail("format", "html")
                    .detail("from", form.getFromDate())
                    .detail("to", form.getToDate()));
        }

        return mav;
    }

    /**
     * The report page's "Download PDF": the PDF is drawn in the browser, so the page tells the server it was
     * downloaded, for the audit trail. Records data.exported and nothing else.
     */
    @RequiresFeature(LicensedFeature.METRICS)
    @PostMapping("/report/exported")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    @ResponseBody
    public ResponseEntity<Void> reportExported(@RequestParam String groupDn,
                                               @RequestParam(required = false) String fromDate,
                                               @RequestParam(required = false) String toDate,
                                               @RequestParam(defaultValue = "pdf") String format) {
        audit.record(AuditEntry.of(AuditEntry.Category.DATA, "data.exported")
                .target("group", groupDn, LdapDn.cnOf(groupDn))
                .detail("report", "group")
                .detail("format", "pdf".equalsIgnoreCase(format) ? "pdf" : format)
                .detail("from", fromDate)
                .detail("to", toDate));
        return ResponseEntity.noContent().build();
    }
}
