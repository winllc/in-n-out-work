package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.data.OrgNode;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.service.OrgChartService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orgnodes")
public class OrgNodeRestService {

    private static final Logger log = LoggerFactory.getLogger(OrgNodeRestService.class);

    @Autowired
    private OrgChartService orgChartService;

    @GetMapping("/hierarchy")
    public OrgNode getHierarchy(Authentication authentication) {
        // loadStatistics() builds the tree AND populates each node's employee-type breakdowns;
        // generateOrgChart() alone returns structure only, leaving the data maps empty.
        List<OrgNode> orgNodes = orgChartService.loadStatistics();

        OrgNode top = new OrgNode("TOP");
        top.setId("TOP");
        top.setChildren(orgNodes);

        return top;
    }


}
