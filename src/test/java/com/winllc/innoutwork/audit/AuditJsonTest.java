package com.winllc.innoutwork.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ens-audit/1 wire form and hash rule, against the shared spec's test vector (src/test/resources/audit). */
class AuditJsonTest {

    static final String VECTOR_BODY = "{\"schema\":\"ens-audit/1\",\"id\":\"00000000-0000-4000-8000-000000000001\","
            + "\"seq\":1,\"time\":\"2026-01-01T00:00:00.000Z\",\"product\":\"test\",\"version\":\"dev\",\"host\":\"h\","
            + "\"installationId\":null,\"category\":\"system\",\"action\":\"system.started\",\"outcome\":\"success\","
            + "\"severity\":\"info\",\"actor\":{\"type\":\"system\",\"id\":null,\"name\":null,\"roles\":[]},"
            + "\"source\":null,\"target\":null,\"reason\":null,\"details\":{},\"requestId\":null,\"session\":null,"
            + "\"prevHash\":\"0000000000000000000000000000000000000000000000000000000000000000\"}";

    private static Map<String, String> vector() throws IOException {
        try (InputStream in = AuditJsonTest.class.getResourceAsStream("/audit/vector.txt")) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    values.put(line.substring(0, eq), line.substring(eq + 1).trim());
                }
            }
            return values;
        }
    }

    private static AuditJson.Event vectorEvent() {
        return new AuditJson.Event("00000000-0000-4000-8000-000000000001", 1, Instant.parse("2026-01-01T00:00:00Z"),
                "test", "dev", "h", null, "system", "system.started", "success", "info",
                AuditEntry.Actor.system(), null, null, null, Map.of(), null, null, AuditJson.ZERO_HASH);
    }

    @Test
    void theSerializerProducesTheVectorBodyExactly() {
        assertEquals(VECTOR_BODY, AuditJson.body(vectorEvent()));
    }

    @Test
    void theVectorBodyHashesToThePublishedValue() throws IOException {
        Map<String, String> vector = vector();
        String body = AuditJson.body(vectorEvent());
        String hash = AuditJson.hash(AuditJson.ZERO_HASH, body);

        assertEquals(vector.get("hash"), hash);
        assertEquals(vector.get("line"), AuditJson.line(body, hash));
        assertEquals(body, AuditJson.bodyOf(vector.get("line")));
        assertEquals(hash, AuditJson.hashOf(vector.get("line")));
        assertEquals(AuditJson.ZERO_HASH, AuditJson.prevHashOf(body));
    }

    @Test
    void fieldsAreWrittenInTheSpecOrderWithNothingOmitted() {
        AuditJson.Event event = new AuditJson.Event("id-1", 7, Instant.parse("2026-10-16T02:10:11.123456Z"),
                "readyroom", "1.0", "host", "RR-1", "authentication", "auth.login", "failure", "warning",
                AuditEntry.Actor.anonymous("jdoe"), new AuditEntry.Source("10.1.2.3", "Mozilla/5.0 \"x\""),
                new AuditEntry.Target("user", "jdoe", "Jane Doe"), "bad credentials",
                new LinkedHashMap<>(Map.of("method", "ldap")), "req", "0123456789abcdef", "a".repeat(64));
        String body = AuditJson.body(event);

        List<String> order = List.of("schema", "id", "seq", "time", "product", "version", "host", "installationId",
                "category", "action", "outcome", "severity", "actor", "source", "target", "reason", "details",
                "requestId", "session", "prevHash");
        int last = -1;
        for (String field : order) {
            int at = body.indexOf("\"" + field + "\":");
            assertTrue(at > last, field + " out of order in " + body);
            last = at;
        }
        assertTrue(body.contains("\"time\":\"2026-10-16T02:10:11.123Z\""), "milliseconds, UTC, Z");
        assertTrue(body.contains("\"userAgent\":\"Mozilla/5.0 \\\"x\\\"\""), "strings are escaped");
        assertTrue(body.endsWith("\"prevHash\":\"" + "a".repeat(64) + "\"}"));
    }

    @Test
    void anyChangeToTheBodyChangesTheHash() {
        String body = AuditJson.body(vectorEvent());
        assertNotEquals(AuditJson.hash(AuditJson.ZERO_HASH, body),
                AuditJson.hash(AuditJson.ZERO_HASH, body.replace("system.started", "system.stopped")));
    }

    @Test
    void secretsAreRedactedByNameAndByLook() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("password", "hunter2");
        details.put("password.old", "a");
        details.put("password.new", "b");
        details.put("token", "abc");
        details.put("tokenName", "Splunk");
        details.put("splunk.token", "xyz");
        details.put("apiKey", "k");
        details.put("licenseText", "-----BEGIN ENS LICENSE-----");
        details.put("pin", "1234");
        details.put("note", "my token is ensat_ABCDEFGHIJKLMNOP");
        details.put("count", 3);
        details.put("long", "x".repeat(1500));

        Map<String, Object> safe = AuditRedaction.details(details);

        for (String key : List.of("password", "password.old", "password.new", "token", "splunk.token", "apiKey",
                "licenseText", "pin", "note")) {
            assertEquals(AuditRedaction.REDACTED, safe.get(key), key);
        }
        assertEquals("Splunk", safe.get("tokenName"));
        assertEquals(3, safe.get("count"));
        String body = AuditJson.body(new AuditJson.Event("i", 1, Instant.now(), "p", "v", "h", null, "data", "x",
                "success", "info", AuditEntry.Actor.system(), null, null, null, safe, null, null, AuditJson.ZERO_HASH));
        assertFalse(body.contains("hunter2"));
        assertFalse(body.contains("ensat_ABCDEFGH"));
        assertTrue(body.contains("\"long\":\"" + "x".repeat(1000) + "\""), "strings are cut to 1000 characters");
    }
}
