package com.winllc.innoutwork.license;

import com.enssolutions.license.LicenseException;
import com.enssolutions.license.LicenseVerifier;
import com.enssolutions.license.LicenseVerifier.Algorithm;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.AlgorithmParameters;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The production keys this build trusts: the Ed25519 key, and the ECDSA P-256 key for Java runtimes
 * without Ed25519 (FIPS mode). Production licenses cannot be made here (no private key), so the P-256
 * key is checked by its curve and id, and the test-key fixtures must be refused.
 */
class EnsKeysTest {

    static final String ED25519_ID = "dabdc531a28916c5";
    static final String P256_ID = "e0a2e4ff9c4f5bfc";

    @Test
    void trustsTheEd25519AndP256ProductionKeys() {
        Map<String, Algorithm> keys = LicenseVerifier.trusting(EnsKeys.PUBLIC_KEYS).trustedKeys();
        assertEquals(Map.of(ED25519_ID, Algorithm.ED25519, P256_ID, Algorithm.ECDSA_P256), keys);
    }

    @Test
    void p256KeyIsPrime256v1WithItsKeyId() throws Exception {
        ECPublicKey key = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(EnsKeys.PUBLIC_KEYS[1])));
        AlgorithmParameters p256 = AlgorithmParameters.getInstance("EC");
        p256.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected = p256.getParameterSpec(ECParameterSpec.class);
        assertEquals(expected.getCurve(), key.getParams().getCurve());
        assertEquals(expected.getGenerator(), key.getParams().getGenerator());
        assertEquals(expected.getOrder(), key.getParams().getOrder());
        assertEquals(P256_ID, LicenseVerifier.keyId(key));
    }

    /** Licenses signed with the published test keys, P-256 included, are not production licenses. */
    @Test
    void refusesTestKeyLicenses() throws Exception {
        Path fixtures = Path.of("src/test/resources/ens-license-fixtures");
        LicenseVerifier production = LicenseVerifier.trusting(EnsKeys.PUBLIC_KEYS);
        for (String name : new String[] {"p256-only.lic", "dual-signed.lic", "subscription.lic"}) {
            LicenseException e = assertThrows(LicenseException.class,
                    () -> production.verify(Files.readString(fixtures.resolve(name))), name);
            assertEquals(LicenseException.Reason.UNKNOWN_KEY, e.reason(), name);
        }
    }
}
