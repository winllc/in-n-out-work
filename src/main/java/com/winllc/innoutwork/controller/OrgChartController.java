package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.OrgNode;
import com.winllc.innoutwork.service.OrgChartService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
@RequestMapping("/app/orgchart")
public class OrgChartController {

    private static final Logger log = LoggerFactory.getLogger(OrgChartController.class);

    private final OrgChartService orgChartService;
    private final ApplicationProperties properties;

    public OrgChartController(OrgChartService orgChartService, ApplicationProperties properties) {
        this.orgChartService = orgChartService;
        this.properties = properties;
    }

    @GetMapping
    public ModelAndView details(HttpSession session, Authentication auth) {

        // The hierarchy renders server-side, the same way the Groups page does, so the
        // tree (with its attendance statistics) is built here rather than fetched by the
        // browser. /api/orgnodes/hierarchy remains available for other consumers.
        OrgNode orgTree = orgChartService.loadStatistics();
        orgTree.rollupStats();

        ModelAndView mav = new ModelAndView("orgchart");
        mav.addObject("orgTree", orgTree);
        mav.addObject("initiallyExpanded", properties.isGroupsInitiallyExpanded());

        return mav;
    }

    @GetMapping("/details/{orgName}")
    public ModelAndView orgdetails(HttpSession session, Authentication auth,
                                   @PathVariable(name = "orgName") String orgName) {
        ModelAndView mav = new ModelAndView();

        mav.setViewName("orgdetails");
        mav.addObject("orgName", orgName);

        return mav;
    }

}
