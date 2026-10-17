package com.winllc.innoutwork.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keeps secrets out of the audit trail. A detail whose name says it is a secret (password, token, API key,
 * private key, license text, session id, one-time or emergency code, PIN, tokencode, seed) is recorded as
 * {@code "[redacted]"}, as is any value that looks like one (an audit API token, a PEM block or an ENS license).
 * Values are flattened to strings, numbers, booleans or null.
 */
public final class AuditRedaction {

    public static final String REDACTED = "[redacted]";

    private static final Pattern SECRET_NAME = Pattern.compile(
            ".*(password|passwd|passphrase|pwd|secret|token|apikey|api_key|api-key|privatekey|private_key|"
                    + "private-key|licensetext|license_text|license-text|licensefile|sessionid|session_id|"
                    + "session-id|otp|pin|passcode|tokencode|seed|credential|credentials|emergencycode|"
                    + "onetimecode)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(ensat_[A-Za-z0-9_-]{8,})|(-----BEGIN [A-Z ]+-----)|(BEGIN ENS LICENSE)");

    private AuditRedaction() {
    }

    static Map<String, Object> details(Map<String, Object> details) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (details == null) {
            return safe;
        }
        details.forEach((key, value) -> safe.put(key, value(key, value)));
        return safe;
    }

    static Object value(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (isSecretName(key)) {
            return REDACTED;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return text(value.toString());
    }

    /** Free text with anything secret-looking replaced. */
    static String text(String value) {
        if (value == null) {
            return null;
        }
        return SECRET_VALUE.matcher(value).find() ? REDACTED : value;
    }

    static boolean isSecretName(String key) {
        String base = key;
        if (base.endsWith(".old") || base.endsWith(".new")) {
            base = base.substring(0, base.length() - 4);
        }
        int dot = base.lastIndexOf('.');
        if (dot >= 0) {
            base = base.substring(dot + 1);
        }
        return SECRET_NAME.matcher(base).matches();
    }
}
