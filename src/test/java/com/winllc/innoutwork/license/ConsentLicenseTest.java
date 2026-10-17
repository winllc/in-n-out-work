package com.winllc.innoutwork.license;

import com.enssolutions.license.LicenseManager;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.config.EnsProperties;
import com.winllc.innoutwork.controller.ConsentController;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.security.ConsentBanner;
import com.winllc.innoutwork.service.CheckInOutService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.winllc.innoutwork.license.LicenseGatingSupport.USER;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The notice-and-consent banner in every license state: it comes before sign-in, so before anyone could install a
 * license, and agreeing to it changes nothing the license governs.
 */
@WebMvcTest
class ConsentLicenseTest {

    @Configuration
    @Import({LicenseGatingSupport.Slice.class, ConsentController.class})
    static class Config {
        @Bean
        ConsentBanner consentBanner() {
            EnsProperties.ConsentBanner settings = new EnsProperties.ConsentBanner();
            settings.setEnabled(true);
            return new ConsentBanner(settings);
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    LicenseManager licenses;

    @MockitoBean
    AuditLog audit;
    @MockitoBean
    CheckInOutService checkIns;
    @MockitoBean
    UserRecordRepository userRecords;

    @AfterEach
    void fullLicenseAgain() {
        TestLicenses.installFile(licenses, TestLicenses.full());
    }

    @Test
    void theBannerIsShownAndAgreedToWithoutALicense() throws Exception {
        TestLicenses.removeFile(licenses);

        mockMvc.perform(get("/consent").with(USER).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("I agree")));
        mockMvc.perform(post("/consent").with(USER).with(csrf())).andExpect(redirectedUrl("/"));
    }

    @Test
    void theBannerIsAgreedToWhileReadOnly() throws Exception {
        TestLicenses.installFile(licenses, TestLicenses.license().expired(5).sign());

        mockMvc.perform(post("/consent").with(USER).with(csrf())).andExpect(redirectedUrl("/"));
    }
}
