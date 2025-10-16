package com.winllc.innoutwork.data;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class LdapGroup {
    private String dn;
    private String cn;
    private String description;
    private List<LdapGroup> childGroups = new ArrayList<>();

    public void addChild(LdapGroup group) {
        this.childGroups.add(group);
    }
}