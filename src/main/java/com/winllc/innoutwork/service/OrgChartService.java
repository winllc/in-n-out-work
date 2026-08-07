package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.TopLevelGroupProperties;
import com.winllc.innoutwork.data.CheckInOutRecordGroup;
import com.winllc.innoutwork.data.LdapGroup;
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

    /**
     * Builds the org-chart hierarchy and populates each node's employee-type statistics.
     * A failure loading stats for one node is logged and skipped so the rest of the tree
     * still renders.
     *
     * @return the populated list of top-level org nodes (never {@code null})
     */
    public List<OrgNode> loadStatistics(){
        List<OrgNode> orgNodes = generateOrgChart();

        for(OrgNode orgNode : orgNodes){
            try {
                loadOrgStats(orgNode);
                // Roll the per-node counts up through each subtree and derive the present %.
                orgNode.rollupStats();
            } catch (Exception e) {
                log.error("Failed to load org stats for {}",
                        orgNode != null ? orgNode.getFullName() : null, e);
            }
        }

        return orgNodes;
    }

    private void loadOrgStats(OrgNode orgNode){
        if(orgNode == null || StringUtils.isBlank(orgNode.getFullName())) return;

        List<CheckInOutRecord> currentRecords =
                checkInOutRecordRepository.findByDutySubOrganizationEqualsIgnoreCaseAndTimestampBetween(orgNode.getFullName(),
                DateTimeUtil.getStartOfToday(), DateTimeUtil.getEndOfToday());

        Map<String, CheckInOutRecordGroup> groups = currentRecords.stream()
                .collect(Collectors.groupingBy(CheckInOutRecord::getDn))
                .entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(entry ->
                        new CheckInOutRecordGroup(entry.getKey(),
                                entry.getValue().get(0).getEmployeeType(), entry.getValue()))
                .collect(Collectors.toMap(CheckInOutRecordGroup::getEmployeeType, group -> group));

        Map<String, Integer> currentEntriesByEmployeeType = groups.values().stream()
                .collect(Collectors.groupingBy(CheckInOutRecordGroup::getEmployeeType,
                        Collectors.summingInt(group -> group.isCheckedIn() ? 1 : 0)));

        Map<String, Integer> totalEntriesByEmployeeType = ldapService.getTotalEntriesWithAttributeValueSplitOnAttribute(props.getUserBaseDn(), props.getUserLdapDutySubOrganizationAttribute(),
                    orgNode.getFullName(), props.getUserLdapEmployeeTypeAttribute());

        orgNode.getData().setNodeMembersByEmployeeType(currentEntriesByEmployeeType);
        orgNode.getData().setTotalMembersByEmployeeType(totalEntriesByEmployeeType);

        if(orgNode.getChildren() != null && !orgNode.getChildren().isEmpty()){
            for(OrgNode child : orgNode.getChildren()){
                loadOrgStats(child);
            }
        }
    }

    public List<OrgNode> generateOrgChart(List<String> allSubOrgNames){
        List<OrgNode> list = allSubOrgNames.stream()
                .filter(s -> !s.isEmpty())
                .map(s -> buildOrgNodeFromAttribute(s))
                .toList();

        return mergeOrgNodes(list);
    }

    public List<OrgNode> generateOrgChart(){

        if(StringUtils.isEmpty(props.getDutySubOrgGroupsBaseDn())){
            return new ArrayList<>();
        }

        TopLevelGroupProperties groupProperties = new TopLevelGroupProperties();
        groupProperties.setGroupsBaseDn(props.getDutySubOrgGroupsBaseDn());

        List<String> orgs = ldapService.getAllUniqueValuesForAttributes(props.getUserLdapDutySubOrganizationAttribute());

        return generateOrgChart(orgs);
    }

    public List<OrgNode> mergeOrgNodes(List<OrgNode> orgNodes){

        orgNodes.forEach(orgNode -> {
            orgNode.mergeAll(orgNodes);
        });

        Set<OrgNode> orgNodeSet = new HashSet<>(orgNodes);
        return new ArrayList<>(orgNodeSet);
    }

    public OrgNode buildOrgNodeFromAttribute(String orgValue){

        Optional<OrgParseRuleRecord> parseRule = orgParseRuleRecordRepository.findAll().stream()
                .filter(rule -> orgValue.toLowerCase().startsWith(rule.getOrgName().toLowerCase()))
                .findFirst();

        if(parseRule.isPresent()){
            OrgParseRuleRecord rule = parseRule.get();
            return ruleOrgNodeParse(orgValue, rule.getOrgParseRegex());
        }else{
            return defaultOrgNodeParse(orgValue);
        }

    }

    public OrgNode defaultOrgNodeParse(String orgValue){
        String[] result = orgValue.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");

        return buildOrgNodeFromFlatGroups(Arrays.asList(result));
    }

    public OrgNode ruleOrgNodeParse(String orgValue, String regex){
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(orgValue);

        // Check if the regex matches the string
        if (matcher.matches()) {
            List<String> groups = new ArrayList<>();

            // 2. Loop from 1 to the total group count
            for (int i = 1; i <= matcher.groupCount(); i++) {
                groups.add(matcher.group(i));
            }

           return buildOrgNodeFromFlatGroups(groups);

        }
        return null;
    }

    private OrgNode buildOrgNodeFromFlatGroups(List<String> groups){
        if(!groups.isEmpty()){
            OrgNode orgNode = new OrgNode(groups.getFirst());

            if(groups.size() > 1){
                for (int i = 1; i < groups.size(); i++) {
                    OrgNode childOrgNode = new OrgNode(groups.get(i));
                    childOrgNode.setName(groups.get(i));

                    childOrgNode.setParent(new OrgNode(groups.get(i - 1)));

                    orgNode.addChildToLowest(childOrgNode);
                }
            }

            orgNode.buildFullName("");
            return orgNode;
        }
        return null;
    }
}
