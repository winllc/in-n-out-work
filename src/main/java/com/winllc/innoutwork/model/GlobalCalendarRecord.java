package com.winllc.innoutwork.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "global_calendar_records")
@EqualsAndHashCode(callSuper = false)
@ToString
public class GlobalCalendarRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate date;
    private boolean holiday = false;
    private String title;
}
