package com.winllc.innoutwork.data.reports;

import com.winllc.innoutwork.data.LdapGroup;
import lombok.Getter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.winllc.innoutwork.constant.DateTimeConstants.DATE_FORMATTER;

@Getter
public class GroupReport {
    private String groupDn;
    private String groupName;
    private List<UserReport> userReports = new ArrayList<>();
    private LocalDate fromDate;
    private LocalDate toDate;
    private List<DayReport> dayReports = new ArrayList<>();
    /**
     * Set when the report reaches back before what the retention purge kept: check-ins and statuses before this day
     * are gone, and {@link #monthlySummaries} stand in for them, one per member and month.
     */
    private LocalDate purgedBefore;
    private List<com.winllc.innoutwork.model.AttendanceSummary> monthlySummaries = new ArrayList<>();

    /** The report covers days the retention purge has summarised; their per-day rows are empty. */
    public void addPurgedPeriod(LocalDate purgedBefore, List<com.winllc.innoutwork.model.AttendanceSummary> summaries) {
        this.purgedBefore = purgedBefore;
        this.monthlySummaries = new ArrayList<>(summaries);
    }

    public String getFormattedPurgedBefore() {
        return purgedBefore == null ? null : DATE_FORMATTER.format(purgedBefore);
    }

    public static GroupReport build(LdapGroup ldapGroup, LocalDate fromDate, LocalDate toDate) {
        GroupReport report = new GroupReport();
        report.groupDn = ldapGroup.getDn();
        report.groupName = ldapGroup.getCn();
        report.fromDate = fromDate;
        report.toDate = toDate;
        return report;
    }

    public String getFormattedFromDate() {
        return DATE_FORMATTER.format(fromDate);
    }

    public String getFormattedToDate() {
        return DATE_FORMATTER.format(toDate);
    }
}
