package com.winllc.innoutwork.data;

import com.winllc.innoutwork.model.UserRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AppUserDetails implements UserDetails {

    private String dn;
    private String organization;
    private String employeeType;
    private String location;
    private boolean isMyHr = false;
    private List<GrantedAuthority> grantedAuthorities = new ArrayList<>();

    public AppUserDetails(UserRecord record) {
        this.dn = record.getDn();
        this.organization = record.getOrganization();
        this.employeeType = record.getEmployeeType();
        this.location = record.getLocation();
    }

    public void addAuthority(String grantedAuthority) {
        grantedAuthorities.add(new SimpleGrantedAuthority(grantedAuthority));
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return grantedAuthorities;
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return this.dn;
    }
}
