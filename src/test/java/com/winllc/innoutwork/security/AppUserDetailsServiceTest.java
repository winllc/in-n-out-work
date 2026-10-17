package com.winllc.innoutwork.security;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Who a certificate or a directory bind signs in as: a directory user with their roles, or nobody. */
class AppUserDetailsServiceTest {

    private static final String BOB = "CN=Bob,OU=Users,DC=winllc,DC=com";
    private static final String ADA = "CN=Ada,OU=Users,DC=winllc,DC=com";

    private final UserService userService = mock(UserService.class);
    private final ApplicationProperties properties = new ApplicationProperties();
    private AppUserDetailsService service;

    @BeforeEach
    void setUp() {
        Map<String, LdapUser> directory = Map.of(
                BOB, LdapUser.builder().dn(BOB).build(),
                ADA, LdapUser.builder().dn(ADA).build());
        LoadingCache<String, LdapUser> users = Caffeine.newBuilder().build(directory::get);
        properties.setSuperUserDns(List.of(ADA));
        when(userService.createUserIfDoesNotExist(any()))
                .thenAnswer(inv -> UserRecord.builder().dn(inv.<LdapDn>getArgument(0).dn()).build());

        service = new AppUserDetailsService(userService, properties, users);
    }

    private static Set<String> authorities(UserDetails details) {
        return details.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @Test
    void aDirectoryUserSignsInWithTheirRoles() {
        assertEquals(Set.of("USER"), authorities(service.loadUserByUsername(BOB)));
        assertEquals(Set.of("USER", "ADMIN"), authorities(service.loadUserByUsername(ADA)));
    }

    /** It used to sign in as a role-less "NOTFOUND" principal, which then passed every authenticated() rule. */
    @Test
    void aCertificateForNobodyInTheDirectoryDoesNotSignIn() {
        assertThrows(UsernameNotFoundException.class,
                () -> service.loadUserByUsername("CN=PC01,OU=Computers,DC=winllc,DC=com"));

        verifyNoInteractions(userService);
    }

    @Test
    void aNameThatIsNotADnDoesNotSignInEither() {
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("not a dn"));
    }

    @Test
    void signingInKeepsTheUsersRecord() {
        service.loadUserByUsername(BOB);

        verify(userService).createUserIfDoesNotExist(new LdapDn(BOB));
    }
}
