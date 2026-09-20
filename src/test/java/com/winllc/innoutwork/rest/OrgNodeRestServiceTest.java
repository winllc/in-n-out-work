package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.OrgChartService;
import com.winllc.innoutwork.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The org-chart user lookup identifies people with the same configured filter as every other
 * user lookup; it used to name inetOrgPerson itself, so a directory configured for anything
 * else returned nobody here while working everywhere else.
 */
@ExtendWith(MockitoExtension.class)
class OrgNodeRestServiceTest {

    @Mock
    private OrgChartService orgChartService;
    @Mock
    private LdapService ldapService;
    @Mock
    private UserService userService;

    private ApplicationProperties props;
    private OrgNodeRestService service;

    @BeforeEach
    void setUp() {
        props = new ApplicationProperties();
        service = new OrgNodeRestService(orgChartService, props, ldapService, userService);

        when(ldapService.search(anyString())).thenReturn(List.of());
    }

    private String filterSentFor(String orgName) {
        service.getUsers(null, new MockHttpSession(), orgName);

        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(ldapService).search(filter.capture());
        return filter.getValue();
    }

    @Test
    void theDefaultFilterIsAndedWithTheOrganizationName() {
        assertEquals("(&(objectclass=inetOrgPerson)(dutySubOrganization=RYS34B))",
                filterSentFor("RYS34B"));
    }

    @Test
    void aConfiguredFilterIsUsedInsteadOfInetOrgPerson() {
        props.setUserLdapFilter("(objectclass=posixAccount)");

        assertEquals("(&(objectclass=posixAccount)(dutySubOrganization=RYS34B))",
                filterSentFor("RYS34B"));
    }

    @Test
    void aBareConfiguredFilterStillComposesIntoAValidFilter() {
        // Unparenthesised in config; without normalisation this would compose to
        // "(&objectclass=posixAccount(...))", which JNDI rejects.
        props.setUserLdapFilter("objectclass=posixAccount");

        assertEquals("(&(objectclass=posixAccount)(dutySubOrganization=RYS34B))",
                filterSentFor("RYS34B"));
    }
}
