package com.winllc.innoutwork.license;

import com.winllc.innoutwork.controller.advice.GlobalModelAttributes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.winllc.innoutwork.license.LicenseGatingSupport.ADMIN;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A license for some features: those work and show, the rest are refused and hidden. */
@WebMvcTest
class PartialLicenseGatingTest {

    @DynamicPropertySource
    static void license(DynamicPropertyRegistry registry) {
        String file = TestLicenses.write(TestLicenses.readyroom("reports", "2099-12-31")).toString();
        registry.add("ens.license.file", () -> file);
    }

    @Autowired
    private MockMvc mockMvc;

    /** The slice's configuration; a nested one is what @WebMvcTest picks up here (see the README). */
    @Configuration
    @Import({LicenseConfig.class, LicenseExceptionHandler.class, GlobalModelAttributes.class,
            LicenseGatingSupport.Pages.class, LicenseGatingSupport.Beans.class})
    static class Config {
    }

    @Test
    void aLicensedFeatureWorks() throws Exception {
        mockMvc.perform(get("/api/users/reports").with(ADMIN)).andExpect(status().isOk());
    }

    @Test
    void anUnlicensedFeatureIsStillRefused() throws Exception {
        mockMvc.perform(get("/app/metrics").with(ADMIN)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/orgnodes/hierarchy").with(ADMIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.feature").value("org-chart"));
    }

    @Test
    void theMenuShowsOnlyWhatIsLicensed() throws Exception {
        mockMvc.perform(get("/app/user/search").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/app/user/reports\"")))
                .andExpect(content().string(not(containsString("href=\"/app/metrics\""))))
                .andExpect(content().string(not(containsString("href=\"/app/orgchart\""))))
                .andExpect(content().string(containsString("Licensed to Test until 2099-12-31")));
    }
}
