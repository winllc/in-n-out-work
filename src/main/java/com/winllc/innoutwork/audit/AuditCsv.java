package com.winllc.innoutwork.audit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** The CSV export: one row per event, every field of the event, details as their JSON object. */
final class AuditCsv {

    static final List<String> HEADER = List.of("seq", "id", "time", "category", "action", "outcome", "severity",
            "actor_type", "actor_id", "actor_name", "actor_roles", "source_ip", "source_user_agent", "target_type",
            "target_id", "target_name", "reason", "details", "request_id", "session", "product", "version", "host",
            "installation_id", "prev_hash", "hash");

    private static final ObjectMapper JSON = new ObjectMapper();

    private AuditCsv() {
    }

    static String row(AuditEventRecord e) {
        JsonNode n = JSON.readTree(e.getLine());
        List<String> roles = new ArrayList<>();
        n.path("actor").path("roles").forEach(r -> roles.add(r.asString()));
        List<String> cells = List.of(
                String.valueOf(e.getSeq()), text(n, "id"), text(n, "time"), e.getCategory(), e.getAction(),
                e.getOutcome(), e.getSeverity(), text(n.path("actor"), "type"), text(n.path("actor"), "id"),
                text(n.path("actor"), "name"), String.join(";", roles), text(n.path("source"), "ip"),
                text(n.path("source"), "userAgent"), text(n.path("target"), "type"), text(n.path("target"), "id"),
                text(n.path("target"), "name"), text(n, "reason"), n.path("details").toString(), text(n, "requestId"),
                text(n, "session"), text(n, "product"), text(n, "version"), text(n, "host"),
                text(n, "installationId"), text(n, "prevHash"), text(n, "hash"));
        List<String> escaped = new ArrayList<>(cells.size());
        for (String cell : cells) {
            escaped.add(cell(cell));
        }
        return String.join(",", escaped);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return "";
        }
        return v.isString() ? v.asString() : v.toString();
    }

    /**
     * RFC 4180 quoting, and a leading quote for anything a spreadsheet would run as a formula (an event's text
     * is partly user-supplied).
     */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
