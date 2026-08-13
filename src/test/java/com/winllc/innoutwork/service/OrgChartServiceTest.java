package com.winllc.innoutwork.service;

import com.winllc.innoutwork.data.OrgNode;
import com.winllc.innoutwork.model.OrgParseRuleRecord;
import com.winllc.innoutwork.repository.OrgParseRuleRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgChartServiceTest {

    @Mock
    private OrgParseRuleRecordRepository orgParseRuleRecordRepository;

    @InjectMocks
    private OrgChartService service;

    /**
     * The default parse splits on every letter/digit boundary, so "RYS34B" becomes the
     * chain RYS -> 34 -> B, with each node's fullName being the concatenation of the path.
     */
    @Test
    void defaultOrgNodeParseSplitsOnLetterDigitBoundaries() {
        OrgNode node = service.defaultOrgNodeParse("RYS34B");

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
        OrgNode ruleNode = service.ruleOrgNodeParse("abc445de", "([a-zA-Z]+\\d)(\\d+)([a-zA-Z]+)$");

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
        assertNull(service.ruleOrgNodeParse("nomatch", "^(\\d+)$"));
    }

    /**
     * Merging trees that share a root collapses the common prefix and keeps the differing
     * leaves as siblings, rather than duplicating the shared ancestors.
     */
    @Test
    void mergeCollapsesSharedAncestorsAndKeepsDistinctLeaves() {
        OrgNode node = service.defaultOrgNodeParse("RYS34B");
        OrgNode node2 = service.defaultOrgNodeParse("RYS34C");
        OrgNode node3 = service.defaultOrgNodeParse("RYS35A");

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
        OrgNode node = service.defaultOrgNodeParse("RYS34B");
        int before = node.getTotalChildren();

        node.merge(service.defaultOrgNodeParse("ZZZ11A"));

        assertEquals(before, node.getTotalChildren(), "a differently-rooted tree must not be merged in");
    }

    @Test
    void mergeIgnoresNull() {
        OrgNode node = service.defaultOrgNodeParse("RYS34B");
        int before = node.getTotalChildren();

        node.merge(null);

        assertEquals(before, node.getTotalChildren());
    }

    /**
     * Values sharing a root end up under one node; unrelated values become separate roots.
     */
    @Test
    void generateOrgChartGroupsValuesByRoot() {
        when(orgParseRuleRecordRepository.findAll()).thenReturn(List.of());

        List<OrgNode> chart = service.generateOrgChart(
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

        List<OrgNode> chart = service.generateOrgChart(List.of("abc445de"));

        assertEquals(1, chart.size());
        // The rule splits as abc4/45/de rather than the default abc/445/de.
        assertEquals("abc4", chart.get(0).getName());
    }

    @Test
    void generateOrgChartSkipsNullAndEmptyValues() {
        when(orgParseRuleRecordRepository.findAll()).thenReturn(List.of());

        List<OrgNode> chart = service.generateOrgChart(
                java.util.Arrays.asList("RYS34B", null, ""));

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

        List<OrgNode> chart = service.generateOrgChart(List.of("abc445de", "RYS34B"));

        assertEquals(1, chart.size());
        assertEquals("RYS", chart.get(0).getName());
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
