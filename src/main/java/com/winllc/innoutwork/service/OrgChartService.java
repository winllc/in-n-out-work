package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.CheckInOutRecordGroup;
import com.winllc.innoutwork.data.OrgNode;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.OrgParseRuleRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.OrgParseRuleRecordRepository;
import com.winllc.innoutwork.util.DateTimeUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class OrgChartService {

    private static final Logger log = LoggerFactory.getLogger(OrgChartService.class);

    @Autowired
    private OrgParseRuleRecordRepository orgParseRuleRecordRepository;
    @Autowired
    private CheckInOutRecordRepository checkInOutRecordRepository;
    @Autowired
    private LdapService ldapService;
    @Autowired
    private ApplicationProperties props;

    @Autowired
    @Qualifier("ldapOrgLoadingCache")
    private LoadingCache<String, OrgNode> orgNodeCache;

    /**
     * Builds the org-chart hierarchy and populates each node's employee-type statistics.
     * A failure loading stats for one node is logged and skipped so the rest of the tree
     * still renders.
     *
     * @return the populated list of top-level org nodes (never {@code null})
     */
    public OrgNode loadStatistics(){
        long start = System.currentTimeMillis();

        OrgNode orgNode = orgNodeCache.get(props.getOrganizationName());

        log.debug("Loading org statistics for {} top-level org(s) under {}",
                orgNode.getChildren().size(), props.getOrganizationName());

        for(OrgNode node : orgNode.getChildren()){
            try {
                loadOrgStats(node);
                // Roll the per-node counts up through each subtree and derive the present %.
                node.rollupStats();
            } catch (Exception e) {
                log.error("Failed to load org stats for {}",
                        node != null ? node.getFullName() : null, e);
            }
        }

        // Runs on every org-chart page load, so debug: the cost is only interesting
        // when someone is investigating a slow page.
        log.debug("Loaded org chart statistics for {} ({} nodes) in {}ms",
                props.getOrganizationName(), orgNode.getTotalChildren(), System.currentTimeMillis() - start);

        return orgNode;
    }

    private void loadOrgStats(OrgNode orgNode){
        if(orgNode == null || StringUtils.isBlank(orgNode.getFullName())) {
            // Nodes without a full name cannot be matched to records or directory entries.
            log.debug("Skipping org node with no full name: {}", orgNode != null ? orgNode.getName() : null);
            return;
        }

        // Isolate each node so a single node's failure doesn't skip the rest of the subtree.
        try {
            List<CheckInOutRecord> currentRecords =
                    checkInOutRecordRepository.findByDutySubOrganizationEqualsIgnoreCaseAndTimestampBetween(orgNode.getFullName(),
                    DateTimeUtil.getStartOfToday(), DateTimeUtil.getEndOfToday());

            // One group per user (DN); count, per employee type, how many are currently checked in.
            Map<String, Integer> currentEntriesByEmployeeType = currentRecords.stream()
                    .collect(Collectors.groupingBy(CheckInOutRecord::getDn))
                    .entrySet().stream()
                    .filter(entry -> !entry.getValue().isEmpty())
                    .map(entry ->
                            new CheckInOutRecordGroup(entry.getKey(),
                                    entry.getValue().get(0).getEmployeeType(), entry.getValue()))
                    .filter(CheckInOutRecordGroup::isCheckedIn)
                    .collect(Collectors.groupingBy(
                            group -> group.getEmployeeType() != null ? group.getEmployeeType() : "N/A",
                            Collectors.summingInt(group -> 1)));

            Map<String, Integer> totalEntriesByEmployeeType = ldapService.getTotalEntriesWithAttributeValueSplitOnAttribute(props.getUserBaseDn(), props.getUserLdapDutySubOrganizationAttribute(),
                        orgNode.getFullName(), props.getUserLdapEmployeeTypeAttribute());

            orgNode.getData().setNodeMembersByEmployeeType(currentEntriesByEmployeeType);
            orgNode.getData().setTotalMembersByEmployeeType(totalEntriesByEmployeeType);

            log.debug("Org {}: {} record(s) today, present {}, total {}", orgNode.getFullName(),
                    currentRecords.size(), currentEntriesByEmployeeType, totalEntriesByEmployeeType);
        } catch (Exception e) {
            log.error("Failed to load stats for node {}", orgNode.getFullName(), e);
        }

        if(orgNode.getChildren() != null && !orgNode.getChildren().isEmpty()){
            for(OrgNode child : orgNode.getChildren()){
                loadOrgStats(child);
            }
        }
    }




}
