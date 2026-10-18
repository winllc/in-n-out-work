package com.winllc.innoutwork.security;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.controller.OrgChartController;
import com.winllc.innoutwork.controller.UserController;
import com.winllc.innoutwork.data.OrgNode;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.rest.OrgNodeRestService;
import com.winllc.innoutwork.rest.UserRestService;
import com.winllc.innoutwork.service.DelegateService;
import com.winllc.innoutwork.service.DirectReportsService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.OrgChartService;
import com.winllc.innoutwork.service.UserService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.winllc.innoutwork.support.TestCerts.cert;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * application.privacy.coworker-visibility on every page and API where someone sees other people: a search, a
 * group's and an organization's members, a person's page, the org chart. In each mode the viewer's own people (here
 * TARGET, as a report) are shown in full; coworkers (OTHER) as the mode says. AccessPolicyTest covers who the
 * viewer's people are and that administrators see everyone in full.
 */
@WebMvcTest
@Import({UserRestService.class, OrgNodeRestService.class, UserController.class, OrgChartController.class,
        CoworkerVisibilityTest.TestSecurityConfig.class})
class CoworkerVisibilityTest {

    private static final String VIEWER = "CN=viewer,OU=Users,DC=winllc,DC=com";
    private static final String TARGET = "CN=target,OU=Users,DC=winllc,DC=com";
    private static final String OTHER = "CN=other,OU=Users,DC=winllc,DC=com";
    private static final String GROUP = "cn=Engineering,ou=Groups,dc=winllc,dc=com";

    @Autowired private MockMvc mockMvc;

    @MockitoBean(name = "accessPolicy") AccessPolicy accessPolicy;
    @MockitoBean LdapService ldapService;
    @MockitoBean UserService userService;
    @MockitoBean UserRecordRepository userRecordRepository;
    @MockitoBean CheckInOutRecordRepository checkInOutRecordRepository;
    @MockitoBean OrgChartService orgChartService;
    @MockitoBean DirectReportsService directReportsService;
    @MockitoBean DelegateService delegateService;
    @MockitoBean com.winllc.innoutwork.service.Approvers approvers;

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .x509(x509 -> x509.subjectPrincipalRegex("(.*)").userDetailsService(userDetailsService()));
            return http.build();
        }

        @Bean
        UserDetailsService userDetailsService() {
            return username -> User.withUsername(username).password("").authorities(UserRoleEnum.USER.name()).build();
        }

        @Bean
        ApplicationProperties applicationProperties() {
            return new ApplicationProperties();
        }
    }

    /** Everyone is on telework today, checked in at 08:00, with notes: a status, a presence, a time and a note. */
    private void policyIs(CoworkerVisibility visibility) {
        when(accessPolicy.coworkerVisibility()).thenReturn(visibility);
        when(accessPolicy.scope(any())).thenReturn(AccessPolicy.Scope.of(Set.of(TARGET)::contains, visibility));
        when(accessPolicy.canViewDetails(any(), anyString())).thenAnswer(inv -> TARGET.equals(inv.getArgument(1)));
        when(accessPolicy.canViewGroup(any(), anyString())).thenReturn(true);
        when(accessPolicy.seesPresenceCounts(any())).thenReturn(visibility != CoworkerVisibility.NONE);
        when(userService.getUserStatuses(any(), any())).thenAnswer(inv -> inv.<Collection<String>>getArgument(0).stream()
                .map(CoworkerVisibilityTest::teleworking).toList());
    }

    private static UserStatus teleworking(String dn) {
        return UserStatus.builder().dn(dn).status("WORK_FROM_HOME").presence("IN").notes("notes of " + dn)
                .checkedInAt(ZonedDateTime.now()).lastStatusChangeAt(ZonedDateTime.now()).organization("Eng").build();
    }

    private static String coworkerStatus(CoworkerVisibility visibility) {
        return switch (visibility) {
            case FULL -> "WORK_FROM_HOME";
            case STATUS -> "IN";
            case NONE -> null;
        };
    }

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request.with(x509(cert(VIEWER))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(body);
    }

    private static void assertRows(JsonNode rows, CoworkerVisibility visibility) {
        assertEquals(2, rows.size(), rows.toString());
        for (JsonNode row : rows) {
            boolean own = TARGET.equals(row.get("dn").asString());
            String expected = own ? "WORK_FROM_HOME" : coworkerStatus(visibility);
            if (expected == null) {
                assertFalse(row.hasNonNull("status"), row.toString());
            } else {
                assertEquals(expected, row.get("status").asString(), row.toString());
            }
            assertEquals(own, row.hasNonNull("notes"), row.toString());
            assertEquals(own, row.hasNonNull("checkedInAt"), row.toString());
            assertEquals("Eng", row.get("organization").asString(), "who they are, in every mode");
            assertFalse(row.has("presence"), "the raw presence is never sent");
        }
    }

    @ParameterizedTest
    @EnumSource(CoworkerVisibility.class)
    void search(CoworkerVisibility visibility) throws Exception {
        policyIs(visibility);
        when(ldapService.search(anyString())).thenReturn(List.of(
                UserStatus.builder().dn(TARGET).build(), UserStatus.builder().dn(OTHER).build()));

        assertRows(json(get("/api/users/search").param("search", "t")), visibility);
    }

    @ParameterizedTest
    @EnumSource(CoworkerVisibility.class)
    void aGroupsMembers(CoworkerVisibility visibility) throws Exception {
        policyIs(visibility);
        when(ldapService.getGroupMembers(any())).thenReturn(List.of(TARGET, OTHER));

        assertRows(json(get("/api/users/group/{group}", GROUP)).get("data"), visibility);
    }

    @ParameterizedTest
    @EnumSource(CoworkerVisibility.class)
    void anOrganizationsMembers(CoworkerVisibility visibility) throws Exception {
        policyIs(visibility);
        when(ldapService.search(anyString())).thenReturn(List.of(
                UserStatus.builder().dn(TARGET).build(), UserStatus.builder().dn(OTHER).build()));

        assertRows(json(get("/api/orgnodes/users/{org}", "Engineering")), visibility);
    }

    @ParameterizedTest
    @EnumSource(CoworkerVisibility.class)
    void aPersonsPage(CoworkerVisibility visibility) throws Exception {
        policyIs(visibility);
        when(userService.getUserDetails(any(), any())).thenAnswer(inv -> teleworking(inv.<com.winllc.innoutwork.data.LdapDn>getArgument(0).dn()));

        UserStatus coworker = (UserStatus) mockMvc.perform(get("/app/user/details/{dn}", OTHER).with(x509(cert(VIEWER))))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("user");
        UserStatus own = (UserStatus) mockMvc.perform(get("/app/user/details/{dn}", TARGET).with(x509(cert(VIEWER))))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("user");

        assertEquals(coworkerStatus(visibility), coworker.getStatus());
        assertNull(coworker.getNotes());
        assertNull(coworker.getCheckedInAt());
        assertEquals("Eng", coworker.getOrganization());
        assertEquals("WORK_FROM_HOME", own.getStatus());
        assertEquals("notes of " + TARGET, own.getNotes());
    }

    /** The organization's root, with one organization under it where three of four people are in. */
    private static OrgNode orgWithThreeOfFourIn() {
        OrgNode root = new OrgNode("TOP");
        root.setFullName("TOP");
        OrgNode org = new OrgNode("RYS34");
        org.setFullName("RYS34");
        org.getData().setFullName("RYS34");
        org.getData().setNodeMembersByEmployeeType(Map.of("CIV", 3));
        org.getData().setTotalMembersByEmployeeType(Map.of("CIV", 4));
        org.setParent(root);
        root.getChildren().add(org);
        return root;
    }

    @ParameterizedTest
    @EnumSource(CoworkerVisibility.class)
    void theOrgChartsPresentCounts(CoworkerVisibility visibility) throws Exception {
        policyIs(visibility);
        when(orgChartService.loadStatistics()).thenAnswer(inv -> orgWithThreeOfFourIn());

        JsonNode data = json(get("/api/orgnodes/hierarchy")).get("children").get(0).get("data");
        String page = mockMvc.perform(get("/app/orgchart").with(x509(cert(VIEWER))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        boolean hidden = visibility == CoworkerVisibility.NONE;
        assertEquals(hidden, data.get("presenceHidden").asBoolean());
        assertEquals(hidden ? 0 : 3, data.get("fullTreeNodeMembersByEmployeeType").path("CIV").asInt(0));
        assertEquals(4, data.get("fullTreeTotalMembersByEmployeeType").get("CIV").asInt(), "the headcount stays");
        assertEquals(!hidden, page.contains("3 / 4"), page);
        assertEquals(hidden, page.contains("4 people"));
    }

    @org.junit.jupiter.api.Test
    void aCopyWithoutPresenceLeavesTheSharedTreeAlone() {
        OrgNode tree = orgWithThreeOfFourIn();
        tree.rollupStats();

        OrgNode copy = tree.withoutPresence();

        assertTrue(copy.getData().isPresenceHidden());
        assertTrue(copy.getChildren().getFirst().getData().getNodeMembersByEmployeeType().isEmpty());
        assertEquals(4, copy.getData().getFullTreeTotalCount());
        assertEquals(3, tree.getData().getFullTreePresentCount(), "the cached tree is untouched");
        assertFalse(tree.getData().isPresenceHidden());
    }
}
