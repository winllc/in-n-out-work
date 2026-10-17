package com.winllc.innoutwork.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.winllc.innoutwork.constant.RequestState;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static com.winllc.innoutwork.constant.DateTimeConstants.DATE_TIME_FORMAT;

/**
 * A status a person asked for over a range of days (leave, telework, ...): {@link RequestState#REQUESTED} until their
 * manager, a delegate or an administrator approves or rejects it, or they cancel it. Only an approved request puts
 * the status on the calendar ({@link UserEventRecord#getRequestId()}), so only then does it excuse an absence.
 * Schema: db/migration/V2__status_requests.sql.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "status_requests")
public class StatusRequest {

    /** The most characters of a requester's note or an approver's comment kept. */
    public static final int NOTE_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String requesterDn;

    @Column(nullable = false, columnDefinition = "varchar(50)")
    @Enumerated(EnumType.STRING)
    private UserStatusEnum status;

    /** The first day. */
    @Column(nullable = false)
    private LocalDate fromDate;

    /** The last day, included. */
    @Column(nullable = false)
    private LocalDate toDate;

    /** A late arrival's time, when the status is LATE_ARRIVAL. */
    private LocalTime loginByTime;

    /** Optional, from the requester; shown to the approvers. */
    @Column(length = NOTE_LENGTH)
    private String note;

    @Column(nullable = false, columnDefinition = "varchar(20)")
    @Enumerated(EnumType.STRING)
    private RequestState state;

    @Column(nullable = false)
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DATE_TIME_FORMAT)
    private ZonedDateTime createdAt;

    /** Who approved or rejected it, and when. */
    private String decidedByDn;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DATE_TIME_FORMAT)
    private ZonedDateTime decidedAt;
    /** Optional, from the approver; shown to the requester. */
    @Column(length = NOTE_LENGTH)
    private String decisionNote;

    private String cancelledByDn;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DATE_TIME_FORMAT)
    private ZonedDateTime cancelledAt;

    public String getRequesterCn() {
        return LdapDn.cnOf(requesterDn);
    }

    public String getDecidedByCn() {
        return decidedByDn == null ? null : LdapDn.cnOf(decidedByDn);
    }

    public String getStatusName() {
        return status == null ? null : status.getFriendlyName();
    }

    /** Days covered, both ends included. */
    public int getDays() {
        return fromDate == null || toDate == null ? 0 : (int) ChronoUnit.DAYS.between(fromDate, toDate) + 1;
    }
}
