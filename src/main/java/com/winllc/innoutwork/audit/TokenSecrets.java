package com.winllc.innoutwork.audit;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Bearer secrets handed to other systems (the audit pull API's tokens, the public API's tokens, webhook signing
 * secrets): a fixed prefix that says what the secret is for, then 32 random bytes, base64url. A secret is shown
 * once when it is made; what is kept is its SHA-256 ({@link #hash}) and its first characters ({@link #prefix}), so
 * an administrator can tell tokens apart.
 */
public final class TokenSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Characters of the secret, after its prefix, kept for display. */
    static final int SHOWN = 4;

    private TokenSecrets() {
    }

    /** A new secret: {@code prefix} and 32 random bytes, base64url without padding. */
    public static String generate(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** What is stored of a token: its SHA-256, in hex. */
    public static String hash(String token) {
        return AuditJson.sha256Hex(token);
    }

    /** The part of {@code token} that may be shown later: its prefix and a few characters. */
    public static String prefix(String token, String prefix) {
        return token.substring(0, Math.min(token.length(), prefix.length() + SHOWN));
    }
}
