package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.LdapUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.LdapTemplate;

import javax.naming.directory.SearchControls;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the LDAP filter behind the "my reports" table. The relationship is a pair of attributes:
 * a manager carries their own id in {@code managerLdapIdAttribute} and each report repeats it in
 * {@code userLdapManagerIdAttribute}, so the reports of a manager are the entries whose
 * {@code userLdapManagerIdAttribute} equals that id.
 */
@ExtendWith(MockitoExtension.class)
class LdapServiceReportsTest {

    @Mock
    private LdapTemplate ldapTemplate;

    private LdapService ldapService;

    @BeforeEach
    void setUp() {
        ApplicationProperties props = new ApplicationProperties();
        props.setUserBaseDn("dc=winllc,dc=com");
        props.setUserLdapFilter("objectclass=inetOrgPerson");
        // Matches the shipped application.yml: reports carry the manager id in 'title'.
        props.setUserLdapManagerIdAttribute("title");
        props.setManagerLdapIdAttribute("street");

        ldapService = new LdapService(ldapTemplate, props);
    }

    @Test
    void buildsAnAndFilterOnTheManagerIdAttribute() {
        when(ldapTemplate.search(anyString(), anyString(), any(SearchControls.class), any(ContextMapper.class)))
                .thenReturn(List.of(LdapUser.builder().dn("cn=bob").build()));

        List<LdapUser> reports = ldapService.findUsersReportingTo("MGR-100");

        ArgumentCaptor<String> base = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(ldapTemplate).search(base.capture(), filter.capture(),
                any(SearchControls.class), any(ContextMapper.class));

        assertEquals("dc=winllc,dc=com", base.getValue());
        assertEquals("(&(objectclass=inetOrgPerson)(title=MGR-100))", filter.getValue());
        assertEquals(1, reports.size());
    }

    /** A blank id must never become a filter that matches every entry in the directory. */
    @Test
    void aBlankManagerIdNeverQueries() {
        assertTrue(ldapService.findUsersReportingTo("").isEmpty());
        assertTrue(ldapService.findUsersReportingTo("   ").isEmpty());
        assertTrue(ldapService.findUsersReportingTo(null).isEmpty());

        verify(ldapTemplate, never()).search(anyString(), anyString(),
                any(SearchControls.class), any(ContextMapper.class));
    }

    /** Filter metacharacters in the id are escaped rather than altering the query. */
    @Test
    void theManagerIdIsEscaped() {
        when(ldapTemplate.search(anyString(), anyString(), any(SearchControls.class), any(ContextMapper.class)))
                .thenReturn(List.of());

        ldapService.findUsersReportingTo("MGR*)(uid=admin");

        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(ldapTemplate).search(anyString(), filter.capture(),
                any(SearchControls.class), any(ContextMapper.class));

        // Built from the char code so the expected value carries no source-level escaping.
        String esc = String.valueOf((char) 92);
        assertEquals("(&(objectclass=inetOrgPerson)(title=MGR"
                + esc + "2a" + esc + "29" + esc + "28uid=admin))", filter.getValue());
    }

    /** A directory failure degrades to an empty table rather than a 500. */
    @Test
    void aSearchFailureYieldsNoReports() {
        when(ldapTemplate.search(anyString(), anyString(), any(SearchControls.class), any(ContextMapper.class)))
                .thenThrow(new RuntimeException("directory down"));

        assertTrue(ldapService.findUsersReportingTo("MGR-100").isEmpty());
    }

    /** The search must cover nested OUs, not just the base entry. */
    @Test
    void theSearchIsSubtreeScoped() {
        when(ldapTemplate.search(anyString(), anyString(), any(SearchControls.class), any(ContextMapper.class)))
                .thenReturn(List.of());

        ldapService.findUsersReportingTo("MGR-100");

        ArgumentCaptor<SearchControls> controls = ArgumentCaptor.forClass(SearchControls.class);
        verify(ldapTemplate).search(anyString(), anyString(), controls.capture(), any(ContextMapper.class));

        assertEquals(SearchControls.SUBTREE_SCOPE, controls.getValue().getSearchScope());
    }
}
