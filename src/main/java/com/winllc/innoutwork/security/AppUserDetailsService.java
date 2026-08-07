package com.winllc.innoutwork.security;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.service.UserService;
import org.jspecify.annotations.NonNull;
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

    private final UserService userService;
    private final ApplicationProperties properties;
    private final LoadingCache<String, LdapUser> userCache;

    public AppUserDetailsService(UserService userService,
                                 ApplicationProperties properties,
                                 @Qualifier("ldapUserLoadingCache")
                                 LoadingCache<String, LdapUser> userCache) {
        this.userService = userService;
        this.properties = properties;
        this.userCache = userCache;
    }

    @Override
    public UserDetails loadUserByUsername(@NonNull String username) throws UsernameNotFoundException {
        LdapDn dn = LdapDn.builder().dn(username).build();

        log.info("Looking up user: {}", dn);

        LdapUser ldapUser = userCache.get(username);

        if (ldapUser != null) {
            Set<UserRoleEnum> roles = new HashSet<>();
            roles.add(UserRoleEnum.USER);

            UserRecord record = userService.createUserIfDoesNotExist(dn);

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


}
