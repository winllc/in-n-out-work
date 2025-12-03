package com.winllc.innoutwork.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LdapGroup {
    private String id;
    private String name;
    private boolean selectable = true;
    private boolean favorite;
    private int groupSize = 0;

    private String dn;
    private String cn;
    private String description;
    private List<LdapGroup> children = new ArrayList<>();

    public LdapGroup(String dn, String cn) {
        this.dn = dn;
        this.cn = cn;
        this.id = cn;
        this.name = cn;
    }

    public void addChild(LdapGroup group) {
        this.children.add(group);
    }
}