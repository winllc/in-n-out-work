package com.winllc.innoutwork.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * A group attendance report e-mailed on a schedule (RR-19): every day (yesterday), week (last Monday to Sunday) or
 * month (last month), as CSV or XLSX, to people who may run that report themselves. Its owner's and recipients'
 * rights are checked again at every run. Schema: V10__reports.sql.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@Entity
@Table(name = "report_schedules")
public class ReportSchedule {

    public enum Frequency {
        DAILY("Every day, for the day before"),
        WEEKLY("Every Monday, for the week before"),
        MONTHLY("On the 1st, for the month before");

        private final String description;

        Frequency(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "owner_dn", nullable = false, length = 1000)
    private String ownerDn;

    @Column(name = "group_dn", nullable = false, length = 1000)
    private String groupDn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Frequency frequency;

    /** csv or xlsx. */
    @Column(nullable = false, length = 10)
    private String format;

    /** The recipients' DNs, ';' separated. */
    @Column(nullable = false, length = 4000)
    private String recipients;

    /** The zone its periods and its sending hour are in: its owner's when it was made. */
    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    /** The hour of the day (0 to 23) it is sent at. */
    @Column(name = "send_hour", nullable = false)
    private int sendHour;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_result", length = 500)
    private String lastResult;

    public List<String> getRecipientList() {
        return recipients == null || recipients.isBlank() ? List.of()
                : Arrays.stream(recipients.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public String getGroupName() {
        return com.winllc.innoutwork.data.LdapDn.cnOf(groupDn);
    }
}
