package com.winllc.innoutwork.data.reports;

import com.winllc.innoutwork.data.LdapGroup;
import lombok.Getter;
import lombok.Setter;

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
