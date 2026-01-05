package com.winllc.innoutwork.data.reports;

import io.micrometer.common.util.StringUtils;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@NoArgsConstructor
@Data
public class DayReport implements Comparable<DayReport> {
    private LocalDate date;
    private int totalLoggedIn;
    private int totalLoggedOut;
    private int hoursWorked;
    private Map<String, Integer> employeeTypeCounts = new HashMap<>();
    private Map<String, Integer> orgCounts = new HashMap<>();
    private Map<String, Integer> locationCounts = new HashMap<>();

    public DayReport(LocalDate date) {
        this.date = date;
    }

    public void addUserReport(UserReport userReport) {
        userReport.getDayReports().stream()
                .filter(udr -> udr.getDay().equals(this.date))
                .findFirst()
                .ifPresent(udr -> {
                    if(udr.getCheckInTime() != null){
                        this.totalLoggedIn++;

                        employeeTypeCounts.compute(StringUtils.isBlank(userReport.getEmployeeType()) ? "N/A" : userReport.getEmployeeType(), (key, value) -> value == null ? 1 : value + 1);
                        orgCounts.compute(StringUtils.isBlank(userReport.getOrganization()) ? "N/A" : userReport.getOrganization(), (key, value) -> value == null ? 1 : value + 1);
                        locationCounts.compute(StringUtils.isBlank(userReport.getLocation()) ? "N/A" : userReport.getLocation(), (key, value) -> value == null ? 1 : value + 1);
                    }
                    if(udr.getCheckOutTime() != null){
                        this.totalLoggedOut++;
                    }
                   });
    }

    @Override
    public int compareTo(DayReport o) {
        return this.date.compareTo(o.date);
    }
}
