package com.winllc.innoutwork.data;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class LdapUser {
    private String dn;
    private String cn;
    private String sn;
    private String mail;
    private String uid;
    private String employeeType;
    private String organization;
    private String location;
    private String department;
    private String branch;
    private String managerId;
    private String phoneNumber;
    private String email;
}
