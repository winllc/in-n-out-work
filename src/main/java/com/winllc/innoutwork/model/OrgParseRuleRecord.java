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
public class OrgParseRuleRecord implements java.io.Serializable {

    /** A form that failed validation goes back to the page as a flash attribute, in the database session. */
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String orgName;
    private String orgParseRegex;
}
