package com.winllc.innoutwork.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * The service's one heartbeat row (id 1, created by V6): when any running instance last said it was up. A gap is an
 * outage of the whole service (see service.ServiceOutages).
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "service_heartbeat")
public class ServiceHeartbeat {

    public static final int ID = 1;

    @Id
    private Integer id;

    private Instant beatAt;

    /** The instance that last beat, for diagnosis. */
    private String instance;
}
