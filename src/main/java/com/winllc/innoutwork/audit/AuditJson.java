package com.winllc.innoutwork.audit;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code ens-audit/1} wire form and its hash chain. Language-independent by construction:
 * <ul>
 *   <li>{@code body} is the event as compact JSON, fields in the spec's order, without {@code hash}; it ends
 *       with {@code "prevHash":"..."}}.</li>
 *   <li>{@code hash = hex(sha256(utf8(prevHash + "\n" + body)))}.</li>
 *   <li>The stored and emitted line is {@code body} with its final {@code }} replaced by
 *       {@code ,"hash":"<hash>"}}.</li>
 * </ul>
 * The line is stored verbatim, so verification never re-serializes anything.
 */
public final class AuditJson {

    public static final String SCHEMA = "ens-audit/1";
    public static final String ZERO_HASH = "0".repeat(64);
    /** Each string in {@code details}, and the other free-text fields, is cut to this many characters. */
    public static final int MAX_STRING = 1000;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern HASH_SUFFIX = Pattern.compile(",\"hash\":\"([0-9a-f]{64})\"}$");
    private static final Pattern PREV_HASH_SUFFIX = Pattern.compile("\"prevHash\":\"([0-9a-f]{64})\"}$");

    private AuditJson() {
    }

    /** Everything that goes into one event, in the order it is written. */
    public record Event(String id, long seq, Instant time, String product, String version, String host,
                        String installationId, String category, String action, String outcome, String severity,
                        AuditEntry.Actor actor, AuditEntry.Source source, AuditEntry.Target target, String reason,
                        Map<String, Object> details, String requestId, String session, String prevHash) {
    }

    public static String time(Instant instant) {
        return TIME.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    /** The body: compact JSON in field order, without the hash. */
    public static String body(Event e) {
        StringBuilder b = new StringBuilder(512);
        b.append('{');
        field(b, "schema", SCHEMA).append(',');
        field(b, "id", e.id()).append(',');
        b.append("\"seq\":").append(e.seq()).append(',');
        field(b, "time", time(e.time())).append(',');
        field(b, "product", e.product()).append(',');
        field(b, "version", e.version()).append(',');
        field(b, "host", e.host()).append(',');
        field(b, "installationId", e.installationId()).append(',');
        field(b, "category", e.category()).append(',');
        field(b, "action", e.action()).append(',');
        field(b, "outcome", e.outcome()).append(',');
        field(b, "severity", e.severity()).append(',');

        AuditEntry.Actor actor = e.actor() != null ? e.actor() : AuditEntry.Actor.system();
        b.append("\"actor\":{");
        field(b, "type", actor.type()).append(',');
        field(b, "id", cut(actor.id())).append(',');
        field(b, "name", cut(actor.name())).append(',');
        b.append("\"roles\":[");
        List<String> roles = actor.roles();
        for (int i = 0; i < roles.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            string(b, roles.get(i));
        }
        b.append("]},");

        b.append("\"source\":");
        if (e.source() == null) {
            b.append("null");
        } else {
            b.append('{');
            field(b, "ip", e.source().ip()).append(',');
            field(b, "userAgent", cut(e.source().userAgent()));
            b.append('}');
        }
        b.append(',');

        b.append("\"target\":");
        if (e.target() == null) {
            b.append("null");
        } else {
            b.append('{');
            field(b, "type", e.target().type()).append(',');
            field(b, "id", cut(e.target().id())).append(',');
            field(b, "name", cut(e.target().name()));
            b.append('}');
        }
        b.append(',');

        field(b, "reason", cut(e.reason())).append(',');

        b.append("\"details\":{");
        boolean first = true;
        if (e.details() != null) {
            for (Map.Entry<String, Object> d : e.details().entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                string(b, d.getKey());
                b.append(':');
                value(b, d.getValue());
            }
        }
        b.append("},");

        field(b, "requestId", e.requestId()).append(',');
        field(b, "session", e.session()).append(',');
        field(b, "prevHash", e.prevHash());
        b.append('}');
        return b.toString();
    }

    /** {@code hex(sha256(utf8(prevHash + "\n" + body)))}. */
    public static String hash(String prevHash, String body) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest((prevHash + "\n" + body).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The emitted and stored line: the body with {@code ,"hash":"..."} added before its final brace. */
    public static String line(String body, String hash) {
        return body.substring(0, body.length() - 1) + ",\"hash\":\"" + hash + "\"}";
    }

    /** The body a line was hashed over, or null if the line does not end in a hash. */
    public static String bodyOf(String line) {
        Matcher m = HASH_SUFFIX.matcher(line);
        return m.find() ? line.substring(0, m.start()) + "}" : null;
    }

    /** The hash a line carries, or null. */
    public static String hashOf(String line) {
        Matcher m = HASH_SUFFIX.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    /** The prevHash a body names, or null. */
    public static String prevHashOf(String body) {
        Matcher m = PREV_HASH_SUFFIX.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** Lowercase hex SHA-256 of the UTF-8 text. */
    public static String sha256Hex(String text) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String cut(String s) {
        return s == null || s.length() <= MAX_STRING ? s : s.substring(0, MAX_STRING);
    }

    private static StringBuilder field(StringBuilder b, String name, String value) {
        string(b, name);
        b.append(':');
        if (value == null) {
            b.append("null");
        } else {
            string(b, value);
        }
        return b;
    }

    private static void value(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof Boolean bool) {
            b.append(bool);
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            b.append(((Number) v).longValue());
        } else if (v instanceof Double d && (d.isNaN() || d.isInfinite())) {
            b.append("null");
        } else if (v instanceof Float f && (f.isNaN() || f.isInfinite())) {
            b.append("null");
        } else if (v instanceof Number n) {
            b.append(new BigDecimal(n.toString()).stripTrailingZeros().toPlainString());
        } else {
            string(b, cut(v.toString()));
        }
    }

    /** A JSON string: quotes, backslashes and control characters escaped, everything else as is. */
    static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }

    /** A JSON string literal, for building responses from stored lines. */
    public static String quote(String s) {
        StringBuilder b = new StringBuilder();
        if (s == null) {
            return "null";
        }
        string(b, s);
        return b.toString();
    }
}
