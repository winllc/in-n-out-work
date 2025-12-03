package com.winllc.innoutwork.data;

import lombok.Data;
import lombok.Getter;
import lombok.ToString;

public class LdapDn {

    @Getter
    private final String dn;

    public LdapDn(String dn) {
        this.dn = dn.replace(", ", ",").replace(" , ", ",");
    }

    @Override
    public String toString() {
        return dn;
    }

    public String getCn() {
        String[] parts = dn.split(",");
        if(parts.length > 0){
            return parts[0].trim().replace("cn=", "").replace("CN=", "");
        }
        return dn;
    }
}
