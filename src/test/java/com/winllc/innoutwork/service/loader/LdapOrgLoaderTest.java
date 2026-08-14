package com.winllc.innoutwork.service.loader;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.OrgNode;
import com.winllc.innoutwork.model.OrgParseRuleRecord;
import com.winllc.innoutwork.repository.OrgParseRuleRecordRepository;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.OrgChartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The org-chart parsing/merging logic moved here from {@link OrgChartService}; the loader
 * now builds the tree and the service only decorates it with statistics.
 */
@ExtendWith(MockitoExtension.class)
class LdapOrgLoaderTest {

    private static final String ORG_ATTRIBUTE = "dutySubOrganization";

    @Mock
    private OrgParseRuleRecordRepository orgParseRuleRecordRepository;

    @Mock
    private LdapService ldapService;

    @Mock
    private OrgChartService orgChartService;

    private ApplicationProperties props;
    private LdapOrgLoader loader;

    @BeforeEach
    void setUp() {
        // A real properties object rather than a mock: these are plain getters, and
        // stubbing each one would just add noise.
        props = new ApplicationProperties();
        props.setOrganizationName("TOP");
        props.setDutySubOrgGroupsBaseDn("ou=orgs,dc=example,dc=com");
        props.setUserLdapDutySubOrganizationAttribute(ORG_ATTRIBUTE);

        loader = new LdapOrgLoader(props, orgParseRuleRecordRepository, ldapService);
    }

    /* ------------------------------------------------------------------ *
     * Parsing a single org value
     * ------------------------------------------------------------------ */

    /**
     * The default parse splits on every letter/digit boundary, so "RYS34B" becomes the
     * chain RYS -> 34 -> B, with each node's fullName being the concatenation of the path.
     */
    @Test
    void defaultOrgNodeParseSplitsOnLetterDigitBoundaries() {
        OrgNode node = loader.defaultOrgNodeParse("RYS34B");

        assertEquals("RYS", node.getName());
        assertEquals("RYS", node.getFullName());
        // RYS + 34 + B
        assertEquals(3, node.getTotalChildren());

        OrgNode mid = node.getChildren().get(0);
        assertEquals("34", mid.getName());
        assertEquals("RYS34", mid.getFullName());

        OrgNode leaf = mid.getChildren().get(0);
        assertEquals("B", leaf.getName());
        assertEquals("RYS34B", leaf.getFullName());
        assertTrue(leaf.getChildren().isEmpty());
    }

    @Test
    void ruleOrgNodeParseUsesRegexCaptureGroups() {
        OrgNode ruleNode = loader.ruleOrgNodeParse("abc445de", "([a-zA-Z]+\\d)(\\d+)([a-zA-Z]+)$");

        assertNotNull(ruleNode);
        assertEquals("abc4", ruleNode.getName());

        OrgNode mid = ruleNode.getChildren().get(0);
        assertEquals("45", mid.getName());
        assertEquals("abc445", mid.getFullName());

        OrgNode leaf = mid.getChildren().get(0);
        assertEquals("de", leaf.getName());
        assertEquals("abc445de", leaf.getFullName());
    }

    @Test
    void ruleOrgNodeParseReturnsNullWhenRegexDoesNotMatch() {
        assertNull(loader.ruleOrgNodeParse("nomatch", "^(\\d+)$"));
    }

    /* ------------------------------------------------------------------ *
     * Merging parsed trees
     * ------------------------------------------------------------------ */

    /**
     * Merging trees that share a root collapses the common prefix and keeps the differing
     * leaves as siblings, rather than duplicating the shared ancestors.
     */
    @Test
    void mergeCollapsesSharedAncestorsAndKeepsDistinctLeaves() {
        OrgNode node = loader.defaultOrgNodeParse("RYS34B");
        OrgNode node2 = loader.defaultOrgNodeParse("RYS34C");
        OrgNode node3 = loader.defaultOrgNodeParse("RYS35A");

        node.merge(node2);
        // RYS + 34 + B + C
        assertEquals(4, node.getTotalChildren());

        node.merge(node3);
        // RYS + 34 + B + C + 35 + A
        assertEquals(6, node.getTotalChildren());

        // "34" and "35" are siblings under the single shared "RYS" root.
        assertEquals("RYS", node.getFullName());
        assertEquals(List.of("RYS34", "RYS35"), childFullNames(node));

        // "B" and "C" are siblings under the shared "RYS34".
        OrgNode thirtyFour = node.getChildren().get(0);
        assertEquals(List.of("RYS34B", "RYS34C"), childFullNames(thirtyFour));
    }

    @Test
    void mergeIgnoresNodeWithDifferentRoot() {
        OrgNode node = loader.defaultOrgNodeParse("RYS34B");
        int before = node.getTotalChildren();

        node.merge(loader.defaultOrgNodeParse("ZZZ11A"));

        assertEquals(before, node.getTotalChildren(), "a differently-rooted tree must not be merged in");
    }

    @Test
    void mergeIgnoresNull() {
        OrgNode node = loader.defaultOrgNodeParse("RYS34B");
        int before = node.getTotalChildren();

        node.merge(null);

        assertEquals(before, node.getTotalChildren());
    }

    /* ------------------------------------------------------------------ *
     * Building the chart from a list of org values
     * ------------------------------------------------------------------ */

    /**
     * Values sharing a root end up under one node; unrelated values become separate roots.
     */
    @Test
    void generateOrgChartGroupsValuesByRoot() {
        when(orgParseRuleRecordRepository.findAll()).thenReturn(List.of());

        List<OrgNode> chart = loader.generateOrgChart(
                List.of("RYS34B", "RYS34C", "RYS35A", "abc445de"));

        assertEquals(2, chart.size());

        OrgNode rys = findRoot(chart, "RYS");
        assertEquals(6, rys.getTotalChildren());

        OrgNode abc = findRoot(chart, "abc");
        // abc + 445 + de
        assertEquals(3, abc.getTotalChildren());
    }

    @Test
    void generateOrgChartAppliesMatchingParseRule() {
        when(orgParseRuleRecordRepository.findAll()).thenReturn(List.of(
                OrgParseRuleRecord.builder()
                        .orgName("abc")
                        .orgParseRegex("([a-zA-Z]+\\d)(\\d+)([a-zA-Z]+)$")
                        .build()));

        List<OrgNode> chart = loader.generateOrgChart(List.of("abc445de"));

        assertEquals(1, chart.size());
        // The rule splits as abc4/45/de rather than the default abc/445/de.
        assertEquals("abc4", chart.get(0).getName());
    }

    @Test
    void generateOrgChartSkipsNullAndEmptyValues() {
        when(orgParseRuleRecordRepository.findAll()).thenReturn(List.of());

        List<OrgNode> chart = loader.generateOrgChart(Arrays.asList("RYS34B", null, ""));

        assertEquals(1, chart.size());
        assertEquals("RYS", chart.get(0).getName());
    }

    /**
     * A parse rule whose regex fails to match yields no node at all, and must not blow up
     * the rest of the chart.
     */
    @Test
    void generateOrgChartDropsValuesWhoseRuleDoesNotMatch() {
        when(orgParseRuleRecordRepository.findAll()).thenReturn(List.of(
                OrgParseRuleRecord.builder()
                        .orgName("abc")
                        .orgParseRegex("^(\\d+)$")
                        .build()));

        List<OrgNode> chart = loader.generateOrgChart(List.of("abc445de", "RYS34B"));

        assertEquals(1, chart.size());
        assertEquals("RYS", chart.get(0).getName());
    }

    /* ------------------------------------------------------------------ *
     * Reading org values from the directory
     * ------------------------------------------------------------------ */

    @Test
    void generateOrgChartReadsTheOrgValuesFromTheConfiguredAttribute() {
        when(ldapService.getAllUniqueValuesForAttributes(ORG_ATTRIBUTE))
                .thenReturn(List.of("RYS34B", "RYS34C"));
        when(orgParseRuleRecordRepository.findAll()).thenReturn(List.of());

        List<OrgNode> chart = loader.generateOrgChart();

        assertEquals(1, chart.size());
        assertEquals("RYS", chart.get(0).getFullName());
    }

    /**
     * Without a duty sub-org base DN there is nothing to enumerate, so the directory is
     * never queried.
     */
    @Test
    void generateOrgChartReturnsEmptyWhenNoBaseDnIsConfigured() {
        props.setDutySubOrgGroupsBaseDn("");

        assertTrue(loader.generateOrgChart().isEmpty());

        verifyNoInteractions(ldapService);
    }

    /* ------------------------------------------------------------------ *
     * Top-level node and CacheLoader contract
     * ------------------------------------------------------------------ */

    /**
     * The roots parsed out of the directory hang off a synthetic node named after the
     * configured organization - this is what the REST layer serves as the chart root.
     */
    @Test
    void generateTopLevelOrgChartWrapsRootsUnderTheConfiguredOrganizationName() {
        when(ldapService.getAllUniqueValuesForAttributes(ORG_ATTRIBUTE))
                .thenReturn(List.of("RYS34B", "abc445de"));
        when(orgParseRuleRecordRepository.findAll()).thenReturn(List.of());

        OrgNode top = loader.generateTopLevelOrgChart();

        assertEquals("TOP", top.getName());
        assertEquals("TOP", top.getId());
        assertEquals(List.of("RYS", "abc"), top.getChildren().stream().map(OrgNode::getName).toList());
    }

    @Test
    void loadReturnsTheTopLevelChart() {
        when(ldapService.getAllUniqueValuesForAttributes(ORG_ATTRIBUTE))
                .thenReturn(List.of("RYS34B"));
        when(orgParseRuleRecordRepository.findAll()).thenReturn(List.of());

        OrgNode loaded = loader.load("TOP");

        assertEquals("TOP", loaded.getName());
        assertEquals(1, loaded.getChildren().size());
    }

    /**
     * Refresh happens in the background, so a failed rebuild must leave the cached tree in
     * place rather than propagating and evicting it.
     */
    @Test
    void reloadFallsBackToThePreviousValueWhenTheRebuildFails() throws Exception {
        OrgNode previous = new OrgNode("TOP");
        when(ldapService.getAllUniqueValuesForAttributes(ORG_ATTRIBUTE))
                .thenThrow(new RuntimeException("ldap down"));

        assertSame(previous, loader.reload("TOP", previous));
    }

    @Test
    void reloadReturnsTheRebuiltChartOnSuccess() throws Exception {
        when(ldapService.getAllUniqueValuesForAttributes(ORG_ATTRIBUTE))
                .thenReturn(List.of("RYS34B"));
        when(orgParseRuleRecordRepository.findAll()).thenReturn(List.of());

        OrgNode previous = new OrgNode("TOP");
        OrgNode reloaded = loader.reload("TOP", previous);

        assertNotSame(previous, reloaded);
        assertEquals(1, reloaded.getChildren().size());
    }

    private static List<String> childFullNames(OrgNode node) {
        return node.getChildren().stream().map(OrgNode::getFullName).toList();
    }

    private static OrgNode findRoot(List<OrgNode> nodes, String fullName) {
        return nodes.stream()
                .filter(n -> fullName.equals(n.getFullName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no root named " + fullName + " in " + nodes));
    }
}
