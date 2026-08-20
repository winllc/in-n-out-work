package com.winllc.innoutwork.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

import java.util.*;

@Data
@ToString
public class OrgNode {

    private String id;
    private String name;
    private String fullName;
    private String managerDn;
    @ToString.Exclude
    @JsonIgnore
    private OrgNode parent;
    @ToString.Exclude
    private List<OrgNode> children = new ArrayList<>();
    private OrgNodeData data;

    private List<String> memberDns = new ArrayList<>();

    public OrgNode(String name){
        this.id = name;
        this.name = name;
        this.data = new OrgNodeData();
        this.data.setName(name);
    }

    public void mergeAll(List<OrgNode> orgNodes){
        for(OrgNode orgNode : orgNodes){
            merge(orgNode);
        }
    }

    /**
     * Merges another OrgNode hierarchy into this one.
     * <p>
     * The two roots must represent the same node (equal {@code fullName}). Children are
     * matched by {@code fullName}: a child that exists in both trees is merged recursively,
     * while a child present only in {@code other} is attached to this node as a new subtree.
     * Existing nodes are never duplicated. The {@code other} tree is consumed — subtrees
     * taken from it are re-parented onto this tree.
     *
     * @param other the hierarchy to merge into this node
     * @throws IllegalArgumentException if the two roots have different {@code fullName}s
     */
    public void merge(OrgNode other) {
        if (other == null) {
            return;
        }
        if (!Objects.equals(this.fullName, other.getFullName())) {
            return;
        }

        // Index this node's children by fullName so matches are O(1).
        Map<String, OrgNode> existingChildren = new HashMap<>();
        for (OrgNode child : this.children) {
            existingChildren.put(child.getFullName(), child);
        }

        for (OrgNode otherChild : other.getChildren()) {
            OrgNode match = existingChildren.get(otherChild.getFullName());
            if (match != null) {
                // Same node in both trees -> merge their descendants recursively.
                match.merge(otherChild);
            } else {
                // Only in the other tree -> attach the whole subtree here.
                otherChild.setParent(this);
                this.children.add(otherChild);
                existingChildren.put(otherChild.getFullName(), otherChild);
            }
        }
    }


    public void buildFullName(String name){
        this.fullName = name + this.name;
        this.data.setFullName(this.fullName);
        this.data.setName(this.fullName);
        this.id = this.fullName;
        for(OrgNode child : children){
            child.buildFullName(fullName);
        }
    }


    /**
     * Rolls this node's employee-type statistics up through its whole subtree.
     * <p>
     * Runs depth-first, so calling it on a root populates every descendant too. For each node,
     * the {@code fullTree*} maps become that node's own {@code node}/{@code total} counts plus the
     * rolled-up totals of all its children, and {@code fullTreePresentPercentage} is set to
     * present / total * 100 over the rolled-up counts.
     */
    public void rollupStats() {
        if (this.data == null) {
            this.data = new OrgNodeData();
        }

        // Seed the rollup with this node's own directly-attributed counts.
        Map<String, Integer> treeNode = copyOf(data.getNodeMembersByEmployeeType());
        Map<String, Integer> treeTotal = copyOf(data.getTotalMembersByEmployeeType());

        // Roll up each child's already-rolled-up subtree.
        for (OrgNode child : children) {
            if (child == null) {
                continue;
            }
            child.rollupStats();
            OrgNodeData childData = child.getData();
            if (childData != null) {
                addInto(treeNode, childData.getFullTreeNodeMembersByEmployeeType());
                addInto(treeTotal, childData.getFullTreeTotalMembersByEmployeeType());
            }
        }

        data.setFullTreeNodeMembersByEmployeeType(treeNode);
        data.setFullTreeTotalMembersByEmployeeType(treeTotal);

        int present = sumValues(treeNode);
        int total = sumValues(treeTotal);
        data.setFullTreePresentPercentage(total > 0 ? (present * 100.0) / total : 0.0);
    }

    private static Map<String, Integer> copyOf(Map<String, Integer> source) {
        return source != null ? new HashMap<>(source) : new HashMap<>();
    }

    private static void addInto(Map<String, Integer> target, Map<String, Integer> source) {
        if (source == null) {
            return;
        }
        source.forEach((key, value) -> target.merge(key, value != null ? value : 0, Integer::sum));
    }

    private static int sumValues(Map<String, Integer> map) {
        int sum = 0;
        if (map != null) {
            for (Integer value : map.values()) {
                if (value != null) {
                    sum += value;
                }
            }
        }
        return sum;
    }

    public int getTotalChildren() {
        return getChildrenRecursive(this);
    }

    private int getChildrenRecursive(OrgNode node) {
        int total = 1;
        if (!node.getChildren().isEmpty()) {
            for(OrgNode child : node.getChildren()) {
                total += getChildrenRecursive(child);
            }
        }
        return total;
    }

    public void addChildToLowest(OrgNode child) {
        addChildToLowestRecursive(this, child);
    }

    private void addChildToLowestRecursive(OrgNode parent, OrgNode child) {
        if(parent.children.isEmpty()){
            parent.children.add(child);
        }else{
            for(OrgNode orgNode : parent.children){
                addChildToLowestRecursive(orgNode, child);
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        OrgNode orgNode = (OrgNode) o;
        return Objects.equals(fullName, orgNode.fullName);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(fullName);
    }


}
