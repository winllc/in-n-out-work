package com.winllc.innoutwork.data;

import lombok.Builder;
import org.springframework.ldap.support.LdapNameBuilder;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.util.Objects;

@Builder
public record LdapDn(String dn) {

    public LdapDn(String dn) {
        try {
            new LdapName(dn);
        } catch (InvalidNameException e) {
            throw new IllegalArgumentException(e);
        }
        this.dn = dn.replace(", ", ",").replace(" , ", ",");
    }

    @Override
    public String toString() {
        return dn;
    }

    public String getCn() {
        String[] parts = dn.split(",");
        if (parts.length > 0) {
            return parts[0].trim().replace("cn=", "").replace("CN=", "");
        }
        return dn;
    }

    public String getName(){
        LdapName name = LdapNameBuilder.newInstance(dn).build();
        Rdn firstRdn = name.getRdn(name.size() - 1);
        return firstRdn.getValue().toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        LdapDn ldapDn = (LdapDn) o;
        return Objects.equals(dn.toUpperCase(), ldapDn.dn.toUpperCase());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(dn.toUpperCase());
    }
}
