package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.support.LdapUtils;

import javax.naming.directory.BasicAttributes;
import javax.naming.directory.SearchControls;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the mapper behind the user search table. Every row the table renders is looked up again
 * by DN -- for the status badge, the notes and the details link -- so the DN the mapper hands back
 * has to be the absolute one, not the entry name relative to the search base.
 */
@ExtendWith(MockitoExtension.class)
class LdapServiceSearchTest {

    @Mock
    private LdapTemplate ldapTemplate;

    private LdapService ldapService;

    @BeforeEach
    void setUp() {
        ApplicationProperties props = new ApplicationProperties();
        props.setUserBaseDn("dc=winllc,dc=com");
        ldapService = new LdapService(ldapTemplate, props);
    }

    @Test
    void searchResultsCarryTheAbsoluteDn() throws Exception {
        when(ldapTemplate.search(anyString(), anyString(), any(SearchControls.class), any(ContextMapper.class)))
                .thenReturn(List.of());

        ldapService.search("(&(objectclass=inetOrgPerson)(cn=*bob*))");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ContextMapper<UserStatus>> mapper = ArgumentCaptor.forClass(ContextMapper.class);
        verify(ldapTemplate).search(anyString(), anyString(), any(SearchControls.class), mapper.capture());

        // With spring.ldap.base set, Spring LDAP hands the mapper a context whose own DN is relative
        // to that base and whose base holds the rest, exactly as DefaultDirObjectFactory builds it.
        DirContextAdapter context = new DirContextAdapter(new BasicAttributes(),
                LdapUtils.newLdapName("cn=bob,ou=users"),
                LdapUtils.newLdapName("dc=winllc,dc=com"));

        UserStatus user = mapper.getValue().mapFromContext(context);

        assertEquals("cn=bob,ou=users,dc=winllc,dc=com", user.getDn());
        assertEquals("bob", user.getCn());
    }
}
