package com.winllc.innoutwork.data;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class LdapOrg {
    private String name;
    private List<LdapDn> members = new ArrayList<>();
}
