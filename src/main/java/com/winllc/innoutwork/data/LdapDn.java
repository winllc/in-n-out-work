package com.winllc.innoutwork.data;

import lombok.Builder;
import org.springframework.ldap.support.LdapNameBuilder;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

@Builder
public record LdapDn(String dn) {

    public LdapDn(String dn) {
        try {
            new LdapName(dn);
        } catch (InvalidNameException e) {
            throw new IllegalArgumentException(e);
        }
        this.dn = normalize(dn);
    }

    /**
     * Rewrites a DN with bare commas between its RDNs, the form DNs are stored and compared in.
     * <p>
     * Parsed rather than string-replaced: in {@code cn=Doe\, Jane} the ", " is part of the value,
     * and dropping its space would name a different entry. A value that does not parse as a DN
     * (an anonymous principal, say) is returned unchanged.
     */
    public static String normalize(String dn) {
        if (dn == null) {
            return null;
        }
        try {
            List<Rdn> rdns = new LdapName(dn).getRdns();
            StringJoiner joined = new StringJoiner(",");
            for (int i = rdns.size() - 1; i >= 0; i--) {
                joined.add(rdns.get(i).toString());
            }
            return joined.toString();
        } catch (InvalidNameException e) {
            return dn;
        }
    }

    /**
     * The display name for a DN: the unescaped value of its leftmost RDN when that is a cn,
     * otherwise that RDN as written.
     */
    public static String cnOf(String dn) {
        if (dn == null) {
            return "";
        }
        try {
            LdapName name = new LdapName(dn);
            if (name.isEmpty()) {
                return "";
            }
            Rdn first = name.getRdn(name.size() - 1);
            return first.getType().equalsIgnoreCase("cn") ? first.getValue().toString() : first.toString();
        } catch (InvalidNameException e) {
            return dn.split(",")[0].trim();
        }
    }

    @Override
    public String toString() {
        return dn;
    }

    public String getCn() {
        return cnOf(dn);
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
