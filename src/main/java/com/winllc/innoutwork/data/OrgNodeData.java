package com.winllc.innoutwork.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    /**
     * Headcount present across this node's whole subtree.
     * <p>
     * Views need the totals of these maps, and summing them in the template means
     * Thymeleaf's #aggregates.sum, which returns {@code null} for an empty collection -
     * an org with nobody checked in then renders as "null". Summed here instead, where
     * empty simply means zero.
     */
    @JsonIgnore
    public int getFullTreePresentCount() {
        return sum(fullTreeNodeMembersByEmployeeType);
    }

    /** Headcount assigned across this node's whole subtree. */
    @JsonIgnore
    public int getFullTreeTotalCount() {
        return sum(fullTreeTotalMembersByEmployeeType);
    }

    private static int sum(Map<String, Integer> counts) {
        if (counts == null) {
            return 0;
        }
        int total = 0;
        for (Integer value : counts.values()) {
            if (value != null) {
                total += value;
            }
        }
        return total;
    }
}
