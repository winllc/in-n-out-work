package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.SecurityConfig;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.ldap.query.LdapQuery;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

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
        String dn = username.replace(", ", ",");

        log.info("Looking up user: {}", dn);

        Optional<LdapUser> ldapUserOptional = ldapService.lookupUser(dn);

        List<String> roles = new ArrayList<>();

        if (ldapUserOptional.isPresent()) {
            LdapUser user =  ldapUserOptional.get();
            UserRecord record = createUserIfDoesNotExist(dn, user);

            AppUserDetails details = new AppUserDetails(record);

            if(properties.getSuperUserDns().stream()
                    .anyMatch(s -> user.getDn().equalsIgnoreCase(s))) {
                details.addAuthority("SUPER_USER");
            }
            return details;

        } else {
            return User.withUsername("NOTFOUND")
                    .password("") // not used with X.509
                    .roles(roles.toArray(new String[0]))
                    .build();
        }
    }

    private UserRecord createUserIfDoesNotExist(String dn, LdapUser ldapUser) {
        Optional<UserRecord> byDnIgnoreCase = userRecordRepository.findByDnIgnoreCase(dn);
        if(byDnIgnoreCase.isEmpty()){
            UserRecord userRecord = UserRecord.builder()
                    .dn(dn)
                    .employeeType(ldapUser.getEmployeeType())
                    .organization(ldapUser.getOrganization())
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
