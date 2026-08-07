package com.winllc.innoutwork.data;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class OrgNodeData {
    private String name;
    private String fullName;
    private Map<String, Integer> nodeMembersByEmployeeType = new HashMap<>();
    private Map<String, Integer> totalMembersByEmployeeType = new HashMap<>();
    private Map<String, Integer> fullTreeNodeMembersByEmployeeType = new HashMap<>();
    private Map<String, Integer> fullTreeTotalMembersByEmployeeType = new HashMap<>();

    // Percentage of members present across this node's full subtree:
    // sum(fullTreeNodeMembers) / sum(fullTreeTotalMembers) * 100. 0 when there are no members.
    private Double fullTreePresentPercentage = 0.0;
}
