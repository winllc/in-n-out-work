package com.winllc.innoutwork.security;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.service.TrackedPeopleLimitException;
import com.winllc.innoutwork.service.UserService;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

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

    /**
     * The directory user {@code username} (a DN) names, with their roles.
     *
     * @throws UsernameNotFoundException when no directory user has that DN. Such a principal, a certificate issued
     *                                   to a machine or to someone not in the directory, does not sign in at all:
     *                                   it used to sign in as a role-less "NOTFOUND" user.
     */
    @Override
    public UserDetails loadUserByUsername(@NonNull String username) throws UsernameNotFoundException {
        LdapDn dn;
        try {
            dn = LdapDn.builder().dn(username).build();
        } catch (IllegalArgumentException e) {
            throw new UsernameNotFoundException("Not a directory DN: " + username);
        }

        // Runs on every authentication.
        log.debug("Looking up user: {}", dn);

        LdapUser ldapUser = userCache.get(username);

        if (ldapUser != null) {
            Set<UserRoleEnum> roles = new HashSet<>();
            roles.add(UserRoleEnum.USER);

            UserRecord record;
            try {
                record = userService.createUserIfDoesNotExist(dn);
            } catch (TrackedPeopleLimitException e) {
                // Over the licensed size: signing in still works, but this person is not tracked (no
                // record is stored, and the check-in API refuses their events with license_limit).
                log.warn("Signing in {} without tracking them: {}", dn, e.getMessage());
                record = new UserRecord(ldapUser);
            }

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
            log.debug("Refused sign-in for {}: no such user in the directory", dn);
            throw new UsernameNotFoundException("No directory user " + dn);
        }
    }


}
