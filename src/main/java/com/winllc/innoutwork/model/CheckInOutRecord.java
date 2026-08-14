package com.winllc.innoutwork.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static com.winllc.innoutwork.constant.DateTimeConstants.DATE_TIME_FORMAT;

@Data
@Builder
@AllArgsConstructor
@Entity
@Table(name = "check_in_out_records")
public class CheckInOutRecord implements Comparable<CheckInOutRecord> {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = true)
    private String dn;

    @Column(nullable = false)
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DATE_TIME_FORMAT)
    private ZonedDateTime timestamp;

    private String sessionId;

    @Column(nullable = false, columnDefinition = "text")
    @Enumerated(EnumType.STRING)
    private CheckInOutEnum action;
    private String windowsUserId;
    private String organization;
    private String employeeType;
    private String location;
    private String branch;
    private String dutySubOrganization;
    private Boolean forced = false;

    public CheckInOutRecord() {
        this.sessionId = UUID.randomUUID().toString(); // Generate UUID in constructor
    }

    @PrePersist
    public void generateId() {
        if (this.sessionId == null) {
            this.sessionId = UUID.randomUUID().toString();
        }
    }

    @Override
    public int compareTo(CheckInOutRecord o) {
        return o.timestamp.compareTo(this.timestamp);
    }

    public ZonedDateTime getZonedDateTimestamp(){
        return ZonedDateTime.ofInstant(timestamp.toInstant(), ZoneId.systemDefault());
    }
}
