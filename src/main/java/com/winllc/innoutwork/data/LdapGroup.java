package com.winllc.innoutwork.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.CollectionUtils;

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

    public LdapGroup filterByWhitelist(List<LdapDn> whitelist) {

        return filterGroup(this, whitelist);
    }

    public LdapGroup filterGroup(LdapGroup group, List<LdapDn> whitelist) {
        LdapGroup ldapGroup = new LdapGroup();
        ldapGroup.setId(group.getId());
        ldapGroup.setName(group.getName());
        ldapGroup.setSelectable(group.isSelectable());
        ldapGroup.setFavorite(group.isFavorite());
        ldapGroup.setGroupSize(group.getGroupSize());
        ldapGroup.setDn(group.getDn());

        if(!CollectionUtils.isEmpty(group.getChildren())){
            List<LdapGroup> children = new ArrayList<>();

            for(LdapGroup child : group.getChildren()){
                LdapGroup childFiltered = filterGroup(child, whitelist);
                if(childFiltered != null){
                    children.add(childFiltered);
                }
            }
            ldapGroup.setChildren(children);
        }

        boolean whitelisted = whitelist.stream()
                .anyMatch(d -> new LdapDn(ldapGroup.getDn()).equals(d));
        if(whitelisted){
            return ldapGroup;
        }else{
            return null;
        }
    }
}