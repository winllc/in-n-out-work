package com.winllc.innoutwork.model;

import com.winllc.innoutwork.constant.UserStatusEnum;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.ZonedDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_event_records")
@EqualsAndHashCode(callSuper = false)
@ToString
public class UserEventRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String dn;
    @Column(nullable = false)
    private LocalDate date;
    @Column(nullable = false, columnDefinition = "text")
    @Enumerated(EnumType.STRING)
    private UserStatusEnum status;
}
