package com.winllc.innoutwork.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * The retention purge's one row (id 1, created by V4): up to which day records have been summarised and deleted, and
 * when it last ran. Every purge batch locks it, so purges on several instances take turns.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "retention_state")
public class RetentionState {

    public static final int ID = 1;

    @Id
    private Integer id;

    /** Check-in records and statuses before this day are gone, summarised by month; null until the first purge. */
    private LocalDate purgedBefore;

    private ZonedDateTime lastRunAt;
}
