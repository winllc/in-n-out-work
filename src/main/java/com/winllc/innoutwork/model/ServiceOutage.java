package com.winllc.innoutwork.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A time no instance of ReadyRoom was running, so events the Windows client sent then were lost. */
@Data
@NoArgsConstructor
@Entity
@Table(name = "service_outage")
public class ServiceOutage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The last heartbeat before the outage. */
    private Instant downFrom;

    /** The first heartbeat after it. */
    private Instant downUntil;

    public ServiceOutage(Instant downFrom, Instant downUntil) {
        this.downFrom = downFrom;
        this.downUntil = downUntil;
    }
}
