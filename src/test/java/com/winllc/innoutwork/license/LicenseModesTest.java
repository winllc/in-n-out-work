package com.winllc.innoutwork.license;

import com.enssolutions.license.LicenseManager;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.CheckInOutService;
import com.winllc.innoutwork.service.TrackedPeopleLimitException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static com.winllc.innoutwork.license.LicenseGatingSupport.ADMIN;
import static com.winllc.innoutwork.license.LicenseGatingSupport.USER;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three license modes end to end through the interceptor, the exception handler and the layout:
 * unlicensed (only sign-in and the license screen), read-only (GET only), and full with features
 * gated one by one.
 */
@WebMvcTest
class LicenseModesTest {

    private static final String CHECK_IN = "{\"windowsUserId\":\"bob\"}";

    @Configuration
    @Import(LicenseGatingSupport.Slice.class)
    static class Config {
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    LicenseManager licenses;

    @MockitoBean
    CheckInOutService checkIns;
    @MockitoBean
    UserRecordRepository userRecords;

    @AfterEach
    void fullLicenseAgain() {
        TestLicenses.installFile(licenses, TestLicenses.full());
    }

    // --- unlicensed ---------------------------------------------------------------------------------------

    @Test
    void unlicensedBrowsersAreSentToTheLicenseScreen() throws Exception {
        TestLicenses.removeFile(licenses);

        mockMvc.perform(get("/app/user/search").with(ADMIN).accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/admin/license"));
        mockMvc.perform(get("/app/metrics").with(USER).accept(MediaType.TEXT_HTML))
                .andExpect(redirectedUrl("/app/admin/license"));
    }

    @Test
    void unlicensedApiCallersGetJsonNotARedirect() throws Exception {
        TestLicenses.removeFile(licenses);

        // The Windows client: a redirect would be followed to an HTML page and taken as success.
        mockMvc.perform(post("/api/check/in").with(USER).contentType(MediaType.APPLICATION_JSON).content(CHECK_IN))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("license_required"))
                .andExpect(jsonPath("$.state").value("MISSING"))
                .andExpect(jsonPath("$.message").value("No license is installed. Upload one on the license screen."));
        verifyNoInteractions(checkIns);
    }

    @Test
    void anInvalidFileIsUnlicensedToo() throws Exception {
        TestLicenses.installFile(licenses, "not a license");

        mockMvc.perform(get("/api/users/reports").with(USER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value("INVALID"));
    }

    @Test
    void unlicensedSignInStillWorks() throws Exception {
        TestLicenses.removeFile(licenses);

        mockMvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign in")));
        mockMvc.perform(get("/app/user/search").accept(MediaType.TEXT_HTML))
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void unlicensedAdminsGetTheLicenseScreen() throws Exception {
        TestLicenses.removeFile(licenses);

        mockMvc.perform(get("/app/admin/license").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"license-state\"")))
                .andExpect(content().string(containsString(">MISSING<")))
                .andExpect(content().string(containsString(licenses.installationId())))
                .andExpect(content().string(containsString("id=\"feature-classification-banners\"")))
                // The menu is down to the license screen.
                .andExpect(content().string(not(containsString("href=\"/app/groups\""))))
                .andExpect(content().string(containsString("href=\"/app/admin/license\"")));
    }

    @Test
    void unlicensedNonAdminsAreToldToAskAnAdministrator() throws Exception {
        TestLicenses.removeFile(licenses);

        mockMvc.perform(get("/app/admin/license").with(USER))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("ReadyRoom is not licensed")))
                .andExpect(content().string(containsString("ask your administrator")))
                .andExpect(content().string(not(containsString("installation-id"))));
    }

    @Test
    void theStatusEndpointWorksUnlicensedAndOnlyAdminsSeeTheInstallation() throws Exception {
        TestLicenses.removeFile(licenses);

        mockMvc.perform(get("/api/license").with(USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("MISSING"))
                .andExpect(jsonPath("$.mode").value("UNLICENSED"))
                .andExpect(jsonPath("$.product").value("readyroom"))
                .andExpect(jsonPath("$.installationId").doesNotExist())
                .andExpect(jsonPath("$.file").doesNotExist());
        mockMvc.perform(get("/api/license").with(ADMIN))
                .andExpect(jsonPath("$.installationId").value(licenses.installationId()));
    }

    // --- read-only ----------------------------------------------------------------------------------------

    @Test
    void readOnlyAllowsGetButRefusesChanges() throws Exception {
        TestLicenses.installFile(licenses, TestLicenses.license().expired(5).sign());

        mockMvc.perform(get("/app/user/search").with(USER))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"read-only-banner\"")));
        mockMvc.perform(post("/api/check/in").with(USER).contentType(MediaType.APPLICATION_JSON).content(CHECK_IN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("license_read_only"))
                .andExpect(jsonPath("$.state").value("EXPIRED"));
        mockMvc.perform(post("/app/profile").with(USER).accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("ReadyRoom is read-only")));
        verifyNoInteractions(checkIns);
    }

    @Test
    void readOnlyHidesNothingButUnlocksNoFeature() throws Exception {
        TestLicenses.installFile(licenses, TestLicenses.license().expired(5).sign());

        mockMvc.perform(get("/app/metrics").with(ADMIN).accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden());
    }

    @Test
    void readOnlyStillTakesALicenseUpload() throws Exception {
        TestLicenses.installFile(licenses, TestLicenses.license().expired(5).sign());

        mockMvc.perform(post("/app/admin/license").with(ADMIN).param("text", TestLicenses.full()))
                .andExpect(redirectedUrl("/app/admin/license"));
        mockMvc.perform(get("/api/license").with(USER)).andExpect(jsonPath("$.state").value("VALID"));
    }

    // --- full ---------------------------------------------------------------------------------------------

    @Test
    void fullLicenseOpensEverything() throws Exception {
        when(checkIns.lookupBySessionId(any())).thenReturn(java.util.Optional.empty());
        when(checkIns.saveCheckInOutRecord(any())).thenAnswer(inv -> inv.<CheckInOutRecord>getArgument(0));

        mockMvc.perform(get("/app/metrics").with(ADMIN)).andExpect(status().isOk());
        mockMvc.perform(get("/api/orgnodes/hierarchy").with(USER)).andExpect(status().isOk());
        mockMvc.perform(post("/api/check/in").with(USER).contentType(MediaType.APPLICATION_JSON).content(CHECK_IN))
                .andExpect(status().isOk());
        mockMvc.perform(get("/app/user/search").with(ADMIN))
                .andExpect(content().string(containsString("href=\"/app/metrics\"")))
                .andExpect(content().string(containsString("href=\"/app/orgchart\"")))
                .andExpect(content().string(containsString("href=\"/app/user/reports\"")))
                .andExpect(content().string(containsString("classification-bar-top")))
                .andExpect(content().string(not(containsString("id=\"evaluation-banner\""))))
                .andExpect(content().string(not(containsString("id=\"renewal-warning\""))));
    }

    @Test
    void aFeatureOffIsRefusedAndHidden() throws Exception {
        TestLicenses.installFile(licenses, TestLicenses.license()
                .without(LicensedFeature.METRICS, LicensedFeature.ORG_CHART, LicensedFeature.CLASSIFICATION_BANNERS)
                .sign());

        mockMvc.perform(get("/app/metrics").with(ADMIN).accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Metrics and reports is not licensed")))
                .andExpect(content().string(containsString("id=\"sidebarnav\"")));
        mockMvc.perform(post("/app/group/report").with(ADMIN)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/reports").with(USER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("license_required"))
                .andExpect(jsonPath("$.state").value("VALID"))
                .andExpect(jsonPath("$.feature").value("metrics"));
        mockMvc.perform(get("/api/orgnodes/hierarchy").with(USER))
                .andExpect(jsonPath("$.feature").value("org-chart"));
        // Calendar is still on.
        mockMvc.perform(get("/api/event/all").with(USER)).andExpect(status().isOk());

        mockMvc.perform(get("/app/user/search").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/app/groups\"")))
                .andExpect(content().string(not(containsString("href=\"/app/metrics\""))))
                .andExpect(content().string(not(containsString("href=\"/app/user/reports\""))))
                .andExpect(content().string(not(containsString("href=\"/app/orgchart\""))))
                .andExpect(content().string(not(containsString("id=\"help-metrics\""))))
                // No classification banners, and no room kept for them.
                .andExpect(content().string(not(containsString("classification-bar-top"))))
                .andExpect(content().string(containsString("no-classification")));
    }

    @Test
    void attendanceOffRefusesTheWindowsClientWithJson() throws Exception {
        TestLicenses.installFile(licenses, TestLicenses.license().without(LicensedFeature.ATTENDANCE).sign());

        mockMvc.perform(post("/api/check/lock").with(USER).contentType(MediaType.APPLICATION_JSON).content(CHECK_IN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("license_required"))
                .andExpect(jsonPath("$.feature").value("attendance"));
        verifyNoInteractions(checkIns);
    }

    @Test
    void overTheLicensedSizeANewPersonsCheckInIsRefusedWithJson() throws Exception {
        when(checkIns.lookupBySessionId(any())).thenReturn(java.util.Optional.empty());
        when(checkIns.saveCheckInOutRecord(any())).thenThrow(new TrackedPeopleLimitException("CN=bob,OU=Users,DC=winllc,DC=com"));

        mockMvc.perform(post("/api/check/in").with(USER).contentType(MediaType.APPLICATION_JSON).content(CHECK_IN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("license_limit"))
                .andExpect(jsonPath("$.message").value(containsString("as many people as its license allows")));
    }

    @Test
    void anEvaluationShowsItsBannerToEveryone() throws Exception {
        TestLicenses.installFile(licenses, TestLicenses.license().evaluation().sign());

        mockMvc.perform(get("/app/user/search").with(USER))
                .andExpect(content().string(containsString("id=\"evaluation-banner\"")));
        mockMvc.perform(get("/login"))
                .andExpect(content().string(containsString("id=\"evaluation-banner\"")));
    }

    @Test
    void adminsAreWarnedBeforeExpiryAndInGrace() throws Exception {
        TestLicenses.installFile(licenses, TestLicenses.license().expiresIn(Duration.ofDays(10)).sign());
        mockMvc.perform(get("/app/user/search").with(ADMIN))
                .andExpect(content().string(containsString("id=\"renewal-warning\"")))
                .andExpect(content().string(containsString("10 days")));
        mockMvc.perform(get("/app/user/search").with(USER))
                .andExpect(content().string(not(containsString("id=\"renewal-warning\""))));

        TestLicenses.installFile(licenses, TestLicenses.license().inGrace(2, 30).sign());
        mockMvc.perform(get("/app/metrics").with(ADMIN)).andExpect(status().isOk());
        mockMvc.perform(get("/app/user/search").with(ADMIN))
                .andExpect(content().string(containsString("id=\"renewal-warning\"")))
                .andExpect(content().string(containsString("28 days")));
    }

    // --- the license screen -------------------------------------------------------------------------------

    @Test
    void theLicenseScreenShowsWhatIsInstalledAndTheUsage() throws Exception {
        when(userRecords.count()).thenReturn(42L);
        TestLicenses.installFile(licenses, TestLicenses.license().without(LicensedFeature.ORG_CHART).quantity(40).sign());

        mockMvc.perform(get("/app/admin/license").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">VALID<")))
                .andExpect(content().string(containsString("Pat Tester, Test Agency")))
                .andExpect(content().string(containsString("42 of 40 tracked people: over the licensed size")))
                .andExpect(content().string(containsString(licenses.installationId())))
                .andExpect(content().string(containsString("ti-circle-x")))
                .andExpect(content().string(containsString("Org chart")));
    }

    @Test
    void nonAdminsMayNotUseTheLicenseScreenWhenLicensed() throws Exception {
        mockMvc.perform(get("/app/admin/license").with(USER))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("installation-id"))));
        mockMvc.perform(post("/app/admin/license").with(USER).param("text", TestLicenses.full()))
                .andExpect(status().isForbidden());
    }
}
