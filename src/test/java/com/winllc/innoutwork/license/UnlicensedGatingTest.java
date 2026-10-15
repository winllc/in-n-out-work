package com.winllc.innoutwork.license;

import com.winllc.innoutwork.controller.advice.GlobalModelAttributes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static com.winllc.innoutwork.license.LicenseGatingSupport.ADMIN;
import static com.winllc.innoutwork.license.LicenseGatingSupport.USER;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** No license file: the core still works, every licensed page and call is refused, and the menu hides them. */
@WebMvcTest(properties = "ens.license.file=build/no-such-dir/license.lic")
class UnlicensedGatingTest {

    @Autowired
    private MockMvc mockMvc;

    /** The slice's configuration; a nested one is what @WebMvcTest picks up here (see the README). */
    @Configuration
    @Import({LicenseConfig.class, LicenseExceptionHandler.class, LicenseRestService.class, GlobalModelAttributes.class,
            LicenseGatingSupport.Pages.class, LicenseGatingSupport.Beans.class})
    static class Config {
    }

    @Test
    void corePagesAndCallsStillWork() throws Exception {
        mockMvc.perform(get("/app/user/search").with(ADMIN)).andExpect(status().isOk());
        mockMvc.perform(get("/api/check/records").with(ADMIN)).andExpect(status().isOk());
    }

    @Test
    void aLicensedPageGetsTheNeedsALicensePage() throws Exception {
        mockMvc.perform(get("/app/metrics").with(ADMIN).accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Metrics needs a license")))
                // Rendered in the layout, with the same menu as every other page.
                .andExpect(content().string(containsString("id=\"sidebarnav\"")))
                .andExpect(content().string(not(containsString("href=\"/app/metrics\""))));
    }

    @Test
    void theGroupReportPostIsRefused() throws Exception {
        mockMvc.perform(post("/app/group/report").with(ADMIN)).andExpect(status().isForbidden());
    }

    @Test
    void aLicensedCallGetsAJsonRefusal() throws Exception {
        mockMvc.perform(get("/api/users/reports").with(USER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("license_required"))
                .andExpect(jsonPath("$.feature").value("reports"))
                .andExpect(jsonPath("$.message").value("Reports requires an ENS Solutions license."));
        mockMvc.perform(get("/api/orgnodes/hierarchy").with(USER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.feature").value("org-chart"));
    }

    @Test
    void theMenuHidesLicensedFeaturesAndAdminsSeeWhy() throws Exception {
        mockMvc.perform(get("/app/user/search").with(ADMIN))
                .andExpect(content().string(containsString("href=\"/app/groups\"")))
                .andExpect(content().string(not(containsString("href=\"/app/metrics\""))))
                .andExpect(content().string(not(containsString("href=\"/app/user/reports\""))))
                .andExpect(content().string(not(containsString("href=\"/app/orgchart\""))))
                .andExpect(content().string(not(containsString("id=\"help-metrics\""))))
                .andExpect(content().string(containsString("Unlicensed: core features only (no license file)")));
    }

    @Test
    void onlyAdminsSeeTheLicenseLine() throws Exception {
        mockMvc.perform(get("/app/user/search").with(USER))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"license-status\""))));
    }

    @Test
    void theStatusEndpointTellsAnySignedInUserButOnlyAdminsWhereTheFileIs() throws Exception {
        mockMvc.perform(get("/api/license").with(USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("MISSING"))
                .andExpect(jsonPath("$.product").value("readyroom"))
                .andExpect(jsonPath("$.file").doesNotExist());
        mockMvc.perform(get("/api/license").with(ADMIN))
                .andExpect(jsonPath("$.file").value("build/no-such-dir/license.lic"));
    }
}
