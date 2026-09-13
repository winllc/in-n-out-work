package com.winllc.innoutwork.diagnostics;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.TopLevelGroupProperties;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.support.InMemoryDirectory;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class RequestMetricsTest {

    private static final String BASE = InMemoryDirectory.BASE_DN;

    @Test
    void nothingIsCountedOutsideARequest() {
        RequestMetrics.sqlStatement();
        RequestMetrics.ldapOperation();

        assertEquals(new RequestMetrics.Counts(0, 0), RequestMetrics.stop());
    }

    /** One count per directory operation: a lookup, a paged search of several pages, a cached read (none). */
    @Test
    void ldapOperationsAreCountedOncePerOperation() {
        try (InMemoryDirectory directory = InMemoryDirectory.start(2)) {
            LdapTemplate template = directory.ldapTemplate();
            ((org.springframework.beans.factory.config.BeanPostProcessor) RequestMetricsConfig.requestMetricsLdapCounter())
                    .postProcessAfterInitialization(template, "ldapTemplate");
            ApplicationProperties props = new ApplicationProperties();
            props.setUserBaseDn(BASE);
            props.getLdap().setPageSize(2);
            TopLevelGroupProperties groups = new TopLevelGroupProperties();
            groups.setGroupsBaseDn("ou=Groups," + BASE);
            props.setGroups(List.of(groups));
            LdapService ldap = new LdapService(template, props);

            RequestMetrics.start();
            ldap.lookupUser(new LdapDn("cn=Bob Barker,ou=Users," + BASE));
            assertEquals(5, ldap.search("(objectClass=inetOrgPerson)").size()); // three pages, one connection
            ldap.findGroupsForUser("cn=Bob Barker,ou=Users," + BASE);
            ldap.findGroupsForUser("cn=Bob Barker,ou=Users," + BASE);         // cached
            RequestMetrics.Counts counts = RequestMetrics.stop();

            assertEquals(3, counts.ldapOperations());
        }
    }

    @Test
    void theFilterLogsWhatTheRequestDidAndClearsTheCounts(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/home");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req, HttpServletResponse res) {
                RequestMetrics.sqlStatement();
                RequestMetrics.sqlStatement();
                RequestMetrics.ldapOperation();
            }
        });

        new RequestMetricsFilter().doFilter(request, response, chain);

        assertTrue(output.getOut().contains("GET /app/home -> 200: 2 SQL statement(s), 1 LDAP operation(s)"), output.getOut());
        assertEquals(new RequestMetrics.Counts(0, 0), RequestMetrics.stop(), "counts leaked past the request");
    }

    @Test
    void staticFilesAreNotLogged() {
        assertTrue(new RequestMetricsFilter().shouldNotFilter(new MockHttpServletRequest("GET", "/js/app.js")));
        assertFalse(new RequestMetricsFilter().shouldNotFilter(new MockHttpServletRequest("GET", "/api/users/search")));
    }
}
