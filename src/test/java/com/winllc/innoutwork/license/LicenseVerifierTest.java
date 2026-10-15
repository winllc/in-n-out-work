package com.winllc.innoutwork.license;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The license check, against the test key that src/test/resources/license puts ahead of the real one.
 */
class LicenseVerifierTest {

    private final LicenseVerifier verifier = LicenseVerifier.forProduct(LicenseConfig.PRODUCT);

    @TempDir
    Path dir;

    @Test
    void theCommittedTestLicenseIsValidForEverything() throws IOException {
        License license = verifier.verify(Files.readString(Path.of(TestLicenses.FULL)));

        assertEquals("readyroom", license.product());
        assertTrue(license.coversAllFeatures());
        assertNull(license.expires());
        for (LicensedFeature feature : LicensedFeature.values()) {
            assertTrue(license.covers(feature.id()), feature.id());
        }
    }

    @Test
    void aLicenseCoversOnlyTheFeaturesItLists() {
        License license = verifier.verify(TestLicenses.readyroom("metrics,reports", "2099-12-31"));

        assertEquals(Set.of("metrics", "reports"), license.features());
        assertTrue(license.covers("metrics"));
        assertFalse(license.covers("org-chart"));
        assertEquals(LocalDate.of(2099, 12, 31), license.expires());
    }

    @Test
    void anAlteredLicenseIsRejected() {
        String tampered = TestLicenses.readyroom("metrics", "2099-12-31")
                .replace("features=metrics", "features=*");

        var e = assertThrows(LicenseVerifier.InvalidLicenseException.class, () -> verifier.verify(tampered));
        assertTrue(e.getMessage().contains("signature does not match"), e.getMessage());
    }

    @Test
    void aLicenseForAnotherProductIsRejected() {
        Map<String, String> fields = new TreeMap<>();
        fields.put("licenseId", "other");
        fields.put("product", "cert-alert");
        fields.put("licensee", "Test");
        fields.put("issued", "2026-01-01");
        fields.put("expires", "never");
        fields.put("features", "*");

        var e = assertThrows(LicenseVerifier.InvalidLicenseException.class,
                () -> verifier.verify(TestLicenses.sign(fields)));
        assertTrue(e.getMessage().contains("cert-alert"), e.getMessage());
    }

    @Test
    void anUnsignedLicenseIsRejected() {
        String unsigned = TestLicenses.readyroom("*", "never").replaceAll("(?m)^signature=.*$", "");

        var e = assertThrows(LicenseVerifier.InvalidLicenseException.class, () -> verifier.verify(unsigned));
        assertEquals("the file has no signature", e.getMessage());
    }

    @Test
    void anExpiredLicenseFallsBackToCore() throws IOException {
        Path file = dir.resolve("license.lic");
        Files.writeString(file, TestLicenses.readyroom("*", "2026-06-30"));
        // Good through the end of its expiry date, gone the day after.
        LicenseService lastDay = service(file, "2026-06-30T23:59:00Z");
        LicenseService dayAfter = service(file, "2026-07-01T00:00:00Z");

        assertEquals(LicenseService.State.LICENSED, lastDay.status().state());
        assertTrue(lastDay.isEnabled("metrics"));

        LicenseService.Status status = dayAfter.status();
        assertEquals(LicenseService.State.EXPIRED, status.state());
        assertTrue(status.features().isEmpty());
        assertFalse(dayAfter.isEnabled("metrics"));
        assertTrue(new LicenseInfo(status).getSummary().startsWith("Unlicensed: core features only (the license expired"));
    }

    @Test
    void noLicenseFileMeansCoreOnly() {
        LicenseService service = service(dir.resolve("absent.lic"), "2026-10-15T12:00:00Z");

        assertEquals(LicenseService.State.MISSING, service.status().state());
        assertNull(service.status().problem());
        for (LicensedFeature feature : LicensedFeature.values()) {
            assertFalse(service.isEnabled(feature.id()), feature.id());
        }
    }

    @Test
    void anInvalidLicenseFileMeansCoreOnlyAndSaysWhy() throws IOException {
        Path file = dir.resolve("license.lic");
        Files.writeString(file, "not a license");
        LicenseService service = service(file, "2026-10-15T12:00:00Z");

        assertEquals(LicenseService.State.INVALID, service.status().state());
        assertEquals("the file has no signature", service.status().problem());
        assertFalse(service.isEnabled("metrics"));
    }

    private static LicenseService service(Path file, String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new LicenseService(LicenseVerifier.forProduct(LicenseConfig.PRODUCT), file, LicenseConfig.PRODUCT, clock);
    }
}
