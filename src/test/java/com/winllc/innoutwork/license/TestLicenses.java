package com.winllc.innoutwork.license;

import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseVerifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Signs {@code ens-license/1} files with the ENS portal's published TEST key (the PKCS#8 encoding of
 * the seed 0x07 &times; 32, as in the portal's licensing/fixtures), and makes license managers that
 * trust that key. Production builds trust only {@link EnsKeys}, so nothing signed here is accepted
 * outside the tests.
 *
 * <pre>
 * String lic = TestLicenses.license().features("attendance", "calendar").quantity(50).sign();
 * </pre>
 */
public final class TestLicenses {

    /** fixtures/test-public-key.txt: key id and base64 SPKI. */
    public static final String TEST_KEY_ID = "324be2dea8bc4446";
    public static final String TEST_PUBLIC_KEY = "MCowBQYDK2VwAyEA6kpsY+KcUgq+9VB7Ey7F+ZVHdq6+vnuSQh7qaRRG0iw=";

    private static final String PKCS8_PREFIX = "302e020100300506032b657004220420";
    private static final AtomicLong MODIFIED = new AtomicLong(Instant.parse("2030-01-01T00:00:00Z").getEpochSecond());

    private TestLicenses() {
    }

    /** A verifier trusting only the test key. */
    public static LicenseVerifier verifier() {
        return LicenseVerifier.trusting(TEST_PUBLIC_KEY);
    }

    /** A manager for readyroom over {@code dir} (license file dir/license.lic, state in dir/state), trusting the test key. */
    public static LicenseManager manager(Path dir) {
        return new LicenseManager(LicensedFeature.PRODUCT, verifier(), dir.resolve("license.lic"), dir.resolve("state"),
                Optional.empty(), Clock.systemUTC());
    }

    /**
     * Puts {@code text} in place as the manager's license file, as an administrator copying a file in
     * would, and makes sure the manager notices: each call gets a new modification time.
     */
    public static void installFile(LicenseManager licenses, String text) {
        try {
            Path file = licenses.licenseFile();
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
            Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(MODIFIED.incrementAndGet())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes the manager's license file. */
    public static void removeFile(LicenseManager licenses) {
        try {
            Files.deleteIfExists(licenses.licenseFile());
            licenses.current(); // forget the one loaded
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A full readyroom subscription with every feature, for 2,500 people, valid for a year. */
    public static String full() {
        return license().sign();
    }

    public static Builder license() {
        return new Builder();
    }

    /** The payload fields, with the defaults of a full readyroom subscription. */
    public static final class Builder {
        private String product = LicensedFeature.PRODUCT;
        private String edition = "subscription";
        private List<String> features = Arrays.stream(LicensedFeature.values()).map(LicensedFeature::id).toList();
        private long quantity = 2500;
        private Instant notBefore = Instant.now().minus(Duration.ofDays(1));
        private Instant expiresAt = Instant.now().plus(Duration.ofDays(365));
        private int graceDays = 30;
        private Instant maintenanceUntil;
        private String installationId;
        private String id = "lic_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        private int revision = 1;
        private Boolean watermark;

        public Builder product(String product) {
            this.product = product;
            return this;
        }

        public Builder edition(String edition) {
            this.edition = edition;
            return this;
        }

        /** Feature ids without the product prefix; none for an empty list. */
        public Builder features(String... ids) {
            this.features = List.of(ids);
            return this;
        }

        /** Every feature but these. */
        public Builder without(LicensedFeature... off) {
            List<LicensedFeature> excluded = List.of(off);
            this.features = Arrays.stream(LicensedFeature.values()).filter(f -> !excluded.contains(f))
                    .map(LicensedFeature::id).toList();
            return this;
        }

        public Builder quantity(long quantity) {
            this.quantity = quantity;
            return this;
        }

        /** Expired {@code daysAgo} days ago, with no grace left: read-only. */
        public Builder expired(int daysAgo) {
            this.notBefore = Instant.now().minus(Duration.ofDays(400));
            this.expiresAt = Instant.now().minus(Duration.ofDays(daysAgo));
            this.graceDays = 0;
            return this;
        }

        /** Expired {@code daysAgo} days ago but within {@code graceDays}: full use, renewal warning. */
        public Builder inGrace(int daysAgo, int graceDays) {
            this.notBefore = Instant.now().minus(Duration.ofDays(400));
            this.expiresAt = Instant.now().minus(Duration.ofDays(daysAgo));
            this.graceDays = graceDays;
            return this;
        }

        public Builder expiresIn(Duration duration) {
            this.expiresAt = Instant.now().plus(duration);
            return this;
        }

        public Builder notBefore(Instant notBefore) {
            this.notBefore = notBefore;
            return this;
        }

        public Builder evaluation() {
            this.edition = "evaluation";
            this.graceDays = 0;
            this.watermark = true;
            return this;
        }

        public Builder installationId(String installationId) {
            this.installationId = installationId;
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder revision(int revision) {
            this.revision = revision;
            return this;
        }

        public String payload() {
            boolean wm = watermark != null ? watermark : "evaluation".equals(edition);
            return "{"
                    + "\"format\":\"ens-license/1\","
                    + "\"id\":" + q(id) + ","
                    + "\"revision\":" + revision + ","
                    + "\"issuer\":\"ENS Solutions, LLC\","
                    + "\"product\":" + q(product) + ","
                    + "\"edition\":" + q(edition) + ","
                    + "\"licensee\":{\"name\":\"Pat Tester\",\"organization\":\"Test Agency\",\"email\":\"pat@example.gov\"},"
                    + "\"limit\":{\"quantity\":" + quantity + ",\"unit\":\"tracked people\"},"
                    + "\"features\":[" + features.stream().map(f -> q(f.contains(".") ? f : product + "." + f))
                    .collect(Collectors.joining(",")) + "],"
                    + "\"support\":\"standard\","
                    + "\"issuedAt\":" + q(notBefore.toString()) + ","
                    + "\"notBefore\":" + q(notBefore.toString()) + ","
                    + "\"expiresAt\":" + ("perpetual".equals(edition) ? "null" : q(expiresAt.toString())) + ","
                    + "\"graceDays\":" + graceDays + ","
                    + "\"maintenanceUntil\":" + (maintenanceUntil == null ? "null" : q(maintenanceUntil.toString())) + ","
                    + "\"installationId\":" + (installationId == null ? "null" : q(installationId)) + ","
                    + "\"watermark\":" + wm
                    + "}";
        }

        public String sign() {
            return TestLicenses.sign(payload());
        }

        private static String q(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    /** The armoured license file for this payload JSON, signed with the test key. */
    public static String sign(String payloadJson) {
        try {
            String body = keyId() + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey());
            signer.update(body.getBytes(StandardCharsets.US_ASCII));
            String token = body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < token.length(); i += 64) {
                lines.add(token.substring(i, Math.min(token.length(), i + 64)));
            }
            return "# ENS Solutions license file (TEST KEY - tests only)\n"
                    + "-----BEGIN ENS LICENSE-----\n" + String.join("\n", lines) + "\n-----END ENS LICENSE-----\n";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** First 16 hex digits of SHA-256 over the test public key's SPKI DER, worked out rather than trusted. */
    static String keyId() throws GeneralSecurityException {
        PublicKey key = KeyFactory.getInstance("Ed25519")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(TEST_PUBLIC_KEY)));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded())).substring(0, 16);
    }

    private static PrivateKey privateKey() throws GeneralSecurityException {
        byte[] der = HexFormat.of().parseHex(PKCS8_PREFIX + "07".repeat(32));
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
