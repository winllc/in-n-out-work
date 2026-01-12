package com.winllc.innoutwork.data.reports;

import com.winllc.innoutwork.data.LdapUser;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

@Getter
public class UserReport {
    private String userDn;
    private List<UserDayReport> dayReports;
    private String employeeType;
    private String organization;
    private String department;
    private String location;

    public static UserReport createUserReport(LdapUser ldapUser, List<UserDayReport> dayReports) {
        UserReport report = new UserReport();
        report.userDn = ldapUser.getDn();
        report.dayReports = dayReports;
        report.employeeType = ldapUser.getEmployeeType();
        report.organization = ldapUser.getOrganization();
        report.location = ldapUser.getLocation();
        report.department = ldapUser.getDepartment();

        Collections.sort(report.dayReports);

        return report;
    }
}
