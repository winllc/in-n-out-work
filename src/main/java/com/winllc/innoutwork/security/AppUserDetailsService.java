package com.winllc.innoutwork.security;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.LdapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class AppUserDetailsService implements UserDetailsService {
    private static final Logger log = LoggerFactory.getLogger(AppUserDetailsService.class);

    private final LdapService ldapService;
    private final UserRecordRepository userRecordRepository;
    private final ApplicationProperties properties;

    public AppUserDetailsService(LdapService ldapService, UserRecordRepository userRecordRepository,
                                 ApplicationProperties properties) {
        this.ldapService = ldapService;
        this.userRecordRepository = userRecordRepository;
        this.properties = properties;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        LdapDn dn = LdapDn.builder().dn(username).build();

        log.info("Looking up user: {}", dn);

        Optional<LdapUser> ldapUserOptional = ldapService.lookupUser(dn);

        if (ldapUserOptional.isPresent()) {
            LdapUser user =  ldapUserOptional.get();
            UserRecord record = createUserIfDoesNotExist(dn, user);

            AppUserDetails details = new AppUserDetails(record);

            if(properties.getSuperUserDns().stream()
                    .anyMatch(s -> user.getDn().equalsIgnoreCase(s))) {
                details.addAuthority(UserRoleEnum.ADMIN.name());
            }

            UserRoleEnum userRole = record.getUserRole();
            if(userRole != null) {
                details.addAuthority(userRole.name());
            }else{
                details.addAuthority(UserRoleEnum.USER.name());
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

            if(updated){
                userRecord = userRecordRepository.save(userRecord);
            }

            return userRecord;
        }
    }
}
