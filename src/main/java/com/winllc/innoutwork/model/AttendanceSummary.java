package com.winllc.innoutwork.model;

import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * One person's month, rolled up by the retention purge before it deletes the check-in records and calendar statuses
 * it summarises (service.RetentionService): days checked in, time in, and days per status. Only months the purge
 * has reached have one. Schema: db/migration/V4__retention_summaries.sql.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "attendance_summaries")
public class AttendanceSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String dn;

    /** The first day of the month. */
    @Column(nullable = false)
    private LocalDate month;

    /** Days with a check-in. */
    @Column(nullable = false)
    private int daysCheckedIn;

    /** First check-in to last check-out each day, added up, as the reports count it. */
    @Column(nullable = false)
    private long minutesIn;

    /** Days per calendar status (other than normal). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "attendance_summary_statuses", joinColumns = @JoinColumn(name = "summary_id"))
    @MapKeyColumn(name = "status", columnDefinition = "varchar(50)")
    @MapKeyEnumerated(EnumType.STRING)
    @Column(name = "days", nullable = false)
    private Map<UserStatusEnum, Integer> statusDays = new EnumMap<>(UserStatusEnum.class);

    @Column(nullable = false)
    private ZonedDateTime updatedAt;

    public AttendanceSummary(String dn, LocalDate month) {
        this.dn = dn;
        this.month = month.withDayOfMonth(1);
    }

    public String getCn() {
        return LdapDn.cnOf(dn);
    }

    /** "12 h 05 min". */
    public String getTimeIn() {
        return "%d h %02d min".formatted(minutesIn / 60, minutesIn % 60);
    }

    /** "Scheduled Leave 3, Work From Home 5", in the statuses' order. */
    public String getStatusSummary() {
        StringBuilder text = new StringBuilder();
        for (UserStatusEnum status : UserStatusEnum.values()) {
            Integer days = statusDays.get(status);
            if (days != null && days > 0) {
                if (!text.isEmpty()) {
                    text.append(", ");
                }
                text.append(status.getFriendlyName()).append(' ').append(days);
            }
        }
        return text.toString();
    }
}
