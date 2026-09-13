package com.winllc.innoutwork.model;

import jakarta.persistence.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "permission_records")
@EqualsAndHashCode(callSuper = false)
@ToString
public class PermissionRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Excluded from equals/hashCode as from toString: UserRecord's permissions list points back here,
    // so including it would recurse.
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private UserRecord user;
    private String groupDn;
}
