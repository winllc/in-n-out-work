package com.winllc.innoutwork.webhook;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Webhook signing secrets at rest: AES-256-GCM under a key derived (SHA-256) from application.webhooks.secret-key,
 * with a random 96-bit IV each time. Stored as {@code v1:} and base64 of IV and ciphertext. A secret has to be
 * recoverable to sign with, so it cannot be kept as a hash like a token; a copy of the database alone does not
 * give it away. JCA only, so a FIPS provider applies.
 */
public final class SecretBox {

    private static final String VERSION = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(String secretKey) {
        if (secretKey == null || secretKey.length() < 32) {
            throw new IllegalArgumentException("application.webhooks.secret-key must be at least 32 characters");
        }
        try {
            byte[] derived = MessageDigest.getInstance("SHA-256").digest(secretKey.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(derived, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String seal(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length)
                    .put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt a webhook secret", e);
        }
    }

    /** @throws IllegalStateException when it was sealed under another key, or altered */
    public String open(String sealed) {
        if (sealed == null || !sealed.startsWith(VERSION)) {
            throw new IllegalStateException("Not a sealed webhook secret");
        }
        try {
            byte[] all = Base64.getDecoder().decode(sealed.substring(VERSION.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("A webhook secret could not be decrypted: was"
                    + " application.webhooks.secret-key changed?", e);
        }
    }
}
