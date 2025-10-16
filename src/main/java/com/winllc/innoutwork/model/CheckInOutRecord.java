package com.winllc.innoutwork.model;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@Entity
@Table(name = "check_in_out_records")
public class CheckInOutRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = true)
    private String dn;

    @Column(nullable = false)
    private ZonedDateTime timestamp;

    private String sessionId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private CheckInOutEnum action;
    private String windowsUserId;

    public CheckInOutRecord() {
        this.sessionId = UUID.randomUUID().toString(); // Generate UUID in constructor
    }

    @PrePersist
    public void generateId() {
        if (this.sessionId == null) {
            this.sessionId = UUID.randomUUID().toString();
        }
    }
}
