package com.winllc.innoutwork.model;

import jakarta.persistence.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "org_parse_rules")
@EqualsAndHashCode(callSuper = false)
@ToString
public class OrgParseRuleRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String orgName;
    private String orgParseRegex;
}
