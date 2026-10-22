package com.winllc.innoutwork.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * How a delivery is signed: {@code X-ReadyRoom-Signature: t=<unix seconds>,v1=<hex>}, where the hex is
 * HMAC-SHA256, keyed with the endpoint's secret, of {@code <unix seconds>.<body>} (the body's exact bytes). The
 * timestamp is also sent alone as {@code X-ReadyRoom-Timestamp}. A receiver recomputes it, compares in constant
 * time, and refuses a timestamp more than a few minutes from its own clock, so a captured delivery cannot be
 * replayed later.
 */
public final class WebhookSignature {

    public static final String HEADER = "X-ReadyRoom-Signature";
    public static final String TIMESTAMP_HEADER = "X-ReadyRoom-Timestamp";

    private WebhookSignature() {
    }

    public static String header(String secret, long timestamp, byte[] body) {
        return "t=" + timestamp + ",v1=" + hmac(secret, timestamp, body);
    }

    public static String hmac(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What a receiver does: true when {@code header} signs {@code body} with {@code secret} within tolerance. */
    public static boolean verify(String secret, String header, byte[] body, long nowSeconds, long toleranceSeconds) {
        if (header == null) {
            return false;
        }
        Long t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String p = part.trim();
            if (p.startsWith("t=")) {
                try {
                    t = Long.parseLong(p.substring(2));
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (p.startsWith("v1=")) {
                v1 = p.substring(3);
            }
        }
        if (t == null || v1 == null || Math.abs(nowSeconds - t) > toleranceSeconds) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, t, body).getBytes(StandardCharsets.US_ASCII),
                v1.getBytes(StandardCharsets.US_ASCII));
    }
}
