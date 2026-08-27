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
    private String dutySubOrganization;
    // The id this user is known by to their reports: subordinates carry it in the
    // userLdapManagerIdAttribute, this user carries it in the managerLdapIdAttribute.
    private String managerLdapId;
    private String managerId;
    private String phoneNumber;
    private String email;
}
