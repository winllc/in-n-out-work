package com.winllc.innoutwork.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.UUID;

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
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm a z")
    private ZonedDateTime timestamp;

    private String sessionId;

    @Column(nullable = false, columnDefinition = "text")
    @Enumerated(EnumType.STRING)
    private CheckInOutEnum action;
    private String windowsUserId;
    private String organization;
    private String employeeType;
    private String location;

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
}
