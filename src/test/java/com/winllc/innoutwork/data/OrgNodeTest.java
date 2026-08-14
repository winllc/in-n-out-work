package com.winllc.innoutwork.data;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OrgNodeTest {

    /**
     * A node is identified by its name until buildFullName() walks the tree, so nodes that
     * never get a path - the synthetic org-chart root - still carry an id.
     */
    @Test
    void constructorSeedsIdAndDataWithTheName() {
        OrgNode node = new OrgNode("TOP");

        assertEquals("TOP", node.getId());
        assertEquals("TOP", node.getName());
        assertEquals("TOP", node.getData().getName());
        assertNull(node.getFullName());
    }

    @Test
    void buildFullNameConcatenatesThePathAndSetsId() {
        OrgNode root = new OrgNode("RYS");
        OrgNode child = new OrgNode("34");
        root.getChildren().add(child);

        root.buildFullName("");

        assertEquals("RYS", root.getFullName());
        assertEquals("RYS", root.getId());
        assertEquals("RYS34", child.getFullName());
        assertEquals("RYS34", child.getId());
    }

    /**
     * rollupStats is depth-first: each node's fullTree* maps are its own counts plus every
     * descendant's rolled-up counts, so a root reflects the whole subtree in one pass.
     */
    @Test
    void rollupStatsSumsOwnAndDescendantCounts() {
        OrgNode root = node("RYS", Map.of("CIV", 1), Map.of("CIV", 4));
        OrgNode child = node("34", Map.of("CIV", 2), Map.of("CIV", 6));
        OrgNode grandChild = node("B", Map.of("MIL", 3), Map.of("MIL", 10));

        child.getChildren().add(grandChild);
        root.getChildren().add(child);
        root.buildFullName("");

        root.rollupStats();

        // Leaf rolls up to just its own counts.
        assertEquals(Map.of("MIL", 3), grandChild.getData().getFullTreeNodeMembersByEmployeeType());
        assertEquals(Map.of("MIL", 10), grandChild.getData().getFullTreeTotalMembersByEmployeeType());

        // Middle node adds the leaf's counts to its own.
        assertEquals(Map.of("CIV", 2, "MIL", 3), child.getData().getFullTreeNodeMembersByEmployeeType());
        assertEquals(Map.of("CIV", 6, "MIL", 10), child.getData().getFullTreeTotalMembersByEmployeeType());

        // Root sees the entire tree.
        assertEquals(Map.of("CIV", 3, "MIL", 3), root.getData().getFullTreeNodeMembersByEmployeeType());
        assertEquals(Map.of("CIV", 10, "MIL", 10), root.getData().getFullTreeTotalMembersByEmployeeType());
    }

    @Test
    void rollupStatsComputesPresentPercentageOverTheWholeSubtree() {
        OrgNode root = node("RYS", Map.of("CIV", 1), Map.of("CIV", 4));
        OrgNode child = node("34", Map.of("CIV", 2), Map.of("CIV", 6));
        root.getChildren().add(child);
        root.buildFullName("");

        root.rollupStats();

        // 3 present of 10 total across the subtree.
        assertEquals(30.0, root.getData().getFullTreePresentPercentage(), 0.0001);
        // The child only sees its own 2 of 6.
        assertEquals(2.0 / 6 * 100, child.getData().getFullTreePresentPercentage(), 0.0001);
    }

    @Test
    void rollupStatsYieldsZeroPercentageWhenThereAreNoMembers() {
        OrgNode root = new OrgNode("RYS");
        root.buildFullName("");

        root.rollupStats();

        assertEquals(0.0, root.getData().getFullTreePresentPercentage(), 0.0001);
        assertTrue(root.getData().getFullTreeTotalMembersByEmployeeType().isEmpty());
    }

    @Test
    void getTotalChildrenCountsEveryNodeIncludingItself() {
        OrgNode root = new OrgNode("RYS");
        OrgNode a = new OrgNode("34");
        OrgNode b = new OrgNode("35");
        OrgNode leaf = new OrgNode("B");

        a.getChildren().add(leaf);
        root.getChildren().add(a);
        root.getChildren().add(b);

        assertEquals(4, root.getTotalChildren());
    }

    /**
     * addChildToLowest pushes the child onto every currently-childless node, which is what
     * turns a flat list of parsed groups into a single chain.
     */
    @Test
    void addChildToLowestAppendsBeneathTheDeepestNode() {
        OrgNode root = new OrgNode("RYS");
        OrgNode mid = new OrgNode("34");
        OrgNode leaf = new OrgNode("B");

        root.addChildToLowest(mid);
        root.addChildToLowest(leaf);

        assertEquals(List.of(mid), root.getChildren());
        assertEquals(List.of(leaf), mid.getChildren());
    }

    @Test
    void equalityIsBasedOnFullNameOnly() {
        OrgNode a = new OrgNode("34");
        OrgNode b = new OrgNode("different-name");
        a.buildFullName("RYS");
        b.buildFullName("RYS");
        // Both end up as RYS34 / RYSdifferent-name respectively, so make them match explicitly.
        b.setFullName(a.getFullName());

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    private static OrgNode node(String name, Map<String, Integer> present, Map<String, Integer> total) {
        OrgNode node = new OrgNode(name);
        node.getData().setNodeMembersByEmployeeType(new java.util.HashMap<>(present));
        node.getData().setTotalMembersByEmployeeType(new java.util.HashMap<>(total));
        return node;
    }
}
