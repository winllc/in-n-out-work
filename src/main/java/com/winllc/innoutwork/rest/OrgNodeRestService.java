package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.OrgNode;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.OrgChartService;
import com.winllc.innoutwork.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.filter.AndFilter;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.ldap.filter.Filter;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static com.winllc.innoutwork.service.LdapService.escapeLdapFilter;

@RestController
@RequestMapping("/api/orgnodes")
public class OrgNodeRestService {

    private static final Logger log = LoggerFactory.getLogger(OrgNodeRestService.class);

    private final OrgChartService orgChartService;
    private final ApplicationProperties props;
    private final LdapService ldapService;
    private final UserService userService;

    public OrgNodeRestService(OrgChartService orgChartService, ApplicationProperties props,
                              LdapService ldapService, UserService userService) {
        this.orgChartService = orgChartService;
        this.props = props;
        this.ldapService = ldapService;
        this.userService = userService;
    }

    @GetMapping("/hierarchy")
    public OrgNode getHierarchy(Authentication authentication) {
        // loadStatistics() builds the tree AND populates each node's employee-type breakdowns;
        // generateOrgChart() alone returns structure only, leaving the data maps empty.
        OrgNode top = orgChartService.loadStatistics();

        top.rollupStats();

        return top;
    }

    @GetMapping("/users/{orgName}")
    public List<UserStatus> getUsers(Authentication authentication,
                            HttpSession session,
                            @PathVariable(name = "orgName") String orgName) {

        Filter filter = new AndFilter()
                .and(new EqualsFilter("objectClass", "inetOrgPerson"))
                .and(new EqualsFilter(props.getUserLdapDutySubOrganizationAttribute(), escapeLdapFilter(orgName)));

        List<UserStatus> result = ldapService.search(filter.encode());
        List<UserStatus> users = new ArrayList<>();

        for(UserStatus user : result){
            users.add(userService.getUserStatus(user.getDn(), session));
        }
        return users;
    }

}
