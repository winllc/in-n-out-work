package com.winllc.innoutwork.license;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Signs license files with the test key, in the same canonical form as the vendor's LicenseTool: every
 * field but the signature, sorted, as key=value lines joined by \n. The test resources put the matching
 * public key ahead of the real one on the classpath, so these verify in tests and nowhere else.
 */
public final class TestLicenses {

    /** The full license every test context runs with unless it says otherwise. */
    public static final String FULL = "src/test/resources/license/test.lic";

    private TestLicenses() {
    }

    /** A license for ReadyRoom to licensee "Test" with the given features and expiry ("never" or a date). */
    public static String readyroom(String features, String expires) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("licenseId", "test-" + features.replace('*', 'x'));
        fields.put("product", LicenseConfig.PRODUCT);
        fields.put("licensee", "Test");
        fields.put("issued", "2026-01-01");
        fields.put("expires", expires);
        fields.put("features", features);
        return sign(fields);
    }

    /** The license file text for these fields, signed with the test key. */
    public static String sign(Map<String, String> fields) {
        Map<String, String> sorted = new TreeMap<>(fields);
        StringBuilder canonical = new StringBuilder();
        sorted.forEach((k, v) -> canonical.append(k).append('=').append(v).append('\n'));
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey());
            signer.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(signer.sign());
            return "# ENS Solutions license. Any edit invalidates the signature.\n" + canonical
                    + "signature=" + signature + "\n";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes the text to a new temporary file and returns its path. */
    public static Path write(String text) {
        try {
            Path file = Files.createTempFile("readyroom-test", ".lic");
            file.toFile().deleteOnExit();
            Files.writeString(file, text);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PrivateKey privateKey() throws GeneralSecurityException {
        try (InputStream in = TestLicenses.class.getClassLoader()
                .getResourceAsStream("license/ens-license-test-private.pem")) {
            if (in == null) {
                throw new IllegalStateException("license/ens-license-test-private.pem is not on the test classpath");
            }
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            byte[] der = Base64.getDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
