package com.winllc.innoutwork.service.loader;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.OrgNode;
import com.winllc.innoutwork.model.OrgParseRuleRecord;
import com.winllc.innoutwork.repository.OrgParseRuleRecordRepository;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.OrgChartService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LdapOrgLoader implements CacheLoader<String, OrgNode> {

    private static final Logger log = LoggerFactory.getLogger(LdapOrgLoader.class);

    private final ApplicationProperties props;
    private final OrgParseRuleRecordRepository orgParseRuleRecordRepository;
    private final LdapService ldapService;

    public LdapOrgLoader(ApplicationProperties props, OrgParseRuleRecordRepository orgParseRuleRecordRepository, LdapService ldapService) {
        this.props = props;
        this.orgParseRuleRecordRepository = orgParseRuleRecordRepository;
        this.ldapService = ldapService;
    }


    @Override
    public OrgNode load(String name) {
        // generateTopLevelOrgChart logs the result at info; this is just the trigger.
        log.debug("Building org chart for {} (cache miss)", name);
        return generateTopLevelOrgChart();
    }

    @Override
    public OrgNode reload(String dn, OrgNode oldValue) throws Exception {
        // reload is async and non-blocking for callers
        try {
            log.debug("Refreshing cached org chart for {}", dn);
            return generateTopLevelOrgChart();
        }catch (Exception e) {
            log.error("Failed to reload ldap group %s".formatted(dn), e);
            // The stale tree keeps being served, which is easy to miss without this.
            log.warn("Serving stale cached org chart for {} after refresh failure", dn);
            return oldValue;
        }
    }

    public OrgNode generateTopLevelOrgChart(){
        long start = System.currentTimeMillis();

        OrgNode top = new OrgNode(props.getOrganizationName());

        List<OrgNode> orgNodes = generateOrgChart();

        top.setChildren(orgNodes);

        // Rebuilding walks the whole directory, so record the shape and cost of the result.
        log.info("Built org chart {} with {} top-level orgs ({} nodes total) in {}ms",
                top.getName(), orgNodes.size(), top.getTotalChildren(),
                System.currentTimeMillis() - start);

        return top;
    }

    public List<OrgNode> generateOrgChart(){

        if(StringUtils.isEmpty(props.getDutySubOrgGroupsBaseDn())){
            // Not an error: the org chart is optional. Repeats on every rebuild, so
            // debug rather than info.
            log.debug("No duty sub-org base DN configured; org chart will be empty");
            return new ArrayList<>();
        }

        List<String> orgs = ldapService.getAllUniqueValuesForAttributes(props.getUserLdapDutySubOrganizationAttribute());

        log.debug("Found {} distinct values for attribute {}", orgs.size(),
                props.getUserLdapDutySubOrganizationAttribute());

        return generateOrgChart(orgs);
    }

    public List<OrgNode> generateOrgChart(List<String> allSubOrgNames){
        // Load parse rules once rather than re-querying for every org value.
        List<OrgParseRuleRecord> parseRules = orgParseRuleRecordRepository.findAll();

        log.debug("Parsing {} org values using {} parse rule(s)", allSubOrgNames.size(), parseRules.size());

        List<OrgNode> list = allSubOrgNames.stream()
                .filter(Objects::nonNull)
                .filter(s -> !s.isEmpty())
                .map(s -> buildOrgNodeFromAttribute(s, parseRules))
                .filter(Objects::nonNull) // a parse rule whose regex doesn't match returns null
                .toList();

        List<OrgNode> merged = mergeOrgNodes(list);

        log.debug("Parsed {} of {} org values into {} root(s)", list.size(), allSubOrgNames.size(), merged.size());

        return merged;
    }



    public List<OrgNode> mergeOrgNodes(List<OrgNode> orgNodes){
        // Group roots by fullName, merging each subsequent same-root tree into the first.
        Map<String, OrgNode> byRoot = new LinkedHashMap<>();

        for (OrgNode node : orgNodes) {
            if (node == null) {
                continue;
            }
            OrgNode root = byRoot.get(node.getFullName());
            if (root == null) {
                byRoot.put(node.getFullName(), node);
            } else {
                root.merge(node);
            }
        }

        return new ArrayList<>(byRoot.values());
    }

    public OrgNode buildOrgNodeFromAttribute(String orgValue){
        return buildOrgNodeFromAttribute(orgValue, orgParseRuleRecordRepository.findAll());
    }

    public OrgNode buildOrgNodeFromAttribute(String orgValue, List<OrgParseRuleRecord> parseRules){

        Optional<OrgParseRuleRecord> parseRule = parseRules.stream()
                .filter(rule -> orgValue.toLowerCase().startsWith(rule.getOrgName().toLowerCase()))
                .findFirst();

        if(parseRule.isPresent()){
            OrgParseRuleRecord rule = parseRule.get();
            OrgNode parsed = ruleOrgNodeParse(orgValue, rule.getOrgParseRegex());

            if (parsed == null) {
                // The value is dropped from the chart entirely - almost always a rule whose
                // regex no longer matches the data it was written for.
                log.warn("Parse rule '{}' ({}) did not match org value '{}'; the value is excluded from the org chart",
                        rule.getOrgName(), rule.getOrgParseRegex(), orgValue);
            } else {
                log.trace("Parsed '{}' with rule '{}'", orgValue, rule.getOrgName());
            }

            return parsed;
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
                    // fullName is assigned below by buildFullName(); the parent pointer is
                    // @JsonIgnore and only (re)set during merge, so it's left null here.
                    orgNode.addChildToLowest(childOrgNode);
                }
            }

            orgNode.buildFullName("");
            return orgNode;
        }
        return null;
    }
}