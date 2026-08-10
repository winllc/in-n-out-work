package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.service.OrgChartService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
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

    @Autowired
    private OrgChartService orgChartService;

    @GetMapping
    public ModelAndView details(HttpSession session, Authentication auth) {

        // The chart data (including stats) is fetched client-side from /api/orgnodes/hierarchy,
        // so there's no need to build it here as well.
        ModelAndView mav = new ModelAndView("orgchart");

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
