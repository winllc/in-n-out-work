package com.winllc.innoutwork.service;

import com.winllc.innoutwork.data.OrgNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrgChartServiceTest {

    @Test
    void defaultOrgNodeParse() {
        OrgChartService service = new OrgChartService();
        OrgNode node = service.defaultOrgNodeParse("RYS34B");

        assertEquals(3, node.getTotalChildren());
        assertEquals("34", node.getChildren().get(0).getName());

        OrgNode ruleNode = service.ruleOrgNodeParse("abc445de", "([a-zA-Z]+\\d)(\\d+)([a-zA-Z]+)$");

        assertEquals("abc4", ruleNode.getName());
        assertEquals("de", ruleNode.getChildren().get(0).getChildren().get(0).getName());
        assertEquals("abc445de", ruleNode.getChildren().get(0).getChildren().get(0).getFullName());

        OrgNode node2 = service.defaultOrgNodeParse("RYS34C");
        OrgNode node3 = service.defaultOrgNodeParse("RYS35A");

        node.merge(node2);
        node.merge(node3);

        System.out.println(node.getTotalChildren());

        service.generateOrgChart(List.of("RYS34B", "RYS34C", "RYS35A", "abc445de"));
    }
}