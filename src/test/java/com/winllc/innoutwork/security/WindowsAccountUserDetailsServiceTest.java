package com.winllc.innoutwork.security;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.support.InMemoryDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Which directory user a Windows account signs in as, against a real directory. */
class WindowsAccountUserDetailsServiceTest {

    private static final String BASE = InMemoryDirectory.BASE_DN;
    private static final String BOB = "cn=Bob Barker,ou=Users," + BASE;

    private static InMemoryDirectory directory;

    private ApplicationProperties props;
    private LdapService ldapService;
    private AppUserDetailsService appUsers;

    @BeforeAll
    static void startDirectory() {
        directory = InMemoryDirectory.start();
    }

    @AfterAll
    static void stopDirectory() {
        directory.close();
    }

    @BeforeEach
    void setUp() {
        props = new ApplicationProperties();
        props.setUserBaseDn(BASE);
        props.setUserLdapFilter("(objectclass=inetOrgPerson)");
        props.getWindowsAuth().setServicePrincipal("HTTP/inout.winllc.com@WINLLC.COM");
        // The fixture is OpenLDAP-shaped: the account name is uid rather than Active Directory's sAMAccountName.
        props.getWindowsAuth().setAccountAttribute("uid");
        ldapService = new LdapService(directory.ldapTemplate(), props);

        appUsers = mock(AppUserDetailsService.class);
        when(appUsers.loadUserByUsername(anyString()))
                .thenAnswer(inv -> User.withUsername(inv.getArgument(0)).password("").authorities("USER").build());
    }

    private WindowsAccountUserDetailsService service() {
        return new WindowsAccountUserDetailsService(ldapService, appUsers, props.getWindowsAuth());
    }

    @Test
    void anAccountSignsInAsTheDirectoryUserWithThatAccountName() {
        UserDetails bob = service().loadUserByUsername("bob@WINLLC.COM");

        assertEquals(BOB, bob.getUsername());
        verify(appUsers).loadUserByUsername(BOB); // the same lookup a certificate for Bob makes
    }

    @Test
    void theRealmIsComparedIgnoringCase() {
        assertEquals(BOB, service().loadUserByUsername("bob@winllc.com").getUsername());
    }

    /** A trusted domain's "bob" is not this domain's Bob. */
    @Test
    void onlyTheServicePrincipalsRealmIsAllowedByDefault() {
        assertThrows(UsernameNotFoundException.class, () -> service().loadUserByUsername("bob@PARTNER.COM"));
        assertThrows(UsernameNotFoundException.class, () -> service().loadUserByUsername("bob"));
        verifyNoInteractions(appUsers);
    }

    @Test
    void configuredRealmsReplaceTheDefault() {
        props.getWindowsAuth().setAllowedRealms(List.of("partner.com", "WINLLC.COM"));

        assertEquals(BOB, service().loadUserByUsername("bob@PARTNER.COM").getUsername());
        assertEquals(Set.of("PARTNER.COM", "WINLLC.COM"), WindowsAccountUserDetailsService.allowedRealms(props.getWindowsAuth()));
    }

    @Test
    void serviceAndMachinePrincipalsAreNotUsers() {
        assertThrows(UsernameNotFoundException.class, () -> service().loadUserByUsername("HTTP/inout.winllc.com@WINLLC.COM"));
        assertThrows(UsernameNotFoundException.class, () -> service().loadUserByUsername("host/pc01@WINLLC.COM"));
    }

    @Test
    void anAccountWithNoDirectoryUserIsRefused() {
        assertThrows(UsernameNotFoundException.class, () -> service().loadUserByUsername("nobody@WINLLC.COM"));
        assertThrows(UsernameNotFoundException.class, () -> service().loadUserByUsername("*@WINLLC.COM"),
                "the account name must be matched literally, not as a filter wildcard");
        verifyNoInteractions(appUsers);
    }

    /** Four users share o=WinLLC; guessing one would sign someone in as the wrong person. */
    @Test
    void anAccountNameSharedByMoreThanOneUserIsRefused() {
        props.getWindowsAuth().setAccountAttribute("o");

        assertThrows(UsernameNotFoundException.class, () -> service().loadUserByUsername("WinLLC@WINLLC.COM"));
        verifyNoInteractions(appUsers);
    }

    /** userPrincipalName-style matching: the whole principal against an attribute. */
    @Test
    void theWholePrincipalCanBeMatchedWhenTheRealmIsNotStripped() {
        props.getWindowsAuth().setAccountAttribute("mail");
        props.getWindowsAuth().setStripRealm(false);
        props.getWindowsAuth().setServicePrincipal("HTTP/inout.winllc.com@winllc.com");

        assertEquals(BOB, service().loadUserByUsername("bob@winllc.com").getUsername());
    }

    /** Only user entries count: a group with a matching name is not a person. */
    @Test
    void entriesOutsideTheUserFilterAreIgnored() {
        assertTrue(ldapService.lookupUniqueUser("cn", "Engineering").isEmpty());
        assertTrue(ldapService.lookupUniqueUser("uid", "bob").isPresent());
    }

    @Test
    void aDirectoryUserTheAppCannotLoadIsRefused() {
        doReturn(User.withUsername("NOTFOUND").password("").roles().build())
                .when(appUsers).loadUserByUsername(anyString());

        assertThrows(UsernameNotFoundException.class, () -> service().loadUserByUsername("bob@WINLLC.COM"));
    }
}
