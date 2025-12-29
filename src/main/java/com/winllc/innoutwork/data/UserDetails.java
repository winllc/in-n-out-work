package com.winllc.innoutwork.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Data
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class UserDetails {
    private String dn;
    private List<LdapGroup> memberOf = new ArrayList<>();
    private String notes;
    private String status;
    private String employeeType;
    private String organization;
    private String location;
    private String role;
}
