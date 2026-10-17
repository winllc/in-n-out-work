package com.winllc.innoutwork.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The single row ({@code id = 1}) every audit writer locks before appending: it holds the last seq and hash,
 * so seq stays gapless and the chain consistent however many requests, threads or instances write at once.
 * Also remembers the last recorded license mode, so a change is noticed across restarts.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "audit_chain_head")
public class AuditChainHead {

    public static final int ID = 1;

    @Id
    private Integer id;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "last_hash", nullable = false, length = 64)
    private String lastHash;

    @Column(name = "license_mode", length = 32)
    private String licenseMode;
}
