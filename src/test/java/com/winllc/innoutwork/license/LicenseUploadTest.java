package com.winllc.innoutwork.license;

import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseState;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.CheckInOutService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static com.winllc.innoutwork.license.LicenseGatingSupport.ADMIN;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/** The license screen's upload: what it installs, and why it refuses the rest without touching the installed one. */
@WebMvcTest
class LicenseUploadTest {

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

    @BeforeEach
    void noLicense() {
        TestLicenses.removeFile(licenses);
    }

    @AfterEach
    void fullLicenseAgain() {
        TestLicenses.installFile(licenses, TestLicenses.full());
    }

    private static MockMultipartFile file(String text) {
        return new MockMultipartFile("file", "readyroom.lic", "text/plain", text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aValidLicenseIsInstalledAndTakesEffectAtOnce() throws Exception {
        String lic = TestLicenses.license().evaluation().sign();

        mockMvc.perform(multipart("/app/admin/license").file(file(lic)).with(ADMIN))
                .andExpect(redirectedUrl("/app/admin/license"))
                .andExpect(flash().attribute("notice", containsString("License installed. Licensed to Pat Tester, Test Agency")));

        assertEquals(LicenseState.VALID, licenses.current().state());
        assertTrue(licenses.current().showEvaluationBanner());
        assertEquals(lic, Files.readString(licenses.licenseFile()));
    }

    @Test
    void aLicenseBoundToThisInstallationIsInstalled() throws Exception {
        String lic = TestLicenses.license().installationId(licenses.installationId()).sign();

        mockMvc.perform(post("/app/admin/license").with(ADMIN).param("text", lic))
                .andExpect(flash().attribute("notice", containsString("License installed.")));
        assertEquals(LicenseState.VALID, licenses.current().state());
    }

    @Test
    void aLicenseForAnotherProductIsRefused() throws Exception {
        TestLicenses.installFile(licenses, TestLicenses.full());

        mockMvc.perform(multipart("/app/admin/license").file(file(TestLicenses.license().product("certalert").sign())).with(ADMIN))
                .andExpect(flash().attribute("error",
                        "That license was not installed. This is a license for certalert, not readyroom."));
        assertEquals(LicenseState.VALID, licenses.current().state());
        assertEquals("readyroom", licenses.current().license().orElseThrow().product());
    }

    @Test
    void aLicenseBoundToAnotherInstallationIsRefused() throws Exception {
        mockMvc.perform(multipart("/app/admin/license")
                        .file(file(TestLicenses.license().installationId("RR-OTHR-0002").sign())).with(ADMIN))
                .andExpect(flash().attribute("error", containsString(
                        "This license is bound to installation RR-OTHR-0002; this one is " + licenses.installationId())));
        assertEquals(LicenseState.MISSING, licenses.current().state());
    }

    @Test
    void anOlderRevisionIsRefusedOnceANewerOneIsInstalled() throws Exception {
        String id = "lic_upload_revisions_01";
        mockMvc.perform(post("/app/admin/license").with(ADMIN).param("text", TestLicenses.license().id(id).revision(2).sign()))
                .andExpect(flash().attribute("notice", containsString("License installed.")));

        mockMvc.perform(post("/app/admin/license").with(ADMIN).param("text", TestLicenses.license().id(id).revision(1).sign()))
                .andExpect(flash().attribute("error", containsString("A newer revision (2) of this license")));
        assertEquals(2, licenses.current().license().orElseThrow().revision());
    }

    @Test
    void anEditedOrForeignFileIsRefused() throws Exception {
        String lic = TestLicenses.full();
        String tampered = lic.replace("-----BEGIN ENS LICENSE-----\n", "-----BEGIN ENS LICENSE-----\nAA");

        mockMvc.perform(multipart("/app/admin/license").file(file(tampered)).with(ADMIN))
                .andExpect(flash().attribute("error", containsString("That isn't a valid ENS license")));
        mockMvc.perform(multipart("/app/admin/license").file(file("hello")).with(ADMIN))
                .andExpect(flash().attribute("error", "That isn't a valid ENS license: Not an ENS license."));
        assertEquals(LicenseState.MISSING, licenses.current().state());
    }

    @Test
    void anExpiredLicenseIsInstalledButSaysSo() throws Exception {
        mockMvc.perform(post("/app/admin/license").with(ADMIN).param("text", TestLicenses.license().expired(3).sign()))
                .andExpect(flash().attribute("error", containsString("License installed, but: This license expired")));
        assertEquals(LicenseState.EXPIRED, licenses.current().state());
    }

    @Test
    void nothingChosenSaysSo() throws Exception {
        mockMvc.perform(post("/app/admin/license").with(ADMIN))
                .andExpect(flash().attribute("error", "Choose the license file (.lic) to upload."));
    }
}
