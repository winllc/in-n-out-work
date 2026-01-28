package com.winllc.innoutwork.security;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.LdapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AppUserDetailsService implements UserDetailsService {
    private static final Logger log = LoggerFactory.getLogger(AppUserDetailsService.class);

    private final UserRecordRepository userRecordRepository;
    private final ApplicationProperties properties;
    private final LoadingCache<String, LdapUser> userCache;

    public AppUserDetailsService(UserRecordRepository userRecordRepository,
                                 ApplicationProperties properties,
                                 @Qualifier("ldapUserLoadingCache")
                                 LoadingCache<String, LdapUser> userCache) {
        this.userRecordRepository = userRecordRepository;
        this.properties = properties;
        this.userCache = userCache;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        LdapDn dn = LdapDn.builder().dn(username).build();

        log.info("Looking up user: {}", dn);

        LdapUser ldapUser = userCache.get(username);

        if (ldapUser != null) {
            Set<UserRoleEnum> roles = new HashSet<>();
            roles.add(UserRoleEnum.USER);

            UserRecord record = createUserIfDoesNotExist(dn, ldapUser);

            AppUserDetails details = new AppUserDetails(record);

            if(properties.getSuperUserDns().stream()
                    .anyMatch(s -> ldapUser.getDn().equalsIgnoreCase(s))) {
                roles.add(UserRoleEnum.ADMIN);
            }

            UserRoleEnum userRole = record.getUserRole();
            if(userRole != null) {
                roles.add(userRole);
            }

            for(UserRoleEnum role : roles) {
                details.addAuthority(role.name());
            }

            return details;

        } else {
            return User.withUsername("NOTFOUND")
                    .password("") // not used with X.509
                    .roles(new String[]{})
                    .build();
        }
    }

    private UserRecord createUserIfDoesNotExist(LdapDn dn, LdapUser ldapUser) {
        Optional<UserRecord> byDnIgnoreCase = userRecordRepository.findByDnIgnoreCase(dn.toString());
        if(byDnIgnoreCase.isEmpty()){
            UserRecord userRecord = UserRecord.builder()
                    .dn(dn.toString())
                    .employeeType(ldapUser.getEmployeeType())
                    .organization(ldapUser.getOrganization())
                    .location(ldapUser.getLocation())
                    .branch(ldapUser.getBranch())
                    .userRole(UserRoleEnum.USER)
                    .build();
            return userRecordRepository.save(userRecord);
        }else{
            UserRecord userRecord = byDnIgnoreCase.get();
            boolean updated = false;
            if(!Objects.equals(ldapUser.getEmployeeType(), userRecord.getEmployeeType())){
                userRecord.setEmployeeType(ldapUser.getEmployeeType());
                updated = true;
            }
            if(!Objects.equals(ldapUser.getOrganization(), userRecord.getOrganization())){
                userRecord.setOrganization(ldapUser.getOrganization());
                updated = true;
            }
            if(!Objects.equals(ldapUser.getLocation(), userRecord.getLocation())){
                userRecord.setLocation(ldapUser.getLocation());
                updated = true;
            }
            if(!Objects.equals(ldapUser.getLocation(), userRecord.getBranch())){
                userRecord.setBranch(ldapUser.getBranch());
                updated = true;
            }

            if(updated){
                userRecord = userRecordRepository.save(userRecord);
            }

            return userRecord;
        }
    }
}
